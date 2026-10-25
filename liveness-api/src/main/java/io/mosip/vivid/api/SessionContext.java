package io.mosip.vivid.api;

import java.util.Locale;
import java.util.Objects;

/** Who and what a session is for. The nonce is logged with the challenge sequence to prove unpredictability. */
public final class SessionContext {
    private final Workflow workflow;
    private final String sessionId;
    private final byte[] nonce;
    private final Locale locale;
    private final boolean online;
    private final boolean assistedCapture; // operator declared the resident cannot perform motor challenges

    public SessionContext(Workflow workflow, String sessionId, byte[] nonce, Locale locale, boolean online, boolean assistedCapture) {
        this.workflow = Objects.requireNonNull(workflow); this.sessionId = Objects.requireNonNull(sessionId);
        this.nonce = Objects.requireNonNull(nonce).clone(); this.locale = Objects.requireNonNullElse(locale, Locale.ENGLISH);
        this.online = online; this.assistedCapture = assistedCapture;
    }
    public Workflow workflow() { return workflow; }
    public String sessionId() { return sessionId; }
    public byte[] nonce() { return nonce.clone(); }
    public Locale locale() { return locale; }
    public boolean online() { return online; }
    public boolean assistedCapture() { return assistedCapture; }
}
