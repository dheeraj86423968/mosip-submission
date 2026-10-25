package io.mosip.vivid.av;

import io.mosip.vivid.av.asr.DigitRecognizer;
import io.mosip.vivid.av.asr.VoskDigitRecognizer;
import io.mosip.vivid.av.asr.VoskModels;
import io.mosip.vivid.av.sync.LipSyncScorer;
import io.mosip.vivid.av.sync.MouthTrack;
import io.mosip.vivid.av.testing.Audio;
import io.mosip.vivid.av.testing.Corpus;
import io.mosip.vivid.av.testing.LipTracks;
import io.mosip.vivid.av.testing.Scenarios;
import io.mosip.vivid.av.testing.Scenarios.Kind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Evaluation on the synthetic TTS corpus (tools/synth_corpus.py) with the real Vosk recognisers and the real
 * verifier. Every clip is presented once as each {@link Kind}. Writes target/av-eval/results.md and
 * target/av-eval/trials.tsv. Opt-in (about 3-5 minutes): run with {@code -Dvivid.av.eval=true}. Skipped when the corpus
 * or the models are not installed.
 */
class AvCorpusEvaluationTest {
    private static final long PROMPT = 10_000_000_000L;
    private static final double FPS = 25;

    static final class Row {
        String lang, model, voice, kind, code, heard, outcome;
        boolean attack, passed, codeMatched, synced;
        String sync;
    }

    @Test
    void corpusEvaluation() throws Exception {
        assumeTrue(Boolean.getBoolean("vivid.av.eval"), "corpus evaluation is opt-in: -Dvivid.av.eval=true");
        var corpus = Corpus.load();
        assumeTrue(corpus.isPresent(), "synthetic corpus not generated (liveness-av/tools/synth_corpus.py)");
        Map<String, String> modelFor = new LinkedHashMap<>();
        modelFor.put("en", "en");
        modelFor.put("en-IN", "en");
        modelFor.put("hi", "hi");
        modelFor.put("te", "te");
        modelFor.put("gu", "gu");
        List<Row> rows = new ArrayList<>();
        Random rnd = new Random(20261024L);
        LipSyncScorer scorer = new LipSyncScorer();
        for (Map.Entry<String, String> m : modelFor.entrySet()) {
            String modelTag = m.getKey();
            String lang = m.getValue();
            if (VoskModels.find(modelTag).isEmpty()) continue;
            List<Corpus.Entry> entries = corpus.get().stream().filter(e -> e.lang.equals(lang)).collect(Collectors.toList());
            if (modelTag.equals("en-IN")) entries = entries.stream().filter(e -> e.voice.contains("l2arctic") || e.voice.contains("espeak")).collect(Collectors.toList());
            if (entries.isEmpty()) continue;
            try (DigitRecognizer rec = VoskDigitRecognizer.forLanguage(modelTag)) {
                SpokenCodeVerifier verifier = new SpokenCodeVerifier(rec, scorer);
                for (Corpus.Entry e : entries) {
                    final Corpus.Entry target = e;
                    List<Corpus.Entry> sameVoice = entries.stream().filter(x -> x.voice.equals(target.voice)).collect(Collectors.toList());
                    List<Corpus.Entry> otherVoices = entries.stream().filter(x -> !x.voice.equals(target.voice)).collect(Collectors.toList());
                    short[] spoken = Audio.read(e.file);
                    short[] otherSame = Audio.read(Scenarios.pickOther(sameVoice, e, rnd).file);
                    short[] otherVoice = Audio.read(Scenarios.pickOther(otherVoices.isEmpty() ? sameVoice : otherVoices, e, rnd).file);
                    for (Kind k : Kind.values()) {
                        Scenarios.Presentation p = Scenarios.build(k, e.code, spoken, otherSame, otherVoice, PROMPT, rnd);
                        long end = PROMPT + p.audio.length * 1_000_000_000L / Audio.RATE;
                        MouthTrack track = LipTracks.sample(p.mouth, PROMPT, end, FPS, rnd);
                        AvVerdict v = verifier.verify(SpokenCode.of(e.code), Audio.clip(p.audio, PROMPT), track, PROMPT, end);
                        Row r = new Row();
                        r.lang = lang; r.model = modelTag; r.voice = e.voice; r.kind = k.name(); r.code = e.code;
                        r.heard = v.recognized(); r.outcome = v.outcome().name(); r.attack = k.attack; r.passed = v.passed();
                        r.codeMatched = v.codeMatched(); r.synced = v.synced(); r.sync = String.valueOf(v.sync());
                        rows.add(r);
                    }
                }
            }
        }
        assumeTrue(!rows.isEmpty(), "no Vosk model installed");
        write(rows);

        // Floors that the current defaults meet on this corpus; the exact rates are in target/av-eval/results.md.
        assertThat(rejection(rows, Kind.STATIC_FACE)).as("photo + loudspeaker rejected").isEqualTo(1.0);
        assertThat(rejection(rows, Kind.REPLAY_WRONG_CODE)).as("replay of an old recording rejected").isEqualTo(1.0);
        assertThat(rejection(rows, Kind.SILENCE)).as("silence rejected").isEqualTo(1.0);
        assertThat(rejection(rows, Kind.DUBBED_SHIFTED)).as("lips shifted 400-800 ms rejected").isGreaterThanOrEqualTo(0.97);
        assertThat(rejection(rows, Kind.TWO_SPEAKERS)).as("accomplice reading the code rejected").isGreaterThanOrEqualTo(0.9);
        assertThat(rejection(rows, Kind.DUBBED_OTHER_UTTERANCE)).as("lips of another utterance rejected").isGreaterThanOrEqualTo(0.6);
        // Channel (b) alone: bona fide lips are judged synchronised.
        List<Row> live = rows.stream().filter(r -> r.kind.equals("LIVE")).collect(Collectors.toList());
        assertThat((double) live.stream().filter(r -> r.synced).count() / live.size()).as("LIVE synced").isGreaterThanOrEqualTo(0.95);
        // End to end on the neural voices for which the small models recognise digits reliably.
        for (String model : List.of("en", "hi")) {
            List<Row> l = live.stream().filter(r -> r.model.equals(model) && r.voice.startsWith("piper")).collect(Collectors.toList());
            if (l.isEmpty()) continue;
            assertThat((double) l.stream().filter(r -> r.passed).count() / l.size()).as("bona fide pass rate " + model).isGreaterThanOrEqualTo(0.85);
        }
    }

    private static double rejection(List<Row> rows, Kind k) {
        List<Row> l = rows.stream().filter(r -> r.kind.equals(k.name())).collect(Collectors.toList());
        return l.isEmpty() ? 1.0 : (double) l.stream().filter(r -> !r.passed).count() / l.size();
    }

    private static void write(List<Row> rows) throws IOException {
        Path dir = Paths.get("target", "av-eval");
        Files.createDirectories(dir);
        StringBuilder tsv = new StringBuilder("model\tvoice\tkind\tcode\theard\toutcome\tpassed\tsync\n");
        for (Row r : rows) {
            tsv.append(String.join("\t", r.model, r.voice, r.kind, r.code, r.heard, r.outcome, String.valueOf(r.passed), r.sync)).append('\n');
        }
        Files.write(dir.resolve("trials.tsv"), tsv.toString().getBytes(StandardCharsets.UTF_8));

        StringBuilder md = new StringBuilder();
        md.append("| Model | Voice | n | ").append(java.util.Arrays.stream(Kind.values()).map(k -> k.name() + (k.attack ? " (rejected)" : " (accepted)"))
                .collect(Collectors.joining(" | "))).append(" |\n");
        md.append("|---|---|---|").append("---|".repeat(Kind.values().length)).append('\n');
        Map<String, List<Row>> byVoice = new TreeMap<>(rows.stream().collect(Collectors.groupingBy(r -> r.model + "\t" + r.voice)));
        for (Map.Entry<String, List<Row>> g : byVoice.entrySet()) {
            String[] mv = g.getKey().split("\t");
            Map<Kind, int[]> counts = new EnumMap<>(Kind.class);
            for (Row r : g.getValue()) {
                int[] c = counts.computeIfAbsent(Kind.valueOf(r.kind), x -> new int[2]);
                c[1]++;
                if (r.attack != r.passed) c[0]++;
            }
            md.append("| ").append(mv[0]).append(" | ").append(mv[1]).append(" | ").append(counts.get(Kind.LIVE)[1]).append(" | ");
            for (Kind k : Kind.values()) {
                int[] c = counts.get(k);
                md.append(c[0]).append('/').append(c[1]).append(" | ");
            }
            md.append('\n');
        }
        md.append("\nOutcome counts per scenario (all models):\n\n");
        Map<String, Map<String, Long>> oc = rows.stream().collect(Collectors.groupingBy(r -> r.kind, TreeMap::new,
                Collectors.groupingBy(r -> r.outcome, TreeMap::new, Collectors.counting())));
        for (Map.Entry<String, Map<String, Long>> e : oc.entrySet()) md.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        md.append("\nSync channel (b) alone, share judged synchronised (all models; each clip once per model):\n\n");
        for (Kind k : Kind.values()) {
            List<Row> l = rows.stream().filter(r -> r.kind.equals(k.name()) && r.sync != null && !r.sync.equals("null")).collect(Collectors.toList());
            long syn = l.stream().filter(r -> r.synced).count();
            md.append(String.format(Locale.ROOT, "- %s: %d/%d%n", k, syn, l.size()));
        }
        md.append("\nASR exact-match rate on LIVE presentations (channel a alone):\n\n");
        Map<String, List<Row>> liveBy = new TreeMap<>(rows.stream().filter(r -> r.kind.equals("LIVE")).collect(Collectors.groupingBy(r -> r.model + " / " + r.voice)));
        for (Map.Entry<String, List<Row>> e : liveBy.entrySet()) {
            long ok = e.getValue().stream().filter(r -> r.codeMatched).count();
            md.append(String.format(Locale.ROOT, "- %s: %d/%d%n", e.getKey(), ok, e.getValue().size()));
        }
        Files.write(dir.resolve("results.md"), md.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(md);
    }
}
