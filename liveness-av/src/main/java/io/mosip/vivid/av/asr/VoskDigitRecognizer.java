package io.mosip.vivid.av.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.vivid.av.audio.AudioClip;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Offline digit recogniser on Vosk (Kaldi) small models, Apache-2.0. Decoding is restricted to a closed grammar of
 * the language's digit words plus {@code [unk]} ({@link DigitLexicon#grammarJson}), which is what makes the small
 * models reliable on digits. The model is loaded once; each call uses a fresh {@link Recognizer}, so concurrent calls
 * are safe. Audio is passed to native code in memory and not retained.
 */
public final class VoskDigitRecognizer implements DigitRecognizer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static volatile boolean logConfigured;

    private final String language;
    private final Model model;
    private final String grammar;

    public VoskDigitRecognizer(Path modelDir, String languageTag) throws IOException {
        Objects.requireNonNull(modelDir, "modelDir");
        if (!DigitLexicon.supports(languageTag)) throw new IllegalArgumentException("no digit lexicon for " + languageTag);
        if (!logConfigured) {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            logConfigured = true;
        }
        this.language = languageTag;
        this.model = new Model(modelDir.toString());
        this.grammar = DigitLexicon.grammarJson(languageTag);
    }

    /** Recogniser for an installed default model ({@link VoskModels}). */
    public static VoskDigitRecognizer forLanguage(String languageTag) throws IOException {
        Path dir = VoskModels.find(languageTag).orElseThrow(() -> new IOException(
                "Vosk model for " + languageTag + " not installed under " + VoskModels.root() + "; see liveness-av/models/README.md"));
        return new VoskDigitRecognizer(dir, languageTag);
    }

    @Override public String language() { return language; }

    @Override
    public Recognition recognize(AudioClip clip) {
        if (clip.length() == 0) return Recognition.empty();
        short[] pcm = clip.samples();
        try (Recognizer r = new Recognizer(model, clip.sampleRate(), grammar)) {
            r.setWords(true);
            int chunk = clip.sampleRate() / 5;
            List<String> segments = new ArrayList<>();
            for (int off = 0; off < pcm.length; off += chunk) {
                int len = Math.min(chunk, pcm.length - off);
                short[] part = new short[len];
                System.arraycopy(pcm, off, part, 0, len);
                // true means Vosk closed an utterance at a pause; its words are only available through getResult().
                if (r.acceptWaveForm(part, len)) segments.add(r.getResult());
            }
            segments.add(r.getFinalResult());
            return merge(language, segments);
        } finally {
            java.util.Arrays.fill(pcm, (short) 0);
        }
    }

    static Recognition merge(String language, List<String> segments) {
        StringBuilder digits = new StringBuilder();
        List<Recognition.Token> tokens = new ArrayList<>();
        int unk = 0;
        for (String json : segments) {
            Recognition r = parse(language, json);
            digits.append(r.digits());
            tokens.addAll(r.tokens());
            unk += r.unknownTokens();
        }
        return new Recognition(digits.toString(), tokens, unk);
    }

    static Recognition parse(String language, String json) {
        try {
            JsonNode root = JSON.readTree(json);
            List<Recognition.Token> tokens = new ArrayList<>();
            StringBuilder digits = new StringBuilder();
            int unk = 0;
            JsonNode words = root.path("result");
            if (words.isArray()) {
                for (JsonNode w : words) {
                    String word = w.path("word").asText("");
                    int d = DigitLexicon.digit(language, word);
                    if (d >= 0) digits.append(d); else unk++;
                    tokens.add(new Recognition.Token(word, d, w.path("start").asDouble(), w.path("end").asDouble(), w.path("conf").asDouble()));
                }
            } else {
                for (String word : root.path("text").asText("").trim().split("\\s+")) {
                    if (word.isEmpty()) continue;
                    int d = DigitLexicon.digit(language, word);
                    if (d >= 0) digits.append(d); else unk++;
                    tokens.add(new Recognition.Token(word, d, Double.NaN, Double.NaN, Double.NaN));
                }
            }
            return new Recognition(digits.toString(), tokens, unk);
        } catch (IOException e) {
            throw new IllegalStateException("unparseable Vosk result", e);
        }
    }

    @Override
    public void close() { model.close(); }
}
