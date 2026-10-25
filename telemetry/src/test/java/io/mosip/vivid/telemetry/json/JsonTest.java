package io.mosip.vivid.telemetry.json;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTest {

    @Test
    void writesAllSupportedTypes() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "a\"b\\c\n\t\u0001");
        m.put("i", 42L);
        m.put("d", 0.5);
        m.put("nan", Double.NaN);
        m.put("b", true);
        m.put("n", null);
        m.put("e", Thread.State.NEW);
        m.put("l", List.of(1, "x"));
        m.put("o", Map.of("k", "v"));
        m.put("other", new StringBuilder("sb"));
        assertThat(Json.write(m)).isEqualTo(
                "{\"s\":\"a\\\"b\\\\c\\n\\t\\u0001\",\"i\":42,\"d\":0.5,\"nan\":null,\"b\":true,\"n\":null,\"e\":\"NEW\",\"l\":[1,\"x\"],\"o\":{\"k\":\"v\"},\"other\":\"sb\"}");
        assertThat(Json.write(null)).isEqualTo("null");
        assertThat(Json.write(1.5f)).isEqualTo("1.5");
    }

    @Test
    void parsesRoundTrip() {
        String text = "{\"s\":\"a\\\"b\\\\c\\n\\t\\u0041\\/\\b\\f\\r\",\"i\":-42,\"d\":2.5e1,\"b\":false,\"n\":null,\"l\":[1,[2],{}],\"o\":{\"k\":\"v\"}, \"empty\" : [ ] }";
        Map<String, Object> m = Json.parseObject(text);
        assertThat(m.get("s")).isEqualTo("a\"b\\c\n\tA/\b\f\r");
        assertThat(m.get("i")).isEqualTo(-42L);
        assertThat(m.get("d")).isEqualTo(25.0);
        assertThat(m.get("b")).isEqualTo(false);
        assertThat(m).containsKey("n");
        assertThat(m.get("n")).isNull();
        assertThat(m.get("l")).isEqualTo(List.of(1L, List.of(2L), Map.of()));
        assertThat(m.get("o")).isEqualTo(Map.of("k", "v"));
        assertThat(m.get("empty")).isEqualTo(List.of());
        assertThat(Json.parse(" true ")).isEqualTo(true);
        assertThat(Json.parse("\"x\"")).isEqualTo("x");
    }

    @Test
    void rejectsMalformedInput() {
        for (String bad : new String[] {"", "{", "{\"a\":}", "{\"a\" 1}", "{\"a\":1,}", "[1,", "[1 2]", "tru", "\"abc", "\"\\q\"", "\"\\u12\"", "\"\\u12zz\"", "{\"a\":1}x", "-", "\"a\nb\"", "nul", "{1:2}", "@"}) {
            assertThatThrownBy(() -> Json.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Json.parseObject("[]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
