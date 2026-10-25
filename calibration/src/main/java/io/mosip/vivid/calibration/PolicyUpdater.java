package io.mosip.vivid.calibration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.policy.PolicyDocument;
import io.mosip.vivid.policy.PolicyException;
import io.mosip.vivid.policy.PolicyJson;
import io.mosip.vivid.policy.PolicyLoader;
import io.mosip.vivid.policy.PolicyValidator;
import io.mosip.vivid.policy.signing.PolicySigner;
import io.mosip.vivid.policy.signing.PolicyVerifier;
import io.mosip.vivid.policy.signing.SignedPolicy;

import java.math.BigDecimal;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.List;
import java.util.Map;

/**
 * Produces the signed policy update: the policy in force with only {@code workflows.<WF>.passiveThreshold} changed and a
 * new version, validated with the same {@link PolicyValidator} the clients run and signed with the same
 * {@link PolicySigner} as every other policy, so clients verify it with the unchanged {@link PolicyLoader#loadVerified}.
 */
public final class PolicyUpdater {

    private PolicyUpdater() {}

    /**
     * @param current     the signed policy currently in force for the group
     * @param verifyKey   key the current policy must verify with (refuses to build on an unverified base)
     * @param thresholds  new passive thresholds; workflows not listed keep theirs
     * @param version     version string of the update
     */
    public static SignedPolicy update(SignedPolicy current, PublicKey verifyKey, Map<Workflow, Double> thresholds, String version,
                                      PrivateKey signingKey, String keyId) {
        if (!PolicyVerifier.verify(current, verifyKey)) {
            throw new PolicyException("the policy in force does not verify; refusing to derive an update from it");
        }
        return sign(apply(current.policyJson(), thresholds, version), signingKey, keyId);
    }

    /** Applies thresholds and version to a policy document text and validates the result. */
    public static String apply(String policyJson, Map<Workflow, Double> thresholds, String version) {
        try {
            ObjectNode root = (ObjectNode) PolicyJson.mapper().readTree(policyJson);
            root.put("version", version);
            ObjectNode workflows = root.has("workflows") ? (ObjectNode) root.get("workflows") : root.putObject("workflows");
            for (Map.Entry<Workflow, Double> e : thresholds.entrySet()) {
                JsonNode wf = workflows.get(e.getKey().name());
                ObjectNode w = wf instanceof ObjectNode ? (ObjectNode) wf : workflows.putObject(e.getKey().name());
                w.put("passiveThreshold", new BigDecimal(String.valueOf(e.getValue())));
            }
            String json = PolicyJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(root);
            PolicyDocument doc = new PolicyLoader().parse(json);
            List<String> violations = new PolicyValidator().validate(doc);
            if (!violations.isEmpty()) {
                throw new PolicyException("calibrated policy is invalid: " + violations);
            }
            return json;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new PolicyException("cannot read policy in force: " + e.getOriginalMessage(), e);
        }
    }

    public static SignedPolicy sign(String policyJson, PrivateKey key, String keyId) {
        return PolicySigner.signToEnvelope(policyJson, key, keyId);
    }
}
