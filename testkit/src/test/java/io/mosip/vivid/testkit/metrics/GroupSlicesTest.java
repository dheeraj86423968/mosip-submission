package io.mosip.vivid.testkit.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Hand-computed fixture, threshold 0.5, ten participants P01..P10, one bona fide each:
 * <pre>
 *   subject  bf score  ageBand  device  eyewear
 *   P01      0.9       18-29    D1      none     (+ one bona fide non-response, + PRINT 0.1)
 *   P02      0.9       18-29    D1      none     (+ PRINT 0.1)
 *   P03      0.3 rej   18-29    D2      none     (+ PRINT 0.6 accepted)
 *   P04      0.9       18-29    D1      none     (+ PRINT 0.1)
 *   P05      0.9       18-29    D1      none     (+ PRINT 0.1)
 *   P06      0.2 rej   30-49    D2      none
 *   P07      0.3 rej   30-49    D2      none
 *   P08      0.9       30-49    D1      none
 *   P09      0.9       30-49    D2      none
 *   P10      0.9       30-49    D2      glasses
 * </pre>
 * ageBand 18-29: BPCER 1/5, BPNRR 1/6, PRINT APCER 1/5. ageBand 30-49: BPCER 2/5 (ratio 2.0, not more than twice).
 * device D1: subjects P01,P02,P04,P05,P08 BPCER 0/5; D2: P03,P06,P07,P09,P10 BPCER 3/5 (ratio unbounded, flagged).
 * eyewear none: 9 subjects, BPCER 3/9; glasses: 1 subject, suppressed.
 */
class GroupSlicesTest {
    static List<PadSample> fixture() {
        double[] bf = {0.9, 0.9, 0.3, 0.9, 0.9, 0.2, 0.3, 0.9, 0.9, 0.9};
        String[] device = {"D1", "D1", "D2", "D1", "D1", "D2", "D2", "D1", "D2", "D2"};
        List<PadSample> out = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String p = String.format("P%02d", i + 1);
            Map<String, String> g = Map.of("ageBand", i < 5 ? "18-29" : "30-49", "device", device[i], "eyewear", i == 9 ? "glasses" : "none");
            out.add(PadSample.bonaFide(bf[i], p + "-BONA_FIDE-L1-" + device[i] + "-T1").withGroups(g));
            if (i < 5) out.add(PadSample.attack("PRINT_A4_MATTE", i == 2 ? 0.6 : 0.1, p + "-PRINT_A4_MATTE-L1-" + device[i] + "-T1").withGroups(g));
            if (i == 0) out.add(PadSample.nonResponse(null, "x").withSubject(p).withGroups(g));
        }
        return out;
    }

    static GroupSlices.Slice slice(GroupSlices g, String key, String value) {
        return g.byKey().get(key).stream().filter(s -> s.value().equals(value)).findFirst().orElseThrow();
    }

    @Test
    void perGroupRatesAndIntervalsMatchHandComputation() {
        GroupSlices g = GroupSlices.compute(fixture(), 0.5, 5);
        assertThat(g.byKey().keySet()).containsExactly("ageBand", "device", "eyewear");

        GroupSlices.Slice young = slice(g, "ageBand", "18-29");
        assertThat(young.suppressed()).isFalse();
        assertThat(young.subjects()).isEqualTo(5);
        assertThat(young.bpcer()).isEqualTo(new Rate(1, 5));
        assertThat(young.bpcer().wilson95().lower()).isCloseTo(0.0362241086, within(1e-9));
        assertThat(young.bpcer().wilson95().upper()).isCloseTo(0.6244653702, within(1e-9));
        assertThat(young.bpnrr()).isEqualTo(new Rate(1, 6));
        assertThat(young.bpnrr().wilson95().upper()).isCloseTo(0.5635028222, within(1e-9));
        assertThat(young.apcerBySpecies()).containsExactly(Map.entry("PRINT_A4_MATTE", new Rate(1, 5)));
        assertThat(young.maxApcer()).isCloseTo(0.2, within(1e-12));
        assertThat(young.bpcerRatioToLowest()).isEqualTo(1.0);

        GroupSlices.Slice older = slice(g, "ageBand", "30-49");
        assertThat(older.bpcer()).isEqualTo(new Rate(2, 5));
        assertThat(older.bpcer().wilson95().lower()).isCloseTo(0.1176207742, within(1e-9));
        assertThat(older.bpcerRatioToLowest()).isCloseTo(2.0, within(1e-12));
        assertThat(older.moreThanTwiceLowest()).isFalse();      // exactly twice is not "more than twice"
        assertThat(older.apcerBySpecies()).isEmpty();
        assertThat(older.maxApcer()).isNaN();

        GroupSlices.Slice d1 = slice(g, "device", "D1"), d2 = slice(g, "device", "D2");
        assertThat(d1.bpcer()).isEqualTo(new Rate(0, 5));
        assertThat(d1.bpcer().wilson95().upper()).isCloseTo(0.4344824648, within(1e-9));
        assertThat(d2.bpcer()).isEqualTo(new Rate(3, 5));
        assertThat(d2.bpcer().wilson95().lower()).isCloseTo(0.2307242813, within(1e-9));
        assertThat(d1.bpcerRatioToLowest()).isEqualTo(1.0);
        assertThat(d2.bpcerRatioToLowest()).isNull();            // lowest is 0: ratio unbounded
        assertThat(d2.moreThanTwiceLowest()).isTrue();

        assertThat(slice(g, "eyewear", "none").bpcer()).isEqualTo(new Rate(3, 9));
        assertThat(slice(g, "eyewear", "none").subjects()).isEqualTo(9);
    }

    @Test
    void groupsWithFewerThanFiveSubjectsAreSuppressedWithoutCounts() {
        GroupSlices g = GroupSlices.compute(fixture(), 0.5, 5);
        GroupSlices.Slice glasses = slice(g, "eyewear", "glasses");
        assertThat(glasses.suppressed()).isTrue();
        assertThat(glasses.bpcer()).isNull();
        assertThat(glasses.subjects()).isZero();
        // a higher minimum suppresses the five-subject slices too; lowering below the ethics floor is refused
        assertThat(GroupSlices.compute(fixture(), 0.5, 6).byKey().get("ageBand")).allMatch(GroupSlices.Slice::suppressed);
        assertThatThrownBy(() -> GroupSlices.compute(fixture(), 0.5, 4)).hasMessageContaining("at least 5");
        // samples with no subject (no id, no explicit subject) never count towards the minimum
        List<PadSample> anon = new ArrayList<>();
        for (int i = 0; i < 20; i++) anon.add(PadSample.bonaFide(0.9).withGroups(Map.of("ageBand", "50+")));
        assertThat(GroupSlices.compute(anon, 0.5, 5).byKey().get("ageBand").get(0).suppressed()).isTrue();
    }

    @Test
    void reportExposesSlicesAndHidesSuppressedCounts() {
        List<PadSample> s = fixture();
        Iso30107Metrics m = Iso30107Metrics.compute(s, 0.5);
        Iso30107Report r = new Iso30107Report(m, GroupSlices.compute(s, 0.5, 5), "slices", Map.of());
        JsonNode j = r.toJsonNode();
        assertThat(j.at("/slices/minSubjects").asInt()).isEqualTo(5);
        JsonNode eyewear = j.at("/slices/groups/eyewear");
        JsonNode glasses = eyewear.get(0);
        assertThat(glasses.get("value").asText()).isEqualTo("glasses");
        assertThat(glasses.get("suppressed").asBoolean()).isTrue();
        assertThat(glasses.size()).isEqualTo(2);                 // only value + suppressed
        JsonNode young = j.at("/slices/groups/ageBand/0");
        assertThat(young.at("/bpcer/count").asInt()).isEqualTo(1);
        assertThat(young.at("/bpcer/of").asInt()).isEqualTo(5);
        assertThat(young.at("/bpcer/ci95/0").asDouble()).isCloseTo(0.0362241086, within(1e-9));
        assertThat(young.at("/apcerBySpecies/PRINT_A4_MATTE/rate").asDouble()).isCloseTo(0.2, within(1e-12));
        assertThat(j.at("/slices/groups/device/1/bpcerRatioToLowest").isNull()).isTrue();
        String html = r.toHtml();
        assertThat(html).contains("Per-group slices").contains("&lt; 5 participants, suppressed").contains("⚑");
    }
}
