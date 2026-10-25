package io.mosip.vivid.av.asr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Locates Vosk models on disk. Models are never in the repository; by default they live in
 * {@code ~/.cache/vivid-av/models} (override with system property {@code vivid.av.models} or environment variable
 * {@code VIVID_AV_MODELS}). See liveness-av/models/README.md for the download list and checksums.
 */
public final class VoskModels {
    /** Language tag to model directory name. {@code en} is US English; {@code en-IN} is the Indian English model. */
    public static final Map<String, String> DEFAULT_MODELS = new LinkedHashMap<>();

    static {
        DEFAULT_MODELS.put("en", "vosk-model-small-en-us-0.15");
        DEFAULT_MODELS.put("en-IN", "vosk-model-small-en-in-0.4");
        DEFAULT_MODELS.put("hi", "vosk-model-small-hi-0.22");
        DEFAULT_MODELS.put("te", "vosk-model-small-te-0.42");
        DEFAULT_MODELS.put("gu", "vosk-model-small-gu-0.42");
    }

    private VoskModels() {}

    public static Path root() {
        String p = System.getProperty("vivid.av.models");
        if (p == null || p.isBlank()) p = System.getenv("VIVID_AV_MODELS");
        if (p != null && !p.isBlank()) return Paths.get(p);
        return Paths.get(System.getProperty("user.home"), ".cache", "vivid-av", "models");
    }

    /** The model directory for a language tag, if it is installed (has {@code am/} and {@code graph/}). */
    public static Optional<Path> find(String languageTag) {
        String name = DEFAULT_MODELS.get(languageTag);
        if (name == null) name = DEFAULT_MODELS.get(DigitLexicon.base(languageTag));
        if (name == null) return Optional.empty();
        Path dir = root().resolve(name);
        return Files.isDirectory(dir.resolve("am")) && Files.isDirectory(dir.resolve("graph")) ? Optional.of(dir) : Optional.empty();
    }
}
