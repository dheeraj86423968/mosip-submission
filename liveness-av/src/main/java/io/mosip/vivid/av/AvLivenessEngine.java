package io.mosip.vivid.av;

import io.mosip.vivid.api.EngineInfo;
import io.mosip.vivid.api.FrameAnalyzer;
import io.mosip.vivid.api.LivenessEngine;
import io.mosip.vivid.av.asr.DigitRecognizer;
import io.mosip.vivid.av.audio.AudioSource;
import io.mosip.vivid.av.sync.LipSyncScorer;
import io.mosip.vivid.core.session.TimeSource;

import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Decorates any {@link LivenessEngine} with the spoken-code audio-visual challenge (PLAN.md 17.8), without changing
 * the frozen liveness-api.
 *
 * <p><b>Seam.</b> The frozen {@code ChallengeType} enum has no SPEAK_CODE (see docs/design/13-audio-visual-liveness.md
 * for why it is not added). Instead, the analyzer listens to {@link FrameAnalyzer#onChallenge}: when the
 * orchestrator issues a <em>carrier</em> challenge ({@link AvConfig#carriers()}, default {@code HOLD_STILL}) it
 * draws a code from {@link SecureRandom}, tells the UI ({@link SpokenCodeListener}), opens the microphone and
 * records mouth opening per frame. When the utterance ends (or the response window expires) it decides and reports
 * the result as signal {@link AvSignals#AV_SYNC}: a hard veto on failure, which the orchestrator turns into
 * {@code PAD_DETECTED}, or a low attack probability with full confidence on success.</p>
 *
 * <p><b>Fail-closed timing.</b> {@link AvSetup#carrierThresholds} makes the carrier's hold time longer than the
 * response window, and the analyzer always decides by the end of the window, on the same clock as the orchestrator.
 * So the verdict reaches fusion before the carrier can complete, and a failed round always vetoes.</p>
 */
public final class AvLivenessEngine implements LivenessEngine {
    private final LivenessEngine inner;
    private final AvConfig config;
    private final DigitRecognizer recognizer;
    private final Supplier<AudioSource> audio;
    private final TimeSource time;
    private final SecureRandom random;
    private final LipSyncScorer scorer;
    private final List<SpokenCodeListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * @param time the orchestrator's time source (production: {@code TimeSource.system()}); audio and mouth samples
     *             are stamped with it
     */
    public AvLivenessEngine(LivenessEngine inner, AvConfig config, DigitRecognizer recognizer, Supplier<AudioSource> audio,
                            TimeSource time, SecureRandom random) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.config = Objects.requireNonNull(config, "config");
        this.recognizer = Objects.requireNonNull(recognizer, "recognizer");
        this.audio = Objects.requireNonNull(audio, "audio");
        this.time = Objects.requireNonNull(time, "time");
        this.random = Objects.requireNonNull(random, "random");
        this.scorer = new LipSyncScorer(config.sync());
    }

    public void addListener(SpokenCodeListener l) { listeners.add(Objects.requireNonNull(l)); }
    public void removeListener(SpokenCodeListener l) { listeners.remove(l); }
    public AvConfig config() { return config; }
    public LivenessEngine inner() { return inner; }

    @Override
    public EngineInfo info() {
        EngineInfo i = inner.info();
        Set<String> signals = new LinkedHashSet<>(i.signalIds());
        signals.add(AvSignals.AV_SYNC);
        return new EngineInfo(i.engineId() + "+av", i.engineVersion(), i.modelBundleId(), i.modelBundleVersion(),
                i.supportedChallenges(), signals, i.producesEmbeddings());
    }

    @Override
    public FrameAnalyzer newAnalyzer() {
        return new AvFrameAnalyzer(inner.newAnalyzer(), config, new SpokenCodeVerifier(recognizer, scorer), audio, time, random, listeners);
    }

    @Override
    public SelfTestResult selfTest() { return inner.selfTest(); }

    /** Closes the wrapped engine. The recogniser is owned by the caller. */
    @Override
    public void close() { inner.close(); }
}
