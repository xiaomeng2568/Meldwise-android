# Sprint 1 security and recovery boundaries

## Trust and identity

Only pinned https://auth.openai.com discovery is used. Metadata issuer must match exactly; authorization/token/JWKS endpoints must be HTTPS on the pinned host with no user-info, query, fragment or alternate port. Redirects and authenticator retries are disabled. JWT-supplied jku/x5u cannot select a retrieval endpoint. RS256 only, RSA >=2048 bits, exactly one matching key, issuer, issued client audience, exp, iat, subject presence, authorization nonce, returning subject and multi-audience azp are checked. Clock tolerance: 5 seconds. JWKS cache: memory-only 10 minutes; one retrieval after an unfamiliar kid per validation attempt. Identity values never enter diagnostics.

Fresh state, nonce and PKCE verifier use 256 random bits. Loopback binds IPv4 127.0.0.1 on an ephemeral port; matching HTTP host/path/state and unique query parameters are required. Browser authorization has a five-minute deadline. Up to 32 actual connections are processed; invalid states do not consume authorization. Only one accepted code is exchanged. No id_token_hint is included in browser URLs. Pending OAuth secrets are memory-only, not saved into Android instance state.

## Credential transaction

One selected account and one application process are supported. Installation UUIDv4 lives in no-backup storage. Credentials/registration are one encrypted record under Keystore-generated AES-256-GCM with a fresh provider-generated nonce per write, versioned envelope and purpose-bound AAD. Chat uses a separate Keystore alias and AAD. No plaintext fallback on key failure. Backup and device-transfer exclusions are explicit; allowBackup is false.

| Crash/failure window | Local outcome | Provider claim |
| --- | --- | --- |
| Before refresh marker commits | No request starts; old complete record remains or storage blocks | No provider evidence |
| Marker committed, before/during POST | On restart quarantine and ReauthRequired; no old-token replay | Rotation unknown |
| Provider rotated, replacement not durably committed | Same quarantine behavior | Cannot recover remote outcome automatically |
| Replacement atomic write and readback complete | Publish generation+1 to callers | Response validated; no extra refresh |
| Explicit terminal invalid_grant family | Retain registration, clear usable credentials, ReauthRequired | Category only |
| Confirmed request not sent | Preserve old active credentials; waiting callers share bounded failure | No replay within that operation |
| Ambiguous network/5xx/protocol failure | Preserve encrypted record in RECOVERY_UNCERTAIN; reauthorize | No assumed revocation or rotation outcome |
| Secure storage corruption / key loss | StorageUnavailable; no plaintext recovery | No provider inference |

The marker is committed before a rotating refresh POST. Mutex ownership and generation tracking share the committed replacement with waiting callers. No refresh retry loop. A new explicit request may try again only when the prior attempt was proven not sent. Cancelled/ambiguous attempts require reauthorization. Missing refreshed ID token retains the previously validated token; returned ID tokens pass signature/issuer/audience/time/subject validation. Initial authorization nonce is not a fabricated refresh nonce.

Temporary failures do not imply deletion or server logout: ambiguous credentials remain encrypted but unusable. AtomicFile + fsync + whole-record readback are used; actual power-loss filesystem guarantees remain device-test/review work. Cross-process coordination is NOT TESTED and NOT implemented; no extra process, service or worker is declared. Hardware-backed Keystore is not claimed. JVM plaintext strings cannot be reliably zeroized.

## Transport, output and logging

One explicit Responses POST; connection retries, redirects, token-refresh replay after 401, model fallback and API-key fallback are disabled. Request bodies are one-shot; 503 Retry-After: 0 is not allowed to cause an automatic follow-up; a per-call network-exchange guard prevents hidden second sends. This is internal no-retry policy, not claimed provider metadata. Connect/write/read/call deadlines: 10/20/30/60 seconds; stream overall 120 seconds. Cancellation cancels OkHttp. Foreground exit cancels chat, not browser OAuth handoff. SSE line/frame/output sizes are bounded; assistant role/item mapping and completed status are required. Empty terminal output may be compatible when prior assistant output_text events yielded text. EOF without terminal completion is Incomplete.

Closed-schema diagnostics accept only operation/outcome enums and HTTP status; no arbitrary strings, exceptions, URLs, headers, identities, tokens or content. UI displays user/assistant chat text, never credentials or identity values. No logging interceptor, analytics or remote crash reporting. Test fixtures are labeled synthetic and are not provider content.

Chat Pending/Streaming becomes Incomplete on restart; no automatic resume or replay. Streaming writes target 200 ms plus finalization. The latest uncommitted fragment may be lost on abrupt death; no stronger guarantee is claimed. Messages use UUIDv7 and parentMessageId. Incomplete pairs are excluded from subsequent input, not silently treated as completed history.

## Official knowledge absorbed, not architecture copied

Sources checked 2026-10-02: [Open-source sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in), [Profiles and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions), [Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference), [Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations), [Errors and recovery](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery), [Token reference](https://developers.openai.com/siwc/token-sharing-open-source/token-reference), [Identity guidance](https://developers.openai.com/siwc/website).

Official model catalog uses models/visibility/display_name/slug; UI preserves order and requires explicit selection. No capabilities inferred from model names. Supported baseline is text/stream/store=false only; advanced domain capabilities are declared but not advertised. No unsupported max_output_tokens, temperature, top_p, previous_response_id or store=true is emitted. earliest_refresh_at parsing (epoch seconds or ISO-8601) is defensive and not new provider evidence. OpenAI Docs influenced protocol/trust choices; Phase 1 classes were not promoted into this project.

## SPRINT 1 FINAL ACCEPTANCE

### IMPLEMENTED

Bounded internal source/config review of integrated production code. Auth, encrypted storage and refresh semantics were unchanged by diagnostic integration. No Phase 1 architecture, trust-all TLS, plaintext fallback, response/credential/callback logging, fallback or streamed POST replay was introduced.

| Boundary | Internal source/config result | Evidence scope |
| --- | --- | --- |
| Credential encryption / Keystore / no plaintext fallback | PASS | AES-256-GCM, purpose AAD, separate aliases, fail closed |
| Atomic persistence / recovery | PASS | fsync/readback, refresh transaction marker, uncertain result quarantine |
| Backup exclusion | PASS | noBackupFilesDir, allowBackup=false, backup/transfer rules; not OEM transfer test |
| Local-first / analytics / remote crash reporting | PASS | encrypted local records, explicit transmissions, no telemetry integration |
| Authorization/token/raw callback/response diagnostic logging absent | PASS | closed enums/booleans/counts; no logging interceptor |
| No model or API-key fallback | PASS | explicit provider catalog selection; no alternative provider execution |
| No automatic streamed POST retry | PASS | retries/redirects/authenticator disabled, one-shot body, second-exchange guard |
| Buffered response bounds | PASS | successful catalog 2 MiB; other ordinary/error bodies 256 KiB; decompressed bound |
| SSE bounds / text validation | PASS | line 32 KiB, frame 256 KiB, max 4096 lines, text 4 MiB; strict association/consistency |
| Refresh single-flight / rotation uncertainty / ReauthRequired | PASS | one owner, waiters share settlement, quarantined ambiguous rotation, no retry loop |
| No secrets/machine-local tracked artifacts | PASS at source review | no APK/keystore/key/local.properties/absolute developer path |

PASS here describes reviewed controls, not external audit or proof of every provider/OEM behavior. No unresolved HIGH finding was identified within this bounded review; this is not a universal security assurance.

### LOCAL TESTED

104/104 Debug and Release tests PASS, including mock transport no-replay/cancellation, body/decompression bounds, parser conflicts, deterministic refresh owner/waiters, crypto/corruption/recovery and static boundaries. Final Android tests compiled; physical Keystore execution pending. Lock files unchanged. No test transmitted real provider credentials or messages.

### REAL DEVICE TESTED

Previous production catalog/inference accepted in observed scope. Four real-device storage tests PASS using isolated synthetic records and actual Keystore/AtomicFile: authenticated record roundtrip, corruption fail closed, no plaintext on key failure and encrypted journal state restoration. This does not establish actual account record restoration or real crash/rotation behavior.

Cold Activity instrumentation did not complete and is a merge blocker. Its synchronization was made bounded using real lifecycle/first-draw/local-restore signals and async main checkpoints; assertions were not removed, and no production auth/storage/UI logic changed. No provider test has been initiated. Normal cold launch returning ok alone is not zero-network/restored-credential evidence. Process/force-stop recovery and truthful cancellation/actual journal acceptance remain pending. Synthetic journal crash-window assertions must not be relabeled observed provider behavior.

### GITHUB CI VERIFIED

Historical push/PR checks passed; new final HEAD checks pending. Remote JVM CI cannot certify Android Keystore or hardware backing.

### KNOWN LIMITATIONS

Single-process refresh coordinator, one selected account, JVM strings not reliably zeroizable, latest uncommitted stream fragment can be lost. AtomicFile/readback is not a demonstrated real power-loss proof. SIWC preview remains CONDITIONAL; no production-readiness or independent audit claim.

### NOT TESTED

Two physical devices, long-term natural expiry, real network interruption during provider rotation, cross-process coordination, independent external security/compliance audit, OEM backup/transfer behavior and real power-loss recovery.

### DEFERRED TO FUTURE

Any untested capability requires separately scoped verification. No extra provider, fallback, release, feature sprint or automatic retry is authorized by this acceptance report.

## LATEST CHECKPOINT — 2026-10-02 19:28 Asia/Shanghai

The preceding no-provider/manual-pending statements describe the automated/pre-manual-test checkpoint. The developer later supplied real normal-chat and cancellation evidence. No automatic provider operations were initiated. Mid-stream cancellation now shows one network exchange, text produced, no completed/validated terminal success, CANCELLED and protocol NONE; UI marks partial output Cancelled. Earlier record retention/no auto continuation was developer-confirmed, but persistence/reopen of the newer partial output remains NOT TESTED. No server-side compute-stop or extended no-replay guarantee is inferred.

Four isolated real-device Keystore/storage tests already passed; full cold-start instrumentation remains NOT COMPLETED, not 5/5 PASS. Genuine process-change/zero-startup-request assertions remain INCONCLUSIVE. This documentation update performs no code/security-boundary changes or new requests. SIWC and final Sprint decision remain CONDITIONAL; GitHub final-HEAD CI/promotion/merge/cleanup are incomplete. See [current report](phase-2-sprint-1-current-status-2026-10-02.md).

## FINAL DEVELOPER ACCEPTANCE — Sprint 1 closure

Developer-observed cold restart/reopen, offline behavior and encrypted/local persistence/chat restoration are explicitly accepted PASS within tested scope. Manual behavioral acceptance supersedes prior pending rows; it does not fabricate instrumented process/counter traces or complete the unresolved cold Activity test. No extra provider operation, crypto/storage change, fallback, retry or security relaxation is introduced. Long-term rotation/network/cross-process/physical-device/audit limitations remain NOT TESTED. Four real isolated Android Keystore tests and full local security regressions remain the measured technical evidence. Final CI/merge status appears in [final acceptance](phase-2-sprint-1-final-acceptance.md); SIWC remains CONDITIONAL regardless of Sprint acceptance.
