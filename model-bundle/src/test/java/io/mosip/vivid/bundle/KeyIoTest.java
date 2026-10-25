package io.mosip.vivid.bundle;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;

class KeyIoTest {
    @Test
    void ecKeysRoundTripThroughPemAndDer() throws Exception {
        KeyPair kp = KeyIo.generateEcKeyPair();
        assertThat(KeyIo.readPublicKey(KeyIo.toPem(kp.getPublic()).getBytes(StandardCharsets.US_ASCII))).isEqualTo(kp.getPublic());
        assertThat(KeyIo.readPrivateKey(KeyIo.toPem(kp.getPrivate()).getBytes(StandardCharsets.US_ASCII))).isEqualTo(kp.getPrivate());
        assertThat(KeyIo.readPublicKey(kp.getPublic().getEncoded())).isEqualTo(kp.getPublic());
        assertThat(KeyIo.signatureAlgorithm(kp.getPublic())).isEqualTo("SHA256withECDSA");
    }

    @Test
    void rsaKeysSignAndVerify() throws Exception {
        KeyPair kp = KeyIo.generateRsaKeyPair(2048);
        assertThat(KeyIo.signatureAlgorithm(kp.getPrivate())).isEqualTo("SHA256withRSA");
        byte[] manifest = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] sigEntry = new BundleSigner(kp.getPrivate()).signatureEntry(manifest);
        java.security.Signature v = java.security.Signature.getInstance("SHA256withRSA");
        v.initVerify(KeyIo.readPublicKey(KeyIo.toPem(kp.getPublic()).getBytes(StandardCharsets.US_ASCII)));
        v.update(manifest);
        assertThat(v.verify(java.util.Base64.getMimeDecoder().decode(new String(sigEntry, StandardCharsets.US_ASCII).trim()))).isTrue();
    }

    @Test
    void sha256Helpers() {
        assertThat(Sha256.hex("abc".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Sha256.equalsHex("ABC", "abc")).isTrue();
        assertThat(Sha256.equalsHex("abc", "abd")).isFalse();
        assertThat(Sha256.equalsHex(null, "abc")).isFalse();
    }
}
