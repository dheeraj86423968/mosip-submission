package io.mosip.vivid.av.testing;

import io.mosip.vivid.av.asr.DigitRecognizer;
import io.mosip.vivid.av.asr.Recognition;
import io.mosip.vivid.av.audio.AudioClip;

import java.util.List;
import java.util.function.Function;

/** Recogniser stand-in: answers with whatever the test decides (for example "the code that was actually spoken"). */
public final class FakeRecognizer implements DigitRecognizer {
    private final Function<AudioClip, String> answer;
    public int calls;
    public FakeRecognizer(Function<AudioClip, String> answer) { this.answer = answer; }
    @Override public String language() { return "en"; }
    @Override public Recognition recognize(AudioClip clip) { calls++; return new Recognition(answer.apply(clip), List.of(), 0); }
}
