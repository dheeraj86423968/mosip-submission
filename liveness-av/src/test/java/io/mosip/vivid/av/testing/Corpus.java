package io.mosip.vivid.av.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The synthetic TTS corpus made by liveness-av/tools/synth_corpus.py in ~/.cache/vivid-av/corpus (override with
 * -Dvivid.av.corpus). Tests that need it are skipped when it is absent.
 */
public final class Corpus {
    public static final class Entry {
        public final Path file; public final String lang; public final String voice; public final String code;
        Entry(Path file, String lang, String voice, String code) { this.file = file; this.lang = lang; this.voice = voice; this.code = code; }
        @Override public String toString() { return voice + "/" + file.getFileName() + "(" + code + ")"; }
    }

    private Corpus() {}

    public static Path root() {
        String p = System.getProperty("vivid.av.corpus");
        return p != null ? Paths.get(p) : Paths.get(System.getProperty("user.home"), ".cache", "vivid-av", "corpus");
    }

    public static Optional<List<Entry>> load() {
        Path m = root().resolve("manifest.tsv");
        if (!Files.isRegularFile(m)) return Optional.empty();
        try {
            List<Entry> out = new ArrayList<>();
            for (String line : Files.readAllLines(m, StandardCharsets.UTF_8)) {
                if (line.startsWith("file\t") || line.isBlank()) continue;
                String[] f = line.split("\t");
                out.add(new Entry(root().resolve(f[0]), f[1], f[2], f[3]));
            }
            return Optional.of(out);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
