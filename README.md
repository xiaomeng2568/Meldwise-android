# Meldwise — Production Foundation Sprint 1

Phase 2 starts on production/foundation-sprint-1 in a separate checkout. v0.3 remains frozen. This is a foundation validation build, **not a production-ready release**. Phase 1 feasibility is GO; SIWC compatibility remains CONDITIONAL.

## Scope

- Fresh single-module Kotlin / Compose application, manual dependency wiring; no spike classes, Room or Hilt.
- OAuth authorization code + S256 PKCE + installation host identity; trusted discovery and RS256 ID-token validation with bounded JWKS refresh.
- TokenManager with single-process refresh single-flight, scope checks and ReauthRequired.
- Android Keystore AES-256-GCM, encrypted atomic session persistence, pre-refresh crash marker and quarantine of uncertain rotation.
- Provider domain and ChatGPT Plan adapter, OkHttp, bounded SSE parser, cancellation, deadlines and closed-schema memory-only diagnostics.
- Minimal connection/status/model selector/Single chat. Model catalog loading and sending are explicit actions. No model, billing or API-key fallback.
- Encrypted local chat journal; partial responses survive interruption as Incomplete, never automatically replayed.

The adapter currently supports the verified text/stream baseline only. No collaboration, Debate, tools, image input, multi-account switching or production remote-logout screen is included.

## Build

JDK 17, Android SDK 35, Gradle 8.13. Paths for SDK/project must be ASCII and space-free. Set your own ignored local.properties SDK path, then run ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug.

Dependencies resolve through Google and Maven Central. No API key, client secret or account information belongs in build configuration. No provider request occurs merely by building or opening the app. Real authorization and model requests require explicit user actions.

## Privacy and recovery

Conversations and credentials are encrypted locally in backup-excluded app storage. Sending a message transmits its content to OpenAI under OpenAI's policies; local-first does not mean local-only. Clear app data / uninstall removes local connection state and creates a new installation identity on subsequent connection.

An interrupted refresh cannot prove whether the provider rotated its token. The old credential remains encrypted in a quarantined record and is not replayed; reauthorization is required. A complete replacement is published only after atomic write and readback. Disconnect locally clears usable credentials but does **not** revoke the remote renewable session or delete the provider client.

Keystore hardware backing and actual on-device crash/backup behavior require device validation. Plaintext values exist temporarily in JVM memory; there is no claim of guaranteed heap zeroization. Do not export browser/callback URLs, token values, response bodies or account identity in bug reports.

## Reports

- [Phase 1 feasibility decision](docs/phase-1-feasibility-decision.md)
- [Sprint 1 delivery and verification](docs/production-foundation-sprint-1-report.md)
- [Security and crash-window behavior](docs/security-and-recovery.md)
- [Explicit real-device acceptance plan](docs/production-device-validation.md)

Meldwise is unofficial and is not affiliated with, endorsed by, or sponsored by OpenAI. Users must comply with provider terms. Source: [MPL-2.0](LICENSE).
