# Meldwise

Meldwise 是一个 Android 多模型协作客户端。我们希望让多个模型分别回答、互相检查，再把结果整理给用户。

目前完成了 Phase 2 Sprint 1：ChatGPT 登录、本地加密存储、模型选择和单模型聊天。这一轮已经验收并合并到 main，多模型协作会在后续阶段继续做。

当前 Sprint 2 分支已接入 DeepSeek：在手机里配置独立加密的 API Key，加载官方模型列表，再选择模型聊天。本地 Debug、Release 各 200 项测试和构建/lint 已通过，真实 DeepSeek 调用等开发者在手机上验证。过程见 [Sprint 2 接入记录](docs/第二阶段第二轮-DeepSeek-Provider.md)。

v0.3 规格保持冻结。SIWC 兼容性仍按 CONDITIONAL（有条件通过）记录，正式发布还需要补充长周期和更多设备上的验证。

## 现在能做什么

- 使用 ChatGPT 账号连接，按账号套餐调用模型。
- 加载账号可用的模型列表，手动选择模型。
- 进行单模型聊天，实时接收回答，也可以中途取消。
- 加密保存登录状态和聊天记录，重开应用后恢复本地状态。
- 在请求失败时显示脱敏诊断，方便定位问题。
- 在 Sprint 2 分支选择 ChatGPT 或 DeepSeek；聊天记录按服务商和模型分开保存。

当前支持文本聊天。多模型比较、协作、辩论、工具调用和图片输入暂时留在后续计划中。

## 工程基础

工程使用 Kotlin、Jetpack Compose 和 Material 3，依赖通过手工组装。

登录采用 OAuth 授权码和 S256 PKCE，并校验 ID Token 的签名、issuer、audience 和 nonce。凭据通过 Android Keystore 和 AES-256-GCM 加密保存；刷新流程包含并发合并、原子写入和异常恢复处理。

网络层使用 OkHttp，模型目录和 SSE 流分别设置读取边界。模型选择、加载列表和发送消息都由用户明确操作，请求失败后的下一步也交给用户决定。

## 本地构建

需要：

- JDK 17
- Android SDK 35
- Gradle 8.13

工程和 SDK 路径请使用英文字符，并避开空格。在本机的 `local.properties` 中配置 SDK 路径，然后运行：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

依赖从 Google 和 Maven Central 获取。`local.properties`、密钥和账号凭据留在本机。

Sprint 2 的 Debug 包叫“Meldwise · Sprint 2”，可与原应用并行安装；Release 保持正式包名。并行验证包使用独立的本地数据。

## 数据和恢复

登录凭据、聊天记录保存在设备端，使用加密存储，并排除系统备份。

发送消息时，内容会传给 OpenAI，由其按照相关政策处理。模型调用使用你的 ChatGPT 套餐。

选择 DeepSeek 时，内容会传给 DeepSeek，使用你配置的 API Key 并按 DeepSeek API 计费。两家的凭据各自加密保存，ChatGPT 套餐不承担 DeepSeek 费用。

中途取消的回答会保留已收到的部分，并显示相应状态。应用重开后恢复本地记录，继续发送需要用户操作。

如果刷新令牌时连接中断，客户端可能无法判断服务端是否已经完成轮换。这种情况下会隔离旧凭据，并要求重新登录。本机“断开连接”清除的是本地可用凭据，远端会话仍会保留。

清除应用数据或卸载应用会移除本地连接和记录；之后重新连接会建立新的安装身份。

## 验证进度

Sprint 1 已完成以下验证：

- Debug、Release 完整单元测试各 104 项通过。
- 两种构建通过，lint 均为 0 错误、1 条既有警告。
- 真机 Keystore 和加密存储测试 4 项通过。
- 模型目录、正常聊天和流式取消通过。
- 开发者确认重开、强停重开、断网和本地记录恢复在测试范围内通过。
- 最终生产提交的 push、PR CI，以及合并后的 main CI 均通过。

冷启动自动化测试还没完成，相关行为目前采用开发者真机观察作为验收证据。

两台物理设备、长期自然到期、令牌轮换期间的真实网络中断、跨进程刷新和独立安全审计，仍需要后续验证。详细过程和证据范围放在 `docs` 中。

反馈问题时，提供操作步骤和脱敏诊断即可。令牌、回调链接、账号信息和私人对话请留在本机。

## 项目说明

Meldwise 是社区开发的非官方客户端，与 OpenAI 没有隶属或赞助关系。使用时请遵守服务商条款。

源码采用 MPL-2.0 许可证。

---

# English

Meldwise is an Android client for multi-model collaboration. Its goal is to let models answer independently, review each other’s work, and produce a combined result.

Phase 2 Sprint 1 is complete and merged into main. It establishes ChatGPT authentication, encrypted local storage, model discovery, and Single Chat. Multi-model workflows remain planned work.

The Sprint 2 feature branch adds DeepSeek with an independently encrypted API key, dynamic model discovery, and streaming Single Chat. Local verification passed 200 tests per Debug/Release variant, builds, and lint. Real-provider verification is pending developer testing on Android. See the [Sprint 2 report](docs/第二阶段第二轮-DeepSeek-Provider.md).

The v0.3 specification remains frozen. SIWC compatibility remains CONDITIONAL; broader and longer-term validation is required before a public production release.

## Current features

- ChatGPT account connection and plan-backed model access.
- Explicit model discovery and selection.
- Streaming text chat with cancellation.
- Encrypted local credentials and chat history.
- Sanitized diagnostics for troubleshooting.
- ChatGPT / DeepSeek selection and provider/model-bound conversations on the Sprint 2 branch.

Compare, Collaborate, Debate, tool use, and image input are deferred.

## Foundation

The application uses Kotlin, Jetpack Compose, and Material 3 with manual dependency wiring.

Authentication uses authorization-code OAuth, S256 PKCE, and ID-token validation. Credentials use Android Keystore and AES-256-GCM. Refresh handling includes single-flight coordination, atomic persistence, and rotation-uncertainty recovery.

OkHttp transport and SSE parsing enforce operation-specific bounds. Provider requests require explicit user actions.

## Build

Use JDK 17, Android SDK 35, and Gradle 8.13. Keep project and SDK paths ASCII and space-free. Configure the SDK in your local `local.properties`, then run:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Dependencies resolve through Google and Maven Central. Keep local configuration and credentials outside version control.

The Sprint 2 Debug APK uses a separate application ID for side-by-side validation. Existing app data remains in the original installation; Release retains the production ID.

## Privacy and recovery

Credentials and conversations are encrypted on-device and excluded from system backup. Sending a message transmits its content to OpenAI under its policies and uses your ChatGPT Plan.

When DeepSeek is selected, messages go to DeepSeek and incur DeepSeek API charges using the configured key. Credentials are isolated; ChatGPT Plan access does not cover DeepSeek usage.

Interrupted responses retain their truthful local state. Restoring history does not automatically resume requests.

An uncertain refresh outcome requires reauthorization. Local disconnect clears usable local credentials while leaving the remote session intact. Clearing app data or uninstalling removes local state.

## Validation

Sprint 1 passed 104 Debug and 104 Release unit tests, both builds, and lint with zero errors and one existing warning per variant. Four real-device storage tests passed.

Model discovery, completed chat, and streaming cancellation were verified. Restart, offline behavior, and local restoration were accepted through developer-observed device tests. Final production-head push/PR CI and post-merge main CI passed.

Cold-start automation remains incomplete. Two physical devices, natural long-term expiry, real refresh-rotation network interruption, cross-process refresh, and independent security review remain untested. Evidence and limitations are recorded in `docs`.

Share sanitized diagnostics when reporting issues. Keep tokens, callback URLs, account details, and private conversations out of reports.

Meldwise is an unofficial community project, unaffiliated with OpenAI. Use is subject to provider terms. Source is licensed under MPL-2.0.
