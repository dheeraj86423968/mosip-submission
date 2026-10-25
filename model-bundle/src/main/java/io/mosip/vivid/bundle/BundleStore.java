package io.mosip.vivid.bundle;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * On-disk store of verified bundles.
 *
 * <pre>
 * root/
 *   store.json                      active pointer + activation history (most recent first)
 *   &lt;bundleId&gt;/&lt;version&gt;/manifest.json, manifest.sig, model files...
 * </pre>
 *
 * Install is atomic (extract into a temp sibling, then rename). Every bundle is re-verified from disk when it is
 * activated or returned as {@link #current()}, so on-disk tampering after install is detected as well.
 * Rollback walks the activation history; {@code keepPrevious} limits how many non-active versions are retained.
 * Thread-safe through coarse synchronisation; this is a rare, administrative path.
 */
public final class BundleStore {
    private static final Logger LOG = LoggerFactory.getLogger(BundleStore.class);
    private static final String STATE_FILE = "store.json";
    private static final ObjectMapper MAPPER = new ObjectMapper().configure(SerializationFeature.INDENT_OUTPUT, true);

    /** Persisted pointer file. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class State {
        public String activeBundleId;
        public String activeVersion;
        /** Previously active {@code bundleId@version}, most recent first, excluding the active one. */
        public List<String> history = new ArrayList<>();
    }

    private final Path root;
    private final BundleVerifier verifier;
    private final BundleCompatibility compatibility; // nullable: no compatibility enforcement
    private final int keepPrevious;
    private final BundleEventListener listener;

    /**
     * @param root          store directory (created if missing)
     * @param verifier      pinned-key verifier applied on install and on every activation
     * @param compatibility engine compatibility matrix, or {@code null} to skip
     * @param keepPrevious  how many non-active versions per bundle id to retain after activation (>= 0)
     * @param listener      audit hook
     */
    public BundleStore(Path root, BundleVerifier verifier, BundleCompatibility compatibility, int keepPrevious, BundleEventListener listener) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        this.verifier = Objects.requireNonNull(verifier);
        this.compatibility = compatibility;
        if (keepPrevious < 0) throw new IllegalArgumentException("keepPrevious must be >= 0");
        this.keepPrevious = keepPrevious;
        this.listener = listener == null ? BundleEventListener.logging() : listener;
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public BundleStore(Path root, BundleVerifier verifier, BundleCompatibility compatibility, int keepPrevious) {
        this(root, verifier, compatibility, keepPrevious, null);
    }

    public Path root() { return root; }

    /**
     * Verifies and extracts an archive into {@code <root>/<bundleId>/<version>/}. Does not activate.
     * Re-installing an existing version replaces it only if the new archive verifies.
     */
    public synchronized InstalledBundle install(Path mlb) throws BundleRejectedException {
        VerifiedBundle verified;
        try {
            verified = verifier.verify(mlb);
            if (compatibility != null) compatibility.check(verified.manifest());
        } catch (BundleRejectedException e) {
            listener.onRejected(mlb, e.reason(), e.getMessage());
            throw e;
        }
        BundleManifest m = verified.manifest();
        Path target = bundleDir(m.getBundleId(), m.getVersion());
        Path tmp = target.resolveSibling(m.getVersion() + ".installing-" + System.nanoTime());
        try {
            Files.createDirectories(tmp);
            extract(mlb, verified, tmp);
            if (Files.exists(target)) deleteRecursively(target);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try { deleteRecursively(tmp); } catch (IOException ignored) { /* best effort */ }
            throw new BundleRejectedException(BundleRejectedException.Reason.IO_ERROR, "install failed: " + e.getMessage(), e);
        }
        InstalledBundle installed = new InstalledBundle(m, target);
        listener.onInstalled(installed);
        return installed;
    }

    /** Makes an installed version the active one after re-verifying it on disk, and prunes old versions. */
    public synchronized InstalledBundle activate(String bundleId, String version) throws BundleRejectedException {
        InstalledBundle next = load(bundleId, version);
        State state = readState();
        InstalledBundle previous = currentUnchecked(state).orElse(null);
        String nextKey = key(bundleId, version);
        if (previous != null && key(previous.bundleId(), previous.version()).equals(nextKey)) {
            return next; // already active
        }
        if (previous != null) {
            state.history.remove(key(previous.bundleId(), previous.version()));
            state.history.add(0, key(previous.bundleId(), previous.version()));
        }
        state.history.remove(nextKey);
        state.activeBundleId = bundleId;
        state.activeVersion = version;
        writeState(state);
        listener.onActivated(next, previous);
        prune(state);
        return next;
    }

    /**
     * Re-activates the most recently active previous bundle. Used by diagnostic mode (one click) and automatically
     * when a freshly activated bundle fails its self-test.
     *
     * @return the restored bundle, or empty when there is no history
     */
    public synchronized Optional<InstalledBundle> rollback() throws BundleRejectedException {
        State state = readState();
        InstalledBundle from = currentUnchecked(state).orElse(null);
        while (!state.history.isEmpty()) {
            String[] parts = state.history.get(0).split("@", 2);
            if (parts.length == 2 && Files.isDirectory(bundleDir(parts[0], parts[1]))) {
                InstalledBundle restored = activate(parts[0], parts[1]);
                // activate() put 'from' at the head of the history; demote it to the tail so that a second
                // rollback walks further back instead of alternating between the two most recent bundles.
                if (from != null) {
                    State after = readState();
                    String fromKey = key(from.bundleId(), from.version());
                    if (after.history.remove(fromKey)) {
                        after.history.add(fromKey);
                        writeState(after);
                    }
                }
                listener.onRolledBack(restored, from);
                return Optional.of(restored);
            }
            state.history.remove(0);
            writeState(state);
        }
        return Optional.empty();
    }

    /** The active bundle, re-verified from disk; empty when nothing is active. */
    public synchronized Optional<InstalledBundle> current() throws BundleRejectedException {
        State state = readState();
        if (state.activeBundleId == null || state.activeVersion == null) return Optional.empty();
        return Optional.of(load(state.activeBundleId, state.activeVersion));
    }

    /** All installed bundles (not verified; use {@link #load} for a verified handle). */
    public synchronized List<InstalledBundle> list() {
        List<InstalledBundle> out = new ArrayList<>();
        try (DirectoryStream<Path> ids = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path idDir : ids) {
                try (DirectoryStream<Path> versions = Files.newDirectoryStream(idDir, Files::isDirectory)) {
                    for (Path vDir : versions) {
                        Path manifest = vDir.resolve(BundleManifest.MANIFEST_ENTRY);
                        if (!Files.isRegularFile(manifest)) continue;
                        try {
                            BundleManifest m = BundleManifest.parse(Files.readAllBytes(manifest));
                            out.add(new InstalledBundle(m, vDir));
                        } catch (BundleRejectedException | IOException e) {
                            LOG.warn("skipping unreadable bundle directory {}: {}", vDir, e.getMessage());
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.comparing(InstalledBundle::bundleId).thenComparing(b -> SemanticVersion.parse(b.version())));
        return out;
    }

    /** Verified handle on an installed version. */
    public synchronized InstalledBundle load(String bundleId, String version) throws BundleRejectedException {
        Path dir = bundleDir(bundleId, version);
        if (!Files.isDirectory(dir)) {
            throw new BundleRejectedException(BundleRejectedException.Reason.NOT_INSTALLED, key(bundleId, version));
        }
        try {
            VerifiedBundle v = verifier.verifyDirectory(dir);
            if (compatibility != null) compatibility.check(v.manifest());
            return new InstalledBundle(v.manifest(), dir);
        } catch (BundleRejectedException e) {
            listener.onRejected(dir, e.reason(), e.getMessage());
            throw e;
        }
    }

    /** Removes an installed version. Refuses to remove the active one. */
    public synchronized void remove(String bundleId, String version) throws IOException {
        State state = readState();
        if (bundleId.equals(state.activeBundleId) && version.equals(state.activeVersion)) {
            throw new IllegalStateException("cannot remove the active bundle " + key(bundleId, version));
        }
        Path dir = bundleDir(bundleId, version);
        if (Files.exists(dir)) {
            deleteRecursively(dir);
            state.history.remove(key(bundleId, version));
            writeState(state);
            listener.onRemoved(bundleId, version);
        }
    }

    /** Current persisted state (copy). */
    public synchronized State state() {
        return readState();
    }

    // ---- internals -------------------------------------------------------------------------------------------

    private void prune(State state) {
        java.util.Map<String, List<InstalledBundle>> byId = new java.util.HashMap<>();
        for (InstalledBundle b : list()) byId.computeIfAbsent(b.bundleId(), k -> new ArrayList<>()).add(b);
        for (List<InstalledBundle> versions : byId.values()) {
            List<InstalledBundle> candidates = new ArrayList<>();
            for (InstalledBundle b : versions) {
                if (b.bundleId().equals(state.activeBundleId) && b.version().equals(state.activeVersion)) continue;
                candidates.add(b);
            }
            // Keep the most recently activated ones first, then the newest by version.
            candidates.sort(Comparator.comparingInt((InstalledBundle b) -> {
                int i = state.history.indexOf(key(b.bundleId(), b.version()));
                return i < 0 ? Integer.MAX_VALUE : i;
            }).thenComparing(b -> SemanticVersion.parse(b.version()), Comparator.reverseOrder()));
            for (int i = keepPrevious; i < candidates.size(); i++) {
                InstalledBundle victim = candidates.get(i);
                try {
                    remove(victim.bundleId(), victim.version());
                } catch (IOException e) {
                    LOG.warn("could not prune {}: {}", victim, e.getMessage());
                }
            }
        }
    }

    private Optional<InstalledBundle> currentUnchecked(State state) {
        if (state.activeBundleId == null || state.activeVersion == null) return Optional.empty();
        Path dir = bundleDir(state.activeBundleId, state.activeVersion);
        Path manifest = dir.resolve(BundleManifest.MANIFEST_ENTRY);
        if (!Files.isRegularFile(manifest)) return Optional.empty();
        try {
            return Optional.of(new InstalledBundle(BundleManifest.parse(Files.readAllBytes(manifest)), dir));
        } catch (IOException | BundleRejectedException e) {
            return Optional.empty();
        }
    }

    private Path bundleDir(String bundleId, String version) {
        if (!BundleManifest.isSafeRelativePath(bundleId) || bundleId.contains("/")
                || !BundleManifest.isSafeRelativePath(version) || version.contains("/")) {
            throw new IllegalArgumentException("unsafe bundle id or version");
        }
        return root.resolve(bundleId).resolve(version);
    }

    private static String key(String id, String version) { return id + "@" + version; }

    private State readState() {
        Path f = root.resolve(STATE_FILE);
        if (!Files.isRegularFile(f)) return new State();
        try {
            State s = MAPPER.readValue(f.toFile(), State.class);
            if (s.history == null) s.history = new ArrayList<>();
            return s;
        } catch (IOException e) {
            LOG.warn("store state unreadable, treating as empty: {}", e.getMessage());
            return new State();
        }
    }

    private void writeState(State s) {
        Path f = root.resolve(STATE_FILE);
        Path tmp = root.resolve(STATE_FILE + ".tmp");
        try {
            MAPPER.writeValue(tmp.toFile(), s);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void extract(Path mlb, VerifiedBundle verified, Path dir) throws IOException {
        Files.write(dir.resolve(BundleManifest.MANIFEST_ENTRY), verified.manifestBytes());
        Files.write(dir.resolve(BundleManifest.SIGNATURE_ENTRY), verified.signatureBytes());
        try (ZipFile zip = new ZipFile(mlb.toFile())) {
            for (String rel : verified.manifest().referencedFiles()) {
                ZipEntry e = zip.getEntry(rel);
                Path out = dir.resolve(rel).normalize();
                if (e == null || !out.startsWith(dir)) throw new IOException("unsafe or missing entry " + rel);
                Files.createDirectories(out.getParent());
                try (InputStream in = zip.getInputStream(e)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            List<Path> all = new ArrayList<>();
            walk.forEach(all::add);
            Collections.reverse(all);
            for (Path x : all) Files.deleteIfExists(x);
        }
    }
}
