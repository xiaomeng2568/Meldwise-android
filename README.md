# Meldwise — 生产基础 Sprint 1 / Production Foundation Sprint 1

## 中文

Meldwise 是非官方 Android 多模型协作客户端。本仓库当前交付 Phase 2 Sprint 1 的生产基础：先验证可靠的 ChatGPT 套餐登录与单模型聊天；尚未实现多模型协作功能。v0.3 规格保持冻结。Phase 1 可行性结论为 GO，**SIWC 兼容性仍为 CONDITIONAL，不是公共生产发布或无条件生产就绪**。最新验收、提交与 CI 状态见[最终验收报告](docs/phase-2-sprint-1-final-acceptance.md)。

### 已实现：

- 独立 Kotlin / Compose 单模块工程，手工依赖组装；未复制实验架构和使用 Room / Hilt。
- OAuth 授权码 + S256 PKCE、安装级 host identity、可信发现及 RS256 ID Token 校验，有界 JWKS 刷新。
- TokenManager 单进程刷新 single-flight、scope 检查及 ReauthRequired。
- Android Keystore + AES-256-GCM、加密原子会话持久化、刷新前崩溃标记、隔离轮换不确定状态。
- Provider 领域基础、ChatGPT 套餐适配器、OkHttp、有界 SSE、取消、超时和固定结构的内存脱敏诊断。
- 中文最小界面：连接状态、显式加载/选择模型、单模型流式聊天。
- 加密本地聊天记录；中断后的未完成状态如实恢复，未续传或重放。

仅支持已验证的文本流式基础能力；不包含协作/辩论、工具调用、图片输入、多账号切换或正式远程退出界面。

### 构建

使用 JDK 17、Android SDK 35、Gradle 8.13。工程与 SDK 路径必须为 ASCII 且无空格。在不纳入版本管理的 local.properties 中配置本机 SDK 路径，然后运行：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

依赖来自 Google / Maven Central。构建配置禁止包含 API 密钥、client secret 或账号信息。构建/打开应用不自动发起服务请求；登录、加载模型与发送消息均需显式操作。

### 隐私与恢复

对话和凭据在设备端加密保存，并排除系统备份。发送消息会把内容传给 OpenAI 并按其政策处理；本地优先不代表内容永不离开设备。清除应用数据或卸载会移除本地连接状态，之后连接时生成新的安装身份。

刷新中断可能无法确定服务端是否已轮换令牌：旧凭据被加密隔离，不重放，需重新授权；只有原子写入并读回完整成功后才发布新凭据。本机断开不会撤销远端会话或删除服务端客户端。

真机存储测试及开发者确认的重开/断网/聊天恢复已在测试范围内通过；冷启动自动测试未完成，不能宣称 5/5 自动测试通过。未验证长期自然过期、两台物理设备、真实轮换网络中断、跨进程刷新、独立安全/合规审计、硬件密钥保障或真实断电恢复。JVM 内存中的明文无法保证彻底清零。报告问题时不要提供令牌、回调/浏览器 URL、响应正文或账号信息。

### 报告与许可

- [Phase 1 可行性决策](docs/phase-1-feasibility-decision.md)
- [Sprint 1 最终验收 / Final acceptance](docs/phase-2-sprint-1-final-acceptance.md)
- [交付与本地验证](docs/production-foundation-sprint-1-report.md)
- [安全及崩溃窗口](docs/security-and-recovery.md)
- [真机证据](docs/production-device-validation.md)

本项目与 OpenAI 无隶属、认可或赞助关系。使用者须遵守服务商条款。源码采用 [MPL-2.0](LICENSE)。验收后停止，不自动开始 Sprint 2 或发布。

## English

Phase 2 Sprint 1 establishes the production foundation on production/foundation-sprint-1. v0.3 remains frozen. This is a foundation validation build, **not a production-ready release**. Phase 1 feasibility is GO; SIWC compatibility remains CONDITIONAL. See the [final acceptance report](docs/phase-2-sprint-1-final-acceptance.md) for actual verification and closure status.

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

Four isolated real-device storage tests and developer-observed restart/offline/chat-restoration behavior passed within tested scope. Cold Activity automation did not complete; do not claim 5/5 instrumentation PASS. Two physical devices, long-term natural expiry, real refresh-rotation network interruption, cross-process refresh, independent audit, hardware backing and real power-loss recovery remain untested. Stop after Sprint 1 closure; no automatic Sprint 2 or public release.

## Reports

- [Phase 1 feasibility decision](docs/phase-1-feasibility-decision.md)
- [Sprint 1 final acceptance and closure](docs/phase-2-sprint-1-final-acceptance.md)
- [Sprint 1 delivery and verification](docs/production-foundation-sprint-1-report.md)
- [Security and crash-window behavior](docs/security-and-recovery.md)
- [Explicit real-device acceptance plan](docs/production-device-validation.md)

Meldwise is unofficial and is not affiliated with, endorsed by, or sponsored by OpenAI. Users must comply with provider terms. Source: [MPL-2.0](LICENSE).
