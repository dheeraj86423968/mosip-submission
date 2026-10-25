package io.mosip.vivid.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * The single, strictly configured {@link ObjectMapper} used by the policy module.
 * Unknown properties fail, enum values are case-sensitive, and output is deterministic (sorted keys).
 */
public final class PolicyJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
            .configure(DeserializationFeature.ACCEPT_FLOAT_AS_INT, false)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private PolicyJson() {}

    /** Shared mapper; callers must not reconfigure it. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Serialises a document to compact JSON with deterministic key order. */
    public static String write(PolicyDocument document) {
        try {
            return MAPPER.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new PolicyException("cannot serialise policy document", e);
        }
    }

    /** Serialises a document to indented JSON for humans. */
    public static String writePretty(PolicyDocument document) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new PolicyException("cannot serialise policy document", e);
        }
    }
}
