package io.mosip.vivid.policy.signing;

import io.mosip.vivid.policy.PolicyException;
import io.mosip.vivid.policy.PolicyLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningTest {

    private static final String DOC = "{\"schemaVersion\":\"1.0\",\"workflows\":{\"RESIDENT\":{\"passiveThreshold\":0.8,\"enabled\":true}},\"version\":\"a\"}";

    @Test
    void canonicalFormSortsKeysRecursivelyAndStripsWhitespace() {
        String pretty = "{\n  \"version\": \"a\",\n  \"workflows\": { \"RESIDENT\": { \"enabled\": true, \"passiveThreshold\": 0.8 } },\n  \"schemaVersion\": \"1.0\"\n}";
        assertThat(CanonicalJson.canonicalize(pretty)).isEqualTo(CanonicalJson.canonicalize(DOC))
                .isEqualTo("{\"schemaVersion\":\"1.0\",\"version\":\"a\",\"workflows\":{\"RESIDENT\":{\"enabled\":true,\"passiveThreshold\":0.8}}}");
        assertThat(CanonicalJson.canonicalize("[{\"b\":1,\"a\":[2,{\"z\":0,\"y\":null}]}]")).isEqualTo("[{\"a\":[2,{\"y\":null,\"z\":0}],\"b\":1}]");
        assertThatThrownBy(() -> CanonicalJson.canonicalize("{")).isInstanceOf(PolicyException.class);
        assertThatThrownBy(() -> CanonicalJson.canonicalize("")).isInstanceOf(PolicyException.class);
    }

    @Test
    void ecSignatureSurvivesReformattingButNotEdits() {
        KeyPair kp = PolicyKeys.generateEc();
        String sig = PolicySigner.sign(DOC, kp.getPrivate());
        assertThat(PolicyVerifier.verify(DOC, sig, kp.getPublic())).isTrue();
        String reordered = "{\"version\":\"a\",\"workflows\":{\"RESIDENT\":{\"enabled\":true,\"passiveThreshold\":0.8}},\"schemaVersion\":\"1.0\"}";
        assertThat(PolicyVerifier.verify(reordered, sig, kp.getPublic())).isTrue();
        assertThat(PolicyVerifier.verify(DOC.replace("0.8", "0.1"), sig, kp.getPublic())).isFalse();
        assertThat(PolicyVerifier.verify(DOC, sig, PolicyKeys.generateEc().getPublic())).isFalse();
    }

    @Test
    void rsaSignatureWorksToo() {
        KeyPair kp = PolicyKeys.generateRsa(2048);
        assertThat(PolicySigner.algorithmFor(kp.getPublic())).isEqualTo(PolicySigner.DEFAULT_RSA_ALGORITHM);
        String sig = PolicySigner.sign(DOC, kp.getPrivate());
        assertThat(PolicyVerifier.verify(DOC, sig, kp.getPublic())).isTrue();
        assertThat(PolicyVerifier.verify(DOC + " ", sig, kp.getPublic())).isTrue();
        assertThat(PolicyVerifier.verify(DOC, sig, PolicyKeys.generateEc().getPublic())).isFalse();
    }

    @Test
    void verifierNeverThrows() {
        KeyPair kp = PolicyKeys.generateEc();
        assertThat(PolicyVerifier.verify(null, "x", kp.getPublic())).isFalse();
        assertThat(PolicyVerifier.verify(DOC, null, kp.getPublic())).isFalse();
        assertThat(PolicyVerifier.verify(DOC, "x", null)).isFalse();
        assertThat(PolicyVerifier.verify(DOC, "not base64 !!", kp.getPublic())).isFalse();
        assertThat(PolicyVerifier.verify(DOC, "AAAA", kp.getPublic())).isFalse();
        assertThat(PolicyVerifier.verify("{", "AAAA", kp.getPublic())).isFalse();
        assertThat(PolicyVerifier.verify(DOC, "AAAA", kp.getPublic(), "NoSuchAlgorithm")).isFalse();
        assertThat(PolicyVerifier.verify(DOC, "AAAA", kp.getPublic(), null)).isFalse();
        assertThat(PolicyVerifier.verify((SignedPolicy) null, kp.getPublic())).isFalse();
    }

    @Test
    void envelopeRoundTrip() {
        KeyPair kp = PolicyKeys.generateEc();
        SignedPolicy signed = PolicySigner.signToEnvelope(DOC, kp.getPrivate(), "key-2026");
        assertThat(signed.algorithm()).isEqualTo(PolicySigner.DEFAULT_EC_ALGORITHM);
        assertThat(signed.keyIdOptional()).contains("key-2026");
        String env = signed.toEnvelopeJson();
        SignedPolicy parsed = SignedPolicy.fromEnvelopeJson(env);
        assertThat(parsed.signatureBase64()).isEqualTo(signed.signatureBase64());
        assertThat(parsed.keyId()).isEqualTo("key-2026");
        assertThat(PolicyVerifier.verify(parsed, kp.getPublic())).isTrue();
        assertThat(new PolicyLoader().parse(parsed.policyJson()).version()).isEqualTo("a");
        SignedPolicy noKeyId = SignedPolicy.fromEnvelopeJson("{\"policy\":{\"schemaVersion\":\"1.0\"},\"signature\":\"AAAA\"}");
        assertThat(noKeyId.keyIdOptional()).isEmpty();
        assertThat(noKeyId.algorithm()).isEqualTo(PolicySigner.DEFAULT_EC_ALGORITHM);
        assertThatThrownBy(() -> SignedPolicy.fromEnvelopeJson("{\"policy\":[]}")).isInstanceOf(PolicyException.class);
        assertThatThrownBy(() -> SignedPolicy.fromEnvelopeJson("{")).isInstanceOf(PolicyException.class);
        assertThatThrownBy(() -> new SignedPolicy("{", "x", "SHA256withECDSA", null).toEnvelopeJson()).isInstanceOf(PolicyException.class);
        assertThatThrownBy(() -> new SignedPolicy(null, "x", "SHA256withECDSA", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void keysEncodeAndDecode() {
        KeyPair ec = PolicyKeys.generateEc();
        PublicKey pub = PolicyKeys.decodePublic(PolicyKeys.encodePublic(ec.getPublic()), "EC");
        PrivateKey priv = PolicyKeys.decodePrivate(PolicyKeys.encodePrivate(ec.getPrivate()), "EC");
        assertThat(pub).isEqualTo(ec.getPublic());
        assertThat(PolicyVerifier.verify(DOC, PolicySigner.sign(DOC, priv), pub)).isTrue();
        KeyPair rsa = PolicyKeys.generateRsa(2048);
        assertThat(PolicyKeys.decodePublicAny(PolicyKeys.encodePublic(rsa.getPublic()))).isEqualTo(rsa.getPublic());
        assertThat(PolicyKeys.decodePrivateAny(PolicyKeys.encodePrivate(rsa.getPrivate())).getAlgorithm()).isEqualTo("RSA");
        assertThat(PolicyKeys.decodePublicAny(PolicyKeys.encodePublic(ec.getPublic())).getAlgorithm()).isEqualTo("EC");
        assertThatThrownBy(() -> PolicyKeys.decodePublic("!!", "EC")).isInstanceOf(PolicyException.class);
        assertThatThrownBy(() -> PolicyKeys.decodePublicAny("AAAA")).isInstanceOf(PolicyException.class);
        assertThatThrownBy(() -> PolicyKeys.decodePrivateAny("AAAA")).isInstanceOf(PolicyException.class);
    }

    @Test
    void keyToolGeneratesSignsAndVerifies(@TempDir Path dir) throws Exception {
        Path priv = dir.resolve("priv.key");
        Path pub = dir.resolve("pub.key");
        Path policy = dir.resolve("policy.json");
        Path sig = dir.resolve("policy.sig");
        Files.writeString(policy, PolicyLoader.builtinDefaultsJson(), StandardCharsets.UTF_8);

        assertThat(run("gen", priv.toString(), pub.toString())).isZero();
        assertThat(Files.readString(pub)).isNotBlank();
        assertThat(run("sign", priv.toString(), policy.toString(), sig.toString())).isZero();
        assertThat(run("verify", pub.toString(), policy.toString(), sig.toString())).isZero();
        assertThat(lastOutput).contains("VALID");

        Files.writeString(policy, PolicyLoader.builtinDefaultsJson().replace("0.997", "0.10"), StandardCharsets.UTF_8);
        assertThat(run("verify", pub.toString(), policy.toString(), sig.toString())).isEqualTo(2);
        assertThat(lastOutput).contains("INVALID");

        assertThat(run("gen", "rsa", priv.toString(), pub.toString())).isZero();
        assertThat(run("sign", priv.toString(), policy.toString(), sig.toString())).isZero();
        assertThat(lastOutput).contains("SHA256withRSA");
        assertThat(run("verify", pub.toString(), policy.toString(), sig.toString())).isZero();
    }

    @Test
    void keyToolUsageErrors(@TempDir Path dir) {
        assertThat(run()).isEqualTo(1);
        assertThat(run("frobnicate")).isEqualTo(1);
        assertThat(run("gen")).isEqualTo(1);
        assertThat(run("gen", "dsa", "a", "b")).isEqualTo(1);
        assertThat(run("sign", "a")).isEqualTo(1);
        assertThat(run("verify", "a")).isEqualTo(1);
        assertThat(run("sign", dir.resolve("nope").toString(), "x", "y")).isEqualTo(1);
        assertThat(lastOutput).contains("error");
        assertThat(PolicyKeyTool.run(null, new PrintStream(new ByteArrayOutputStream()))).isEqualTo(1);
    }

    private String lastOutput;

    private int run(String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code = PolicyKeyTool.run(args, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        lastOutput = bytes.toString(StandardCharsets.UTF_8);
        return code;
    }
}
