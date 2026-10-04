# Meldwise

Meldwise 是一个 Android 多模型聊天客户端。你可以持续和一个模型聊，把同一个问题交给两个模型分别回答，也可以让两个模型一起审阅和整理答案。

当前版本：**0.2.0 Alpha 1**（`v0.2.0-alpha.1`）。这轮加入多轮对话和协作，历史按模式分类，欢迎试用和反馈。

## 现在能做什么

- 连接 ChatGPT 账号，使用账号可用的 ChatGPT 套餐模型。
- 在手机上配置 DeepSeek API Key，加载官方模型列表。
- 单模型多轮聊天，同一条历史可以持续聊，重启后也能打开继续。
- 同时询问两个模型，分别查看回答和状态。
- 协作：主模型初答 → 审阅模型补充和检查 → 主模型综合；后续问题接在同一条历史里。
- 实时接收回答，中途取消时保留已收到的内容；协作也会保留已完成阶段。
- 调整 DeepSeek 思考强度，查看服务商返回的可见思考内容。
- 加密保存本地凭据、对话、对比和协作记录，按模式查看、删除和调整顺序。
- 切换浅色、深色、跟随系统和自定义重点色。
- 阅读 Markdown、代码和纯文本，复制需要的内容。

模型按服务商和模型 ID 区分，每条回答保留当时的模型标识。打开应用先读取本地模型列表，列表为空或超过 24 小时后才后台更新；更新失败时旧列表继续保留。发送消息由你操作，恢复历史不会重放旧请求。应用和模式图标是 Meldwise 原创图形。

## 使用前了解一下

Alpha 版本还在打磨，SIWC 兼容性为 CONDITIONAL（有条件通过）。更多设备和长时间使用的验证仍在进行。

ChatGPT 的手动思考设置取决于当前登录路径和模型支持情况；界面只展示可用的用户可见摘要，无法查看隐藏的内部思维链。DeepSeek 返回的可见思考内容会与正文分开展示。

Compare 仍为单轮对比。协作目前支持整轮手动重试，尚不支持单阶段续跑。Debate、Judge、工具调用、图片输入和 OpenAI API Key 接入留待后续。ChatGPT 可能受套餐或所选模型额度限制。旧版 UI 存储格式的降级恢复暂未支持，完整真机 AndroidTest 套件也尚未全部验证。

## 数据与费用

凭据、模型目录和各模式历史通过 Android Keystore 和 AES-256-GCM 加密保存在设备上，并排除系统备份。

多轮请求使用有界的近期可见消息和回答作为上下文，思考过程不进入后续请求。切换服务商继续对话、或让不同服务商协作时，会先确认可见上下文和回答的共享；许可只用于当前对话。协作后续轮次以最终综合回答作为主要上下文，中间阶段留在本地历史供查看。

发送消息会把内容传给所选服务商。ChatGPT 使用你的账号套餐权限；DeepSeek 使用你配置的 API Key，按 DeepSeek API 计费。两家的凭据各自保存，费用也各自计算。

本机断开连接清除本地可用凭据，远端会话仍会保留。清除应用数据或卸载会移除本地记录。刷新结果无法确认时，应用会要求重新登录。

反馈问题时，给出操作步骤和脱敏诊断就好。请把密钥、令牌、回调链接、账号信息和私人对话留在本机。

## 本地构建

工程使用 Kotlin、Jetpack Compose、Material 3 和 OkHttp。需要 JDK 17、Android SDK 36、Gradle 8.13。工程和 SDK 路径建议使用英文字符，避开空格；在本机 `local.properties` 中配置 SDK 路径。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

依赖从 Google 和 Maven Central 获取。Debug 包为独立的 UI Preview 安装，Release 包名为 `io.github.xiaomeng2568.meldwise`。本机配置、签名材料和构建产物请放在版本控制之外。

## 项目说明

服务商名称、商标及其他品牌标识归各自权利人所有。Meldwise 仅在必要范围内使用相关名称来识别或描述对应的服务集成。除非权利人另有明确许可，第三方品牌标识不属于 Meldwise 的 GPL-3.0-only 授权范围。Meldwise 是独立的社区项目，与相关服务商不存在隶属、赞助或背书关系。使用时请遵守服务商条款。

本项目自 `v0.1.0-alpha.2` 起采用 GNU General Public License v3.0 only（`GPL-3.0-only`）发布，完整文本见 [LICENSE](LICENSE)。`v0.1.0-alpha.1` 及其对应源码仍按原始 MPL-2.0 许可提供；本次变更不会撤销先前已经授予的权利。

第三方组件继续遵循各自许可证，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。正式 APK 附有许可文本和声明，对应源码与构建脚本可从同版本 Git 标签取得。你可以自行构建，并使用自己的密钥签名；官方签名私钥保持私有。

---

# English

Meldwise is an Android multi-model chat client. Continue a conversation with one model, compare two independent answers, or let two models review and refine an answer together.

Current version: **0.2.0 Alpha 1** (`v0.2.0-alpha.1`), adding multi-turn conversations, collaboration and mode-first history.

## Features

- ChatGPT account connection with available plan-backed model access.
- DeepSeek API-key configuration and dynamic model discovery.
- Multi-turn Single Chat: reopen and continue the same conversation across restarts.
- Independent two-model Compare.
- Collaborate: Primary initial answer → Reviewer review → Primary synthesis, with follow-up rounds in the same conversation.
- Streaming and cancellation with partial-response and completed-stage preservation.
- DeepSeek thinking controls and provider-visible reasoning, separate from the answer.
- Encrypted local credentials and Chat/Compare/Collaborate history, with categorized navigation, deletion and reordering.
- Light, Dark, System and custom accent themes.
- Native Markdown, code and plain-text rendering with copy actions.

Models are identified by provider and model ID, with per-output identity snapshots. Startup uses the local catalog immediately and refreshes configured providers in the background only when the cache is empty or over 24 hours old. Failed refreshes preserve the previous catalog. Sending remains explicit; restored history never resumes inference. Launcher and mode icons are original Meldwise assets.

## Limitations

This is Alpha software. SIWC compatibility remains CONDITIONAL. Broader device coverage and long-duration validation are incomplete.

ChatGPT reasoning controls depend on the supported route and model. Only available user-visible summaries are shown; hidden internal chain-of-thought is unavailable. DeepSeek provider-visible reasoning remains separate from the final answer.

Compare remains one-shot. Collaborate retries whole rounds only, without single-stage resume. Debate, Judge, tools, image input and an OpenAI API-key provider are not implemented. ChatGPT may be subject to plan/model usage limits. Downgrading to older UI journal formats is unsupported. Full real-device AndroidTest coverage is not claimed.

## Privacy and billing

Credentials, model catalogs and histories use Android Keystore-backed AES-256-GCM encryption and are excluded from system backup. Messages are sent to the selected provider under its policies. ChatGPT uses available account-plan access; DeepSeek incurs API charges using the configured key. Credentials remain isolated.

Multi-turn context contains a bounded recent subset of visible messages and answers, never reasoning. Continuing across providers or collaborating across providers requires explicit confirmation before sharing visible context/outputs, scoped to the conversation. Later Collaborate rounds primarily use final synthesis answers as context; intermediate stages remain in local history for review.

Local disconnect leaves the remote session intact. Clearing app data or uninstalling removes local state. Uncertain refresh outcomes require reauthorization.

Report issues with reproduction steps and sanitized diagnostics. Keep keys, tokens, callback URLs, account details and private conversations out of reports.

## Build

Use JDK 17, Android SDK 36 and Gradle 8.13. Keep project and SDK paths ASCII and space-free, and configure the SDK in your local `local.properties`.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Dependencies resolve through Google and Maven Central. Debug uses a separate UI Preview installation; Release uses `io.github.xiaomeng2568.meldwise`. Keep local configuration, signing material and build artifacts outside version control.

Provider names, trademarks, and other brand identifiers are the property of their respective owners. Meldwise uses provider names only as necessary to identify or describe the corresponding service integrations. Unless separately licensed by their respective owners, third-party brand identifiers are not covered by Meldwise's GPL-3.0-only license. Meldwise is an independent community project and is not affiliated with, sponsored by, or endorsed by the referenced providers. Use is subject to provider terms.

Starting with `v0.1.0-alpha.2`, Meldwise is distributed under the GNU General Public License v3.0 only (`GPL-3.0-only`); see [LICENSE](LICENSE). `v0.1.0-alpha.1` and its corresponding source remain available under their original MPL-2.0 terms. This change does not revoke rights already granted for prior releases.

Third-party components retain their respective licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The APK includes license texts and notices. Corresponding source and build scripts are available at the matching Git tag. Recipients can rebuild and sign with their own key; the official private signing key remains private.
