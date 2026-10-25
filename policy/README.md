# policy

The liveness policy document for MOSIP Vivid. This module holds the JSON model, the loader that merges a document
over compiled-in defaults, a semantic validator, a properties adapter for MOSIP global-param sync, and
detached-signature verification. It depends only on `liveness-api` and `jackson-databind`.

## Document

The document follows PLAN.md §5.1. The JSON Schema (draft-07, for documentation) is
[`src/main/resources/policy-schema.json`](src/main/resources/policy-schema.json). The compiled-in default is
[`src/main/resources/policy-default.json`](src/main/resources/policy-default.json).

```json
{ "schemaVersion": "1.0", "version": "centre-42",
  "workflows": { "RESIDENT": { "passiveThreshold": 0.85, "active": { "pool": ["BLINK","TURN_LEFT","NOD"] } } } }
```

Every field except `schemaVersion` is optional. Absent fields keep the value from `LivenessPolicy.defaults(workflow)`,
so a two-line document is valid. Unknown properties are rejected, so a misspelled key fails loudly instead of being
silently ignored.

## Usage

```java
PolicyLoader loader = new PolicyLoader(new PolicyValidator(engine.info()));
LoadedPolicy loaded = loader.loadVerified(json, signatureBase64, trustedKey, "global-params");
audit(POLICY_LOADED, loaded.report());               // version, signatureStatus, violations, usedFallback
LivenessPolicy resident = loaded.effective(Workflow.RESIDENT);
```

`loadVerified` never throws on bad input. If the signature is wrong, the document is unsigned while a key is
configured, the JSON is malformed or any validation rule fails, it returns the built-in defaults and a
`PolicyLoadReport` with `usedFallback = true`. Tampering with the local configuration therefore cannot disable
liveness silently (PLAN.md §5.2).

| Class | Role |
|-------|------|
| `PolicyDocument`, `WorkflowPolicy`, `ActivePolicy`, `SignalPolicy`, `IdentityPolicy`, `DevicePolicy`, `DiagnosticsPolicy` | Immutable Jackson records for the document |
| `PolicyLoader` | Parses a String, InputStream or Path; `toLivenessPolicy(doc, workflow)`; `loadVerified(...)` |
| `PolicyValidator` | Returns human-readable violations: ranges, min <= max, non-empty pool, positive timeouts, latency window, depth challenge present, and engine support when an `EngineInfo` is given |
| `PropertiesPolicyAdapter` | Flattens to and from `mosip.registration.liveness.*`. `mosip.registration.liveness.policy.json` (plus `.policy.signature`) holds the full document and takes precedence |
| `signing.CanonicalJson` | Sorted-key, whitespace-free canonical form; this is what gets signed |
| `signing.PolicySigner`, `signing.PolicyVerifier`, `signing.SignedPolicy` | SHA256withECDSA (P-256) or SHA256withRSA via `java.security` only |
| `signing.PolicyKeys`, `signing.PolicyKeyTool` | Key generation and encoding, plus an ops CLI |

## Property keys

```
mosip.registration.liveness.<workflow>.enabled
mosip.registration.liveness.<workflow>.passive.threshold | pad.hard.threshold | passive.window.ms | passive.min.frames
mosip.registration.liveness.<workflow>.active.mode | active.min | active.max | active.pool | active.accessibility.pool
mosip.registration.liveness.<workflow>.active.require.depth | active.timeout.ms | active.latency.min.ms | active.latency.max.ms
mosip.registration.liveness.<workflow>.retries.max | retries.action | session.timeout.ms | quality.min
mosip.registration.liveness.<workflow>.identity.required | identity.drift.threshold
mosip.registration.liveness.<workflow>.signals.<signalId>.enabled | .weight
mosip.registration.liveness.device.min.fps | device.reconnect.attempts | device.stall.timeout.ms
mosip.registration.liveness.diagnostics.mode | schema.version | version
```

## Signing a policy (ops)

```sh
CP=policy/target/classes:$(ls ~/.m2/repository/io/mosip/vivid/liveness-api/0.1.0-SNAPSHOT/*.jar):...jackson jars...
java -cp $CP io.mosip.vivid.policy.signing.PolicyKeyTool gen ec policy.key policy.pub
java -cp $CP io.mosip.vivid.policy.signing.PolicyKeyTool sign policy.key policy.json policy.sig
java -cp $CP io.mosip.vivid.policy.signing.PolicyKeyTool verify policy.pub policy.json policy.sig
```

The signature covers the canonical JSON, so reformatting the file does not invalidate it, but changing any value
does. In production the verification key should come from the MOSIP key manager.

## Build

`mvn -q -f policy/pom.xml install`
