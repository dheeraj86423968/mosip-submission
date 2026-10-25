# model-bundle

Signed, versioned model bundles (`.mlb`) for the MOSIP Vivid engines (PLAN.md section 6), with secure offline update
and rollback. The module needs only `java.security`, Jackson and SLF4J.

## Format

An `.mlb` is a zip file:

```
manifest.json      # the signed document
manifest.sig       # base64(SHA256withECDSA or SHA256withRSA over the exact manifest.json bytes)
<model files>      # paths as listed in the manifest
selftest/...       # optional self-test images
```

```json
{
  "bundleId": "mosip-vivid-desktop",
  "version": "1.2.0",
  "platform": "jvm",
  "minEngineVersion": "0.1.0",
  "models": [
    { "name": "yunet", "file": "face_detection_yunet_2023mar.onnx", "sha256": "8f23...", "inputShape": [1,3,640,640], "purpose": "face-detection" },
    { "name": "minifas", "file": "antispoof_minifasnetv2se_128.onnx", "sha256": "af23...", "inputShape": [1,3,128,128], "purpose": "anti-spoofing" },
    { "name": "sface", "file": "face_recognition_sface_2021dec.onnx", "sha256": "0ba9...", "inputShape": [1,3,112,112], "purpose": "embedding" }
  ],
  "calibration": { "cnn_texture": { "platt_a": 1.0, "platt_b": 0.0, "threshold": 0.9 } },
  "selfTest": [ { "file": "selftest/live_01.jpg", "expectedSignal": "cnn_texture", "expectedMin": 0.0, "expectedMax": 0.3 } ]
}
```

## Verification (`BundleVerifier`)

1. `manifest.json` and `manifest.sig` must both be present.
2. The signature is checked against the **pinned** public key(s) *before* the JSON is parsed. Several keys may be
   trusted at once to allow key rotation.
3. The manifest is validated: semver fields, platform, safe relative paths, and 64-hex hashes.
4. Unsafe zip entries are refused (zip-slip). Every model's SHA-256 must match, and every self-test file must exist.

Any failure throws `BundleRejectedException` with a stable `Reason`:

* `MISSING_MANIFEST`, `MISSING_SIGNATURE`, `BAD_SIGNATURE`, `MALFORMED_MANIFEST`
* `MISSING_FILE`, `HASH_MISMATCH`, `UNSAFE_PATH`
* `INCOMPATIBLE_PLATFORM`, `INCOMPATIBLE_ENGINE`
* `NOT_INSTALLED`, `IO_ERROR`

Every rejection reaches a `BundleEventListener`, which is the audit hook.

## Store, update channels and rollback

* `BundleStore` uses the layout `<root>/<bundleId>/<version>/` plus `store.json` (the active pointer and activation
  history).
* `install` verifies, then extracts into a temporary directory and renames it into place atomically.
* `activate` and `current()` **re-verify from disk**, so tampering after install is caught.
* `rollback()` walks back through the activation history. `keepPrevious = N` prunes older versions.
* `BundleCompatibility` enforces platform equality and `engineVersion >= minEngineVersion`, with an optional cap on
  the major version.
* `BundleImporter` is the offline channel (a USB stick or a local folder, optionally recursive). It reports per-file
  outcomes and can activate the newest bundle. An online channel downloads to a temporary file and calls the same
  path.
* Automatic rollback on self-test failure is a few lines in the host:

  ```java
  store.activate(id, v);
  if (!OnnxEngineFactory.fromStore(store).selfTest().passed) store.rollback();
  ```

## Command line

```sh
java -cp model-bundle.jar:<deps> io.mosip.vivid.bundle.BundleTool <command>
  keygen   --out-dir keys [--rsa]
  create   --dir models --manifest manifest.json --key keys/bundle-signing.key --out desktop-1.2.0.mlb
  verify   --bundle desktop-1.2.0.mlb --pubkey keys/bundle-signing.pub [--engine-version 0.1.0]
  install  --store /opt/vivid/bundles --bundle desktop-1.2.0.mlb --pubkey keys/bundle-signing.pub --activate
  import   --store /opt/vivid/bundles --from /media/usb --pubkey keys/bundle-signing.pub --recursive --activate
  list     --store /opt/vivid/bundles
  activate --store ... --pubkey ... --id ID --version V
  rollback --store ... --pubkey ...
```

`create` computes every hash itself, so the template manifest may leave `sha256` empty. Keys are PKCS#8 / X.509 PEM
or DER. EC P-256 is recommended, and RSA is supported.

## Tests

29 tests run against keys generated per run and tiny fake model files. They cover:

* tampered manifest, tampered model, wrong key, RSA keys;
* missing pieces, zip-slip and garbage archives;
* on-disk tampering after install;
* compatibility, pruning and repeated rollback;
* USB-style import with a rejected bundle in the batch;
* the CLI end to end.
