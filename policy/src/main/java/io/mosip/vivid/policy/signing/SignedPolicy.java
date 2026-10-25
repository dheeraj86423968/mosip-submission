package io.mosip.vivid.policy.signing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosip.vivid.policy.PolicyException;
import io.mosip.vivid.policy.PolicyJson;

import java.util.Objects;
import java.util.Optional;

/**
 * A policy document together with its detached signature, as transported in one envelope:
 * <pre>{ "policy": { ...document... }, "signature": "base64", "algorithm": "SHA256withECDSA", "keyId": "..." }</pre>
 *
 * <p>The signature covers the canonical form of {@code policy} only (see {@link CanonicalJson}), so the envelope
 * may be re-serialised freely and the document can also be shipped as two separate properties.</p>
 *
 * @param policyJson      the document text exactly as delivered (any formatting)
 * @param signatureBase64 Base64 of the raw signature bytes
 * @param algorithm       JCA signature algorithm name used to produce the signature
 * @param keyId           optional identifier of the signing key, for key rotation
 */
public final class SignedPolicy {
    private final String policyJson;
    private final String signatureBase64;
    private final String algorithm;
    private final String keyId;

    /** Canonical constructor with null checks. */
    public SignedPolicy(String policyJson, String signatureBase64, String algorithm, String keyId) {
        Objects.requireNonNull(policyJson, "policyJson");
        Objects.requireNonNull(signatureBase64, "signatureBase64");
        Objects.requireNonNull(algorithm, "algorithm");
        this.policyJson = policyJson;
        this.signatureBase64 = signatureBase64;
        this.algorithm = algorithm;
        this.keyId = keyId;
    }

    public String policyJson() { return policyJson; }
    public String signatureBase64() { return signatureBase64; }
    public String algorithm() { return algorithm; }
    public String keyId() { return keyId; }

    /** The key id, if the envelope carried one. */
    public Optional<String> keyIdOptional() {
        return Optional.ofNullable(keyId);
    }

    /** Serialises the envelope. */
    public String toEnvelopeJson() {
        try {
            ObjectNode env = PolicyJson.mapper().createObjectNode();
            env.set("policy", PolicyJson.mapper().readTree(policyJson));
            env.put("signature", signatureBase64);
            env.put("algorithm", algorithm);
            if (keyId != null) {
                env.put("keyId", keyId);
            }
            return PolicyJson.mapper().writeValueAsString(env);
        } catch (JsonProcessingException e) {
            throw new PolicyException("cannot build signed envelope", e);
        }
    }

    /** Parses an envelope produced by {@link #toEnvelopeJson()}. */
    public static SignedPolicy fromEnvelopeJson(String envelopeJson) {
        try {
            JsonNode env = PolicyJson.mapper().readTree(Objects.requireNonNull(envelopeJson));
            JsonNode policy = env.get("policy");
            JsonNode sig = env.get("signature");
            JsonNode alg = env.get("algorithm");
            if (policy == null || !policy.isObject() || sig == null || !sig.isTextual()) {
                throw new PolicyException("signed envelope must contain 'policy' object and 'signature' string");
            }
            String algorithm = alg != null && alg.isTextual() ? alg.asText() : PolicySigner.DEFAULT_EC_ALGORITHM;
            JsonNode keyId = env.get("keyId");
            return new SignedPolicy(PolicyJson.mapper().writeValueAsString(policy), sig.asText(), algorithm,
                    keyId != null && keyId.isTextual() ? keyId.asText() : null);
        } catch (JsonProcessingException e) {
            throw new PolicyException("malformed signed envelope: " + e.getOriginalMessage(), e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SignedPolicy)) return false;
        SignedPolicy other = (SignedPolicy) o;
        return java.util.Objects.equals(policyJson, other.policyJson)
                && java.util.Objects.equals(signatureBase64, other.signatureBase64)
                && java.util.Objects.equals(algorithm, other.algorithm)
                && java.util.Objects.equals(keyId, other.keyId);
    }

    @Override
    public int hashCode() {
        int h = 0;
        h = 31 * h + java.util.Objects.hashCode(policyJson);
        h = 31 * h + java.util.Objects.hashCode(signatureBase64);
        h = 31 * h + java.util.Objects.hashCode(algorithm);
        h = 31 * h + java.util.Objects.hashCode(keyId);
        return h;
    }

    @Override
    public String toString() {
        return "SignedPolicy[policyJson=" + policyJson + ", signatureBase64=" + signatureBase64 + ", algorithm=" + algorithm + ", keyId=" + keyId + "]";
    }
}
