package io.mosip.vivid.device.mock.sbi;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import java.util.HexFormat;
import java.util.UUID;

/**
 * Device side of the signed preview-stream extension proposed in {@code docs/design/02-frame-streaming.md} §6
 * (wire format {@code vivid-sp-v1}). An independent implementation from the verifier in {@code device-sbi-stream}, so
 * the two check each other.
 *
 * <ul>
 *   <li>The device holds a P-256 key pair. A real L1 device keeps it in its secure element and binds the public key to
 *       its certificate in {@code digitalId}; the mock generates one per server and advertises it in {@code /info}.</li>
 *   <li>The client's {@code STREAM} request carries an ephemeral P-256 public key and a 32-byte nonce. The device
 *       answers with a {@code stream-init} part: session id, device nonce, and an ECDSA signature over
 *       {@code "vivid-sp-v1|" + sessionId + "|" + clientNonce + "|" + deviceNonce}.</li>
 *   <li>Session key: {@code HKDF-SHA256(salt = clientNonce || deviceNonce, ikm = ECDH(device, client),
 *       info = "vivid-sp-v1 " + sessionId)}, 32 bytes. Only the holder of the device private key can derive it.</li>
 *   <li>Each frame: {@code X-SBI-Frame-Seq} (1, 2, ...), {@code X-SBI-Frame-Time} (epoch ms) and
 *       {@code X-SBI-Frame-Signature = base64(HMAC-SHA256(key, seq + "|" + time + "|" + hex(SHA-256(jpeg))))}.</li>
 * </ul>
 */
final class SignedPreviewDevice {
    static final String ALG = "vivid-sp-v1";

    private final KeyPair deviceKey;
    private final SecureRandom random = new SecureRandom();

    SignedPreviewDevice() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), random);
            deviceKey = g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 unavailable", e);
        }
    }

    /** Base64 X.509 SubjectPublicKeyInfo of the device key, as advertised in {@code /info}. */
    String publicKeyBase64() { return Base64.getEncoder().encodeToString(deviceKey.getPublic().getEncoded()); }

    /** One signed stream: built from the client's request, then used to sign each frame. */
    final class Session {
        final String sessionId = UUID.randomUUID().toString();
        final String clientNonce, deviceNonce, initSignature;
        private final byte[] key;
        private long seq;

        Session(String clientKeyB64, String clientNonceB64) throws GeneralSecurityException {
            PublicKey clientKey = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(clientKeyB64)));
            byte[] cn = Base64.getDecoder().decode(clientNonceB64);
            if (cn.length < 16) throw new GeneralSecurityException("clientNonce too short");
            byte[] dn = new byte[32];
            random.nextBytes(dn);
            clientNonce = clientNonceB64;
            deviceNonce = Base64.getEncoder().encodeToString(dn);
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(deviceKey.getPrivate());
            ka.doPhase(clientKey, true);
            byte[] salt = new byte[cn.length + dn.length];
            System.arraycopy(cn, 0, salt, 0, cn.length);
            System.arraycopy(dn, 0, salt, cn.length, dn.length);
            key = hkdf(salt, ka.generateSecret(), (ALG + " " + sessionId).getBytes(StandardCharsets.UTF_8));
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initSign(deviceKey.getPrivate());
            sig.update((ALG + "|" + sessionId + "|" + clientNonce + "|" + deviceNonce).getBytes(StandardCharsets.UTF_8));
            initSignature = Base64.getEncoder().encodeToString(sig.sign());
        }

        /** {@code stream-init} JSON body. */
        String initJson() {
            return SbiMessages.MAPPER.createObjectNode().put("alg", ALG).put("sessionId", sessionId).put("clientNonce", clientNonce)
                    .put("deviceNonce", deviceNonce).put("signature", initSignature).toString();
        }

        /** Signs the next frame: returns {seq, time, signature}. */
        String[] sign(byte[] jpeg, long epochMillis) {
            long n = ++seq;
            return new String[] {Long.toString(n), Long.toString(epochMillis), mac(n, epochMillis, jpeg)};
        }

        String mac(long n, long epochMillis, byte[] jpeg) {
            try {
                Mac m = Mac.getInstance("HmacSHA256");
                m.init(new SecretKeySpec(key, "HmacSHA256"));
                String msg = n + "|" + epochMillis + "|" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jpeg));
                return Base64.getEncoder().encodeToString(m.doFinal(msg.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** RFC 5869 HKDF-SHA256 with a 32-byte output. */
    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = m.doFinal(ikm);
        m.init(new SecretKeySpec(prk, "HmacSHA256"));
        m.update(info);
        m.update((byte) 1);
        return m.doFinal();
    }
}
