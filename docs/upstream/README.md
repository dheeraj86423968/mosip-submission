# Upstream submissions (drafts)

PLAN.md §17.11 sets the goal of merging this work into MOSIP rather than keeping a fork. These are ready-to-file
drafts. None has been opened: filing them publishes the team's work under the team's GitHub accounts, so the team
files them.

| Target | What | Draft | Evidence it is ready |
|---|---|---|---|
| SBI specification (`mosip/documentation`, SBI 1.x) | Optional signed preview-stream extension | [sbi-signed-preview-change-request.md](sbi-signed-preview-change-request.md) | Two independent implementations interoperate over HTTP (`device-mock`, `device-sbi-stream`); attacks rejected in `SignedPreviewTest` |
| `mosip/registration-client` (`release-1.2.0.x`) | Face liveness and PAD for resident capture, operator and supervisor authentication | [registration-client-pr.md](registration-client-pr.md) | 7 patches apply and compile against `c1cc4e7` ([report](../testing/desktop-mosip-compile.md)) |
| `mosip/android-registration-client` (`release-1.2.x`) | The same on Android, plus operator face matching | [android-registration-client-pr.md](android-registration-client-pr.md) | 8 patches apply to `240c50d` and build a debug APK ([report](../testing/android-arc-integration.md)) |
| `mosip/mosip-mock-services` | Face scenarios, fault injection and signed preview for MockMDS | [mock-services-issue.md](mock-services-issue.md) | Interop findings against MockMDS 1.2.0.2 ([report](../testing/interop-mockmds.md)) |

Suggested order: open the MockMDS issue and the SBI change request first, because they are small and other
vendors benefit. Then open the two client PRs as drafts, linking this repository for the modules they depend on.
