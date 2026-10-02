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
