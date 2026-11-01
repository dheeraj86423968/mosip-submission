package io.mosip.vivid.av;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.av.asr.DigitRecognizer;
import io.mosip.vivid.av.asr.VoskDigitRecognizer;
import io.mosip.vivid.av.asr.VoskModels;
import io.mosip.vivid.av.testing.Audio;
import io.mosip.vivid.av.testing.Corpus;
import io.mosip.vivid.av.testing.FakeClock;
import io.mosip.vivid.av.testing.FakeRecognizer;
import io.mosip.vivid.av.testing.LipTracks;
import io.mosip.vivid.av.testing.QueuedDigitsRandom;
import io.mosip.vivid.av.testing.ScriptedAudioSource;
import io.mosip.vivid.av.testing.ScriptedEngine;
import io.mosip.vivid.core.action.ActionThresholds;
import io.mosip.vivid.core.session.LivenessOrchestrator;
import io.mosip.vivid.core.session.LivenessSessionFactory;
import io.mosip.vivid.telemetry.audit.AuditRecord;
import io.mosip.vivid.telemetry.audit.LivenessAuditEvent;
import io.mosip.vivid.telemetry.metrics.LivenessMetrics;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The spoken code through the real {@link LivenessOrchestrator}: real challenge selection, real HOLD_STILL detector,
 * real fusion and veto, the AV analyzer on the engine seam. The camera and face engine are scripted
 * ({@link ScriptedEngine}); the microphone is scripted ({@link ScriptedAudioSource}). The resident is assisted
 * capture with accessibility pool {HOLD_STILL}: the spoken code is the only active evidence, the accessibility path
 * of PLAN.md 17.8.
 */
class AvOrchestratorEndToEndTest {

    enum Subject { LIVE, STATIC_FACE, REPLAY_WRONG_CODE, DUBBED_SHIFTED, SILENCE }

    static final class Rig {
        final FakeClock clock = new FakeClock();
        final ScriptedEngine face = new ScriptedEngine(clock);
        final List<ScriptedAudioSource> mics = new ArrayList<>();
        final List<AuditRecord> audit = new ArrayList<>();
        final List<SpokenCodePrompt> prompts = new ArrayList<>();
        final List<AvVerdict> verdicts = new ArrayList<>();
        final AvLivenessEngine engine;
        final LivenessSessionFactory factory;
        final Random rnd = new Random(99);
        final AtomicReference<Subject> subject = new AtomicReference<>(Subject.LIVE);
        final Function<String, short[]> voice;
        /** What the scripted subject actually said (the fake recogniser reports it). */
        final AtomicReference<String> said = new AtomicReference<>("");

        /** @param recognizer null for a fake recogniser that hears exactly what was said */
        /** Code a replayed old recording says; default: any other code. */
        Function<String, String> replayCode = shown -> String.format("%04d", (Integer.parseInt(shown) + 1 + rnd.nextInt(9998)) % 10_000);

        Rig(AvConfig cfg, DigitRecognizer recognizer, Function<String, short[]> voice) {
            this(cfg, recognizer, voice, seeded("code"));
        }

        Rig(AvConfig cfg, DigitRecognizer recognizer, Function<String, short[]> voice, SecureRandom codeRandom) {
            this.voice = voice;
            DigitRecognizer asr = recognizer != null ? recognizer : new FakeRecognizer(clip -> said.get());
            engine = new AvLivenessEngine(face, cfg, asr, () -> {
                ScriptedAudioSource s = new ScriptedAudioSource(clock);
                mics.add(s);
                return s;
            }, clock, codeRandom);
            engine.addListener(new SpokenCodeListener() {
                @Override public void onPrompt(SpokenCodePrompt p) { prompts.add(p); respond(p); }
                @Override public void onVerdict(AvVerdict v) { verdicts.add(v); }
            });
            ActionThresholds t = AvSetup.carrierThresholds(ActionThresholds.defaults(), cfg);
            LivenessPolicy policy = LivenessPolicy.builder(Workflow.RESIDENT).policyVersion("av-e2e").passiveThreshold(0.8)
                    .activeMode(LivenessPolicy.ActiveMode.ALWAYS).minChallenges(1).maxChallenges(1)
                    .challengePool(EnumSet.of(ChallengeType.BLINK, ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT))
                    .accessibilityPool(EnumSet.of(ChallengeType.HOLD_STILL)).maxRetries(4).build();
            assertThat(AvSetup.check(policy, true, t, cfg)).isEmpty();
            factory = new LivenessSessionFactory(engine, w -> policy, audit::add, new LivenessMetrics(), clock, seeded("session"), t);
        }

        void respond(SpokenCodePrompt p) {
            String shown = p.code().digits();
            String other = replayCode.apply(shown);
            ScriptedAudioSource mic = mics.get(mics.size() - 1);
            short[] pcm;
            switch (subject.get()) {
                case SILENCE: pcm = Audio.withNoise(Audio.silence(7000), 0.003, rnd); said.set(""); break;
                case REPLAY_WRONG_CODE: pcm = Audio.withNoise(Audio.pad(voice.apply(other), 500, 1500), 0.002, rnd); said.set(other); break;
                default: pcm = Audio.withNoise(Audio.pad(voice.apply(shown), 500 + rnd.nextInt(400), 1500), 0.002, rnd); said.set(shown);
            }
            mic.play(pcm);
            switch (subject.get()) {
                case STATIC_FACE: case SILENCE: face.mouth = LipTracks.still(0.004, rnd); break;
                case DUBBED_SHIFTED: face.mouth = LipTracks.articulated(pcm, p.issuedNanos(), 600, rnd); break;
                default: face.mouth = LipTracks.articulated(pcm, p.issuedNanos(), rnd.nextInt(120), rnd);
            }
        }

        /** Runs one attempt at 30 fps until PASSED, FAILED or LOCKED (max 25 s). */
        SessionState run(LivenessOrchestrator s) {
            for (int i = 0; i < 750; i++) {
                clock.advanceMillis(33);
                SessionState st = s.feed(ScriptedEngine.frame(i, clock.nanoTime()));
                if (st.isTerminal() || st.phase() == SessionState.Phase.FAILED) return st;
            }
            return s.state();
        }

        void resetFace() { face.mouth = t -> LipTracks.REST; }
    }

    private static Function<String, short[]> javaSpeech(Random r) { return code -> Audio.syntheticSpeech(code, r); }

    @Test
    void liveResidentPassesOnTheSpokenCode() {
        Rig rig = new Rig(AvConfig.defaults(), null, javaSpeech(new Random(1)));
        LivenessOrchestrator s = rig.factory.open(Workflow.RESIDENT, Locale.ENGLISH, false, true);
        SessionState st = rig.run(s);
        assertThat(st.phase()).as(st + " " + rig.verdicts).isEqualTo(SessionState.Phase.PASSED);
        LivenessResult r = s.result();
        assertThat(r.decision()).isEqualTo(LivenessResult.Decision.LIVE);
        assertThat(SpokenCodeRequirement.satisfied(r)).isTrue();
        assertThat(r.engineId()).isEqualTo("scripted+av");
        assertThat(r.challenges()).extracting(c -> c.challenge().type()).containsExactly(ChallengeType.HOLD_STILL);
        assertThat(rig.verdicts).hasSize(1);
        assertThat(rig.verdicts.get(0).decidedAfterMillis())
                .as("verdict must precede carrier completion").isLessThan(AvConfig.defaults().responseWindowMillis() + AvSetup.CARRIER_SLACK_MILLIS);
        assertThat(rig.audit).extracting(AuditRecord::event).contains(LivenessAuditEvent.SESSION_PASSED);
        assertThat(rig.mics).allMatch(m -> m.wiped);
    }

    @Test
    void attacksFailWithPadDetectedAndARetryDrawsANewCode() {
        Rig rig = new Rig(AvConfig.defaults(), null, javaSpeech(new Random(2)));
        LivenessOrchestrator s = rig.factory.open(Workflow.RESIDENT, Locale.ENGLISH, false, true);
        Map<Subject, String> expectedClass = new LinkedHashMap<>();
        expectedClass.put(Subject.STATIC_FACE, Signals.ATTACK_PRINT);
        expectedClass.put(Subject.REPLAY_WRONG_CODE, Signals.ATTACK_VIDEO);
        expectedClass.put(Subject.SILENCE, Signals.ATTACK_UNKNOWN);
        int attempt = 0;
        for (Map.Entry<Subject, String> e : expectedClass.entrySet()) {
            if (attempt++ > 0) assertThat(s.retry()).isTrue();
            rig.resetFace();
            rig.subject.set(e.getKey());
            SessionState st = rig.run(s);
            assertThat(st.phase()).as(e.getKey().toString()).isEqualTo(SessionState.Phase.FAILED);
            assertThat(st.failure()).contains(FailureReason.PAD_DETECTED);
            LivenessResult r = s.result();
            assertThat(r.vetoSignal()).contains(AvSignals.AV_SYNC);
            assertThat(r.attackClass()).as(e.getKey().toString()).contains(e.getValue());
        }
        // fourth and last attempt: the real resident
        assertThat(s.retry()).isTrue();
        rig.resetFace();
        rig.subject.set(Subject.LIVE);
        assertThat(rig.run(s).phase()).isEqualTo(SessionState.Phase.PASSED);
        assertThat(rig.prompts).hasSize(4);
        assertThat(rig.prompts.stream().map(p -> p.code().digits()).distinct().count()).as("fresh code per attempt (collision odds 6e-4)").isGreaterThanOrEqualTo(3);
    }

    @Test
    void withVoskAndTtsVoices() throws Exception {
        var corpus = Corpus.load();
        assumeTrue(corpus.isPresent(), "synthetic corpus not generated (tools/synth_corpus.py)");
        Map<String, String> voices = new LinkedHashMap<>();
        voices.put("en", "piper-en-lessac");
        voices.put("hi", "piper-hi-pratham");
        voices.put("te", "piper-te-venkatesh");
        voices.put("gu", "espeak-gu");
        StringBuilder report = new StringBuilder();
        int ran = 0;
        for (Map.Entry<String, String> v : voices.entrySet()) {
            if (VoskModels.find(v.getKey()).isEmpty()) continue;
            List<Corpus.Entry> clips = new ArrayList<>();
            for (Corpus.Entry e : corpus.get()) if (e.voice.equals(v.getValue())) clips.add(e);
            if (clips.size() < 2) continue;
            Map<String, short[]> byCode = new LinkedHashMap<>();
            for (Corpus.Entry e : clips) byCode.put(e.code, Audio.read(e.file));
            List<String> codes = new ArrayList<>(byCode.keySet());
            QueuedDigitsRandom draw = new QueuedDigitsRandom();
            try (DigitRecognizer rec = VoskDigitRecognizer.forLanguage(v.getKey())) {
                Rig rig = new Rig(AvConfig.builder().languageTag(v.getKey()).build(), rec, byCode::get, draw);
                rig.replayCode = shown -> codes.get((codes.indexOf(shown) + 1) % codes.size());
                Map<Subject, int[]> tally = new LinkedHashMap<>();
                int k = 0;
                for (Subject sub : Subject.values()) {
                    int n = sub == Subject.LIVE ? 6 : 3;
                    for (int i = 0; i < n; i++) {
                        draw.queue(codes.get(k++ % codes.size()));
                        rig.resetFace();
                        rig.subject.set(sub);
                        LivenessOrchestrator s = rig.factory.open(Workflow.RESIDENT, Locale.ENGLISH, false, true);
                        SessionState st = rig.run(s);
                        int[] t = tally.computeIfAbsent(sub, x -> new int[2]);
                        t[1]++;
                        if (st.phase() == SessionState.Phase.PASSED) t[0]++;
                        AvVerdict last = rig.verdicts.isEmpty() ? null : rig.verdicts.get(rig.verdicts.size() - 1);
                        report.append(String.format(Locale.ROOT, "  %s %s %s -> %s%n", v.getKey(), sub, last, st.phase()));
                        s.close();
                    }
                }
                report.append(String.format(Locale.ROOT, "%s/%s:", v.getKey(), v.getValue()));
                tally.forEach((sub, t) -> report.append(' ').append(sub).append(" passed ").append(t[0]).append('/').append(t[1]).append(';'));
                report.append('\n');
                for (Subject sub : Subject.values()) {
                    if (sub != Subject.LIVE) assertThat(tally.get(sub)[0]).as(v.getKey() + " " + sub + " accepted").isZero();
                }
                if (v.getKey().equals("en") || v.getKey().equals("hi")) {
                    assertThat(tally.get(Subject.LIVE)[0]).as(v.getKey() + " live passes").isGreaterThanOrEqualTo(5);
                }
                ran++;
            }
        }
        assumeTrue(ran > 0, "no Vosk model installed");
        java.nio.file.Files.createDirectories(java.nio.file.Paths.get("target", "av-eval"));
        java.nio.file.Files.write(java.nio.file.Paths.get("target", "av-eval", "e2e.txt"), report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Seeded SHA1PRNG so challenge and code draws repeat from run to run. */
    private static SecureRandom seeded(String salt) {
        try {
            SecureRandom r = SecureRandom.getInstance("SHA1PRNG");
            r.setSeed(("vivid-av-e2e:" + salt).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return r;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
