# Meldwise for Android

**中文 | English**

Meldwise 是一个 **Local-first、面向 Android 的多模型协作客户端**。

它的目标不是做“又一个多 Provider 聊天壳”，而是让不同 AI 模型围绕同一个问题进行独立作答、对照、协作、互审与综合。

Meldwise is a **local-first Android client for resilient multi-model collaboration**.

Its goal is not to become just another multi-provider chat shell, but to let different AI models independently answer, compare, review, collaborate, and synthesize around the same task.

---

## 中文

### 项目定位

Meldwise 的核心是 **Multi-Model Collaboration Harness（多模型协作编排层）**。

计划中的核心模式：

- **Single**：单模型正常对话
- **Compare**：多个模型并行回答
- **Collaborate**：多个候选答案交由 Judge 综合
- **Debate**：模型互审后再综合最终答案

### 当前状态

- **Specification:** v0.3 **FROZEN**
- **Current stage:** Gate 0 → Gate 1A → Gate 1B
- **Platform:** Android
- **Language:** Kotlin
- **UI:** Jetpack Compose
- **License:** MPL-2.0

> 在拿到 Gate 1 的真实设备证据之前，不编写 v0.4。

当前优先级不是漂亮 UI，也不是完整 OAuth 架构，而是验证高风险技术前提：

1. Android 原生开源/本地客户端使用当前 ChatGPT Plan / SIWC 流程的支持边界；
2. Android 真机上的 `127.0.0.1:<random-port>` loopback callback 是否可靠；
3. 浏览器切换、后台、取消、重复回调、端口冲突等真实场景是否可以稳定处理；
4. 是否存在足以阻止后续 SIWC Gate 的技术或合规问题。

### 设计原则

- **Local-first**
- **Provider-agnostic**
- **Resilience-first**
- 不静默切换模型
- 不静默切换计费路径
- 不隐藏跨 Provider 数据流向
- 不把部分失败伪装成成功
- 先拿真实证据，再做生产架构

### 隐私说明

“Local-first”指的是会话历史和应用状态优先保存在设备本地，**不代表请求内容不会发送给模型服务商**。

当用户调用 OpenAI、DeepSeek 或其他 Provider 时，相应内容会发送给所选服务商，并受各服务商自己的隐私政策与服务条款约束。

在 Collaborate / Debate 模式中，一个 Provider 的输出也可能被发送给另一个 Provider 作为 Reviewer 或 Judge。

### 开发纪律

v0.3 已冻结。

下一份有意义的项目文档应该是 **Gate 1 真机证据报告**，而不是继续扩展纸面规格。

Phase 1 中的 SIWC 代码属于 **disposable spike**：

- 不实现生产级 `OAuthManager`
- 不引入 Room
- 不引入复杂 DI / Hilt 架构
- 不制作正式 UI
- Spike 只负责回答可行性问题
- Spike 成功后，生产实现重新设计/重写，不持续给实验代码打补丁

---

## English

### Project Positioning

Meldwise is a **Multi-Model Collaboration Harness** for Android.

Planned core modes:

- **Single** — standard single-model conversation
- **Compare** — multiple models answer in parallel
- **Collaborate** — multiple candidate answers are synthesized by a Judge
- **Debate** — models cross-review each other before final synthesis

### Current Status

- **Specification:** v0.3 **FROZEN**
- **Current stage:** Gate 0 → Gate 1A → Gate 1B
- **Platform:** Android
- **Language:** Kotlin
- **UI:** Jetpack Compose
- **License:** MPL-2.0

> No v0.4 will be written before real Gate 1 device evidence exists.

The current priority is not polished UI or production OAuth architecture. It is to validate the highest-risk assumptions:

1. Whether the current ChatGPT Plan / SIWC flow is acceptable for a native Android open-source/local client;
2. Whether `127.0.0.1:<random-port>` loopback callbacks work reliably on real Android devices;
3. Whether browser switching, backgrounding, cancellation, duplicate callbacks, and port collisions behave acceptably;
4. Whether any technical or compliance issue is serious enough to block later SIWC gates.

### Principles

- **Local-first**
- **Provider-agnostic**
- **Resilience-first**
- No silent model fallback
- No silent billing-path changes
- No hidden cross-provider data flow
- No presenting partial failure as success
- Evidence before production architecture

### Privacy

“Local-first” means that conversation history and application state are primarily stored on the user's device. It **does not** mean that prompts never leave the device.

When the user invokes OpenAI, DeepSeek, or another provider, the relevant content is transmitted to that provider and processed under its own privacy policy and terms.

In Collaborate / Debate modes, output from one provider may also be sent to another provider acting as a Reviewer or Judge.

### Development Discipline

v0.3 is frozen.

The next meaningful project document should be a **Gate 1 real-device evidence report**, not another speculative specification revision.

SIWC work during Phase 1 is a **disposable spike**:

- no production `OAuthManager`
- no Room
- no heavy DI / Hilt architecture
- no polished production UI
- the spike exists only to answer feasibility questions
- if the spike succeeds, production authentication code should be redesigned/reimplemented rather than patched indefinitely

---

## License

Meldwise source code is licensed under the **Mozilla Public License 2.0 (MPL-2.0)**.

See [LICENSE](./LICENSE).

## Disclaimer

Meldwise is an unofficial open-source project.

It is not affiliated with, endorsed by, or sponsored by OpenAI, DeepSeek, or any other model provider.

OpenAI and ChatGPT are trademarks of OpenAI. Other product and company names may be trademarks of their respective owners.

Users are responsible for complying with the applicable terms of each provider they connect.