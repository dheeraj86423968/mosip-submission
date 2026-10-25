package io.mosip.vivid.av.asr;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DigitLexiconTest {
    @Test
    void everyLanguageCoversAllTenDigits() {
        for (String lang : List.of("en", "hi", "te", "gu")) {
            assertThat(DigitLexicon.words(lang).values()).as(lang).containsAll(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9));
            for (int d = 0; d < 10; d++) assertThat(DigitLexicon.digit(lang, DigitLexicon.spoken(lang, d))).isEqualTo(d);
        }
    }

    @Test
    void regionTagsMapToTheBaseLanguage() {
        assertThat(DigitLexicon.supports("en-IN")).isTrue();
        assertThat(DigitLexicon.digit("en-IN", "Seven")).isEqualTo(7);
        assertThat(DigitLexicon.digit("hi", "छः")).isEqualTo(6);
        assertThat(DigitLexicon.digit("hi", "[unk]")).isEqualTo(-1);
        assertThat(DigitLexicon.supports("ta")).isFalse();
    }

    @Test
    void grammarIsAJsonArrayWithUnknownToken() {
        String g = DigitLexicon.grammarJson("te");
        assertThat(g).startsWith("[\"సున్నా\"").endsWith("\"[unk]\"]").contains("\"తొమ్మిది\"");
    }

    @Test
    void parsesVoskResultsAcrossSegments() {
        String a = "{\"result\":[{\"conf\":1.0,\"end\":0.5,\"start\":0.1,\"word\":\"four\"},{\"conf\":0.9,\"end\":1.0,\"start\":0.6,\"word\":\"eight\"}],\"text\":\"four eight\"}";
        String b = "{\"result\":[{\"conf\":1.0,\"end\":1.5,\"start\":1.1,\"word\":\"[unk]\"},{\"conf\":0.9,\"end\":2.0,\"start\":1.6,\"word\":\"one\"}],\"text\":\"[unk] one\"}";
        String c = "{\"text\":\"seven\"}";
        Recognition r = VoskDigitRecognizer.merge("en", List.of(a, b, c));
        assertThat(r.digits()).isEqualTo("4817");
        assertThat(r.unknownTokens()).isEqualTo(1);
        assertThat(r.tokens()).hasSize(5);
        assertThat(VoskDigitRecognizer.parse("en", "{\"text\":\"\"}").digits()).isEmpty();
    }
}
