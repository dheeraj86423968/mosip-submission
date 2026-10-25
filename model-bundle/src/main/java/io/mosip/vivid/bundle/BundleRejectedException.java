package io.mosip.vivid.bundle;

import java.util.Objects;

/**
 * Thrown when a bundle must not be loaded. The {@link Reason} is stable and intended for audit records;
 * the message carries human-readable detail (file name, expected vs actual hash, ...).
 */
public class BundleRejectedException extends Exception {
    private static final long serialVersionUID = 1L;

    /** Why a bundle was refused. Ordered roughly by the stage of verification that fails. */
    public enum Reason {
        /** The archive could not be read at all. */
        IO_ERROR,
        /** No {@code manifest.json} entry in the archive. */
        MISSING_MANIFEST,
        /** The manifest is not valid JSON or fails structural validation. */
        MALFORMED_MANIFEST,
        /** No {@code manifest.sig} entry in the archive. */
        MISSING_SIGNATURE,
        /** The signature does not verify against the pinned public key(s). */
        BAD_SIGNATURE,
        /** A model or self-test file listed in the manifest is absent from the archive. */
        MISSING_FILE,
        /** A file's SHA-256 differs from the manifest. */
        HASH_MISMATCH,
        /** An archive entry name escapes the extraction directory (zip-slip) or is otherwise unsafe. */
        UNSAFE_PATH,
        /** Bundle targets a different platform than the engine. */
        INCOMPATIBLE_PLATFORM,
        /** Engine version is older than {@code minEngineVersion}. */
        INCOMPATIBLE_ENGINE,
        /** The bundle id / version is not present in the store. */
        NOT_INSTALLED
    }

    private final Reason reason;

    public BundleRejectedException(Reason reason, String message) {
        super(reason + ": " + message);
        this.reason = Objects.requireNonNull(reason);
    }

    public BundleRejectedException(Reason reason, String message, Throwable cause) {
        super(reason + ": " + message, cause);
        this.reason = Objects.requireNonNull(reason);
    }

    /** Stable machine-readable cause. */
    public Reason reason() { return reason; }
}
