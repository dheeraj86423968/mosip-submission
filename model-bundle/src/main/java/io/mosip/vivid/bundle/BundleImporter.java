package io.mosip.vivid.bundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Offline update channel: imports {@code .mlb} files from a USB stick or any local path. Both a single file and
 * a directory (scanned non-recursively by default, recursively on request) are accepted. Every archive goes through
 * the same {@link BundleStore#install} verification as the online channel; failures are reported per file and
 * never abort the batch. Optionally the newest compatible imported bundle is activated.
 */
public final class BundleImporter {
    private static final Logger LOG = LoggerFactory.getLogger(BundleImporter.class);

    /** Outcome of one archive. */
    public static final class Outcome {
        private final Path source;
        private final InstalledBundle installed;      // null on failure
        private final BundleRejectedException failure; // null on success
        private final boolean activated;

        Outcome(Path source, InstalledBundle installed, BundleRejectedException failure, boolean activated) {
            this.source = source; this.installed = installed; this.failure = failure; this.activated = activated;
        }
        public Path source() { return source; }
        public boolean succeeded() { return installed != null; }
        public InstalledBundle installed() { return installed; }
        public BundleRejectedException failure() { return failure; }
        public boolean activated() { return activated; }
        @Override public String toString() {
            return source.getFileName() + ": " + (succeeded() ? "installed " + installed + (activated ? " [active]" : "") : "rejected " + failure.getMessage());
        }
    }

    /** Result of an import run. */
    public static final class Report {
        private final List<Outcome> outcomes;
        Report(List<Outcome> outcomes) { this.outcomes = Collections.unmodifiableList(outcomes); }
        public List<Outcome> outcomes() { return outcomes; }
        public long installedCount() { return outcomes.stream().filter(Outcome::succeeded).count(); }
        public long rejectedCount() { return outcomes.size() - installedCount(); }
        public boolean anyInstalled() { return installedCount() > 0; }
    }

    private final BundleStore store;

    public BundleImporter(BundleStore store) {
        this.store = Objects.requireNonNull(store);
    }

    /**
     * @param fileOrDirectory an {@code .mlb} or a directory containing them (e.g. the USB mount point)
     * @param recursive       scan sub-directories
     * @param activateNewest  after import, activate the highest-versioned successfully installed bundle
     */
    public Report importFrom(Path fileOrDirectory, boolean recursive, boolean activateNewest) {
        List<Path> candidates = findArchives(fileOrDirectory, recursive);
        List<Outcome> outcomes = new ArrayList<>();
        InstalledBundle newest = null;
        for (Path p : candidates) {
            try {
                InstalledBundle b = store.install(p);
                outcomes.add(new Outcome(p, b, null, false));
                if (newest == null || SemanticVersion.parse(b.version()).compareTo(SemanticVersion.parse(newest.version())) > 0) {
                    newest = b;
                }
            } catch (BundleRejectedException e) {
                LOG.warn("import of {} rejected: {}", p, e.getMessage());
                outcomes.add(new Outcome(p, null, e, false));
            }
        }
        if (activateNewest && newest != null) {
            try {
                store.activate(newest.bundleId(), newest.version());
                for (int i = 0; i < outcomes.size(); i++) {
                    Outcome o = outcomes.get(i);
                    if (o.succeeded() && o.installed().bundleId().equals(newest.bundleId()) && o.installed().version().equals(newest.version())) {
                        outcomes.set(i, new Outcome(o.source(), o.installed(), null, true));
                    }
                }
            } catch (BundleRejectedException e) {
                LOG.error("activation of imported bundle {} failed: {}", newest, e.getMessage());
            }
        }
        return new Report(outcomes);
    }

    /** Convenience: non-recursive, no activation. */
    public Report importFrom(Path fileOrDirectory) {
        return importFrom(fileOrDirectory, false, false);
    }

    static List<Path> findArchives(Path p, boolean recursive) {
        if (Files.isRegularFile(p)) return List.of(p);
        if (!Files.isDirectory(p)) return List.of();
        try (Stream<Path> s = recursive ? Files.walk(p) : Files.list(p)) {
            List<Path> out = new ArrayList<>();
            s.filter(Files::isRegularFile).filter(f -> f.getFileName().toString().toLowerCase().endsWith(".mlb")).sorted().forEach(out::add);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
