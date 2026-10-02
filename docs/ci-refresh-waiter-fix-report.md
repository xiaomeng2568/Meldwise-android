# CI refresh-waiter investigation and deterministic regression

2026-10-02 (Asia/Shanghai). Baseline commit: c50ea7269729056f69d62a0964854c549b890115. Existing [Draft PR #1](https://github.com/xiaomeng2568/Meldwise-android/pull/1) remains draft and is not merged.

## Original evidence

- [PR-triggered run](https://github.com/xiaomeng2568/Meldwise-android/actions/runs/36975031707): success.
- [Push-triggered run](https://github.com/xiaomeng2568/Meldwise-android/actions/runs/36974936735): failure, 44 tests / 1 failed; FoundationTests.noAutomaticRetryForWaitingFailedCallers, assertion at original FoundationTests.kt:80.
- Java setup succeeded. The Node.js / setup-java deprecation notices were not the cause of the JUnit assertion failure.

## Investigation first changed TEST synchronization only

The previous delay(100) did not establish that all consumers belonged to the same refresh attempt. runTest also mixes virtual scheduling with real Dispatchers.IO persistence. Passing timing was not proof of the required ordering.

Replace timing with ownerEntered and releaseFailure CompletableDeferred barriers. Start one owner, await its fake endpoint entry, assert Refreshing and persisted REFRESH_IN_FLIGHT. Launch two consumers with CoroutineStart.UNDISPATCHED: each runs accessToken through attempt capture until its first suspension, which must be the owner's held Mutex. Assert both are active/incomplete, owner is incomplete, and endpoint invocation count is 1 BEFORE releasing failure. Only then complete releaseFailure and await all three outcomes.

With the old, UNCHANGED TokenManager this deterministic test failed locally: expected endpoint count 1, actual 2. Therefore merely synchronizing the test was not sufficient: it exposed an existing failure-settlement edge case. No production semantic was weakened to hide it.

## Exact implementation cause and minimal contract-preserving correction

refreshAttempt advanced only at refresh START, not failed SETTLEMENT. A consumer entering after the owner reached the endpoint captured the same attempt value that was still current after failure; the comparison could not distinguish it from a fresh post-failure request.

| Event | Old behavior | Corrected behavior |
| --- | --- | --- |
| Owner starts; two callers join in flight | Waiting callers capture current attempt | Same |
| Confirmed-not-sent NETWORK failure settles | Attempt value remains unchanged | Advance settlement epoch while still holding mutex |
| Existing waiters resume | First waiter can accidentally own a second refresh | Waiters detect settlement and receive original NETWORK failure |
| Genuinely NEW explicit request after settlement | May attempt again | Still may attempt again |

Production change is one additional refreshAttempt increment in the existing RefreshFailure settlement branch, after persist/publish and lastFailure assignment, before mutex release. Initial timing, scope checks, credential generation, transaction/quarantine behavior, expiry checks, retry policy and token logging remain unchanged. There is no permanent failure cache or prohibition on a new explicit request.

The regression test asserts all three original consumers receive NETWORK, invocation count is exactly 1, state restores ACTIVE at generation 1, then a NEW explicit request receives NETWORK from a second endpoint invocation. This last assertion protects against over-restrictive fixes.

## Actual local verification

- Baseline implementation + deterministic barrier test: FAIL, expected 1 / actual 2 (diagnostic red step).
- Corrected implementation + affected test: PASS.
- 200 independent JUnitCore Request.method invocations of the SAME affected test: 200/200 PASS. Not an up-to-date Gradle task or a test-selection-only count; each iteration executes fresh test/manager/barriers and checks both shared failure and fresh explicit retry.
- Full Debug unit tests: 44/44 PASS, 0 failure/error.
- Full Release unit tests: 44/44 PASS, 0 failure/error.
- assembleDebug: PASS.
- lintDebug: PASS, 0 errors / 15 existing warnings.

Full command: ./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon. Repeat verification used a local, external init script and JDK source-mode JUnit harness; it does not modify application build configuration or CI test filtering.

All test data is synthetic. No provider authorization, refresh, resource or model request was performed. No new SDK, production feature, persistence architecture or UI change. Frozen specification and Phase 1 evidence were not edited. Existing Sprint 1 artifacts/reports are historical and were not relabeled as this fix's APK.

## Delivery boundaries

Concurrency correction and setup-java maintenance are separate commits on production/foundation-sprint-1. Only that branch is to be pushed, without force; Draft PR updates automatically. No merge, auto-merge, review-state change or release.

New GitHub run outcomes must be observed after pushing; this document's verification numbers are LOCAL results, not a prediction that remote CI is green. Actions maintenance rationale is recorded separately in ci-actions-maintenance.md.
