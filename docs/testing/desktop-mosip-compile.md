# Desktop Registration Client: compiling the MOSIP-side integration

Date: 2026-10-24. Scope: `integration-desktop/src/mosip/patches/*.patch` and `integration-desktop/src/mosip/java/io/mosip/vivid/desktop/mosip/*`
built against the real MOSIP Desktop Registration Client.

## Result in one paragraph

All seven patches apply with `git apply` to `mosip/registration-client` **`release-1.2.0.x` @ `c1cc4e7fc1625ccc6ef26282910934bfcb9d164c`**
(2026-09-21; the branch the recon doc targets, newest commit on it; newest tag is `v1.2.0.2`). With them applied,
**registration-services compiles with the client's own toolchain (JDK 11, source/target 11)**, and
**registration-client compiles, including our 7 `io.mosip.vivid.desktop.mosip` classes (78 source files, `BUILD SUCCESS`)**,
but only with **JDK 21 `javac --release 11`**: JDK 11's javac cannot read the mosip-vivid jars (class file 61 = Java 17).
The client output stays Java 11 bytecode (major 55). Five existing registration-services test classes that cover the
touched code pass (59 tests, 0 failures, 1 skipped by the client itself). **Nothing was run**: the shipped client
runs on a Zulu **11** JRE, which cannot load our Java 17 jars (see "Runtime blocker").

## Commands (rerunnable)

Everything is in `integration-desktop/src/mosip/compile-against-client.sh`:

```
mvn install                                                      # at the mosip-vivid root: puts io/mosip/vivid jars in ~/.m2
RUN_TESTS=1 integration-desktop/src/mosip/compile-against-client.sh <work-dir> [<maven-local-repo>]
```

What it does:

1. `git clone --depth 1 -b release-1.2.0.x https://github.com/mosip/registration-client` (about 240 MB on disk).
2. `git apply --check` then `git apply` for each `src/mosip/patches/000N-*.patch` and copies `src/mosip/java/.../mosip/`
   into `registration/registration-client/src/main/java/io/mosip/vivid/desktop/mosip/`.
3. `JAVA_HOME=jdk-11 mvn -DskipTests -pl registration-api,registration-api-stub-impl,registration-services -am install`
4. `JAVA_HOME=jdk-21 mvn -Dmaven.test.skip=true -Dlombok.version=1.18.30 -Dmaven.compiler.release=11 -pl registration-client compile`
   (Lombok 1.18.16 from the client pom does not run on JDK 21, so the version is overridden; bytecode target is unchanged.)
5. With `RUN_TESTS=1`: `JAVA_HOME=jdk-11 mvn -pl registration-services test -Dtest=PacketHandlerServiceTest,AppConfigTest,AuditFactoryTest,LoginServiceTest,BioServiceTest`.
   On a machine with little RAM, add `-DargLine=-Xmx512m` and `MAVEN_OPTS=-Xmx700m` (the first attempt here was OOM-killed).

All dependencies resolved from Maven Central (`repo1.maven.org`); the pom's `oss.sonatype.org` snapshot repository
was not needed. The dedicated local repository came to about 540 MB.

## What compiled

| Step | Toolchain | Result |
|---|---|---|
| registration-api, registration-api-stub-impl, registration-services (483 files) with patches 0001, 0002 (DTO + packet metaInfo), 0004, 0005, 0006 | JDK 11.0.x, `source/target 11`, Lombok 1.18.16 | compiled, installed |
| registration-client (78 files) with patches 0002, 0003, 0006 (messages), 0007 (pom) + our 7 classes | JDK 11 | **failed**: `class file has wrong version 61.0, should be 55.0` (38 errors, every mosip-vivid type) |
| same | JDK 21 `--release 11`, Lombok 1.18.30 | **compiled** (`BUILD SUCCESS`, client classes major 55) |
| registration-services tests: BioServiceTest 16, AuditFactoryTest 4, AppConfigTest 3 (1 skipped by the client), LoginServiceTest 26, PacketHandlerServiceTest 10 | JDK 11 | 59 run, 0 failures, 0 errors |

The client has no `src/test` in `registration-client`, so there are no existing tests for `GenericBiometricsController`,
`BaseController` or `AuthenticationController`; `SessionContext` has no direct test either (LoginServiceTest is the nearest).

## What had to be fixed

1. **Patch 0004 could never have compiled (circular dependency).** It made `SessionContext` (in registration-services)
   reference `io.mosip.vivid.desktop.mosip.OperatorAuthLivenessHook`, which lives in registration-client, and
   registration-client depends on registration-services. The first build failed with 7 `package io.mosip.vivid... does not exist`
   errors. Fix: patch 0004 now adds a small interface
   `registration-services/.../service/bio/FaceAuthLivenessGuard` (`beforeFaceAuthCapture(userId)`,
   `afterFaceAuthCapture(userId, biometrics)`); `SessionContext.validateFace` looks it up with
   `applicationContext.getBeansOfType(FaceAuthLivenessGuard.class)` (Spring 5.0.6 has no `getBeanProvider`) and skips
   it when no bean exists, so an unmodified client classpath behaves as today. `OperatorAuthLivenessHook` implements it.
2. **slf4j.** The vivid jars pull `slf4j-api 2.0.16`, which Maven picked over the client's `slf4j-api 1.6.6`
   (the client's binding is logback-classic 1.2.3, which slf4j 2.x ignores, so client logging would have become NOP).
   Patch 0007 now excludes `org.slf4j:slf4j-api` from our three dependencies; the resulting client classpath is exactly
   the original one plus our 8 jars and `onnxruntime-1.20.0.jar` (checked by diffing `dependency:build-classpath`).
   slf4j 1.6 has no varargs `info(String, Object...)`, so four log calls in `src/mosip/java` now pass `new Object[] {...}`.
   Every slf4j method our jars call (checked with `javap -c`) exists in 1.6.6.
3. Not compile errors, but fixed while there:
   * `LivenessSpringConfig.livenessEngine` threw if no engine, a failed self-test or a missing model. That would abort
     `AnnotationConfigApplicationContext` and stop the whole client from starting (sync, finger and iris capture, login).
     Now it returns an `UnavailableLivenessEngine` whose `newAnalyzer()` throws. `LivenessGate` maps that to `ENGINE_ERROR`,
     so face capture still fails closed while the rest of the client keeps working.
   * `ResidentFaceLivenessHook.isOnline()` treated "server profile key present" as online. It now uses the client's own
     `ServiceDelegateUtil.isNetworkAvailable()`, called on the worker thread because it makes an HTTP request.

Apart from these, `src/mosip/java` compiled unchanged. All MOSIP names it assumed exist with those signatures:
`MdmBioDevice.getDeviceMake/getDeviceModel/getSerialNumber/getSpecVersion/getCertification`,
`BioService.getStream(String)`, `AuditManagerService.audit(AuditEvent, Components, String, String)`,
`SessionContext.userId()`, `ClientApplication.getApplicationContext()`, `FaceDecoder.convertFaceISOToImageBytes`,
`RegistrationDTO` fields, and `fxControl.getUiSchemaDTO().getId()`.

## Runtime blocker: Java version

* The client is shipped with **Zulu JRE 11.0.8 (with FX)** (`registration/configure.sh` downloads `zulu11.41.23-ca-fx-jre11.0.8-win_x64.zip`,
  `run.bat` runs `jre\bin\javaw`). All mosip-vivid jars are **class file 61 (Java 17)**. On that JRE the first load of any
  vivid class throws `UnsupportedClassVersionError`. Because the scanned `LivenessSpringConfig` references those types,
  this would happen while the Spring context starts. **The integration as built cannot run in the 1.2.0.x client today.**
* Retargeting the vivid modules to Java 11 is not a flag change. Compiling their sources with `javac --release 11` stops at the parse stage
  with 14 language-level errors: 11 records (`policy`: PolicyDocument, DiagnosticsPolicy, PolicyLoadReport, IdentityPolicy,
  WorkflowPolicy, SignalPolicy, ActivePolicy, DevicePolicy, SignedPolicy; `liveness-core`: FusionResult, QualityGate),
  a switch rule (`liveness-api` LivenessPolicy:64) and `instanceof` pattern matching (`liveness-engine-onnx` OnnxFrameAnalyzer:100).
  There may be more Java 12+ API uses behind these; that was not measured.
* `integration-desktop/src/main` alone **does** compile with `javac --release 11` against the openjfx 11.0.2 jars the
  client ships. This was checked on 2026-10-24 against the sources at that time.
* Options: (a) target the client's `develop-java21` line, which upstream is moving to (not built here), or ship a 17+ JRE
  with 1.2.0.x; (b) backport the listed modules to Java 11 (records become final classes, plus the two syntax spots).

## JavaFX 11 vs 21

* `integration-desktop/src/main/java` (including `ui.LivenessOverlayController`) compiles with JDK 21 `javac --release 11`
  against `javafx-{base,graphics,controls,fxml,...}-11.0.2`. So it uses no Java API newer than 11 and no JavaFX API missing in 11.0.2.
  javac checks the JavaFX members against the 11.0.2 jars because they are on the classpath.
* `LivenessOverlay.fxml` declares `xmlns="http://javafx.com/javafx/11"`. It uses only long-standing elements
  (AnchorPane, StackPane, VBox, HBox, Label, Button, Hyperlink, ImageView, TextArea, Ellipse) and properties.
  The CSS uses only standard `-fx-*` properties. The FXML was **not** loaded at runtime because there is no display or JavaFX toolkit here.

## Not verified

* Nothing was started: no Spring context, no JavaFX, no SBI/MockMDS, no packet. Bean wiring (generic `Supplier`/`BiFunction`
  beans, `destroyMethod`s, component scan of `io.mosip.vivid.desktop.mosip`) is only type-checked.
* `RegistrationUIConstants.getMessageLanguageSpecific` reads the `messages` bundle of the selected language. Patch 0006 adds keys to
  `messages_en.properties` only, so a French or Arabic operator would see the raw key (the client logs an error and returns the key).
* `develop-java21` and other release lines were not built.

## Follow-up (24 Oct 2026): Java 11 retarget

The runtime blocker above is fixed in the mosip-vivid build. The main code of the nine modules that ship in a client
(liveness-api, device-api, telemetry, policy, model-bundle, liveness-core, device-sbi-stream, liveness-engine-onnx,
integration-desktop) is now compiled with `--release 11`: the root pom sets `maven.compiler.release` 11 and
`maven.compiler.testRelease` 17. Tests stay on Java 17. e2e-tests, testkit, device-mock and demo-desktop set
`maven.compiler.release` back to 17.

* The source changes: the 11 records listed above, plus `MultipartStreamReader.Part` and `DeliveryQueue.Item` in
  device-sbi-stream, are now final classes. They keep the same accessors, constructors, validation and
  equals/hashCode/toString. The policy classes carry `@JsonCreator`/`@JsonProperty`. The JSON written for the built-in
  policy is byte-identical to the record version. The switch rule became a classic switch, and the three
  `instanceof` patterns became casts. `java.util.random.RandomGenerator` became `java.util.Random` in
  `ChallengeSelector` and `LivenessOrchestrator` (production passes a `SecureRandom`). With these fixes, every main
  source compiles at `--release 11`. No Java 12+ API calls turned up.
* Checked: `javap -v` reports `major version: 55` for every class in the nine jars. On JDK 11.0.31, all 249 classes
  load (`Class.forName` plus reflection over members), and a policy load, JSON round trip and unknown-key rejection run
  correctly. JDK 11's `javac` compiles code against the installed jars.
* `compile-against-client.sh`, step 2: JDK 11's `javac` was failing only because it could not read class file 61. It
  can now read the vivid jars, so step 2 should run under `JAVA_HOME=$JDK11` without the Lombok override. This has
  **not** been re-run against a fresh clone, and the script still uses JDK 21 `--release 11` for that step. Both
  produce Java 11 bytecode.
* Still to check: the jars have not been loaded inside the real 1.2.0.x client on Zulu 11. integration-desktop now
  builds at `--release 11` against openjfx 21.0.5 (the pom's version). It was checked against openjfx 11.0.2 only
  before this change, so recompile once against the client's openjfx 11 to confirm.
