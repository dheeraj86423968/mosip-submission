package io.mosip.vivid.engine.onnx;

import io.mosip.vivid.bundle.BundleRejectedException;
import io.mosip.vivid.bundle.BundleStore;
import io.mosip.vivid.bundle.InstalledBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Creates an {@link OnnxLivenessEngine} with sensible defaults. Model discovery order:
 * <ol>
 *   <li>the {@code vivid.models} system property, then the {@code MOSIP_VIVID_MODELS} environment variable;</li>
 *   <li>{@code ./models};</li>
 * </ol>
 * In the chosen directory an {@code engine.properties} file wins; otherwise models are recognised by file name:
 * {@code *yunet*} (detector), {@code *antispoof*}/{@code *minifasnet*} (S1, up to two), {@code *sface*}/{@code *arcface*}/
 * {@code *mobilefacenet*}/{@code *mbf*} (embedding). Production deployments should use {@link #fromStore}, which only
 * loads a signed, verified bundle.
 */
public final class OnnxEngineFactory {
    private static final Logger LOG = LoggerFactory.getLogger(OnnxEngineFactory.class);
    public static final String MODELS_PROPERTY = "vivid.models";
    public static final String MODELS_ENV = "MOSIP_VIVID_MODELS";
    public static final String PROPERTIES_FILE = "engine.properties";

    private OnnxEngineFactory() {}

    /** Engine from the first model directory found; fails if no detector can be found. */
    public static OnnxLivenessEngine create() {
        Path dir = findModelDirectory().orElseThrow(() -> new EngineException(
                "no model directory found (set -D" + MODELS_PROPERTY + ", $" + MODELS_ENV + " or create ./models)"));
        return new OnnxLivenessEngine(configFor(dir));
    }

    /** The first existing candidate directory. */
    public static Optional<Path> findModelDirectory() {
        List<String> candidates = new ArrayList<>();
        String prop = System.getProperty(MODELS_PROPERTY);
        if (prop != null && !prop.isBlank()) candidates.add(prop);
        String env = System.getenv(MODELS_ENV);
        if (env != null && !env.isBlank()) candidates.add(env);
        candidates.add("models");
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.isDirectory(p)) return Optional.of(p.toAbsolutePath());
        }
        return Optional.empty();
    }

    /** Configuration for a directory: {@code engine.properties} if present, otherwise discovery by file name. */
    public static EngineConfig configFor(Path dir) {
        Path props = dir.resolve(PROPERTIES_FILE);
        if (Files.isRegularFile(props)) {
            try {
                return EngineConfig.load(props);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return discover(dir).build();
    }

    /** Builder pre-filled with the models recognised in {@code dir}. */
    public static EngineConfig.Builder discover(Path dir) {
        EngineConfig.Builder b = EngineConfig.builder();
        List<Path> onnx = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".onnx")).sorted().forEach(onnx::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int antiSpoof = 0;
        for (Path p : onnx) {
            String n = EngineConfig.Builder.lower(p);
            if (n.contains("yunet")) b.detectorModel(p);
            else if ((n.contains("antispoof") || n.contains("minifasnet") || n.contains("anti_spoof")) && antiSpoof < 2) {
                b.antiSpoofModel(AntiSpoofModelConfig.preset(p, 0));
                antiSpoof++;
            } else if (n.contains("sface") || n.contains("arcface") || n.contains("mobilefacenet") || n.contains("mbf")) b.embeddingModel(p);
        }
        LOG.info("discovered models in {}: {}", dir, onnx);
        return b;
    }

    /** Engine from the active bundle of a verified store. */
    public static OnnxLivenessEngine fromStore(BundleStore store) throws BundleRejectedException {
        InstalledBundle b = store.current().orElseThrow(() ->
                new BundleRejectedException(BundleRejectedException.Reason.NOT_INSTALLED, "no active bundle in " + store.root()));
        return new OnnxLivenessEngine(EngineConfig.fromBundle(b, null));
    }
}
