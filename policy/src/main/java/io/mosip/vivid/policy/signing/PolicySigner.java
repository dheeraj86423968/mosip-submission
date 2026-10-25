package io.mosip.vivid.policy.signing;

import io.mosip.vivid.policy.PolicyException;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;

/**
 * Produces detached signatures over the canonical form of a policy document using only {@code java.security}.
 * EC keys sign with {@value #DEFAULT_EC_ALGORITHM}; RSA keys with {@value #DEFAULT_RSA_ALGORITHM}.
 */
public final class PolicySigner {

    /** Algorithm used for EC private keys. */
    public static final String DEFAULT_EC_ALGORITHM = "SHA256withECDSA";
    /** Algorithm used for RSA private keys. */
    public static final String DEFAULT_RSA_ALGORITHM = "SHA256withRSA";

    private PolicySigner() {}

    /** Chooses the signature algorithm matching a key's algorithm ("EC" or "RSA"). */
    public static String algorithmFor(java.security.Key key) {
        String alg = Objects.requireNonNull(key, "key").getAlgorithm();
        switch (alg) {
            case "EC":
                return DEFAULT_EC_ALGORITHM;
            case "RSA":
                return DEFAULT_RSA_ALGORITHM;
            default:
                throw new PolicyException("unsupported key algorithm " + alg + " (use EC or RSA)");
        }
    }

    /** Signs the canonical form of {@code policyJson} and returns the Base64 signature. */
    public static String sign(String policyJson, PrivateKey privateKey) {
        try {
            Signature sig = Signature.getInstance(algorithmFor(privateKey));
            sig.initSign(privateKey);
            sig.update(CanonicalJson.canonicalBytes(policyJson));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (GeneralSecurityException e) {
            throw new PolicyException("cannot sign policy: " + e.getMessage(), e);
        }
    }

    /** Signs and wraps the document in a {@link SignedPolicy} envelope. */
    public static SignedPolicy signToEnvelope(String policyJson, PrivateKey privateKey, String keyId) {
        return new SignedPolicy(policyJson, sign(policyJson, privateKey), algorithmFor(privateKey), keyId);
    }
}
