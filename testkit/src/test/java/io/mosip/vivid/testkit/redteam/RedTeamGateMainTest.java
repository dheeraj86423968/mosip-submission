package io.mosip.vivid.testkit.redteam;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The gate on hand-made score files: regression detection, fixtures, trend. No models needed. */
class RedTeamGateMainTest {
    final RedTeamVariant bf = RedTeamVariant.draw(AttackSpecies.BONA_FIDE, 1, 1, "g");
    final RedTeamVariant print = RedTeamVariant.draw(AttackSpecies.SIM_PRINT, 1, 1, "g");
    final RedTeamVariant screen = RedTeamVariant.draw(AttackSpecies.SIM_SCREEN_REPLAY, 1, 1, "g");
    final RedTeamVariant cutout = RedTeamVariant.draw(AttackSpecies.SIM_PRINT_CUTOUT, 1, 1, "g");

    Path variants(Path dir) throws Exception {
        List<String> l = new ArrayList<>();
        for (RedTeamVariant v : List.of(bf, print, screen, cutout)) l.add(RedTeamVariant.JSON.writeValueAsString(v.toJson().put("framesSha256", "abc")));
        return Files.write(dir.resolve("variants.jsonl"), l);
    }

    static String line(RedTeamVariant v, double score, String decision, String failure) {
        return String.format(java.util.Locale.ROOT, "{\"id\":\"%s\",\"label\":\"%s\",\"score\":%s,\"decision\":\"%s\",\"failure\":%s,\"signals\":{\"cnn_texture\":0.1}}",
                v.itemId(), v.species().label(), score, decision, failure == null ? "null" : "\"" + failure + "\"");
    }

    Path scores(Path dir, String name, String... lines) throws Exception {
        return Files.writeString(dir.resolve(name), "# test\n" + String.join("\n", lines) + "\n");
    }

    @Test
    void firstRunWritesBaselineThenARegressionFailsAndLeavesAFixture(@TempDir Path dir) throws Exception {
        Path v = variants(dir);
        Path s1 = scores(dir, "s1.jsonl",
                line(bf, 0.95, "LIVE", null),
                line(print, 0.30, "NOT_LIVE", "LIVENESS_BELOW_THRESHOLD"),
                line(screen, 0.92, "ATTACK", "PAD_DETECTED"),          // vetoed: rejected despite the high fused score
                line(cutout, 0.90, "LIVE", null));                      // already a bypass at baseline time
        Path baseline = dir.resolve("baseline.json"), trend = dir.resolve("trend.jsonl");
        int code = RedTeamGateMain.run(new String[] {"--variants", v.toString(), "--scores", "passive=" + s1, "--baseline", baseline.toString(),
                "--write-baseline", baseline.toString(), "--trend", trend.toString(), "--trend-md", dir.resolve("trend.md").toString(),
                "--commit", "aaa", "--date", "2026-10-24", "--export-accepted", dir.resolve("known").toString()});
        assertThat(code).isZero();
        JsonNode b = RedTeamVariant.JSON.readTree(baseline.toFile());
        assertThat(b.at("/modes/passive/" + screen.id() + "/outcome").asText()).isEqualTo("REJECTED");
        assertThat(b.at("/modes/passive/" + cutout.id() + "/outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(Files.list(dir.resolve("known")).map(p -> p.getFileName().toString()).toList()).containsExactly(cutout.id() + "-passive.json");
        JsonNode known = RedTeamVariant.JSON.readTree(dir.resolve("known").resolve(cutout.id() + "-passive.json").toFile());
        assertThat(known.get("knownBypass").asBoolean()).isTrue();
        assertThat(RedTeamVariant.fromJson(known).id()).isEqualTo(cutout.id());

        // second run: the vetoed screen replay is now accepted (regression); the print is still rejected; bona fide flips
        Path s2 = scores(dir, "s2.jsonl",
                line(bf, 0.50, "NOT_LIVE", "LIVENESS_BELOW_THRESHOLD"),
                line(print, 0.10, "NOT_LIVE", "LIVENESS_BELOW_THRESHOLD"),
                line(screen, 0.85, "LIVE", null),
                line(cutout, 0.20, "ATTACK", "PAD_DETECTED"));
        Path fixtures = dir.resolve("fixtures"), md = dir.resolve("summary.md");
        code = RedTeamGateMain.run(new String[] {"--variants", v.toString(), "--scores", "passive=" + s2, "--baseline", baseline.toString(),
                "--fixtures-out", fixtures.toString(), "--trend", trend.toString(), "--trend-md", dir.resolve("trend.md").toString(),
                "--summary-md", md.toString(), "--commit", "bbb", "--date", "2026-10-25"});
        assertThat(code).isEqualTo(1);
        Path fx = fixtures.resolve(screen.id() + "-passive.json");
        assertThat(Files.list(fixtures).toList()).containsExactly(fx);
        JsonNode f = RedTeamVariant.JSON.readTree(fx.toFile());
        assertThat(RedTeamVariant.fromJson(f).params()).isEqualTo(screen.params());     // enough to re-render it
        assertThat(f.get("knownBypass").asBoolean()).isFalse();
        assertThat(f.at("/observed/baselineOutcome").asText()).isEqualTo("REJECTED");
        assertThat(f.at("/observed/score").asDouble()).isEqualTo(0.85);
        assertThat(f.get("framesSha256").asText()).isEqualTo("abc");
        String summary = Files.readString(md);
        assertThat(summary).contains("1 regression(s)", "1 previously accepted attack(s) now rejected", "1 bona fide flip(s)", screen.id());

        List<String> rows = Files.readAllLines(trend);
        assertThat(rows).hasSize(2);
        JsonNode r2 = RedTeamVariant.JSON.readTree(rows.get(1));
        assertThat(r2.get("commit").asText()).isEqualTo("bbb");
        assertThat(r2.get("regressions").asInt()).isEqualTo(1);
        assertThat(r2.at("/passive/apcer/SIM_SCREEN_REPLAY/0").asInt()).isEqualTo(1);
        assertThat(r2.at("/passive/bpcer/0").asInt()).isEqualTo(1);
        assertThat(r2.at("/passive/acer").asDouble()).isEqualTo(1.0);            // (max APCER 1 + BPCER 1) / 2
        String table = Files.readString(dir.resolve("trend.md"));
        assertThat(table).contains("| 2026-10-24 | aaa |", "| 2026-10-25 | bbb |", "1/1 (100 %)");
    }

    @Test
    void nonResponsesAreExcludedAndUnknownVariantsAreNewNotRegressions(@TempDir Path dir) throws Exception {
        Path v = variants(dir);
        Path s = scores(dir, "s.jsonl",
                line(bf, 0.95, "LIVE", null),
                line(print, 0.95, "LIVE", null),
                line(screen, 0.0, "NOT_LIVE", "FACE_NOT_DETECTED"),
                line(cutout, 0.1, "NOT_LIVE", "LIVENESS_BELOW_THRESHOLD"));
        Path summary = dir.resolve("summary.json");
        assertThat(RedTeamGateMain.run(new String[] {"--variants", v.toString(), "--scores", "passive=" + s, "--baseline", dir.resolve("none.json").toString(),
                "--summary-json", summary.toString()})).isZero();
        JsonNode j = RedTeamVariant.JSON.readTree(summary.toFile());
        assertThat(j.get("newBypasses").asInt()).isEqualTo(1);
        assertThat(j.at("/passive/apnrr/0").asInt()).isEqualTo(1);
        assertThat(j.at("/passive/apcer/SIM_SCREEN_REPLAY/1").asInt()).isZero();
        assertThat(j.at("/passive/maxApcer").asDouble()).isEqualTo(1.0);
        assertThat(RedTeamGateMain.run(new String[] {"--variants", v.toString()})).isEqualTo(2);
    }
}
