package io.mosip.vivid.calibration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Client release -> audit sync -> central job -> signed policy -> client verification and application. */
class EndToEndCalibrationLoopTest {

    @Test
    void theLoopClosesAndOnlySignedUpdatesApply(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        CalibrationLoopDemo.Outcome o = CalibrationLoopDemo.run(dir, new PrintStream(log, true, StandardCharsets.UTF_8));
        String text = log.toString(StandardCharsets.UTF_8);
        assertThat(o.clients()).isEqualTo(60);
        assertThat(o.releaseRecords()).isEqualTo(180);
        assertThat(o.duplicatesDropped()).isEqualTo(3);
        assertThat(o.thresholdBefore()).isEqualTo(0.80);
        assertThat(o.thresholdTighteningOnly()).as("tightening-only rules leave the dim centre alone").isEqualTo(0.80);
        assertThat(o.signatureOk()).isTrue();
        assertThat(o.thresholdApplied()).as("one bounded loosening step").isEqualTo(0.75);
        assertThat(o.appliedVersion()).isEqualTo("builtin-1.0+cal.2026Q4.10011-dim");
        assertThat(o.tamperedFellBack()).isTrue();
        // a tampered policy falls back to the compiled defaults
        assertThat(o.tamperedThreshold()).isEqualTo(io.mosip.vivid.api.LivenessPolicy.defaults(io.mosip.vivid.api.Workflow.RESIDENT).passiveThreshold());
        assertThat(o.releasesBeforeExhaustion()).isEqualTo(2);
        assertThat(o.epsilonSpentPerClient()).isEqualTo(6.0);
        assertThat(text).contains("10012-reference").contains("LOOSENING_NOT_AUTHORISED");
    }
}
