# Phase 2 Sprint 2：DeepSeek 接入记录

日期：2026-10-02（Asia/Shanghai）。本轮接入第二家服务商，验证现有 Provider 基础。真实 DeepSeek 验证留给开发者在手机里操作。

SPRINT 2 IMPLEMENTATION：PASS（实现及本地验证范围）。

DEEPSEEK REAL PROVIDER：NOT TESTED。

## 基线和范围

从实际最新 origin/main `0247cb36fe1035aeef85a7a285a84c226a4ef011` 建立 `feature/p2-sprint2-deepseek-provider`。工程使用独立工作树；main 和 Sprint 1 历史保留。v0.3 冻结规格保持原样。

本轮提供 ChatGPT / DeepSeek 选择、独立 API Key、动态模型目录、流式文本 Single Chat，以及服务商/模型绑定的本地记录。Compare、Collaborate、Debate、OpenAI API Key Provider 留在以后。SIWC 兼容性继续记为 CONDITIONAL。

## 架构核查与决策

| 分类 | 现有组件 | 本轮处理 |
| --- | --- | --- |
| 可复用的基础 | LlmProvider / LlmRequest / LlmEvent、NetworkClient、SseParser、AesGcmBox、AndroidAtomicBlob | 保留网络、安全和读取边界 |
| ChatGPT 专属 | OAuthCoordinator、IdentityValidator、TokenManager、StoredSession、SIWC 目录和错误映射 | 保留原有实现；DeepSeek 使用独立路径 |
| 最小扩展 | AppContainer、ProviderRegistry、ModelRef、ChatRepository、MainViewModel | 显式选择 Provider，隔离会话和模型身份 |
| 暂时保持具体实现 | OAuth 刷新事务、API Key 记录、两家目录/错误适配器 | 各自表达真实语义，暂不建设通用认证框架 |

手工组装依赖。没有增加库、Room、Hilt、Retrofit、插件系统或第二套聊天界面。

### ProviderRegistry

稳定 ID 为 `chatgpt` 和 `deepseek`。ChatGPT 原内部标签 `openai-plan` 归一到 `chatgpt`；OAuth 和服务端请求契约保持原样。Registry 只按精确 ID 查找，未知项报错，失败也留在原 Provider。

`ModelRef(providerId, modelId)` 用于选择、准入和本地会话绑定。模型 ID 相同但 Provider 不同，仍是两个身份。实际请求使用目录返回的原始 modelId。

界面按开发者要求显示“服务商-模型名称”，例如 ChatGPT-5.5、DeepSeek-V4.1。展示格式不参与身份判定。尚未加载目录时展示绑定的原始 ID；旧模型未知时显示“ChatGPT-未知模型（旧记录）”。

## API Key 存储

DeepSeek 使用独立的 `deepseek-apikey.v1` 文件、`meldwise.deepseek.apikey.v1` Keystore alias 和同名 purpose AAD。ChatGPT 的 `credentials.v1`、alias 和 OAuth 加密记录保持原样。

API Key 在密码输入框中临时输入，配置弹窗启用 SecureOn。输入没有实例状态保存；提交或关闭后清空界面输入。保存后只显示配置状态。字符串在 JVM 中无法保证彻底零化，这一限制保留。

保存/替换：AES-256-GCM 随机 IV、AtomicFile、fsync、完整记录解密读回校验。删除：原子写入加密空记录，撤去本地可用 Key；不会调用远端撤销，也不会删除 ChatGPT 凭据。加密文件读取上限 8 KiB，Key 最长 4096 个可打印 ASCII 字符，序列化正文另受加密封装上限约束。

损坏或 Key 不可用时进入 UNAVAILABLE，请求准入失败。开发者可以明确重新配置或删除损坏的 DeepSeek 记录。没有明文备用存储。noBackupFilesDir、allowBackup=false、云备份和设备迁移排除规则沿用 Sprint 1。

启动、恢复、选择 Provider、打开配置框、输入和保存 Key 都是本地操作。DeepSeek 的 validateConnection 检查本地配置，READY 表示可以发起显式请求，尚不表示服务端已接受这个 Key。

## 模型目录契约

已核对 [DeepSeek 模型目录](https://api-docs.deepseek.com/api/list-models/)（2026-10-02）。GET `https://api.deepseek.com/models`，Bearer Key。独立解析 `object=list` 和 `data[]`，每项 `object=model`。使用 id / name，name 缺失时显示 id；保留服务端顺序。

目录最多 1024 项；ID 使用受限字符集且最长 128 字符；名称最长 128 字符，拒绝控制字符、无效类型、空名称、重复 ID 和不合法 JSON。显式模态元数据缺少 text 时不进入本轮文本模型列表。可选模态字段缺失时采用官方文本请求基线；新增高级能力没有因此开放。

成功目录沿用现有 2 MiB 解压后读取预算。这允许较丰富的模型元数据，又保持明确内存边界。其他缓冲响应和非成功目录正文仍为 256 KiB。超限保留 BODY_LIMIT / PROTOCOL，Content-Length 仅是传输元数据，实际读取字节仍受约束。没有扩大全局预算。

目录须由用户点击加载。模型由用户明确选择，当前目录之外的模型不会发送请求。

## Responses / SSE

已核对 [Responses 指南](https://api-docs.deepseek.com/guides/responses_api/) 和 [Responses 接口](https://api-docs.deepseek.com/api/create-response/)（2026-10-02）。POST `/responses` 只发送选定 model、input 历史和 stream=true。本轮省略文档不支持的 store，以及 previous_response_id、conversation 等字段。完整的已完成文本消息对作为 input；未完成消息对留在本地。

共享 SseParser 和 ResponsesReader。Reader 新增默认保持 ChatGPT 行为的错误适配回调，DeepSeek 显式注入自己的错误类别和 incomplete 处理。reasoning 事件与非助手 item 被过滤，不进入助手回答、对话存储或诊断。

读取器保留 assistant role / item ID / output index / content index 关联验证、重复 finalized text 去重、冲突拒绝和终态 ID / status 验证。只有携带有效助手文本且通过 response.completed 校验的响应成为 Completed。

| 情况 | 本地结果 |
| --- | --- |
| 已验证 completed + 助手文本 | Completed |
| 无终态的 EOF | Incomplete |
| response.incomplete | Incomplete |
| response.failed，尚无文字 | Failed |
| response.failed，已有部分文字 | Incomplete，保留失败类别和部分文字 |
| 用户取消 | Cancelled，保留已收到的部分 |
| 关联/文本/终态冲突 | Failed 或 Incomplete，依已有文字情况 |

SSE 边界沿用：行 32 KiB、事件 256 KiB、4096 行/帧、助手文字最多 4 MiB；流调用总期限 120 秒。流逐帧读取，没有完整缓冲。

OkHttp 保留 HTTPS 校验、关闭自动重试/重定向/authenticator、one-shot POST、二次网络发送拦截、取消和既有超时。两家间没有回退，模型也不会自动更换。401 不触发流 POST 刷新重放。

### 错误与诊断

依据 [官方错误码](https://api-docs.deepseek.com/quick_start/error_codes/)，401→AUTHENTICATION、403→AUTHORIZATION、402→BILLING、429→RATE_LIMIT、5xx→SERVER。已识别的 model/context 等代码归入固定类别；未知正文不猜测，也不显示原始 message。非成功流正文只检查最多 8 KiB 前缀；HTTP 失败类别在正文读取失败时仍保留。

复用闭合诊断结构：固定 providerId、operation、HTTP 状态、布尔值、计数和枚举。界面标记当前 Provider；DeepSeek 目录展示 data 数组标签。API Key、Authorization、prompt、回答正文、SSE、账号和任意异常消息没有诊断出口。聊天界面正常展示的用户/助手文本与诊断严格分开。

## 聊天记录迁移

Sprint 1 实际格式是加密消息数组，未持久化模型 ID。本轮在相同聊天文件/Keystore alias/AAD 下迁移为版本 2 journal，包含 activeProviderId / activeModelId，以及每个 session 的 providerId / modelId / messages。

旧数组归入 chatgpt / UNKNOWN，保留所有文字和已有状态；Pending / Streaming 在重开时恢复为 Incomplete。保存新版成功后才发布内存状态。失败或损坏保留原密文并阻止读取，避免用空记录覆盖。

会话按 Provider + modelId 分隔。首次选择模型建立对应会话；再选回已有模型恢复它的记录。切换 Provider 恢复该 Provider 最近使用的会话，不发送内容。旧 UNKNOWN 记录通过 ChatGPT 的“旧记录”查看；选择模型开始明确绑定的新会话，旧模型归属仍保持 UNKNOWN。

Key 缺失时会话仍可查看，发送前需要本地凭据准入。恢复不会为可用凭据自动换 Provider、发消息或加载目录。journal 最多 32 个 session、合计 200 条消息和 8 MiB 序列化正文；达到边界时拒绝继续写入，已有历史保留。

## 本地验证

完整验证：Debug / Release 每种 200 项，0 failures / errors / skipped。原 Sprint 1 的 104 项全部保留；新增 96 项覆盖 Registry、凭据、迁移、目录、流式终态、无回退和安全配置。

| Suite | 每种 variant 的实际测试数 |
| --- | ---: |
| FoundationTests | 34 |
| ProviderTransportTests | 6 |
| SecurityBoundaryTests | 4 |
| ModelCatalogNetworkTests | 33 |
| InferenceProtocolTests | 27 |
| SecondProviderFoundationTests | 31 |
| DeepSeekCatalogTests | 29 |
| DeepSeekInferenceTests | 30 |
| SecondProviderSecurityTests | 6 |
| 合计 | 200 |

assembleDebug、assembleRelease、lintDebug、lintRelease 和 assembleDebugAndroidTest 均通过。两个 lint 各 0 错误 / 1 条既有 UseKtx 警告。AndroidTest 共编译 6 个测试方法，本轮未运行真机自动化测试。

10 个既有 compile/runtime 锁定依赖图全部解析成功。没有新增依赖；app/gradle.lockfile SHA-256 为 DD1A81F32D7A0E9B8DBC4CAB49EAE4AB794F07D13AD620FCF707AB456D5D76DB，settings-gradle.lockfile 为 6656E3AED66762D2F39666DE089080C66A3224078AABDF27764B041DA508DF84，均与基线一致。冻结规格 SHA-256 仍为 2F64D6C93FEF56184A4EA38B819389397C920B2D4268D888683D3033C0116A78。

验证过程中首次模拟取消用例在正文已经结束后才取消，得到 STREAM_INTERRUPTED；已修正测试服务的同步条件，未放宽客户端终态或重试规则。随后完整测试通过，最后再强制执行全量 Debug / Release 测试；准确结果以 XML 为准。

测试只使用合成 Key/内容及回环 MockWebServer，没有真实服务商请求。取消测试用收到 TextDelta 的 CompletableDeferred 信号和未结束 HTTP 正文确认操作还在进行，随后取消；没有依靠固定延迟制造时序。

本轮增加一个隔离 Android Keystore 的 DeepSeek 存储测试，作为真机存储验证准备。AndroidTest 编译通过与实际执行分开记录；Sprint 1 未完成的冷启动自动化限制继续保留。

## 已知限制与待验证

DeepSeek REAL PROVIDER 和本轮新界面的真机交互尚未验证。API Key 有效性、真实目录内容、真实 SSE、网络取消和 OEM Keystore 行为要用最终 APK 收集。

配置状态 READY 是本地准入，不是服务端认证成功。密码输入过程仍依赖开发者信任的键盘和系统环境。Debug APK 是工程验证包，Release 构建未配置发布签名。启动/切换零 DeepSeek 请求有源码检查和本地合成验证，尚无本轮真实设备抓包证据。

长周期自然到期、两台物理设备、真实刷新轮换网络中断、跨进程刷新、独立安全审计、OEM 备份行为、断电恢复和硬件级 Keystore 保证继续留为 NOT TESTED。SIWC 仍为 CONDITIONAL。

## 一次真机验证步骤

1. 安装最终并行验证 APK，打开“Meldwise · Sprint 2”。原 Sprint 1 应用和数据留在原处。
2. 选 DeepSeek → 配置密钥，在手机里私下输入 Key → 保存。
3. 点一次“加载模型”；成功后选择实际返回的文本模型。
4. 发一条短消息，确认助手文字和“已完成”。
5. 如需要验证取消，再单独进行一次有界流式取消。

只提供脱敏状态截图/字段。Key、请求头、私人聊天、服务端原始错误和回调链接留在手机上。真实调用和 API 费用由开发者明确操作产生；没有自动余额查询。

本轮提交留在 feature 分支。到此停止等待真机结果，不合并 main、不创建 Release/tag。

## 验证包与提交

实现提交：`7c509cc16e0def445746867261ac2792a4154a0a`。后续提交仅补写 README / 本报告。

APK：`artifacts/Meldwise-P2-Sprint2-DeepSeek-debug.apk`。

SHA-256：`585E059B740368FAAF98C14C72219D49BCF8E77B9B72439CA373FBC573A6C6EC`。

版本：200 / 0.3-p2-sprint2-deepseek。Debug 验证包 applicationId 为 `io.github.xiaomeng2568.meldwise.sprint2`，显示名为“Meldwise · Sprint 2”；Release 正式 applicationId 仍为 `io.github.xiaomeng2568.meldwise`。

选择并行验证的原因：实际比较发现当前构建账户的调试证书与 Sprint 1 APK 不同，原签名文件位于另一个受限构建账户，本轮无法访问。没有修改其权限、导出私钥或卸载旧应用。独立安装保留原数据，新的验证包从自己的空白沙箱开始；这不是覆盖升级/真实旧账号迁移的真机证据。旧记录迁移已经本地合成测试，真实覆盖升级仍待可用的原签名或统一签名方案后验证。

工程分支已提交，尚未推送或创建 PR；本轮没有远端写入，也没有设备安装或真实 Provider 请求。
