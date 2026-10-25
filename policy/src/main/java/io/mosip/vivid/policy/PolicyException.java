package io.mosip.vivid.policy;

/** Raised when a policy document cannot be parsed or converted. Carries no sensitive data. */
public class PolicyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PolicyException(String message) {
        super(message);
    }

    public PolicyException(String message, Throwable cause) {
        super(message, cause);
    }
}
