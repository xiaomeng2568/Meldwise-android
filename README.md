# Meldwise

Meldwise 是一个 Android 多模型聊天客户端。你可以和一个模型一直聊下去，把同一个问题交给两个模型分别回答，让两个模型接力审阅，也可以让它们互相检查，再由 Judge 整理最终答案。

当前公开版本：[**0.3.0 Beta 1**](https://github.com/xiaomeng2568/Meldwise-android/releases/tag/v0.3.0-beta.1)，首个 Beta 预发行版。Alpha 阶段建立了四种核心聊法；Beta 从这里开始，重点转向稳定性、兼容性、用量透明与更成熟的历史和恢复体验。

## 四种聊法

- **对话**：和一个模型持续聊天。同一条历史里可以接着问，关闭应用后也能打开继续。
- **对比**：把同一个问题交给两个模型，各自回答，方便你看看两边的想法。
- **协作**：主模型先回答，审阅模型检查和补充，再由你选定的模型整理出最终答案。后续问题仍接在同一条历史里。
- **辩论**：A 和 B 先独立回答，再互相审阅，最后由 Judge 整理结果。两份初答、两份审阅分别并行进行；Judge 等两份审阅完成后再开始。

协作的审阅强度有简洁、标准、严格三档，最终综合可交给主模型或审阅模型，对应 A → B → A 或 A → B → B。审阅强度控制的是 Meldwise 的协作提示，与服务商的模型思考强度分别设置。旧协作记录继续采用标准审阅、主模型综合。

辩论需要选择不同的 A、B 模型，Judge 可以与 A 或 B 相同，也可以是另一个已配置的模型。三个角色各自使用所选模型支持的思考设置。完整一轮最多发起 **5 次模型请求**，费用和额度按各自服务计算；辩论并不保证答案一定更好。

## 日常使用

连接 ChatGPT 账号后，可以使用账号当前可用的套餐模型；配置 DeepSeek API Key 后，可以加载官方模型列表，调整思考强度，查看服务商返回的可见思考内容。

回答会实时显示。需要停下来时，可以取消并保留已收到的内容；协作和辩论中已经完成的阶段也会留下。每条回答都保留生成时的模型标识，方便回看。

历史按对话、对比、协作、辩论分类，支持打开、删除和调整顺序。辩论可以在同一条历史里继续追问，重新打开时不会自动续发请求。外观可以选择浅色、深色、跟随系统，也可以挑一个自己喜欢的重点色。应用与模式图标采用 Meldwise 原创设计。

打开应用时会先使用本地模型列表；列表为空或超过 24 小时后，再后台更新。暂时更新失败时，已有列表仍然可用。

## 阅读和输入

正文支持 Markdown、纯文本和常用数学公式。这一版补充原生表格、分隔线、删除线、只读任务列表和有界嵌套列表；表格较宽时可以横向查看，链接保持文本展示，不会点击联网。代码块有语言标签和复制按钮，长行可以横向查看，较长内容可以手动展开。代码块与行内代码使用 JetBrains Mono，配合轻量语法高亮，阅读代码更清楚；复制时保留原始内容。

输入框在空闲时保持紧凑，开始输入或编辑多行文字时自然展开。四种模式共用更一致的阅读宽度和间距，模型信息、思考内容与操作按钮也更轻量。

对比的两份回答、协作的初答和审阅、辩论的两份初答都可以独立收起或展开，带有轻量过渡动效。收起后仍保留模型和阶段状态，不会停止生成、删除内容或改变模型收到的上下文。协作综合和 Judge 最终答案保持直接展示。折叠选择只用于当前阅读页面，重新打开会恢复展开。

公式通过 Orcex 原生渲染，支持常用 TeX 子集；遇到未闭合、解析失败或超出支持范围的表达式时，会保留原文。代码高亮采用保守词法识别，适合常见语法的阅读辅助，复杂或难以确定的结构保持普通代码样式。JetBrains Mono 的中文注释由系统字体补充显示。

## 数据与费用

凭据、模型目录和各模式历史通过 Android Keystore 和 AES-256-GCM 加密保存在设备上，系统备份会排除这些数据。

多轮聊天会选取有界的近期可见消息和回答作为上下文。切换服务商继续对话，或让不同服务商参与协作、辩论时，应用会先询问你是否共享可见上下文和回答，许可只用于当前对话与确切的一组服务商。共享范围仅包括可见消息和回答；服务商返回的可见思考内容单独展示，不会作为普通上下文传给其他模型。协作后续轮次主要使用最终综合回答，辩论后续轮次使用用户消息与成功的 Judge 最终回答；中间阶段保留在本地历史里供回看。

请求使用你选择的服务商和模型，发送、重试与更换模型由你操作。重新打开历史会恢复已有内容和状态，继续聊天时再发起新的请求。

ChatGPT 使用你的账号套餐权限；DeepSeek 使用你配置的 API Key，按 DeepSeek API 计费。两家的凭据各自保存，费用也各自计算。

Beta 1 的内部用量基础仅在运行时记录实际发起的模型请求与服务商明确返回的 token 数值，未提供的数据保持未知，部分用量保留覆盖比例。目前没有用量面板、费用显示或用量历史持久化；这些仍属于后续 Beta 工作。应用不会按文字长度估算 token，也不会推算 ChatGPT 套餐剩余额度、重置时间或费用。

在本机断开连接会清除本地可用凭据，远端会话仍会保留。清除应用数据或卸载会移除本地记录。刷新结果无法确认时，应用会请你重新登录。

反馈问题时，操作步骤和脱敏诊断就足够了。密钥、令牌、回调链接、账号信息和私人对话请留在自己的设备上。

## 目前的边界

Meldwise 已进入 Beta，仍是预发行软件，不代表稳定生产版本或 V1 功能完整。四种核心模式的产品形态在此冻结；后续仍可能补充用量与费用展示、历史搜索、重新生成、对话分支、导入导出与可靠性改进。更多设备和长时间使用的验证仍在继续。SIWC 兼容性目前为 CONDITIONAL（有条件通过），完整真机 AndroidTest 覆盖也在继续完善。Markdown 和 TeX 都是有界的实用子集，并非完整 CommonMark/GFM 或 TeX；不使用 WebView，原始 HTML 和脚本样式内容仅作为惰性文本显示。

对比目前围绕单个问题展开；协作保持三阶段，辩论保持五阶段。协作和辩论重试都按整轮、原模型快照进行，不支持单阶段续跑。辩论的 Judge 用于整理答案，不提供胜负评分或答案质量保证。工具调用、图片输入与 OpenAI API Key 接入属于后续方向。旧版 UI 存储格式的降级恢复也仍待完善。

ChatGPT 的可用模型、额度和手动思考设置取决于你的套餐、登录路径及模型支持情况。界面展示服务商提供的用户可见内容：ChatGPT 的可见摘要、DeepSeek 的可见思考与最终回答分别呈现；隐藏的内部思维链属于服务商内部信息。

## 本地构建

工程使用 Kotlin、Jetpack Compose、Material 3 和 OkHttp。准备 JDK 17、Android SDK 36、Gradle 8.13，并在本机 `local.properties` 中配置 SDK 路径。工程与 SDK 路径建议使用英文字符、避开空格。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

依赖从 Google 和 Maven Central 获取。Debug 包使用独立的 UI Preview 安装，Release 包名为 `io.github.xiaomeng2568.meldwise`。本机配置、签名材料和构建产物请放在版本控制之外。

## 项目与许可

服务商名称、商标及其他品牌标识归各自权利人所有。Meldwise 仅在必要范围内使用相关名称来识别或描述对应的服务集成。除非权利人另有明确许可，第三方品牌标识不属于 Meldwise 的 GPL-3.0-only 授权范围。Meldwise 是独立的社区项目，与相关服务商不存在隶属、赞助或背书关系。使用时请遵守服务商条款。

本项目自 `v0.1.0-alpha.2` 起采用 GNU General Public License v3.0 only（`GPL-3.0-only`）发布，完整文本见 [LICENSE](LICENSE)。`v0.1.0-alpha.1` 及其对应源码仍按原始 MPL-2.0 许可提供；本次变更不会撤销先前已经授予的权利。

第三方组件继续遵循各自许可证，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。正式 APK 附有许可文本和声明，对应源码与构建脚本可从同版本 Git 标签取得。你可以自行构建，并使用自己的密钥签名；官方签名私钥保持私有。

---

# English

Meldwise is an Android multi-model chat client. Keep a conversation going with one model, compare two independent answers, have two models refine an answer together, or let them cross-review before a Judge puts together the final answer.

The current public release is [**0.3.0 Beta 1**](https://github.com/xiaomeng2568/Meldwise-android/releases/tag/v0.3.0-beta.1), the first Beta prerelease. Alpha established the four core workflows; Beta now focuses on stability, compatibility, usage transparency and more mature history and recovery behavior.

## Four ways to chat

- **Chat**: keep talking in the same conversation, including after reopening the app.
- **Compare**: ask two models the same question and read their answers independently.
- **Collaborate**: let the Primary model answer first, the Reviewer check and supplement it, and your chosen model put together the final answer. Follow-up questions stay in the same conversation.
- **Debate**: A and B answer independently, cross-review, and a Judge puts together the result. Each pair of initial answers and reviews runs in parallel; Judge starts only after both reviews finish.

Choose Concise, Standard or Strict review, then use either the Primary or Reviewer for synthesis: A → B → A or A → B → B. Review Intensity controls Meldwise's collaboration instructions; provider thinking effort has its own setting. Existing records keep Standard review and Primary synthesis.

Choose different A and B models for Debate. Judge may match either participant or be another configured model. Each role uses reasoning preferences supported by its selected model. A complete round can make up to **5 model requests**, with usage and billing following each provider. Debate does not guarantee a better answer.

## Everyday use

Connect a ChatGPT account to use its available plan-backed models, or configure a DeepSeek API key to load the official model catalog. DeepSeek thinking controls and provider-visible reasoning are available alongside the answer.

Responses arrive as they are generated. You can stop a request and keep the content received so far, including completed Collaborate and Debate stages. Each answer retains the identity of the model that produced it.

History is organized by Chat, Compare, Collaborate and Debate, with opening, deletion and reordering. Continue Debate in the same conversation; reopening history never automatically resends requests. Choose Light, Dark, System or a custom accent. The launcher and mode icons are original Meldwise designs.

The app uses the local model catalog on startup and refreshes it in the background when empty or more than 24 hours old. The existing catalog stays available if a refresh fails.

## Reading and writing

Read Markdown, plain text and common mathematical expressions. This release adds native tables, thematic breaks, strikethrough, read-only task lists and bounded nested lists. Wide tables scroll horizontally; links remain non-network text. Code blocks have language labels and copy controls, with horizontal scrolling for long lines and manual expansion for longer content. JetBrains Mono and lightweight highlighting make code easier to scan, while copying preserves the original source.

The Composer stays compact at rest and expands naturally as you type or edit multiline text. All four modes share a more consistent reading width and spacing, with quieter model metadata, reasoning disclosure and message actions.

Compare answers, Collaborate initial answers and reviews, and Debate initial answers can each be collapsed or expanded with a lightweight transition. Model and stage status remain visible. Collapsing does not stop generation, delete content or change model context. Collaborate synthesis and Judge final answers remain directly visible. Disclosure choices are local to the current reading screen and reset when reopened.

Orcex renders math natively using a supported TeX subset. Incomplete, malformed or unsupported expressions retain their source text. Code highlighting uses conservative lexical recognition; uncertain syntax stays plain. Chinese comments use platform font fallback alongside JetBrains Mono.

## Data and billing

Credentials, model catalogs and history are stored on your device using Android Keystore-backed AES-256-GCM encryption, with these records excluded from system backup.

Multi-turn requests use a bounded recent selection of visible messages and answers. Continuing, collaborating or debating across providers asks for your confirmation before sharing visible context and outputs, scoped to the current conversation and exact provider set. Only visible messages and answers are included; provider-visible reasoning has its own display and is never ordinary downstream model context. Later Collaborate rounds primarily use final synthesis; later Debate rounds use user messages and successful Judge final answers. Intermediate stages stay in local history.

Requests use your selected provider and model. Sending, retrying and changing models are your choices. Reopening history restores existing content and status; your next send starts a new request.

ChatGPT uses your account-plan access. DeepSeek uses your configured key and its API billing. Credentials and costs are kept separate for each provider.

Beta 1's internal usage foundation records dispatched model requests and explicitly provider-reported token values at runtime only. Missing values remain unknown, and partial usage retains coverage information. There is no usage dashboard, cost display or persisted usage history yet; these remain future Beta work. Meldwise does not estimate tokens from text length or infer remaining ChatGPT plan quota, reset times or costs.

Disconnecting clears locally available credentials while leaving the remote session intact. Clearing app data or uninstalling removes local records. An uncertain refresh outcome asks you to sign in again.

When reporting a problem, reproduction steps and sanitized diagnostics are enough. Keep keys, tokens, callback URLs, account details and private conversations on your own device.

## Where things stand

Meldwise has entered Beta and remains prerelease software, not a stable production release or a feature-complete V1. The product shape of the four core modes is frozen here. Future Beta work may still add usage/cost presentation, History Search, Regenerate, conversation branching, Import/Export and reliability improvements. Broader device coverage, longer-running validation and the full real-device AndroidTest suite are ongoing. SIWC compatibility remains CONDITIONAL. Markdown and TeX are bounded useful subsets, not full CommonMark/GFM or TeX implementations. There is no WebView; raw HTML and script-like source remain inert text.

Compare currently handles one question at a time. Collaborate uses three stages; Debate uses five. Both retry whole rounds with their original model snapshots, without stage-only continuation. Judge synthesizes answers rather than providing competitive scores or quality guarantees. Tools, image input and OpenAI API-key access remain future directions, along with downgrade recovery for older UI storage formats.

ChatGPT model access, usage limits and manual thinking controls depend on your plan, sign-in route and the selected model. The UI shows provider-supplied user-visible content, keeping available ChatGPT summaries and DeepSeek reasoning separate from the final answer. Hidden internal chain-of-thought remains provider-internal.

## Build locally

Use JDK 17, Android SDK 36 and Gradle 8.13. Configure the SDK in your local `local.properties`; ASCII paths without spaces are recommended for the project and SDK.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Dependencies come from Google and Maven Central. Debug uses a separate UI Preview installation; Release uses `io.github.xiaomeng2568.meldwise`. Keep local configuration, signing material and build artifacts outside version control.

## Project and licenses

Provider names, trademarks, and other brand identifiers are the property of their respective owners. Meldwise uses provider names only as necessary to identify or describe the corresponding service integrations. Unless separately licensed by their respective owners, third-party brand identifiers are not covered by Meldwise's GPL-3.0-only license. Meldwise is an independent community project and is not affiliated with, sponsored by, or endorsed by the referenced providers. Use is subject to provider terms.

Starting with `v0.1.0-alpha.2`, Meldwise is distributed under the GNU General Public License v3.0 only (`GPL-3.0-only`); see [LICENSE](LICENSE). `v0.1.0-alpha.1` and its corresponding source remain available under their original MPL-2.0 terms. This change does not revoke rights already granted for prior releases.

Third-party components retain their respective licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The APK includes license texts and notices. Corresponding source and build scripts are available at the matching Git tag. Recipients can rebuild and sign with their own key; the official private signing key remains private.
