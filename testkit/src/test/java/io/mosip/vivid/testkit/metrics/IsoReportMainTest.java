package io.mosip.vivid.testkit.metrics;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class IsoReportMainTest {
    @Test
    void writesReportsAndGatesOnAcer() throws Exception {
        Path dir = Files.createTempDirectory("isocli");
        Path scores = dir.resolve("s.jsonl");
        Files.writeString(scores, """
            # fixture
            {"label":"BONA_FIDE","score":0.9}
            {"label":"LIVE","score":0.3,"id":"x"}
            {"label":"print","score":0.6}

            {"bonaFide":false,"pais":"SCREEN_REPLAY","score":0.1}
            """);
        assertThat(IsoReportMain.readSamples(scores)).hasSize(4).extracting(PadSample::species)
                .containsExactly("BONA_FIDE", "BONA_FIDE", "PRINT", "SCREEN_REPLAY");
        Path out = dir.resolve("out");
        assertThat(IsoReportMain.run(new String[] {"--scores", scores.toString(), "--out", out.toString(), "--context", "engine=fake"})).isZero();
        assertThat(out.resolve("iso30107.html")).exists();
        assertThat(Files.readString(out.resolve("iso30107.json"))).contains("\"engine\" : \"fake\"");
        // ACER = (max(1,0) + 0.5)/2 = 0.75
        assertThat(IsoReportMain.run(new String[] {"--scores", scores.toString(), "--out", out.toString(), "--max-acer", "0.5"})).isEqualTo(1);
        assertThat(IsoReportMain.run(new String[] {"--scores", scores.toString()})).isEqualTo(2);
        Files.writeString(scores, "{\"label\":\"PRINT\"}\n");
        assertThat(IsoReportMain.run(new String[] {"--scores", scores.toString(), "--out", out.toString()})).isEqualTo(2);
    }

    /** Regression: a hard-vetoed attack with a high fused score was counted as accepted (APCER overstated). */
    @Test
    void vetoedOrChallengeFailedResponsesAreRejectedAtEveryThreshold() throws Exception {
        Path dir = Files.createTempDirectory("isocli3");
        Path scores = dir.resolve("s.jsonl");
        Files.writeString(scores, String.join("\n",
                "{\"id\":\"P01-SIM_SCREEN_REPLAY-L0-D0-T1\",\"label\":\"SIM_SCREEN_REPLAY\",\"score\":0.88,\"decision\":\"ATTACK\",\"failure\":\"PAD_DETECTED\",\"vetoSignal\":\"cnn_texture\"}",
                "{\"id\":\"P02-SIM_SCREEN_REPLAY-L0-D0-T1\",\"label\":\"SIM_SCREEN_REPLAY\",\"score\":0.95,\"decision\":\"NOT_LIVE\",\"failure\":\"ACTIVE_CHALLENGE_FAILED\"}",
                "{\"id\":\"P03-SIM_SCREEN_REPLAY-L0-D0-T1\",\"label\":\"SIM_SCREEN_REPLAY\",\"score\":0.70,\"decision\":\"NOT_LIVE\",\"failure\":\"LIVENESS_BELOW_THRESHOLD\"}",
                "{\"id\":\"P04-SIM_SCREEN_REPLAY-L0-D0-T1\",\"label\":\"SIM_SCREEN_REPLAY\",\"score\":0.90,\"decision\":\"LIVE\",\"failure\":null}",
                "{\"id\":\"P01-BONA_FIDE-L0-D0-T1\",\"label\":\"BONA_FIDE\",\"score\":0.97,\"decision\":\"ATTACK\",\"failure\":\"PAD_DETECTED\"}",
                "{\"id\":\"P02-BONA_FIDE-L0-D0-T1\",\"label\":\"BONA_FIDE\",\"score\":0.93}") + "\n");
        var samples = IsoReportMain.readSamples(scores);
        assertThat(samples).extracting(PadSample::score).containsExactly(0.0, 0.0, 0.70, 0.90, 0.0, 0.93);
        Path out = dir.resolve("out");
        assertThat(IsoReportMain.run(new String[] {"--scores", scores.toString(), "--out", out.toString(), "--threshold", "0.8"})).isZero();
        JsonNode j = new ObjectMapper().readTree(out.resolve("iso30107.json").toFile());
        assertThat(j.at("/apcerBySpecies/SIM_SCREEN_REPLAY/count").asInt()).isEqualTo(4);
        assertThat(j.at("/apcerBySpecies/SIM_SCREEN_REPLAY/errors").asInt()).isEqualTo(1);  // only the LIVE one
        assertThat(j.at("/bpcer").asDouble()).isCloseTo(0.5, within(1e-12));               // the vetoed bona fide
    }

    @Test
    void readsDecisionsNonResponsesExclusionsSubjectsAndGroups() throws Exception {
        Path dir = Files.createTempDirectory("isocli2");
        Path scores = dir.resolve("s.jsonl");
        StringBuilder sb = new StringBuilder();
        // five participants with bona fide + PRINT; one extra bona fide non-response (explicit), one derived, one cancelled
        for (int i = 1; i <= 5; i++) {
            String p = "P0" + i;
            sb.append("{\"id\":\"").append(p).append("-BONA_FIDE-L1-D1-T1\",\"label\":\"BONA_FIDE\",\"score\":").append(i == 1 ? 0.2 : 0.9)
              .append(",\"decision\":\"").append(i == 1 ? "NOT_LIVE" : "LIVE").append("\",\"response\":true,\"groups\":{\"ageBand\":\"18-29\"},\"eyewear\":\"none\"}\n");
            sb.append("{\"id\":\"").append(p).append("-PRINT_A4_MATTE-L1-D1-T1\",\"label\":\"PRINT_A4_MATTE\",\"score\":0.1,\"decision\":\"ATTACK\",\"failure\":\"PAD_DETECTED\",\"groups\":{\"ageBand\":\"18-29\"}}\n");
        }
        sb.append("{\"id\":\"P01-BONA_FIDE-L3-D1-T1\",\"label\":\"BONA_FIDE\",\"decision\":\"NOT_LIVE\",\"failure\":\"FACE_NOT_DETECTED\",\"response\":false}\n");
        sb.append("{\"id\":\"P02-PRINT_A4_MATTE-L3-D1-T1\",\"label\":\"PRINT_A4_MATTE\",\"score\":0.0,\"decision\":\"NOT_LIVE\",\"failure\":\"SESSION_TIMEOUT\"}\n");
        sb.append("{\"id\":\"P03-PRINT_A4_MATTE-L1-D1-T2\",\"label\":\"PRINT_A4_MATTE\",\"score\":0.9,\"decision\":\"CANCELLED\",\"failure\":\"CANCELLED\"}\n");
        sb.append("{\"subject\":\"P09\",\"label\":\"BONA_FIDE\",\"score\":0.9,\"skinToneBand\":\"8-10\"}\n");
        Files.writeString(scores, sb.toString());

        IsoReportMain.ScoreFile f = IsoReportMain.readScores(scores);
        assertThat(f.excluded()).isEqualTo(1);
        assertThat(f.samples()).hasSize(13);
        assertThat(f.samples()).filteredOn(s -> !s.responded()).hasSize(2);
        PadSample first = f.samples().get(0);
        assertThat(first.subject()).isEqualTo("P01");
        assertThat(first.groups()).containsExactly(Map.entry("ageBand", "18-29"), Map.entry("eyewear", "none"));
        assertThat(f.samples().get(12).subject()).isEqualTo("P09");
        assertThat(f.samples().get(12).groups()).containsExactly(Map.entry("skinToneBand", "8-10"));

        Path out = dir.resolve("out");
        assertThat(IsoReportMain.run(new String[] {"--scores", scores.toString(), "--out", out.toString()})).isZero();
        JsonNode j = new ObjectMapper().readTree(out.resolve("iso30107.json").toFile());
        assertThat(j.at("/bpcer").asDouble()).isCloseTo(1 / 6.0, within(1e-12));          // P01 0.2 rejected of 6 responded
        assertThat(j.at("/bpnrr").asDouble()).isCloseTo(1 / 7.0, within(1e-12));
        assertThat(j.at("/apnrr").asDouble()).isCloseTo(1 / 6.0, within(1e-12));          // P02 timeout of 6 attacks
        assertThat(j.at("/apcerBySpecies/PRINT_A4_MATTE/count").asInt()).isEqualTo(5);
        assertThat(j.at("/context/excludedCancelled").asText()).isEqualTo("1");
        assertThat(j.at("/slices/groups/ageBand/0/subjects").asInt()).isEqualTo(5);
        assertThat(j.at("/slices/groups/ageBand/0/bpcer/count").asInt()).isEqualTo(1);
        assertThat(j.at("/slices/groups/ageBand/0/bpcer/of").asInt()).isEqualTo(5);
        assertThat(j.at("/slices/groups/skinToneBand/0/suppressed").asBoolean()).isTrue();
        assertThat(Files.readString(out.resolve("iso30107.html"))).contains("APNRR").contains("ageBand");
        // --no-slices drops the section, --min-subjects below 5 is a usage error
        assertThat(IsoReportMain.run(new String[] {"--scores", scores.toString(), "--out", out.toString(), "--no-slices"})).isZero();
        assertThat(new ObjectMapper().readTree(out.resolve("iso30107.json").toFile()).has("slices")).isFalse();
        assertThat(IsoReportMain.run(new String[] {"--scores", scores.toString(), "--out", out.toString(), "--min-subjects", "3"})).isEqualTo(2);
    }
}
