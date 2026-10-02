# Sprint 1 modification ledger

All changes are confined to the new P2-Production-Foundation checkout. Original Phase 1 files and frozen specification were not edited.

## Files/groups

- README.md: replace historical gate-stage README with current foundation scope, privacy, build instructions and release limitations.
- .gitattributes, settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, wrapper scripts/JAR/properties, settings-gradle.lockfile, app/gradle.lockfile: line-ending policy, fresh build setup, explicit versions, dependency locks, official wrapper checksum. local.properties is machine-local and ignored.
- app/build.gradle.kts: single app module, compile/target35, min26, Compose, test dependencies, Debug/Release verification.
- app/src/main/AndroidManifest.xml, res/xml/*, res/drawable/ic_launcher.xml: minimal launcher, network/backup/transfer boundaries, correct Meldwise icon/name. No worker/service/extra process.
- auth/AuthModels.kt, IdentityValidator.kt, TokenEndpoint.kt, TokenManager.kt, LoopbackReceiver.kt, OAuthCoordinator.kt: protected records, RS256 trust, code exchange, scope handling, one-process refresh state machine, browser callback lifecycle.
- security/CredentialStore.kt, AndroidStorage.kt: authenticated encryption, Android Keystore, AtomicFile, installation identity, fail-closed storage.
- network/SafeDiagnostics.kt, NetworkClient.kt, SseParser.kt: closed-schema diagnostics, bounded/cancellable transport and framing.
- provider/LlmDomain.kt, ResponsesReader.kt, ChatGptProvider.kt: provider-independent domain, verified assistant stream extraction, explicit ChatGPT Plan adapter.
- data/ChatRepository.kt: encrypted single-conversation journal, UUIDv7/parent linkage, incomplete restart recovery.
- MeldwiseApplication.kt, MainActivity.kt, ui/MainViewModel.kt: manual wiring and smallest production foundation UI with explicit requests/cancellation.
- app/src/test/*: 44 JVM tests; app/src/androidTest/*: local-only device Keystore test source, compiled but not run.
- .github/workflows/android.yml: CI definition only, not executed remotely.
- docs/phase-1-feasibility-decision.md, production-foundation-sprint-1-report.md, security-and-recovery.md, production-device-validation.md, change-log.md: reviewed decision, actual evidence, security/crash-window boundaries and pending acceptance.
- artifacts/*: copied generated APKs with P2 names; ignored by Git, no secrets.

No SDK installation, provider/account mutation, automatic model request, push or release was performed. The production branch records this work locally; original dirty Phase 1 checkout remains untouched.

## SPRINT 1 FINAL ACCEPTANCE

The sections above are initial-delivery history, not the current integration status.

### IMPLEMENTED

- 9b9f1d0: save accepted diagnostic-branch inference compatibility and sanitized observed-device reports.
- d657974389c8f1ca31be11c3938f00e519deae51: non-destructive merge of diagnostic work into production/foundation-sprint-1. Preserve existing auth/storage/refresh waiter fix, deterministic synchronization, workflow maintenance and locks. No conflict replacement or history rewrite.
- 2befd45329673f17f696ebb8e3a53257334e5da1: four isolated Android storage tests, cold restoration test, restoration completion signal, closed network/exchange counters, stronger per-attempt transport assertions, generated-artifact exclusion and removal of a historical absolute path from documentation.
- Final reports distinguish historical real-device evidence from final-APK acceptance; new phase-2-sprint-1-final-acceptance.md records the merge gate rather than declaring unearned acceptance.

### LOCAL TESTED

Full Debug 104/104 and Release 104/104 PASS; builds PASS; lint each 0 errors / 1 existing warning; five instrumentation methods compiled. Ten active dependency graphs resolved using unchanged lock files. No runtime dependencies or SDK additions in this final campaign.

Only generated build output/cache placement was changed locally to overcome Windows output ACL mismatch; the local init script is ignored and not product code. Existing generated output retained recoverably, not deleted. Final APK kept outside Git, signature matches prior installed validation app.

### REAL DEVICE TESTED

Accepted earlier catalog 5/5 and inference 62-event/completion/text/UI evidence preserved. Final production replacement install succeeded, no uninstall/data clear. Four isolated device storage tests PASS; cold Activity instrumentation did not complete and remains BLOCKED. Test-only package revisions add lifecycle/first-draw/completion barriers, async main checkpoints and deadlines without weakening assertions or changing product APK. Explicit runner stops explain subsequent Process crashed output; it is not a spontaneous-app-crash claim. Ordinary COLD launch returned ok, but restoration/zero-request acceptance remains unverified. Full 5/5 instrumentation is not claimed.

Current test APK SHA-256 30988C10810C12F2E5DCAC9E18717BD3F28FAE92C9D2077991D0BA1F4F46085E; initial four-test storage run used 0B3561777D0FCE18BFD9ABEBC33D98CE6F7D0E50398FAC6E672AD855CB8CC232. Formal app SHA-256 unchanged. Stop installing further revisions and stop before provider operations; final chat/cancel approval received, calls not performed. Keep Draft and local reports, no final acceptance push or merge while blocked.

### GITHUB CI VERIFIED

Previously published b8dc1af push/PR checks PASS; final HEAD checks pending. PR #1 remains Draft, main unchanged, no branch cleanup yet.

### KNOWN LIMITATIONS

SIWC CONDITIONAL. Initial 44-test/lint/APK rows remain historical; latest full results and final APK identity are in the acceptance report. No public release or external audit claim.

### NOT TESTED

Two physical devices, natural long-term expiry, real provider rotation interruption, cross-process refresh, independent audit, physical power-loss and OEM transfer scenarios.

### DEFERRED TO FUTURE

Promotion, squash merge and remote branch cleanup are conditional on actual final acceptance and new-HEAD CI success. Sprint 2/features/providers/release/tag remain out of scope.

## 2026-10-02 19:28 — Documentation-only evidence checkpoint

Added the Chinese current-status report and updated the final acceptance matrix using developer-supplied auth/reopen, normal-chat, record-retention and cancellation evidence. Mid-stream cancellation now observed: HTTP 200, 88 events, text produced, completion false, one exchange, CANCELLED. This supersedes the earlier pre-manual-test pending rows; cold instrumentation and zero-startup-call/process-change evidence remain unverified. No product/test code change, rebuild, installation, provider request, commit, push or merge in this update. Read-only GitHub check: PR #1 open/Draft/unmerged, remote head b8dc1af, main dccaab3; both historical CI triggers succeeded, new local HEAD CI still pending. See [current report](phase-2-sprint-1-current-status-2026-10-02.md).

## 2026-10-02 — Final developer acceptance and closure preparation

Recorded explicit developer PASS within tested scope for cold restart/reopen, offline behavior and encrypted/local persistence/chat restoration. Preserved the unresolved cold Activity automation limitation and all long-term NOT TESTED items. Committed the previously compiled bounded AndroidTest lifecycle synchronization and remaining integration/report changes; no product architecture/security behavior changes. Local full verification and new-HEAD CI are required for actual closure; remote promotion/merge/results are recorded in the final acceptance report after execution. No provider/model/inference request is repeated, no APK installed, no Sprint 2 or public release.
