package io.mosip.vivid.bundle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BundleToolTest {
    @TempDir Path tmp;

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private final BundleTool tool = new BundleTool(new PrintStream(buf, true, StandardCharsets.UTF_8));

    private String out() { return buf.toString(StandardCharsets.UTF_8); }

    @Test
    void endToEndKeygenCreateVerifyInstallListRollback() throws Exception {
        Path keys = tmp.resolve("keys");
        assertThat(tool.run("keygen", "--out-dir", keys.toString())).isZero();
        assertThat(Files.readString(keys.resolve("bundle-signing.pub"))).startsWith("-----BEGIN PUBLIC KEY-----");

        Path content = TestBundles.contentDir(tmp);
        Path manifest = tmp.resolve("manifest.json");
        Files.write(manifest, TestBundles.manifest("desk", "1.0.0").toJsonBytes());
        Path mlb = tmp.resolve("out/desk-1.0.0.mlb");
        assertThat(tool.run("create", "--dir", content.toString(), "--manifest", manifest.toString(),
                "--key", keys.resolve("bundle-signing.key").toString(), "--out", mlb.toString())).isZero();
        assertThat(out()).contains("created").contains("sha256=");

        assertThat(tool.run("verify", "--bundle", mlb.toString(), "--pubkey", keys.resolve("bundle-signing.pub").toString(),
                "--engine-version", "0.1.0")).isZero();
        assertThat(out()).contains("OK BundleManifest{desk@1.0.0").contains("COMPATIBLE");

        Path otherPub = tmp.resolve("other.pub");
        Files.writeString(otherPub, KeyIo.toPem(TestBundles.OTHER_KEYS.getPublic()));
        assertThat(tool.run("verify", "--bundle", mlb.toString(), "--pubkey", otherPub.toString())).isEqualTo(1);
        assertThat(out()).contains("REJECTED BAD_SIGNATURE");

        Path store = tmp.resolve("store");
        String pub = keys.resolve("bundle-signing.pub").toString();
        assertThat(tool.run("install", "--store", store.toString(), "--bundle", mlb.toString(), "--pubkey", pub, "--activate")).isZero();

        Files.write(manifest, TestBundles.manifest("desk", "1.1.0").toJsonBytes());
        Path mlb2 = tmp.resolve("out/desk-1.1.0.mlb");
        tool.run("create", "--dir", content.toString(), "--manifest", manifest.toString(), "--key", keys.resolve("bundle-signing.key").toString(), "--out", mlb2.toString());
        assertThat(tool.run("import", "--store", store.toString(), "--from", tmp.resolve("out").toString(), "--pubkey", pub, "--activate")).isZero();

        buf.reset();
        assertThat(tool.run("list", "--store", store.toString())).isZero();
        assertThat(out()).contains("* desk@1.1.0").contains("  desk@1.0.0").contains("rollback targets: [desk@1.0.0]");

        buf.reset();
        assertThat(tool.run("rollback", "--store", store.toString(), "--pubkey", pub)).isZero();
        assertThat(out()).contains("rolled back to desk@1.0.0");

        assertThat(tool.run("activate", "--store", store.toString(), "--pubkey", pub, "--id", "desk", "--version", "1.1.0")).isZero();
    }

    @Test
    void usageErrors() {
        assertThat(tool.run()).isEqualTo(2);
        assertThat(tool.run("bogus")).isEqualTo(2);
        assertThat(tool.run("create", "--dir", "x")).isEqualTo(2);
        assertThat(out()).contains("--manifest is required");
        assertThat(tool.run("verify", "--bundle", tmp.resolve("missing.mlb").toString(), "--pubkey", "nope")).isEqualTo(1);
    }

    @Test
    void optionParsing() {
        var m = BundleTool.parseOptions(new String[] {"--a", "1", "--flag", "--b", "two"});
        assertThat(m).containsEntry("a", "1").containsEntry("flag", "true").containsEntry("b", "two");
    }
}
