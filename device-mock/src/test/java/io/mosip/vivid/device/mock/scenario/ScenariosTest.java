package io.mosip.vivid.device.mock.scenario;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenariosTest {
    static List<String> bundled() { return Scenarios.bundledNames(); }

    @ParameterizedTest @MethodSource("bundled")
    void everyBundledScenarioLoadsAndRenders(String name) {
        Scenario s = Scenarios.bundled(name);
        assertThat(s.name()).isEqualTo(name);
        assertThat(s.segments()).isNotEmpty();
        ScenarioTimeline tl = new ScenarioTimeline(s);
        var pos = tl.positionAt(0).orElseThrow();
        var img = tl.render(pos);
        assertThat(img.getWidth()).isEqualTo(s.width());
        assertThat(img.getHeight()).isEqualTo(s.height());
    }

    @Test
    void parsesTimelineAndFaults() {
        Scenario s = Scenarios.fromJson("""
            {"name":"t","segments":[{"durationMs":2000,"fps":10,"label":"print",
              "timeline":[{"action":"BLINK","atMs":500},{"action":"YAW","fromMs":800,"toMs":1200,"degrees":-20}],
              "faults":[{"atMs":1000,"kind":"STALL","params":{"durationMs":300}},{"atMs":100,"kind":"DISCONNECT"}]}]}
            """);
        Segment seg = s.segments().get(0);
        assertThat(seg.label()).isEqualTo(PresentationLabel.PRINT);
        assertThat(seg.faults()).extracting(Fault::kind).containsExactly(FaultKind.DISCONNECT, FaultKind.STALL);
        assertThat(seg.faults().get(1).longParam("durationMs", 0)).isEqualTo(300);
        assertThat(seg.timeline().stateAt(500).eyeOpen()).isLessThan(0.05);
        assertThat(seg.timeline().stateAt(1000).yawDeg()).isEqualTo(-20.0);
    }

    @Test
    void rejectsMissingFields() {
        assertThatThrownBy(() -> Scenarios.fromJson("{\"segments\":[]}")).hasMessageContaining("name");
        assertThatThrownBy(() -> Scenarios.fromJson("{\"name\":\"x\",\"segments\":[{\"fps\":5}]}")).hasMessageContaining("durationMs");
        assertThatThrownBy(() -> Scenarios.fromJson("{\"name\":\"x\",\"segments\":[{\"durationMs\":5,\"label\":\"bogus\"}]}")).hasMessageContaining("bogus");
    }

    @Test
    void timelineComputesFramesAndFaultsAcrossSegmentsAndLoops() {
        Scenario s = Scenario.builder("two").loop(true)
                .segment(Segment.builder().durationMs(1000).fps(10).fault(new Fault(500, FaultKind.STALL, null)).build())
                .segment(Segment.builder().source(Segment.SOURCE_BLANK).durationMs(500).fps(5).build()).build();
        ScenarioTimeline tl = new ScenarioTimeline(s);
        assertThat(tl.passDurationMs()).isEqualTo(1500);
        assertThat(tl.nextFrameTimeAtOrAfter(0)).isEqualTo(0);
        assertThat(tl.nextFrameTimeAtOrAfter(1)).isEqualTo(100);
        assertThat(tl.nextFrameTimeAtOrAfter(950)).isEqualTo(1000);   // first frame of segment 2
        assertThat(tl.nextFrameTimeAtOrAfter(1001)).isEqualTo(1200);  // 5 fps
        assertThat(tl.positionAt(1700).orElseThrow().segmentIndex()).isEqualTo(0); // looped
        assertThat(tl.positionAt(1700).orElseThrow().loop()).isEqualTo(1);
        assertThat(tl.faultsBetween(-1, 3000)).extracting(ScenarioTimeline.FaultOccurrence::atScenarioMs).containsExactly(500L, 2000L);
        Scenario once = Scenario.builder("once").segment(Segment.builder().durationMs(300).fps(10).build()).build();
        ScenarioTimeline tl2 = new ScenarioTimeline(once);
        assertThat(tl2.nextFrameTimeAtOrAfter(201)).isEqualTo(-1);
        assertThat(tl2.isEnded(300)).isTrue();
    }
}
