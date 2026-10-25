package io.mosip.vivid.testkit.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class Iso30107MetricsTest {
    // hand-computed fixture: 4 bona fide, 2 PRINT, 3 SCREEN_REPLAY, threshold 0.5 (accept if score >= 0.5)
    static final List<PadSample> SAMPLES = List.of(
            PadSample.bonaFide(0.90), PadSample.bonaFide(0.80), PadSample.bonaFide(0.30), PadSample.bonaFide(0.95),
            PadSample.attack("PRINT", 0.10), PadSample.attack("PRINT", 0.60),
            PadSample.attack("SCREEN_REPLAY", 0.20), PadSample.attack("SCREEN_REPLAY", 0.70), PadSample.attack("SCREEN_REPLAY", 0.85));

    @Test
    void apcerPerSpeciesBpcerAndAcerMatchHandComputation() {
        Iso30107Metrics m = Iso30107Metrics.compute(SAMPLES, 0.5);
        assertThat(m.bonaFideCount()).isEqualTo(4);
        assertThat(m.attackCount()).isEqualTo(5);
        assertThat(m.attackCountBySpecies()).containsExactlyInAnyOrderEntriesOf(Map.of("PRINT", 2, "SCREEN_REPLAY", 3));
        assertThat(m.apcerBySpecies().get("PRINT")).isCloseTo(0.5, within(1e-12));          // 0.60 accepted, 0.10 rejected
        assertThat(m.apcerBySpecies().get("SCREEN_REPLAY")).isCloseTo(2 / 3.0, within(1e-12)); // 0.70, 0.85 accepted
        assertThat(m.maxApcer()).isCloseTo(2 / 3.0, within(1e-12));
        assertThat(m.worstSpecies()).isEqualTo("SCREEN_REPLAY");
        assertThat(m.bpcer()).isCloseTo(0.25, within(1e-12));                               // 0.30 rejected
        assertThat(m.acer()).isCloseTo((2 / 3.0 + 0.25) / 2, within(1e-12));
    }

    @Test
    void bpcerAtFixedApcerFromThresholdSweep() {
        Iso30107Metrics m = Iso30107Metrics.compute(SAMPLES, 0.5);
        // APCER <= 10% requires rejecting every attack: threshold > 0.85 -> bona fide 0.30 and 0.80 rejected -> BPCER 50%
        assertThat(m.bpcerAtApcer10()).isCloseTo(0.5, within(1e-12));
        assertThat(m.bpcerAtApcer5()).isCloseTo(0.5, within(1e-12));
        // every sweep point is well formed and the curve spans accept-all to reject-all
        assertThat(m.detCurve()).isNotEmpty();
        assertThat(m.detCurve().get(0).apcer()).isEqualTo(1.0);
        assertThat(m.detCurve().get(0).bpcer()).isEqualTo(0.0);
        DetPoint last = m.detCurve().get(m.detCurve().size() - 1);
        assertThat(last.apcer()).isEqualTo(0.0);
        assertThat(last.bpcer()).isEqualTo(1.0);
        for (int i = 1; i < m.detCurve().size(); i++) {
            assertThat(m.detCurve().get(i).threshold()).isGreaterThan(m.detCurve().get(i - 1).threshold());
            assertThat(m.detCurve().get(i).apcer()).isLessThanOrEqualTo(m.detCurve().get(i - 1).apcer());
            assertThat(m.detCurve().get(i).bpcer()).isGreaterThanOrEqualTo(m.detCurve().get(i - 1).bpcer());
        }
        assertThat(m.eer()).isBetween(0.0, 1.0);
    }

    @Test
    void perfectSeparationGivesZeroErrorsAndUnattainableIsNaN() {
        List<PadSample> perfect = List.of(PadSample.bonaFide(0.9), PadSample.bonaFide(0.8), PadSample.attack("MASK", 0.1), PadSample.attack("MASK", 0.2));
        Iso30107Metrics m = Iso30107Metrics.compute(perfect, 0.5);
        assertThat(m.maxApcer()).isZero(); assertThat(m.bpcer()).isZero(); assertThat(m.acer()).isZero();
        assertThat(m.bpcerAtApcer5()).isZero(); assertThat(m.eer()).isZero();
        // decisions only: everything accepted
        List<PadSample> allAccepted = List.of(PadSample.bonaFide(PadSample.accepted(true)), PadSample.attack("PRINT", PadSample.accepted(true)));
        Iso30107Metrics d = Iso30107Metrics.compute(allAccepted, 0.5);
        assertThat(d.maxApcer()).isEqualTo(1.0);
        assertThat(d.bpcer()).isZero();
        assertThat(d.bpcerAtApcer5()).isEqualTo(1.0);   // only rejecting everything reaches APCER 0
        assertThatThrownBy(() -> Iso30107Metrics.compute(List.of(PadSample.bonaFide(1)), 0.5)).hasMessageContaining("attack");
        assertThatThrownBy(() -> Iso30107Metrics.compute(List.of(PadSample.attack("PRINT", 1)), 0.5)).hasMessageContaining("bona fide");
    }

    @Test
    void reportRendersJsonAndSelfContainedHtml() throws Exception {
        Iso30107Metrics m = Iso30107Metrics.compute(SAMPLES, 0.5);
        Iso30107Report r = new Iso30107Report(m, "Unit fixture", Map.of("engine", "fake", "corpus", "hand<made>"));
        String json = r.toJson();
        assertThat(json).contains("\"maxApcer\" : 0.6666666666666666").contains("\"bpcer\" : 0.25").contains("\"PRINT\"").contains("detCurve");
        String html = r.toHtml();
        assertThat(html).startsWith("<!doctype html>").contains("66.67%").contains("25.00%").contains("<svg").contains("hand&lt;made&gt;");
        assertThat(html).doesNotContain("<script").doesNotContain("http://").doesNotContain("https://");
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("iso30107");
        r.write(dir, "report");
        assertThat(dir.resolve("report.html")).exists();
        assertThat(dir.resolve("report.json")).exists();
    }

    @Test
    void wilsonIntervalsPerSpeciesAndForBpcer() {
        Iso30107Metrics m = Iso30107Metrics.compute(SAMPLES, 0.5);
        Rate print = m.apcerRateBySpecies().get("PRINT");
        assertThat(print.k()).isEqualTo(1); assertThat(print.n()).isEqualTo(2);
        assertThat(print.wilson95().lower()).isCloseTo(0.0945312057, within(1e-9));
        assertThat(print.wilson95().upper()).isCloseTo(0.9054687943, within(1e-9));
        Rate screen = m.apcerRateBySpecies().get("SCREEN_REPLAY");
        assertThat(screen.k()).isEqualTo(2); assertThat(screen.n()).isEqualTo(3);
        assertThat(m.maxApcerCi95().lower()).isCloseTo(0.2076596008, within(1e-9));   // worst species = SCREEN_REPLAY, 2/3
        assertThat(m.maxApcerCi95().upper()).isCloseTo(0.9385080553, within(1e-9));
        assertThat(m.bpcerRate().k()).isEqualTo(1); assertThat(m.bpcerRate().n()).isEqualTo(4);
        assertThat(m.bpcerRate().wilson95().lower()).isCloseTo(0.0455872608, within(1e-9));
        assertThat(m.bpcerRate().wilson95().upper()).isCloseTo(0.6993581574, within(1e-9));
        // no non-responses in this fixture
        assertThat(m.apnrr().k()).isZero(); assertThat(m.apnrr().n()).isEqualTo(5); assertThat(m.bpnrr().value()).isZero();
    }

    @Test
    void bpcerAtApcerReportsTheThresholdAndDistinguishesFiveAndTenPercent() {
        // fixture: threshold 0.90 rejects every attack (max 0.85) and bona fide 0.30, 0.80 -> BPCER 50 %
        assertThat(Iso30107Metrics.compute(SAMPLES, 0.5).bpcerAtApcer10Threshold()).isEqualTo(0.90);
        // 20 attacks of one species: 18 at 0.10, one at 0.60, one at 0.80; bona fide 0.50, 0.65, 0.90, 0.95
        List<PadSample> s = new java.util.ArrayList<>();
        for (int i = 0; i < 18; i++) s.add(PadSample.attack("X", 0.10));
        s.add(PadSample.attack("X", 0.60)); s.add(PadSample.attack("X", 0.80));
        s.add(PadSample.bonaFide(0.50)); s.add(PadSample.bonaFide(0.65)); s.add(PadSample.bonaFide(0.90)); s.add(PadSample.bonaFide(0.95));
        Iso30107Metrics m = Iso30107Metrics.compute(s, 0.5);
        // APCER <= 10 % (<= 2/20 accepted): t = 0.50 accepts 0.60 and 0.80 only, rejects no bona fide -> BPCER 0
        assertThat(m.bpcerAtApcer10()).isZero();
        assertThat(m.bpcerAtApcer10Threshold()).isEqualTo(0.50);
        // APCER <= 5 % (<= 1/20): t = 0.65 accepts only 0.80, rejects bona fide 0.50 -> BPCER 1/4
        assertThat(m.bpcerAtApcer5()).isCloseTo(0.25, within(1e-12));
        assertThat(m.bpcerAtApcer5Threshold()).isEqualTo(0.65);
    }

    @Test
    void nonResponsesAreCountedSeparatelyAndDoNotMoveErrorRates() {
        List<PadSample> s = new java.util.ArrayList<>(SAMPLES);
        s.add(PadSample.nonResponse("PRINT", "a")); s.add(PadSample.nonResponse("PRINT", "b"));
        s.add(PadSample.nonResponse("MASK", "c"));    // species that never produced a decision
        s.add(PadSample.nonResponse(null, "d"));      // bona fide non-response
        Iso30107Metrics m = Iso30107Metrics.compute(s, 0.5);
        Iso30107Metrics base = Iso30107Metrics.compute(SAMPLES, 0.5);
        assertThat(m.apcerBySpecies()).isEqualTo(base.apcerBySpecies());   // MASK has no APCER: nothing was decided
        assertThat(m.bpcer()).isEqualTo(base.bpcer());
        assertThat(m.detCurve()).isEqualTo(base.detCurve());
        assertThat(m.attackCount()).isEqualTo(5); assertThat(m.bonaFideCount()).isEqualTo(4);
        // APNRR = 3 non-responses / 8 attack presentations; per species PRINT 2/4, SCREEN_REPLAY 0/3, MASK 1/1
        assertThat(m.apnrr()).isEqualTo(new Rate(3, 8));
        assertThat(m.apnrr().wilson95().lower()).isCloseTo(0.1368442858, within(1e-9));
        assertThat(m.apnrr().wilson95().upper()).isCloseTo(0.6942576054, within(1e-9));
        assertThat(m.apnrrBySpecies()).containsExactly(Map.entry("MASK", new Rate(1, 1)), Map.entry("PRINT", new Rate(2, 4)),
                Map.entry("SCREEN_REPLAY", new Rate(0, 3)));
        assertThat(m.apnrrBySpecies().get("PRINT").wilson95().lower()).isCloseTo(0.1500389892, within(1e-9));
        // BPNRR = 1 / 5 bona fide presentations
        assertThat(m.bpnrr()).isEqualTo(new Rate(1, 5));
        assertThat(m.bpnrr().wilson95().upper()).isCloseTo(0.6244653702, within(1e-9));
        // only non-responses on one side -> no metric can be computed
        assertThatThrownBy(() -> Iso30107Metrics.compute(List.of(PadSample.nonResponse(null, "x"), PadSample.attack("PRINT", 0.1)), 0.5))
                .hasMessageContaining("bona fide");
    }

    @Test
    void reportJsonCarriesIntervalsNonResponseAndThresholds() throws Exception {
        List<PadSample> s = new java.util.ArrayList<>(SAMPLES);
        s.add(PadSample.nonResponse("PRINT", "a"));
        Iso30107Metrics m = Iso30107Metrics.compute(s, 0.5);
        com.fasterxml.jackson.databind.JsonNode j = new Iso30107Report(m, "x", Map.of()).toJsonNode();
        assertThat(j.at("/apcerBySpecies/PRINT/apcer").asDouble()).isEqualTo(0.5);     // backward-compatible fields
        assertThat(j.at("/apcerBySpecies/PRINT/count").asInt()).isEqualTo(2);
        assertThat(j.at("/apcerBySpecies/PRINT/errors").asInt()).isEqualTo(1);
        assertThat(j.at("/apcerBySpecies/PRINT/ci95/0").asDouble()).isCloseTo(0.0945312057, within(1e-9));
        assertThat(j.at("/bpcerCi95/1").asDouble()).isCloseTo(0.6993581574, within(1e-9));
        assertThat(j.at("/bpcerAtApcer10Threshold").asDouble()).isEqualTo(0.90);
        assertThat(j.at("/apnrr").asDouble()).isCloseTo(1 / 6.0, within(1e-12));
        assertThat(j.at("/nonResponse/bySpecies/PRINT/count").asInt()).isEqualTo(1);
        assertThat(j.at("/nonResponse/bySpecies/PRINT/of").asInt()).isEqualTo(3);
        assertThat(j.at("/bpnrr").asDouble()).isZero();
        assertThat(j.has("slices")).isFalse();
        String html = new Iso30107Report(m, "x", Map.of()).toHtml();
        assertThat(html).contains("APNRR").contains("BPNRR").contains("95% CI").contains("[9.45%, 90.55%]").contains("at threshold 0.900");
    }
}
