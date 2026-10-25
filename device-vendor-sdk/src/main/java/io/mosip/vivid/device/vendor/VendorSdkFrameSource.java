package io.mosip.vivid.device.vendor;

import io.mosip.vivid.device.DeviceCapabilities;
import io.mosip.vivid.device.FaceFrameSource;

import java.util.Objects;

/**
 * {@link FaceFrameSource} over a {@link VendorSdk}. Everything except the SDK calls is inherited from
 * {@link AbstractFrameSourceAdapter}.
 *
 * <pre>{@code
 * FaceFrameSource src = new VendorSdkFrameSource("acme-face", new AcmeVendorSdk(...));
 * src.start(sink, StreamOptions.defaults());
 * }</pre>
 */
public class VendorSdkFrameSource extends AbstractFrameSourceAdapter {
    private final VendorSdk sdk;
    private volatile boolean open;

    /** @param adapterId stable id reported by {@link #adapterId()}, e.g. {@code "acme-face-sdk"} */
    public VendorSdkFrameSource(String adapterId, VendorSdk sdk) { this(adapterId, sdk, Backoff.defaults()); }

    public VendorSdkFrameSource(String adapterId, VendorSdk sdk, Backoff backoff) {
        super(adapterId, backoff);
        this.sdk = Objects.requireNonNull(sdk);
    }

    @Override protected DeviceCapabilities connect() throws VendorSdkException {
        VendorDeviceInfo info = sdk.open();
        open = true;
        return Objects.requireNonNull(info, "VendorSdk.open() returned null").toCapabilities();
    }

    @Override protected void startFrames(FrameEmitter emitter) throws VendorSdkException {
        sdk.startPreview(new VendorSdk.PreviewListener() {
            @Override public void onFrame(VendorFrame f) {
                emitter.emit(f.width(), f.height(), f.format(), f.pixels(), f.depth(), f.ir());
            }
            @Override public void onError(VendorSdkException error) { emitter.fault(error.getMessage()); }
        });
    }

    @Override protected void disconnect() {
        if (!open) return;
        open = false;
        try { sdk.stopPreview(); } finally { sdk.close(); }
    }

    @Override protected DeviceCapabilities probeCapabilities() throws VendorSdkException {
        return Objects.requireNonNull(sdk.describe(), "VendorSdk.describe() returned null").toCapabilities();
    }
}
