/**
 * Signed, versioned model bundles ({@code .mlb}) for the MOSIP Vivid liveness engines.
 *
 * <p>A bundle is a zip archive containing a {@code manifest.json}, a detached {@code manifest.sig}
 * (base64 signature over the exact manifest bytes) and the model and self-test files the manifest lists.
 * The pipeline is:
 *
 * <ol>
 *   <li>{@link io.mosip.vivid.bundle.BundleVerifier} checks the signature against a pinned public key, then every
 *       listed file's SHA-256, and refuses the bundle with a typed {@link io.mosip.vivid.bundle.BundleRejectedException}
 *       otherwise.</li>
 *   <li>{@link io.mosip.vivid.bundle.BundleCompatibility} enforces the engine-version / {@code minEngineVersion} matrix.</li>
 *   <li>{@link io.mosip.vivid.bundle.BundleStore} keeps verified bundles in {@code <root>/<bundleId>/<version>/},
 *       tracks the active one, and supports rollback to previously activated versions.</li>
 *   <li>{@link io.mosip.vivid.bundle.BundleImporter} is the offline (USB / local path) update channel; an online
 *       channel would download to a temp file and call the same importer.</li>
 *   <li>{@link io.mosip.vivid.bundle.BundleTool} is the command-line entry point to create, sign, verify and list bundles.</li>
 * </ol>
 *
 * Only {@code java.security} is used for cryptography; no third-party crypto provider is required.
 */
package io.mosip.vivid.bundle;
