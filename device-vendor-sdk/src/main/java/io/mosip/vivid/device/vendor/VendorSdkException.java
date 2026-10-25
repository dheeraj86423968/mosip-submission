package io.mosip.vivid.device.vendor;

/** Thrown by a {@link VendorSdk} implementation; wrap the SDK's own error codes in the message. */
public class VendorSdkException extends Exception {
    private final String vendorCode;

    public VendorSdkException(String message) { this(message, null, null); }
    public VendorSdkException(String message, Throwable cause) { this(message, null, cause); }
    /** @param vendorCode the SDK's native error code, kept for diagnostics (may be null) */
    public VendorSdkException(String message, String vendorCode, Throwable cause) {
        super(vendorCode == null ? message : message + " [vendor code " + vendorCode + "]", cause);
        this.vendorCode = vendorCode;
    }
    /** The SDK's native error code, or null. */
    public String vendorCode() { return vendorCode; }
}
