package io.mosip.vivid.policy.signing;

import io.mosip.vivid.policy.PolicyException;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * Verifies detached policy signatures produced by {@link PolicySigner}. Never throws on bad input: any malformed
 * signature, canonicalisation problem or key mismatch simply yields {@code false}, which the loader treats as
 * tampering and answers with compiled-in defaults.
 */
public final class PolicyVerifier {

    private PolicyVerifier() {}

    /** Verifies {@code signatureBase64} over the canonical form of {@code policyJson} with the key's natural algorithm. */
    public static boolean verify(String policyJson, String signatureBase64, PublicKey publicKey) {
        if (policyJson == null || signatureBase64 == null || publicKey == null) {
            return false;
        }
        try {
            return verify(policyJson, signatureBase64, publicKey, PolicySigner.algorithmFor(publicKey));
        } catch (PolicyException e) {
            return false;
        }
    }

    /** Verifies using an explicit JCA algorithm name, e.g. from a {@link SignedPolicy} envelope. */
    public static boolean verify(String policyJson, String signatureBase64, PublicKey publicKey, String algorithm) {
        if (policyJson == null || signatureBase64 == null || publicKey == null || algorithm == null) {
            return false;
        }
        try {
            byte[] signature = Base64.getDecoder().decode(signatureBase64.trim());
            Signature sig = Signature.getInstance(algorithm);
            sig.initVerify(publicKey);
            sig.update(CanonicalJson.canonicalBytes(policyJson));
            return sig.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException | PolicyException e) {
            return false;
        }
    }

    /** Verifies an envelope with the algorithm it declares. */
    public static boolean verify(SignedPolicy signed, PublicKey publicKey) {
        if (signed == null) {
            return false;
        }
        return verify(signed.policyJson(), signed.signatureBase64(), publicKey, signed.algorithm());
    }
}
