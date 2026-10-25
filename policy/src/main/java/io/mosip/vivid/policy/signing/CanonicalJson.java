package io.mosip.vivid.policy.signing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosip.vivid.policy.PolicyException;
import io.mosip.vivid.policy.PolicyJson;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Produces the canonical form of a JSON document that signatures are computed over: object keys sorted
 * lexicographically at every level, no insignificant whitespace, UTF-8. Reformatting or reordering a signed
 * document therefore does not break its signature, while changing any value does.
 */
public final class CanonicalJson {

    private CanonicalJson() {}

    /** Canonical compact text of the given JSON. */
    public static String canonicalize(String json) {
        try {
            JsonNode tree = PolicyJson.mapper().readTree(json);
            if (tree == null || tree.isMissingNode()) {
                throw new PolicyException("empty JSON cannot be canonicalised");
            }
            return PolicyJson.mapper().writeValueAsString(sort(tree));
        } catch (JsonProcessingException e) {
            throw new PolicyException("cannot canonicalise JSON: " + e.getOriginalMessage(), e);
        }
    }

    /** Canonical UTF-8 bytes, the exact input to signing and verification. */
    public static byte[] canonicalBytes(String json) {
        return canonicalize(json).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                sorted.put(e.getKey(), sort(e.getValue()));
            }
            ObjectNode out = PolicyJson.mapper().createObjectNode();
            sorted.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = PolicyJson.mapper().createArrayNode();
            for (JsonNode child : node) {
                out.add(sort(child));
            }
            return out;
        }
        return node;
    }
}
