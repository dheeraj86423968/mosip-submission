package io.mosip.vivid.bundle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BundleStoreTest {
    @TempDir Path tmp;

    private final List<String> events = new ArrayList<>();

    private BundleStore store(int keep) {
        BundleEventListener listener = new BundleEventListener() {
            @Override public void onRejected(Path source, BundleRejectedException.Reason reason, String detail) { events.add("rejected:" + reason); }
            @Override public void onInstalled(InstalledBundle b) { events.add("installed:" + b.version()); }
            @Override public void onActivated(InstalledBundle b, InstalledBundle previous) { events.add("activated:" + b.version()); }
            @Override public void onRolledBack(InstalledBundle restored, InstalledBundle from) { events.add("rollback:" + restored.version()); }
            @Override public void onRemoved(String id, String version) { events.add("removed:" + version); }
        };
        return new BundleStore(tmp.resolve("store"), TestBundles.verifier(), new BundleCompatibility("0.1.0", "jvm"), keep, listener);
    }

    @Test
    void installActivateCurrentAndLayout() throws Exception {
        BundleStore store = store(2);
        assertThat(store.current()).isEmpty();
        InstalledBundle b = store.install(TestBundles.bundle(tmp, "desk", "1.0.0"));
        assertThat(b.directory()).isEqualTo(tmp.resolve("store/desk/1.0.0").toAbsolutePath().normalize());
        assertThat(Files.isRegularFile(b.directory().resolve("manifest.json"))).isTrue();
        assertThat(Files.isRegularFile(b.directory().resolve("manifest.sig"))).isTrue();
        assertThat(Files.isRegularFile(b.directory().resolve("selftest/live.jpg"))).isTrue();
        assertThat(b.modelPath("yunet")).contains(b.directory().resolve("detector.onnx"));
        assertThat(b.modelPathByPurpose("anti-spoofing")).contains(b.directory().resolve("antispoof.onnx"));
        assertThat(store.current()).isEmpty();

        store.activate("desk", "1.0.0");
        assertThat(store.current()).isPresent();
        assertThat(store.current().get().version()).isEqualTo("1.0.0");
        assertThat(store.list()).hasSize(1);
        assertThat(events).containsExactly("installed:1.0.0", "activated:1.0.0");
    }

    @Test
    void rejectedInstallIsAuditedAndLeavesNothingBehind() throws Exception {
        BundleStore store = store(2);
        Path bad = TestBundles.bundle(tmp, "desk", "1.0.0", TestBundles.OTHER_KEYS);
        assertThatThrownBy(() -> store.install(bad)).isInstanceOf(BundleRejectedException.class);
        assertThat(events).containsExactly("rejected:BAD_SIGNATURE");
        assertThat(store.list()).isEmpty();
        assertThat(Files.exists(tmp.resolve("store/desk"))).isFalse();
    }

    @Test
    void incompatibleBundleIsRefusedAtInstall() throws Exception {
        BundleStore store = store(2);
        Path content = TestBundles.contentDir(tmp);
        BundleManifest m = TestBundles.manifest("desk", "1.0.0");
        m.setMinEngineVersion("9.0.0");
        Path mlb = tmp.resolve("future.mlb");
        new BundleWriter(TestBundles.KEYS.getPrivate()).write(m, content, mlb);
        assertThatThrownBy(() -> store.install(mlb)).satisfies(e -> assertThat(((BundleRejectedException) e).reason()).isEqualTo(BundleRejectedException.Reason.INCOMPATIBLE_ENGINE));
    }

    @Test
    void rollbackWalksHistoryAndPruningKeepsN() throws Exception {
        BundleStore store = store(1);
        for (String v : List.of("1.0.0", "1.1.0", "1.2.0")) {
            store.install(TestBundles.bundle(tmp, "desk", v));
            store.activate("desk", v);
        }
        // keepPrevious=1: after activating 1.2.0 only 1.1.0 is retained besides the active one.
        assertThat(store.list()).extracting(InstalledBundle::version).containsExactly("1.1.0", "1.2.0");
        assertThat(store.state().history).containsExactly("desk@1.1.0");
        assertThat(events).contains("removed:1.0.0");

        assertThat(store.rollback()).isPresent();
        assertThat(store.current().get().version()).isEqualTo("1.1.0");
        assertThat(events).contains("rollback:1.1.0");
        // 1.2.0 is still installed (keepPrevious=1) and is now the only rollback target.
        assertThat(store.list()).extracting(InstalledBundle::version).containsExactly("1.1.0", "1.2.0");
        assertThat(store.rollback()).isPresent();
        assertThat(store.current().get().version()).isEqualTo("1.2.0");
    }

    @Test
    void rollbackSkipsDeletedHistoryAndReportsEmptyWhenNothingLeft() throws Exception {
        BundleStore store = store(5);
        store.install(TestBundles.bundle(tmp, "desk", "1.0.0"));
        store.install(TestBundles.bundle(tmp, "desk", "1.1.0"));
        store.activate("desk", "1.0.0");
        store.activate("desk", "1.1.0");
        store.remove("desk", "1.0.0");
        assertThat(store.rollback()).isEmpty();
        assertThat(store.current().get().version()).isEqualTo("1.1.0");
        assertThatThrownBy(() -> store.remove("desk", "1.1.0")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void secondRollbackGoesFurtherBack() throws Exception {
        BundleStore store = store(5);
        for (String v : List.of("1.0.0", "1.1.0", "1.2.0")) {
            store.install(TestBundles.bundle(tmp, "desk", v));
            store.activate("desk", v);
        }
        store.rollback();
        assertThat(store.current().get().version()).isEqualTo("1.1.0");
        store.rollback();
        assertThat(store.current().get().version()).isEqualTo("1.0.0");
    }

    @Test
    void onDiskTamperingAfterInstallIsDetectedOnActivation() throws Exception {
        BundleStore store = store(2);
        InstalledBundle b = store.install(TestBundles.bundle(tmp, "desk", "1.0.0"));
        Files.write(b.directory().resolve("antispoof.onnx"), new byte[] {1, 2, 3});
        assertThatThrownBy(() -> store.activate("desk", "1.0.0"))
                .satisfies(e -> assertThat(((BundleRejectedException) e).reason()).isEqualTo(BundleRejectedException.Reason.HASH_MISMATCH));
        assertThat(store.current()).isEmpty();
        assertThatThrownBy(() -> store.activate("desk", "9.9.9"))
                .satisfies(e -> assertThat(((BundleRejectedException) e).reason()).isEqualTo(BundleRejectedException.Reason.NOT_INSTALLED));
    }

    @Test
    void reinstallReplacesVersionAndActivateIsIdempotent() throws Exception {
        BundleStore store = store(2);
        store.install(TestBundles.bundle(tmp, "desk", "1.0.0"));
        store.activate("desk", "1.0.0");
        store.activate("desk", "1.0.0");
        assertThat(store.state().history).isEmpty();
        store.install(TestBundles.bundle(tmp.resolve("again"), "desk", "1.0.0"));
        assertThat(store.list()).hasSize(1);
        assertThat(store.current().get().version()).isEqualTo("1.0.0");
    }
}
