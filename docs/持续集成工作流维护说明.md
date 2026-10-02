# GitHub Actions maintenance — separate from refresh-test correction

2026-10-02 (Asia/Shanghai): update actions/setup-java from v4 to v6, the current stable major recommended by its official maintainers. Keep distribution=temurin and java-version=17 unchanged. Hosted ubuntu-latest is used, not an old self-hosted runner. No Android SDK, AGP, Kotlin, application dependency, CI trigger or test filter is changed.

Official references: [setup-java README](https://github.com/actions/setup-java#older-versions), [v6.0.0 release](https://github.com/actions/setup-java/releases/tag/v6.0.0), [v6 action definition](https://github.com/actions/setup-java/blob/v6/action.yml). v6 runs on Node 24; existing inputs used here remain supported. This maintenance is committed separately from the concurrency fix.

The previous Java setup step succeeded. The failed run reached JUnit and failed FoundationTests.noAutomaticRetryForWaitingFailedCallers. Node.js deprecation warnings are not the cause of that assertion failure. Other existing action-major warnings are not silently treated as fixed by this setup-java change.
