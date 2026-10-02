# Production foundation device acceptance — pending

No Phase 2 authorization, refresh or model request is executed automatically by this delivery. Spike results are historical evidence, not production-code acceptance. Use the fresh production package, not Host A/B. Obtain explicit approval before any real-provider campaign.

## Local checks first

1. Install the Sprint 1 debug APK (package io.github.xiaomeng2568.meldwise, label Meldwise); verify cold launch emits zero provider requests.
2. Execute included Android instrumentation Keystore/AtomicFile roundtrip tests (no provider calls). Record booleans/build SHA-256, never raw identity/token values.
3. Confirm backup/transfer exclusions on target OS/OEM. Do not claim hardware backing without device evidence.
4. Local chat interruption should retain partial text as Incomplete without resuming HTTP. Do not clear genuine data or provider connections just for testing.

## Separately approved provider flow

1. Tap Continue with ChatGPT once and authorize using external browser. Record only exchange status and identity-validation categories. No callback screenshots/URLs.
2. Tap Load models once; select a listed model explicitly. No automatic choice or fallback.
3. Send one bounded non-sensitive test message; record completion/extraction booleans, not text. Confirm no sensitive logging.
4. Restart / force-stop and reopen. Encrypted connection state should restore locally without network; an explicitly approved later call can test restored access.
5. Separately approve expiry/refresh testing: one owner, rotation presence/change booleans and committed generation. Do not repeatedly force rotation or replay quarantined tokens.

## Outstanding evidence

- Phase 2 real authorization -> persistence -> restored invocation: NOT TESTED.
- Included Android Keystore/AtomicFile instrumentation execution: pending until actually run.
- Actual process death during refresh/commit and OEM backup/key lifecycle: NOT TESTED.
- Two physical devices, long-term natural expiry, actual provider network interruption during rotation and cross-process refresh coordination: NOT TESTED.
- CI execution and independent security/compliance review: pending.

Never collect tokens, authorization code, callback URL, raw host/client/subject IDs, account identity or response bodies in evidence. Stop after an approved batch for review; no automatic release or next sprint.
