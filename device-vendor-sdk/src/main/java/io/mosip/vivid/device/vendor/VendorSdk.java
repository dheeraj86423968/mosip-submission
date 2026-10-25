package io.mosip.vivid.device.vendor;

/**
 * What a device vendor implements to plug a face camera that ships its own SDK (not an SBI service) into the
 * MOSIP Registration Clients. Wrap the SDK's native or Java API in this interface and pass it to
 * {@link VendorSdkFrameSource}; threading, buffering, stall detection, reconnect and health are handled there.
 *
 * <p>Call order per connection: {@link #open()}, {@link #startPreview}, frames via the listener, then
 * {@link #stopPreview()} and {@link #close()}. After an error or a stall the adapter calls {@code stopPreview()} and
 * {@code close()} and starts again with {@code open()}. The adapter never calls these methods concurrently.
 *
 * <p>Pull-style SDKs ({@code grabFrame(timeout)}) start their own grab thread in {@code startPreview} and stop it
 * in {@code stopPreview}; see the README.
 */
public interface VendorSdk {
    /** Receives preview frames; called on the SDK's thread. Both methods return immediately. */
    interface PreviewListener {
        void onFrame(VendorFrame frame);
        /** A connection-level error (device unplugged, driver reset). The adapter will reconnect. */
        void onError(VendorSdkException error);
    }

    /** Opens (or re-opens) the device. May block briefly. */
    VendorDeviceInfo open() throws VendorSdkException;

    /** Starts preview frames to {@code listener}. Must return promptly. */
    void startPreview(PreviewListener listener) throws VendorSdkException;

    /** Stops preview frames; after it returns the listener receives nothing more. Idempotent, must not throw. */
    void stopPreview();

    /** Releases the device. Idempotent, must not throw. */
    void close();

    /**
     * Describes the device without streaming, for {@code capabilities()} before {@code start()}. The default opens
     * and closes the device; override when the SDK can enumerate devices without opening one.
     */
    default VendorDeviceInfo describe() throws VendorSdkException {
        try { return open(); } finally { close(); }
    }
}
