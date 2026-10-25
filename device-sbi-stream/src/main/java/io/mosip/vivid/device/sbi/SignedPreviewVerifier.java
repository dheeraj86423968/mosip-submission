package io.mosip.vivid.device.sbi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Client side of the signed preview-stream extension ({@code vivid-sp-v1}, {@code docs/design/02-frame-streaming.md}
 * §6). One instance per STREAM request:
 * <ol>
 *   <li>{@link #requestFields()} adds an ephemeral P-256 public key and a 32-byte nonce to the request;</li>
 *   <li>{@link #acceptInit(byte[])} checks the device's ECDSA signature on the {@code stream-init} part against the
 *       trusted device key and derives the session key, {@code HKDF-SHA256(clientNonce || deviceNonce,
 *       ECDH(client, device), "vivid-sp-v1 " + sessionId)};</li>
 *   <li>{@link #verify(Map, byte[])} checks each frame's HMAC over {@code seq|time|hex(SHA-256(jpeg))} and that the
 *       sequence number strictly increases (no replay).</li>
 * </ol>
 * A frame that fails any check is returned without a signature; the core treats an unsigned frame from a device that
 * signs frames as an injection attack.
 */
public final class SignedPreviewVerifier {
    private static final Logger LOG = LoggerFactory.getLogger(SignedPreviewVerifier.class);
    public static final String ALG = "vivid-sp-v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PublicKey deviceKey;
    private final KeyPair ephemeral;
    private final String clientNonce;
    private byte[] sessionKey;
    private long lastSeq;
    private long verified, rejected;

    /** @param deviceKeyBase64 base64 X.509 SubjectPublicKeyInfo of the trusted device key */
    public SignedPreviewVerifier(String deviceKeyBase64) throws GeneralSecurityException {
        deviceKey = publicKey(deviceKeyBase64);
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        ephemeral = g.generateKeyPair();
        byte[] n = new byte[32];
        RANDOM.nextBytes(n);
        clientNonce = Base64.getEncoder().encodeToString(n);
    }

    static PublicKey publicKey(String base64) throws GeneralSecurityException {
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64.trim())));
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("device key is not base64", e);
        }
    }

    /** The {@code signedPreview} object for the STREAM request body. */
    public ObjectNode requestFields() {
        return MAPPER.createObjectNode().put("alg", ALG)
                .put("clientKey", Base64.getEncoder().encodeToString(ephemeral.getPublic().getEncoded()))
                .put("clientNonce", clientNonce);
    }

    /** Verifies the {@code stream-init} part and derives the session key; false (and no key) on any failure. */
    public boolean acceptInit(byte[] json) {
        try {
            JsonNode n = MAPPER.readTree(json);
            String sessionId = n.path("sessionId").asText(""), deviceNonce = n.path("deviceNonce").asText("");
            if (!ALG.equals(n.path("alg").asText()) || sessionId.isEmpty() || !clientNonce.equals(n.path("clientNonce").asText())) {
                LOG.warn("signed preview: stream-init does not answer our request");
                return false;
            }
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initVerify(deviceKey);
            sig.update((ALG + "|" + sessionId + "|" + clientNonce + "|" + deviceNonce).getBytes(StandardCharsets.UTF_8));
            if (!sig.verify(Base64.getDecoder().decode(n.path("signature").asText("")))) {
                LOG.warn("signed preview: stream-init signature does not verify against the trusted device key");
                return false;
            }
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(ephemeral.getPrivate());
            ka.doPhase(deviceKey, true);
            byte[] cn = Base64.getDecoder().decode(clientNonce), dn = Base64.getDecoder().decode(deviceNonce);
            byte[] salt = new byte[cn.length + dn.length];
            System.arraycopy(cn, 0, salt, 0, cn.length);
            System.arraycopy(dn, 0, salt, cn.length, dn.length);
            sessionKey = hkdf(salt, ka.generateSecret(), (ALG + " " + sessionId).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            LOG.warn("signed preview: bad stream-init: {}", e.toString());
            return false;
        }
    }

    public boolean established() { return sessionKey != null; }

    /** The frame's signature when it verifies and is fresh; empty otherwise. {@code headers} keys are lower-case. */
    public Optional<byte[]> verify(Map<String, String> headers, byte[] jpeg) {
        Optional<byte[]> r = check(headers, jpeg);
        if (r.isPresent()) verified++; else rejected++;
        return r;
    }

    private Optional<byte[]> check(Map<String, String> headers, byte[] jpeg) {
        if (sessionKey == null) return Optional.empty();
        String seqText = headers.get("x-sbi-frame-seq"), time = headers.get("x-sbi-frame-time"), sigText = headers.get("x-sbi-frame-signature");
        if (seqText == null || time == null || sigText == null) return Optional.empty();
        try {
            long seq = Long.parseLong(seqText.trim());
            if (seq <= lastSeq) {
                LOG.warn("signed preview: frame sequence {} not after {} (replay)", seq, lastSeq);
                return Optional.empty();
            }
            byte[] given = Base64.getDecoder().decode(sigText.trim());
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(sessionKey, "HmacSHA256"));
            String msg = seq + "|" + time.trim() + "|" + hex(MessageDigest.getInstance("SHA-256").digest(jpeg));
            byte[] expected = m.doFinal(msg.getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(expected, given)) {
                LOG.warn("signed preview: frame {} signature mismatch", seq);
                return Optional.empty();
            }
            lastSeq = seq;
            return Optional.of(given);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long verified() { return verified; }
    public long rejected() { return rejected; }

    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = m.doFinal(ikm);
        m.init(new SecretKeySpec(prk, "HmacSHA256"));
        m.update(info);
        m.update((byte) 1);
        return m.doFinal();
    }

    private static String hex(byte[] b) {
        char[] d = "0123456789abcdef".toCharArray();
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) { out[2 * i] = d[(b[i] >> 4) & 15]; out[2 * i + 1] = d[b[i] & 15]; }
        return new String(out);
    }
}
