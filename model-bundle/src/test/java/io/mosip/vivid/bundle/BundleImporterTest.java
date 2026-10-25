package io.mosip.vivid.bundle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BundleImporterTest {
    @TempDir Path tmp;

    @Test
    void importsEveryArchiveFromAUsbLikeDirectoryAndActivatesNewest() throws Exception {
        Path usb = tmp.resolve("usb");
        Files.createDirectories(usb.resolve("nested"));
        Files.move(TestBundles.bundle(tmp, "desk", "1.0.0"), usb.resolve("desk-1.0.0.mlb"));
        Files.move(TestBundles.bundle(tmp, "desk", "1.2.0"), usb.resolve("desk-1.2.0.mlb"));
        Files.move(TestBundles.bundle(tmp, "desk", "1.1.0", TestBundles.OTHER_KEYS), usb.resolve("desk-1.1.0.mlb"));
        Files.move(TestBundles.bundle(tmp, "desk", "1.3.0"), usb.resolve("nested/desk-1.3.0.mlb"));
        Files.writeString(usb.resolve("README.txt"), "not a bundle");

        BundleStore store = new BundleStore(tmp.resolve("store"), TestBundles.verifier(), null, 5);
        BundleImporter importer = new BundleImporter(store);

        BundleImporter.Report flat = importer.importFrom(usb, false, true);
        assertThat(flat.outcomes()).hasSize(3);
        assertThat(flat.installedCount()).isEqualTo(2);
        assertThat(flat.rejectedCount()).isEqualTo(1);
        assertThat(flat.outcomes().stream().filter(o -> !o.succeeded()).findFirst().get().failure().reason()).isEqualTo(BundleRejectedException.Reason.BAD_SIGNATURE);
        assertThat(store.current().get().version()).isEqualTo("1.2.0");
        assertThat(flat.outcomes().stream().filter(BundleImporter.Outcome::activated).map(o -> o.installed().version())).containsExactly("1.2.0");

        BundleImporter.Report deep = importer.importFrom(usb, true, true);
        assertThat(deep.installedCount()).isEqualTo(3);
        assertThat(store.current().get().version()).isEqualTo("1.3.0");

        assertThat(importer.importFrom(tmp.resolve("does-not-exist")).outcomes()).isEmpty();
        BundleImporter.Report single = importer.importFrom(usb.resolve("desk-1.0.0.mlb"));
        assertThat(single.anyInstalled()).isTrue();
        assertThat(single.outcomes().get(0).toString()).contains("installed");
    }
}
