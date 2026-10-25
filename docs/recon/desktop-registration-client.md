# Desktop Registration Client — reconnaissance for liveness integration

**Source read:** `mosip/registration-client`, branch **`release-1.2.0.x`** (HEAD `c1cc4e7f`, merged 2026-09-21).
Remote `HEAD` points to `master`; `develop`, `develop-java21`, `release-1.2.0` and `release-1.2.0.x` also exist.
`release-1.2.0.x` was chosen because it is the maintenance line of the 1.2.0 LTS that deployments run; `develop-java21`
is the forward port and class names are the same for everything referenced here (not re-verified line by line).

Checked out sparsely (blobless, depth 1, cone sparse-checkout on
`registration/registration-{api,services,client}/src/main/{java,resources}`), 562 Java files, 53 MB on disk.
Disk before clone: 6.2 GB free; after clone: 8.1 GB free (another process released space meanwhile; the clone itself cost 53 MB).

Paths below are relative to `registration/` in that repo. Line numbers are from that commit.
Everything marked **verified** was read in the code; everything marked **inferred** is a reading of the code that
was not executed. Nothing here was run.

---

## 0. Summary table — requirement → injection point → what we change

| # | Requirement (PLAN §1.2 / §7) | Injection point (file : line, class, method) | What we change |
|---|---|---|---|
| 1 | Consume the SBI `STREAM` as a `FaceFrameSource` | `registration-services/.../service/bio/impl/BioServiceImpl.java:143` `getStream(MdmBioDevice, String)` → `MosipDeviceSpecificationProvider.stream(...)` returns raw `InputStream` (multipart MJPEG) | Do **not** touch `BioServiceImpl`. Wrap the returned `InputStream` in `io.mosip.vivid.desktop.stream.MjpegInputStreamFrameSource` at the call sites in the controllers (rows 3, 5, 6). |
| 2 | MJPEG rendering in the preview | `registration-client/.../controller/device/Streamer.java:` `startStream(InputStream, ImageView, ImageView)`, `retrieveNextImage(InputStream)` | Our frame source replaces `Streamer` for face modality only; the `LivenessOverlayController` renders each `FaceFrame` into the same `ImageView` (`biometricImage` / `faceImage`). `Streamer` stays for finger/iris. |
| 3 | Resident face capture gate (before `rcapture`) | `registration-client/.../controller/device/GenericBiometricsController.java:480-492` in `scan(ActionEvent)` → `deviceSearchTask.setOnSucceeded`: line 480 `bioService.getStream(...)`, line 491 `rCaptureTaskService()`, line 492 `streamer.startStream(...)` | Patch: when `isFace(currentModality)` and liveness enabled, hand the stream to `ResidentFaceLivenessHook.run(...)`, which runs `LivenessGate.verify(RESIDENT, source, overlay)` on a JavaFX `Task` and calls `rCaptureTaskService()` only on `PASSED`. |
| 4 | Bind the signed `rcapture` image to the session (identity continuity) | `GenericBiometricsController.java:590-650` `rCaptureTaskService().setOnSucceeded`, after `isValidBiometric(...)` (line 597) and before `fxControl.setData(biometricsMap)` (line 633) | Patch: call `LivenessCaptureVerifier.verify(livenessResult, biometricsDto)` which decodes the ISO via `io.mosip.biometrics.util.face.FaceDecoder.convertFaceISOToImageBytes` and runs `CaptureBinding`. On drift: generic alert, discard capture, audit `IDENTITY_DRIFT`. |
| 5 | Operator / supervisor packet authentication with face | `registration-client/.../controller/auth/AuthenticationController.java:515-575` `executeFaceValidationTask` → line 532 `captureAndValidateFace(userId, true, isReviewer)` → `registration-client/.../controller/BaseController.java:1713` `captureAndValidateFace(String,boolean,boolean)` → `bioService.captureModalityForAuth` → `authenticationService.authValidator(userId, "Face", biometrics)` | Patch `BaseController.captureAndValidateFace`: before `captureModalityForAuth`, run `OperatorAuthLivenessHook.verify(isReviewer ? SUPERVISOR : OPERATOR, faceImage)`; return `false` unless `accepted()`; after capture run `CaptureBinding`; only then call `authValidator`. `isReviewer` is the existing supervisor/reviewer flag. |
| 6 | Operator login with face (application login) | `registration-services/.../context/SessionContext.java:418-446` `validateFace(...)` (static, called from `SessionContext.create` by `LoginController.captureFace` line 660 / `executeValidationTask` line 676) | Patch `SessionContext.validateFace`: resolve `LivenessGate` from the Spring context (`applicationContext.getBean(LivenessGate.class)`), run with `Workflow.OPERATOR` using the `faceImage` of `LoginController` via the hook's overlay; return `false` unless accepted. |
| 7 | Config keys | `registration-services/.../context/ApplicationContext.java:192` `static Map<String,Object> map()`; `.../service/BaseService.java:343` `getGlobalConfigValueOf(String)`; `.../constants/RegistrationConstants.java:952,987,809,808,1267` | No code change needed to *read* keys: `DesktopLivenessConfig.fromApplicationMap(ApplicationContext.map())`. Add our key names as constants in `RegistrationConstants` (patch) for discoverability. Values arrive by the existing `GlobalParamService.synchConfigData` (`get_registration_center_config`) and are stored in `global_param` table, so they work offline after the first sync; our code falls back to compiled-in defaults when the key is absent. |
| 8 | Existing face retry count | `BioServiceImpl.java:275` `getRetryCount(Modality.FACE)` → key `mosip.registration.num_of_face_retries`; UI retry boxes `GenericBiometricsController.java:914` `createQualityBox`, `:985` `clearAttemptsBox`; attempts tracked in `RegistrationDTO.ATTEMPTS` (`RegistrationDTO.java:81`) | We do **not** add a second counter. Liveness attempts are *inside* one capture attempt (the liveness session is retried `maxRetries` times before `rcapture` is ever called; `rcapture` failures still consume the existing MOSIP retry). `DesktopLivenessConfig.maxRetries` defaults to `mosip.registration.liveness.retries` and, if absent, to `mosip.registration.num_of_face_retries`. |
| 9 | Audit | `registration-services/.../audit/AuditManagerService.java` `audit(AuditEvent, Components, String refId, String refIdType)`; enums `constants/AuditEvent.java` (170 entries, last id `REG-EVT-103`), `constants/Components.java` (last `CLIENT_UPGRADE("REG-MOD-152")`), `constants/AuditEventType.java`, `constants/AuditReferenceIdTypes.java` | Patch: add `Components.LIVENESS("REG-MOD-153", "Liveness / PAD")` and `AuditEvent.LIVENESS_*("REG-LIV-0nn", ...)` constants; implement `LivenessAuditAdapter` with `MosipLivenessAuditAdapter` that calls `auditFactory.audit(...)`. Detail (scores, nonce, attack class) goes into the description-less audit row only via refId; richer detail is logged through the telemetry module's sink, never shown to the user. |
| 10 | i18n | `ApplicationContext.java:111-161` `loadResourceBundle()` / `getBundle(langCode, "labels"|"messages")`; bundles `registration-client/src/main/resources/{labels,messages}_{en,fr,ar}.properties`; controllers use `RegistrationUIConstants.getMessageLanguageSpecific(key)` (`constants/RegistrationUIConstants.java:25`) for alerts and `%key` in FXML for labels via `BaseController.load(url, bundle)` (`BaseController.java:305,345,752`) | Ship `i18n/liveness_{en,fr,ar}.properties` in our jar. `LivenessOverlayController` loads them with `ResourceBundle.getBundle("i18n.liveness", locale)` where `locale = new Locale(ApplicationContext.applicationLanguage())`. The patch also appends the same keys to `messages_*.properties` so `generateAlert(...)` can show them with the existing `#TYPE#` convention. |
| 11 | Packaging | `registration-client/pom.xml:78-108` (jar manifest `Main-Class io.mosip.registration.controller.Initialization`, `maven-dependency-plugin copy-dependencies` → `target/lib`), `registration/configure.sh` (adds SDK jars and `custom-impl.zip` jars to `target/lib`, updates `registration-services-*.jar` with `props/mosip-application.properties`), `registration-services/.../update/SoftwareUpdateHandler.java:75-78,324-337` (`lib/` + `MANIFEST.MF` per-jar SHA checksum, downloaded from `mosip.reg.client.url/<version>/lib/<jar>`), `update/ManifestCreator.java` | Our jars (`liveness-api`, `device-api`, `liveness-core`, `policy`, `telemetry`, `liveness-engine-onnx`, `integration-desktop`, plus `onnxruntime` and `opencv` jars whose natives are **inside the jar** and extracted at runtime) go into `target/lib` either as Maven dependencies of `registration-client` (patch to its pom) or through the existing `reg_client_custom_impls_url` zip mechanism in `configure.sh`. No `.dll/.so` files are shipped loose: the client ships no native files today (none found in the tree) and the updater only knows jars. Model bundle goes next to `lib/` as `models/` and is referenced by `mosip.registration.liveness.model.dir`. |
| 12 | Spring wiring | `registration-services/.../config/AppConfig.java:43-56` `@ComponentScan(basePackages = { "io.mosip.registration", ..., "io.mosip.registration.api.config" })`; context created in `registration-client/.../controller/ClientApplication.java:84` `new AnnotationConfigApplicationContext(AppConfig.class)` | `io.mosip.vivid.*` is **not** scanned. Patch adds `"io.mosip.vivid.desktop.mosip"` to `basePackages`, which picks up `LivenessSpringConfig` (`@Configuration`) that defines the engine/policy/audit/gate beans. Alternative without touching `AppConfig`: put the configuration class in package `io.mosip.registration.api.config` (already scanned) — kept as an option in the patch comments. |
| 13 | Exception / override flow for `onMaxRetries` | `AuthenticationController.java:938` `isValidReviewer`, `RegistrationConstants.REVIEWER_AUTH_CONFIG` (`mosip.registration.reviewer_authentication_configuration`), `SUPERVISOR_APPROVAL_CONFIG_FLAG`; biometric exceptions `GenericBiometricsController.java:1371` `addException`, `RegistrationDTO.addBiometricException` | `ASSISTED_CAPTURE_WITH_SUPERVISOR` maps onto the existing reviewer authentication (`isReviewer=true`) before the capture is re-enabled; `FALLBACK_TO_EXCEPTION_FLOW` maps onto marking the face as a biometric exception (existing exception-photo path). `LOCK_AND_ALERT` disables `scanBtn` and audits. All **inferred**; not exercised. |

---

## 1. How the SBI stream is consumed today (verified)

* Device discovery and spec dispatch: `registration-services/src/main/java/io/mosip/registration/mdm/service/impl/MosipDeviceSpecificationFactory.java`
  (`getDeviceInfoByModality(String)`, `getMdsProvider(String specVersion)`, `isDeviceAvailable(MdmBioDevice)`, `initializeDeviceMap(boolean)`).
* SPI: `registration-services/src/main/java/io/mosip/registration/mdm/integrator/MosipDeviceSpecificationProvider.java`
  ```java
  public interface MosipDeviceSpecificationProvider {
      String getSpecVersion();
      InputStream stream(MdmBioDevice bioDevice, String modality) throws RegBaseCheckedException;     // line 54
      List<BiometricsDto> rCapture(MdmBioDevice bioDevice, MDMRequestDto mdmRequestDto) throws RegBaseCheckedException; // line 67
      List<MdmBioDevice> getMdmDevices(String deviceInfoResponse, int port);
      boolean isDeviceAvailable(MdmBioDevice mdmBioDevice);
  }
  ```
  Implementations: `mdm/spec_0_9_2/service/impl/MosipDeviceSpecification_092_ProviderImpl`, `mdm/spec_0_9_5/.../MosipDeviceSpecification_095_ProviderImpl`,
  `mdm/sbi/spec_1_0/service/impl/MosipDeviceSpecification_SBI_1_0_ProviderImpl` (SBI 1.0 is what 1.2.0 deployments use).
* `MosipDeviceSpecification_SBI_1_0_ProviderImpl.stream(...)` (lines 132-199): builds `http://127.0.0.1:<port>/stream`, custom HTTP verb
  `STREAM` via Apache `RequestBuilder.create("STREAM")`, body `StreamSbiRequestDTO { deviceSubId, serialNo, timeout }`.
  `deviceSubId` for face is `"0"` (`getDeviceSubId`, line 323). Timeout comes from `mosip.registration.capture_time_out` (default `"60000"`).
  It reads **one** JPEG with `MosipDeviceSpecificationHelper.getJPEGByteArray(urlStream, deadline)` to confirm the stream is alive, then
  returns the *same* `InputStream` positioned after the first frame. The stream is **not signed** (plain multipart; only `rcapture` data is a JWT, validated at line 254 `validateJWTResponse(..., rCaptureTrustDomain)`).
* `BioServiceImpl.getStream(MdmBioDevice, String)` (line 143) just delegates; `getStream(String modality)` (line 135) resolves the device first.
* Rendering: `registration-client/src/main/java/io/mosip/registration/controller/device/Streamer.java` (`@Component`, one shared instance).
  `startStream(InputStream, ImageView streamImage, ImageView scanImage)` spawns thread `STREAMER_THREAD` that loops `retrieveNextImage(urlStream)`:
  scans bytes for `Content-Length:`, parses the integer, skips to the first `0xFF`, reads `contentLength` bytes, builds `new javafx.scene.image.Image(new ByteArrayInputStream(bytes))`
  and calls `streamImage.setImage(img)` **from the streamer thread** (not `Platform.runLater`; JavaFX tolerates `ImageView.setImage` off-thread badly but this is what ships).
  `setUrlStream(null)` closes the stream and ends the loop; `stop()` also re-enables auto-logout.
* Frame format details the stream parser must honour (from `Streamer.retrieveNextImage` and `MosipDeviceSpecificationHelper.getJPEGByteArray`):
  the header block ends at the first CR/LF after `Content-Length: N`; the JPEG starts at the next `0xFF` byte; payload length is `N` bytes starting at that `0xFF`.
  Resolution and frame rate are **not** advertised anywhere in the client; they are whatever the SBI sends (MockMDS sends a static JPEG repeatedly). `DeviceCapabilities` must be populated from the first decoded frame.

## 2. Face capture flow (verified)

Controller `GenericBiometricsController` (`registration-client/.../controller/device/GenericBiometricsController.java`, FXML `/fxml/GenericBiometricFXML.fxml`, `fx:controller` at FXML line 13; preview `ImageView fx:id="biometricImage"` at FXML line 99 inside `GridPane biometricPane`).

1. `scan(ActionEvent)` (line 429): audits `REG_BIO_FACE_CAPTURE` (via `getAuditEventForScan`, line 786), runs `deviceSearchTask` (`deviceSpecificationFactory.getDeviceInfoByModality("FACE_FULL FACE")`).
2. `deviceSearchTask.setOnSucceeded` (line 470): `SessionContext.setAutoLogout(false)`; `urlStream = bioService.getStream(mdmBioDevice, "FACE_FULL FACE")` (line 480); checks `urlStream.read() != -1`;
   **starts `rCaptureTaskService()` (line 491) *before* `streamer.startStream(urlStream, biometricImage, biometricImage)` (line 492)**.
   So today `rcapture` is already in flight while the preview is shown; the SBI decides when to capture (quality/timeout). This is the exact place liveness must be inserted: open the session on the stream, and only call `rCaptureTaskService()` after `PASSED`.
3. `rCapture(fieldId, modality)` (line 742) builds `MDMRequestDto("FACE_FULL FACE", exceptions, "Registration", <server profile>, captureTimeout, count, (int) bioService.getMDMQualityThreshold(FACE))` and calls `bioService.captureModality(...)` →
   `MosipDeviceSpecification_SBI_1_0_ProviderImpl.rCapture` (line 202): `RCAPTURE` verb to `/capture`, JWT-validates each biometric, decodes payload to `SbiRCaptureResponseDataDTO`, returns `BiometricsDto(bioAttribute, decodedBioValue /*ISO 19794-5 bytes*/, qualityScore)` with `payLoad`, `signature`, `specVersion`.
4. Quality: `BioServiceImpl.captureModality` (line 81) validates score range `[0,100]` and, if `mosip.registration.quality_check_with_sdk=Y`, sets `sdkScore` via `getSDKScore` (`BioAPIFactory.getBioProvider(FACE, QUALITY_CHECK).getQualityScore`). Threshold `mosip.registration.face_threshold` (`BioServiceImpl.getMDMQualityThreshold`, line 252).
5. `rCaptureTaskService.setOnSucceeded` (line 590): `isValidBiometric` (line 724) → local de-dup `identifyInLocalGallery` (line 1102) → increments `RegistrationDTO.ATTEMPTS["<fieldId>_FACE"]` → `biometricsDto.setNumOfRetries(...)` → `fxControl.setData(biometricsMap)` (stores into `RegistrationDTO` via `BiometricFxControl`) → `addStreamImageAndScoreToCache` (line 656) which for `FACE` calls `FaceDecoder.convertFaceISOToImageBytes(ConvertRequestDto{version="ISO19794_5_2011", inputBytes=attributeISO})` and caches the JPEG in `RegistrationDTO.BIO_CAPTURES` and the scores in `BIO_SCORES`/`SDK_SCORES`.
   `finally`: `SessionContext.setAutoLogout(true)`, `streamer.setUrlStream(null)`, pane re-enabled.
6. Packet: `registration-services/.../service/packet/impl/PacketHandlerServiceImpl.java:195-211` builds `metaInfoMap`; `setBiometrics(registrationDTO, metaInfoMap)` (line 200) writes BIRs; officer/supervisor BIRs lines 203-205; `setMetaInfo` (line 283) → `setOperationsData` (329) / `setOthersMetaInfo` (373) / `setMetaData` (299) → `packetWriter.addMetaInfo(rid, metaInfoMap, source, process)` (line 211).
   **A free-form entry can be added here**: `metaInfoMap.put("livenessSummary", json)` next to `"blockListedWords"` (line 326). The packet manager carries `metaInfo` as a string map, so no schema change is needed on the client side (server-side consumers ignore unknown keys — inferred).
7. Older duplicate `BiometricsController` (`controller/device/BiometricsController.java:939-963`) has the same stream/capture pattern; it is still compiled but `GenericBiometricsController` is what the dynamic UI (`BiometricFxControl`) opens (`RegistrationConstants.BIOMETRIC_FXML = "/fxml/GenericBiometricFXML.fxml"`, `init(...)` line 1394).

## 3. Operator and supervisor authentication (verified)

Three entry points, all end in `AuthenticationServiceImpl.authValidator(String userId, String modality, List<BiometricsDto>)` (`registration-services/.../service/security/impl/AuthenticationServiceImpl.java:86`), which loads the user's enrolled BIRs (`userDetailDAO.getUserSpecificBioDetails`), builds sample BIRs with `BIRBuilder.buildBir(BiometricsDto, ProcessedLevelType.RAW)` and calls `bioAPIFactory.getBioProvider(FACE, MATCH).verify(sample, record, FACE, null)` (local biometric SDK, line 122-134 `verifyBiometrics`).

| Flow | Controller | Capture call | Match gate | Supervisor vs operator |
|---|---|---|---|---|
| Application login | `controller/auth/LoginController.java:599` `streamFace()` (preview), `:660` `captureFace(ActionEvent)` → `executeValidationTask(...)` (676) → `SessionContext.create(userDTO, "Face", ...)` | `SessionContext.java:429` `bioService.captureModalityForAuth(MDMRequestDto("FACE_FULL FACE", null, "Registration", profile, captureTimeout, 1, mosip.face_authentication.quality_score))` | `SessionContext.java:430` `authService.authValidator(userId, "Face", biometrics)` → `createSessionContext()` | operator only (`Workflow.OPERATOR`) |
| Packet operator / reviewer (supervisor) auth | `controller/auth/AuthenticationController.java` (FXML `OperatorAuthentication.fxml`, `faceImage` at FXML line 830, `faceScanButton` line 867 → `scan()` line 911 → `validateFace()` line 500 → `executeFaceValidationTask` line 515) | `BaseController.java:1713` `captureAndValidateFace(userId, isPacketAuth=true, isReviewer)` → `bioService.captureModalityForAuth` | same method, line 1726: `authenticationService.authValidator(userId, SingleType.FACE.value(), biometrics)`; on success `addOperatorBiometrics` → `RegistrationDTO.addSupervisorBiometrics` / `addOfficerBiometrics` (`RegistrationDTO.java:293/297`) | `isReviewer` field of `AuthenticationController` (`true` = supervisor/reviewer flow, audit `REG_REVIEWER_AUTH_FACE`; `false` = operator, `REG_OPERATOR_AUTH_FACE`). There is also `REG_SUPERVISOR_AUTH_FACE` (`REG-EVT-101`) used by EOD approval. |
| EOD approval auth | `controller/eodapproval/EODAuthenticationController.java` | OTP/password only in this branch (line 431) — no face capture found | n/a | n/a |

Face for auth uses **`captureModalityForAuth`**, which is a thin device-availability check over the same `rcapture` (`BioServiceImpl.java:125`); there is no plain `/capture` (auth-capture) call in the desktop client at all. The preview for auth is `streamer.startStream(bioService.getStream("FACE_FULL FACE"), faceImage, null)` (`AuthenticationController.startStream()` line 484, `LoginController.streamFace()` line 599) and it is **not** stopped before `captureModalityForAuth`, so the SBI serves stream and rcapture concurrently.

## 4. Configuration (verified)

* In-memory map: `io.mosip.registration.context.ApplicationContext` (`registration-services/.../context/ApplicationContext.java`):
  `static Map<String,Object> map()` (line 192), `getApplicationMap()` (236), `setApplicationMap(Map)` (245), `setGlobalConfigValueOf(code,val)` (264),
  `getIntValueFromApplicationMap` (284), `getStringValueFromApplicationMap` (290), `getFloatValueFromApplicationMap` (296). Values are **strings** (`GlobalParamDAOImpl.getGlobalParams`, line 46, trims `param.getVal()`).
* Filled at startup by `ClientApplication.setupAppProperties()` (`registration-client/.../controller/ClientApplication.java:238-243`): `globalParamService.getGlobalParams()` + `localConfigService.getLocalConfigurations()` (per-machine "local preferences", `LocalConfigService`), and lazily by `BaseService.getGlobalConfigValueOf(key)` (line 343) if the map is empty.
* Sync: `GlobalParamServiceImpl.synchConfigData(boolean isJob)` (line 101) → `serviceDelegateUtil.get("get_registration_center_config", {key_index}, ...)` (line 156) → `saveGlobalParams` → `global_param` table (`GlobalParamDAO`). Keys containing `kernel`, `mosip.primary`, `mosip.biometric.sdk`, `languages` trigger a restart prompt (`isPropertyRequireRestart`, line 251). Our `mosip.registration.liveness.*` keys do not match those patterns, so they hot-reload on next sync (but `ApplicationContext.map()` is only refreshed by `updateApplicationMap`, line 363, for changed keys — fine).
* Offline: the table persists across restarts; the first run before any sync has an empty map, which is why `DesktopLivenessConfig` ships compiled-in defaults (= `LivenessPolicy.defaults(workflow)`).
* Face keys that exist today (`constants/RegistrationConstants.java`):
  | Constant | Key | Used by |
  |---|---|---|
  | `FACE_THRESHOLD` (952) | `mosip.registration.face_threshold` | `BioServiceImpl.getMDMQualityThreshold` |
  | `FACE_RETRY_COUNT` (987) | `mosip.registration.num_of_face_retries` | `BioServiceImpl.getRetryCount` |
  | `PHOTO_RETRY_COUNT` (988) | `mosip.registration.num_of_photo_retries` | exception photo |
  | `CAPTURE_TIME_OUT` (809) | `mosip.registration.capture_time_out` | stream + rcapture timeout (ms, string) |
  | `FACE_AUTHENTICATION_THRESHOLD` (808) | `mosip.face_authentication.quality_score` | auth capture requested score |
  | `QUALITY_CHECK_WITH_SDK` (1267) | `mosip.registration.quality_check_with_sdk` | `Y`/`N` |
  | `MDS_RESP_ALLOWED_LAG_MINS` (1599) | `mosip.registration.mds.allowed.lag.mins` | rcapture JWT freshness |
  | `SERVER_ACTIVE_PROFILE` (1596) | `mosip.registration.server_profile` | `env` in MDM requests |
  Static strings: `FACE_FULLFACE = "FACE_FULL FACE"` (197), `BIOMETRIC_FXML = "/fxml/GenericBiometricFXML.fxml"` (1805), `LABELS="labels"`, `MESSAGES="messages"` (1809-1810).
* `registration-default.properties` itself is **not** in this repo (it lives in `mosip-config`); the client only has `spring.properties` and `props/mosip-application.properties` (environment/update URLs). Not read here.

## 5. Audit (verified)

* Service: `registration-services/.../audit/AuditManagerService.java` — `void audit(AuditEvent auditEventEnum, Components appModuleEnum, String refId, String refIdType)`; impl `AuditManagerSerivceImpl` (sic) line 54 (fills host, overrides `refId` with the registration id when the event id contains `REGISTRATION_EVENTS` and a `RegistrationDTO` is in session, otherwise with `SessionContext.userId()`), persists through `AuditDAO` into `reg.audit_log`, which the existing client-to-server sync uploads.
* Controllers hold it as `protected AuditManagerService auditFactory` (`BaseController.java:147`); usage pattern:
  `auditFactory.audit(AuditEvent.REG_BIO_FACE_CAPTURE, Components.REG_BIOMETRICS, SessionContext.userId(), AuditReferenceIdTypes.USER_ID.getReferenceTypeId());`
* `AuditEvent` enum (`constants/AuditEvent.java`): `NAME("REG-XXX-nnn", AuditEventType.USER_EVENT|SYSTEM_EVENT .getCode(), "NAME", "description")`; 170 entries; face-related today: `LOGIN_WITH_FACE` (REG-AUTH-008), `REG_BIO_FACE_CAPTURE` (REG-EVT-039), `REG_BIO_EXCEP_FACE_CAPTURE` (040), `REG_OPERATOR_AUTH_FACE` (054), `REG_REVIEWER_AUTH_FACE` (063), `REG_SUPERVISOR_AUTH_FACE` (101). Highest `REG-EVT` id is **103** (`REG_BLOCKLISTED_WORD_REJECTED`). Adding an event = adding one enum constant; there is no DB registry of event ids on the client.
* `Components` enum (`constants/Components.java`): `NAME("REG-MOD-nnn", "label")`, last is `CLIENT_UPGRADE("REG-MOD-152")`. Adding a component = one enum constant. We add `LIVENESS("REG-MOD-153", "Liveness and PAD")` and events `REG-LIV-001..016` (own prefix so they cannot collide with upstream additions to `REG-EVT`).
* `AuditReferenceIdTypes`: `USER_ID`, `REGISTRATION_ID`, `APPLICATION_ID`.

## 6. i18n (verified)

* Bundles: `registration-client/src/main/resources/labels_{en,fr,ar}.properties` (607/608/443 lines) for FXML `%key` labels and `messages_{en,fr,ar}.properties` (473/470/475 lines) for alerts; loaded per configured language in `ApplicationContext.loadResourceBundle()` (line 111-150: `ResourceBundle.getBundle("labels", locale)` / `("messages", locale)` into `resourceBundleMap` keyed `<lang>_labels` / `<lang>_messages`), accessor `ApplicationContext.getBundle(langCode, "labels")` (161). Application language: `ApplicationContext.applicationLanguage()` (201), RTL check `isLanguageRightToLeft(langCode)` (80).
* Controllers: `RegistrationUIConstants.getMessageLanguageSpecific(key)` (`constants/RegistrationUIConstants.java:25`, reads `bundle = ApplicationContext.getInstance().getApplicationLanguageMessagesBundle()`), then `generateAlert(String title, String context)` (`BaseController.java:386`). Message values embed the alert type: `BIOMETRIC_CAPTURE_SUCCESS = Biometrics Captured Successfully#TYPE#SUCCESS`, `STREAMING_ERROR = ...#TYPE#ERROR`, `FACE_MATCH = Face match not found` (no type → plain).
* FXML: `BaseController.load(URL, ResourceBundle)` / `loadChild(URL)` (752) pass the labels bundle to `FXMLLoader`, so `%liveness.hint.blink` in our FXML would resolve only if the key is in `labels_*.properties` — therefore our overlay controller resolves keys itself from `i18n/liveness_*.properties` and sets text programmatically; the FXML uses no `%` keys.

## 7. Packaging (verified, from the build files that are in the tree)

* `registration/pom.xml`: Java 11 (`maven.compiler.source/target 11`), `openjfx.version 11.0.2`; modules `registration-api`, `registration-api-stub-impl`, `registration-services`, `registration-client`, `registration-test`.
* `registration-client/pom.xml`: depends on `registration-services`, `org.openjfx:javafx-{fxml,graphics,swing,media,controls,web}`; `maven-jar-plugin` with `Main-Class: io.mosip.registration.controller.Initialization` and `addClasspath=true` (so `lib/*.jar` on the manifest classpath); `maven-dependency-plugin:copy-dependencies` → `target/lib`; `javafx-maven-plugin 0.0.3`; `maven-antrun-plugin` makes the jar executable. A legacy `fx_lib/jfxrt.jar` sits in client resources.
* `registration/configure.sh` (run by the Dockerfile `ENTRYPOINT`): writes `props/mosip-application.properties` into `registration-services-<v>.jar`, downloads `resources.zip` into the client jar, downloads the biometric SDK zip (or `mock-sdk.jar`) and an optional `custom-impl.zip` whose `*.jar` are copied into `target/lib`, builds the per-jar checksum `MANIFEST.MF` (`ManifestCreator`). The client later self-updates from `mosip.reg.client.url/<version>/lib/<jar>` (`SoftwareUpdateHandler.java:324-337`) with checksum validation.
* Natives: there are **no** `.dll`/`.so` files in the client tree; everything is a jar. ONNX Runtime (`com.microsoft.onnxruntime:onnxruntime`) and OpenCV (`org.openpnp:opencv`) both carry their natives inside the jar and extract to a temp dir at load — compatible with the `lib/` + manifest updater without changes. Size impact (not measured here, from artifact sizes): onnxruntime ~ 25 MB per platform jar, openpnp opencv ~ 90 MB multi-platform; recommend the model-bundle directory `lib/../models` outside the jar.
* Not found / not searched: there is no `run.bat`, `.jnlp` or `mosip-sw` reference in this branch's tree (searched `find -name '*.bat' -o -name '*.jnlp'` and `grep -r mosip-sw`); the Windows launcher lives in the separate `mosip/registration-client` release zip / `mosip-infra` build, not in this repo.

## 8. Spring wiring (verified)

* `AppConfig` (`registration-services/.../config/AppConfig.java:39-60`): `@Configuration @EnableAspectJAutoProxy @Import({DaoConfig, AuditConfig, TemplateManagerBuilderImpl}) @EnableJpaRepositories(basePackages="io.mosip.registration") @ComponentScan(basePackages={"io.mosip.registration", "io.mosip.kernel.idvalidator", ..., "io.mosip.kernel.biosdk.provider.impl", "io.mosip.kernel.biosdk.provider.factory", "io.mosip.commons.packet", "io.mosip.registration.api.config"}) @PropertySource({"classpath:spring.properties", "classpath:props/mosip-application.properties"})`.
  `DaoConfig` additionally scans `io.mosip.kernel.core`, `io.mosip.kernel.clientcrypto.service.impl`, `io.mosip.kernel.partnercertservice.*`.
* Context: `ClientApplication.java:84` `new AnnotationConfigApplicationContext(AppConfig.class)`; JavaFX controllers are Spring beans (`@Controller`) fetched with `applicationContext.getBean(...)` inside `BaseController.load` — so our `LivenessOverlayController` must **not** be a Spring bean (it is instantiated by `FXMLLoader` per overlay) while `LivenessGate` and hooks are.
* Conclusion: a bean in `io.mosip.vivid.*` is **not** discovered. Options: (a) patch `AppConfig` to add `"io.mosip.vivid.desktop.mosip"`; (b) place our `@Configuration` under the already-scanned `io.mosip.registration.api.config` package (the `registration-api` module's extension-point package, which today holds nothing in this branch — `registration-api` only has `docscanner` and `geoposition` SPIs). The patch does (a) and documents (b).

## 9. Retry handling (verified)

* Existing: `GenericBiometricsController.updateBiometric(...)` (line 830) → `createQualityBox(retryCount, threshold)` (914) draws `retryAttempt_<n>` labels; `setCapturedValues` (876) disables `scanBtn` when `retry == bioService.getRetryCount(currentModality)` (903). Attempt counter is `RegistrationDTO.ATTEMPTS` keyed `"<fieldId>_FACE"`, incremented only on a **successful** `rcapture` (line 612-613), so failed device captures do not burn retries.
* Composition rule adopted: liveness retries are internal to a scan click. The retry boxes keep counting successful captures; liveness failures never increment `ATTEMPTS`. When the liveness session reaches `LOCKED`, the hook disables `scanBtn` like the existing max-retry path and audits `MAX_RETRIES_REACHED`; recovery follows `onMaxRetries` (see row 13).

## 10. What was not found / not verified

* The actual `registration-default.properties` (mosip-config repo) was not cloned; key defaults quoted above are from code paths, not from the properties file.
* `registration-test`, `registration-api-stub-impl` and `src/test` trees were excluded from the sparse checkout.
* `BiometricFxControl.setData` (where biometrics land in `RegistrationDTO`) was not opened; the call at line 633 was taken as the storage point.
* No class was compiled or run; FXML overlay placement over `biometricPane` is inferred from the FXML structure (`GridPane biometricPane` containing the `ImageView`), and the patch wraps the `ImageView` in a `StackPane` at runtime rather than editing the FXML.
* `develop-java21` was not diffed against `release-1.2.0.x`.

## 11. Where this is implemented

`integration-desktop/src/mosip/patches/0001..0007-*.patch` implement rows 3-12 of the table in section 0 and pass
`git apply --check` against `release-1.2.0.x@c1cc4e7f` (the sources use CRLF line endings; the patches preserve them).
The hooks they call live in `integration-desktop/src/mosip/java/io/mosip/vivid/desktop/mosip/`
(`ResidentFaceLivenessHook`, `OperatorAuthLivenessHook`, `LivenessCaptureVerifier`, `LivenessOverlayInstaller`,
`MosipLivenessAuditAdapter`, `LivenessSpringConfig`). Note: `BaseController.applicationContext` is MOSIP's
`io.mosip.registration.context.ApplicationContext`, not Spring's; Spring beans are fetched with
`ClientApplication.getApplicationContext().getBean(...)` (as `BaseController.load` does at line 346), while
`SessionContext` holds a static Spring `applicationContext` (line 54).
