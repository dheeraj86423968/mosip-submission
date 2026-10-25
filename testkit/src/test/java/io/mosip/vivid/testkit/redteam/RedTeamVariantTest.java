package io.mosip.vivid.testkit.redteam;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Variant ids, JSON round trip and deterministic rendering. No models needed. */
class RedTeamVariantTest {

    /** The same variant with a 400 ms session (6 frames) to keep the test fast. */
    static RedTeamVariant shortened(RedTeamVariant v) {
        Map<String, Object> p = new HashMap<>(v.params());
        p.put("s.duration_ms", 400L);
        return new RedTeamVariant(v.species(), v.subject(), v.take(), p);
    }

    @Test
    void idsAreStableAndDependOnEveryInput() {
        RedTeamVariant a = RedTeamVariant.draw(AttackSpecies.SIM_PRINT, 3, 1, "s1");
        assertThat(RedTeamVariant.draw(AttackSpecies.SIM_PRINT, 3, 1, "s1").id()).isEqualTo(a.id());
        assertThat(a.id()).matches("SIM_PRINT-[0-9a-f]{10}");
        assertThat(RedTeamVariant.draw(AttackSpecies.SIM_PRINT, 3, 1, "s2").id()).isNotEqualTo(a.id());
        assertThat(RedTeamVariant.draw(AttackSpecies.SIM_PRINT, 4, 1, "s1").id()).isNotEqualTo(a.id());
        assertThat(shortened(a).id()).isNotEqualTo(a.id());
        assertThat(a.itemId()).isEqualTo("P03-SIM_PRINT-L0-D0-T1");
        // attack species of a subject share the live session parameters
        RedTeamVariant screen = RedTeamVariant.draw(AttackSpecies.SIM_SCREEN_REPLAY, 3, 1, "s1");
        assertThat(screen.d("s.face_scale")).isEqualTo(a.d("s.face_scale"));
        assertThat(screen.l("s.blink1_ms")).isEqualTo(a.l("s.blink1_ms"));
    }

    @Test
    void jsonRoundTripKeepsTheIdAndRejectsTampering() throws Exception {
        for (AttackSpecies sp : AttackSpecies.values()) {
            RedTeamVariant v = RedTeamVariant.draw(sp, 7, 2, "rt");
            String json = RedTeamVariant.JSON.writeValueAsString(v.toJson());
            RedTeamVariant back = RedTeamVariant.fromJson(RedTeamVariant.JSON.readTree(json));
            assertThat(back.id()).isEqualTo(v.id());
            assertThat(back.params()).isEqualTo(v.params());
            assertThat(back.toJson().get("paiClass").asText()).isEqualTo(sp.paiClass());
        }
        String json = RedTeamVariant.JSON.writeValueAsString(RedTeamVariant.draw(AttackSpecies.SIM_PRINT, 1, 1, "rt").toJson());
        assertThatThrownBy(() -> RedTeamVariant.fromJson(RedTeamVariant.JSON.readTree(json.replace("\"s.face_scale\":", "\"s.face_scale\":0.5,\"x\":"))))
                .hasMessageContaining("id mismatch");
        assertThatThrownBy(() -> RedTeamVariant.fromJson(RedTeamVariant.JSON.readTree(json.replace(RedTeamVariant.GENERATOR, "redteam-gen-0"))))
                .hasMessageContaining("redteam-gen-0");
    }

    @Test
    void everySpeciesRendersDeterministicCameraFrames() throws Exception {
        for (AttackSpecies sp : AttackSpecies.values()) {
            RedTeamVariant v = shortened(RedTeamVariant.draw(sp, 2, 1, "det"));
            RedTeamRenderer.Frames a = RedTeamRenderer.render(v), b = RedTeamRenderer.render(v);
            assertThat(a.sha256()).as(sp.name()).isEqualTo(b.sha256());
            assertThat(a.jpegs()).as(sp.name()).hasSizeBetween(4, 6);
            assertThat(a.timestampsMillis()[0]).isZero();
            for (int i = 1; i < a.timestampsMillis().length; i++) assertThat(a.timestampsMillis()[i]).isGreaterThan(a.timestampsMillis()[i - 1]);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(a.jpegs().get(0)));
            assertThat(img.getWidth()).isEqualTo(RedTeamRenderer.W);
            assertThat(img.getHeight()).isEqualTo(RedTeamRenderer.H);
        }
    }

    @Test
    void planHasBonaFideTakesAndOneVariantPerAttackSpecies() {
        List<RedTeamVariant> plan = RedTeamCorpusMain.plan(3, 2, "p", EnumSet.allOf(AttackSpecies.class));
        assertThat(plan).hasSize(3 * (2 + AttackSpecies.values().length - 1));
        assertThat(plan.stream().map(RedTeamVariant::itemId).distinct().count()).isEqualTo(plan.size());
        assertThat(plan.stream().map(RedTeamVariant::id).distinct().count()).isEqualTo(plan.size());
        assertThat(plan.get(0).itemId()).isEqualTo("P01-BONA_FIDE-L0-D0-T1");
        assertThat(plan.get(1).itemId()).isEqualTo("P01-BONA_FIDE-L0-D0-T2");
    }
}
