# Phase 2 Sprint 1 — Inference Protocol Diagnostic

## 1. Decision / 当前结论

**INFERENCE: PASS within observed real-device inference / response-extraction scope.**

模型目录已由开发者接受 PASS，不能用来证明推理通过。先前真机截图只证明：已连接 ChatGPT 套餐、目录成功解析 5 个可见模型、开发者选定目录中的模型并手动发送一次消息后，助手结果失败，界面分类为 PROTOCOL。

先前失败的推理请求未采集 HTTP 状态、服务错误结构、SSE 事件及精确失败阶段。因此旧失败的准确根因仍为 **NOT YET ISOLATED**；不能追溯性地归因为 VPN、网络、模型、HTTP 入站校验或某个具体解析事件。没有转录截图中的聊天内容。

修正已确认的生产代码读取遗漏并加入封闭诊断后，开发者于 2026-10-02 返回真实设备截图并确认可以使用。当前诊断证明 HTTP 200、62 个 SSE 事件、助手文本产生及成功终态验证，失败层与错误类别均 NONE；聊天界面显示助手已完成。故本次观测范围内推理 PASS，但不宣称旧故障唯一根因已被证明、完整 Sprint 1 PASS 或 SIWC 生产就绪。整体 SIWC compatibility 仍为 CONDITIONAL。

## 2. Scope and implementation / 修改范围

- 活动分支：`fix/p2-model-catalog-network`；起点 `b33ecdfa2ac8a1d23ba87cf2077393dfff61aa95`。
- 不修改冻结规格、OAuth、TokenManager、凭据存储、模型目录预算或请求负载。
- `ResponsesReader` 现在读取 `response.content_part.done.part` 和 `response.output_item.done.item.content` 中的助手 `output_text`；之前这两个事件未读取最终文本。
- 依然支持 output_item.added、output_text.delta、output_text.done 和 completed。终态快照为空时，可使用经助手输出项关联的流文本。
- 收尾事件包含完整文本，不作为新 delta 重复追加；已累计文本与最终文本不一致仍拒绝 TEXT_CONFLICT。助手角色、索引、输出项身份关联、4,194,304 字符输出预算，以及 completed 状态/非空响应 ID/实际助手文本的成功条件均保留。ID 仅在内存参与校验，不进入诊断。
- SSE 原有限制保持：单行 32,768 字节、单帧数据约 256 KiB、每帧最多 4,096 行；不整流缓冲。没有扩大模型之外的响应预算。
- 非 200 请求保留 HTTP 状态和固定的提供方错误类别。最多检查 8 KiB 错误正文前缀，标准 error 对象、detail 对象、其他 JSON、非 JSON 分开标记；不导出任何正文或 detail。无法解析的前缀不代表整个服务正文必然不是 JSON。未知 400 为 UNKNOWN/HTTP，不冒充流协议失败；403 保持授权类 HTTP 失败。
- 保留单次 POST、禁用重试/重定向/认证重放、TLS 验证和无 API-key/模型回退。HTTP 401 原有 ReauthRequired 转换保持，不增加刷新或重授权请求。

## 3. Documentation and Phase 1 comparison / 协议依据

正式读取器重写保留独立 SSE/Responses 解析，没有复制一次性实验的认证架构。对照 Gate 1D：已接受的真机证据为 STREAM_EVENTS 提取成功、delta/done 出现、终态快照可无文本；旧实验读取路径还处理 part.done 和 item.done 的最终文本。生产读取路径对这两种形式有明确遗漏，但先前截图不能证明本次故障使用了它们。

官方 Responses 事件定义中，content_part.done 包含 part，output_item.done 包含 item，output_text.done 包含最终完整文本；本轮按这些明确结构提取，未放宽文本一致性或将未知事件当作助手输出。[Responses streaming events](https://developers.openai.com/api/reference/resources/responses/streaming-events)

SIWC 文档区分 HTTP 入站失败与流中失败，且直接路由可能返回 detail 对象；detail 文本不是稳定错误代码。只使用固定 allowlist 分类，不导出原始字符串。[SIWC errors and recovery](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery)

## 4. Closed-schema diagnostics / 脱敏诊断

`InferenceDiagnostic` 只包含布尔、有限计数、HTTP 状态/缺省值和固定枚举。未知事件映射 OTHER，未知服务错误码映射 UNKNOWN；没有字符串字段供任意值进入。

采集：credentialAvailable、requestBuilt、requestStarted、httpReceived、httpStatus、streamBodyOpened、sseParserStarted；parsedEventCount、firstEvent、lastEvent；created/assistant item/text delta/text done/content part done/output item done/completed/failed/incomplete 是否出现；assistantTextProduced、terminalSuccessValidated；failureStage、protocolCategory、providerBodyShape、providerCode、resultCategory、transportCategory。

事件计数是成功解码为 JSON 对象的 SSE 帧数，最大显示 1,000,000；未知或结构冲突的对象也计数，坏 JSON 不计数。事件 seen 只表示识别到该类型，不表示成功验证。只有完整终态校验成功才设置 terminalSuccessValidated。

失败层区分凭据、请求构建、HTTP、流打开、SSE 分帧、事件 JSON、事件结构、助手关联、文本提取、终态校验、提供方流内失败、终态前 EOF 和取消。成功或失败后的终态快照不会被迟到取消回调覆盖。

诊断仅保存在内存，通过现有界面中的独立脱敏文本区域展示；不写日志、不持久化该诊断。没有导出 prompt、助手正文、原始 SSE/JSON、凭据、Authorization、账号、回调 URL、响应 ID 或异常消息。现有合法聊天显示与加密保存功能没有改变；截图请只截诊断区域，不含对话正文。

## 5. Local verification / 本地验证

新增本地模拟测试涵盖：HTTP 400 标准错误/detail/未知代码/非 JSON，401、403、429/套餐配额；普通 delta/done/completed、空终态、仅 part.done、仅 item.done、收尾去重、仅终态文本；failed/incomplete、坏 JSON、事件类型冲突、SSE 超限、EOF、未关联助手、项 ID 冲突、文本冲突、非法终态状态/缺少 ID、未知事件脱敏、终态抗迟到取消，以及诊断字段类型封闭。

模拟传输每轮精确一个目录 GET 和一个推理 POST；后者无重试/刷新/回退，不访问真实提供方。现有安全与 TokenManager 并发回归测试保持原意，不为 CI 放宽。

验证结果（2026-10-02）：Debug / Release 全量单元测试分别 104/104 PASS，0 failures/errors/skipped，含新增 27 项推理测试；assembleDebug PASS；lintDebug PASS（0 errors，1 条既有 UseKtx warning）。第一轮测试发现 JSON 库会接受未加引号的顶层文本 primitive，导致 NON_JSON 分类错误；已在错误正文检查中校验顶层 JSON 词法形式并通过回归，没有改动流协议成功条件。

诊断 APK：`artifacts/Meldwise-P2-Inference-Protocol-Diagnostic.apk`；SHA-256：`9C99A64B4440880F14EAFD1FDD91CD1294B5E7514ECEEC39CF16E752CD8B8E69`。二进制不进入 Git。已验证与上一版目录诊断 APK 的签名证书一致。

冻结规格指纹仍为 `2F64D6C93FEF56184A4EA38B819389397C920B2D4268D888683D3033C0116A78`。生产工作树无修改；没有推送或合并 main，也未改动现有 Draft PR。

## 6. Real-device rerun / 真机结果与范围

设备基线：vivo V2458A；Android 16，SDK 36。构建为 Debug。以签名检查及 `adb install -r` 保留当前数据，不卸载、不清数据，不自动启动授权或发提供方请求。

更新后模型目录的内存列表可能清空；若为空，仅由开发者手动加载一次已验证目录，并从实际返回列表选择原先测试的模型。不得硬编码或切换模型代替目录。

给开发者的重测要求为：确认已连接后只手动发送一次无敏感信息的短消息，返回脱敏诊断截图，不代发、不重试、不自动更换模型、不为补证重复请求。若状态为需要重新授权或凭据有异常，停止并报告，不自动重新连接。

新 APK 的 `adb install -r` 已返回 Success，未卸载、未清数据、未自动启动应用或授权；当前凭据与聊天存储按覆盖更新保留。安装确认不等于认证或推理验收。

### REAL DEVICE / REAL PROVIDER evidence

证据来源：开发者反馈“可以使用”，并提供 `codex-clipboard-b2909c8c-4bb7-4e6c-9a40-f8016643de60.jpg` 与 `codex-clipboard-453040ad-6992-4fd4-9c93-3968466a9e0c.jpg`；手机画面时间均为 17:17。第一张为本应用封闭诊断，第二张用于确认聊天界面助手结果为已完成；不复制第二张的提示或回答文本，也不将原始截图纳入仓库。

界面登录状态为已连接 ChatGPT 套餐，所选模型显示为 GPT-5.6-Sol。这不是独立的后端模型身份验证，不以助手回答的自述证明模型型号或认证/计费路径。

| 字段 | 真实观测 |
| --- | --- |
| credentialAvailable / requestBuilt / requestStarted | true / true / true |
| httpReceived / httpStatus | true / 200 |
| streamBodyOpened / sseParserStarted | true / true |
| parsedEventCount | 62 |
| firstEvent / lastEvent | CREATED / COMPLETED |
| createdSeen / assistantOutputItemSeen | true / true |
| textDeltaSeen / textDoneSeen | true / true |
| contentPartDoneSeen / outputItemDoneSeen | true / true |
| completedSeen / failedSeen / incompleteSeen | true / false / false |
| assistantTextProduced / terminalSuccessValidated | true / true |
| failureStage / protocolCategory | NONE / NONE |
| providerBodyShape / providerCode | NONE / NONE |
| resultCategory / transportCategory | NONE / NONE |
| Chat UI assistant completion | completed observed; content not recorded |
| INFERENCE acceptance | PASS within observed scope |

当前观测故障层：**NONE**。HTTP 接收、SSE 分帧、事件处理、助手关联/文本提取及终态成功校验均通过本次真实观测。

### Evidence limits / 不扩大结论

- 截图展示不止一条已完成的消息，无法据此确认本轮实际授权/请求总数，也不能确认哪些历史消息属于本轮。单请求总数及完整操作顺序 **NOT INDEPENDENTLY VERIFIED**；不把截图写成只发送过一次的证明。62 是当前诊断操作的事件计数，不是所有对话的累计计数。
- delta、text.done、part.done 和 item.done 均出现；seen 布尔不能证明唯一文本来源，也不能证明旧失败一定由两个收尾路径遗漏造成。旧失败缺少细分诊断，保持根因未完全定位。
- 无重试/回退边界由源代码及本地模拟回归支持，不冒充此次完整真机网络抓包证据。未进行额外授权、刷新、目录、资源或模型请求来补证；助手不重复测试。
- 长期稳定性、所有模型/账号/网络条件、完整 Sprint 1 验收及 SIWC 生产就绪均不在本次 PASS 范围。停止等待人工评审。

## 7. Update summary / 更新说明

中文：补齐两种最终助手文本的事件读取，保留严格校验、输出预算和完成标准；新增封闭推理诊断，区分 HTTP 服务端拒绝与 SSE/事件/文本/终态解析失败。Debug/Release 各 104 项测试及构建/lint 通过。开发者真机截图为 HTTP 200、62 个事件、助手文本与完成终态验证成功、错误类别均 NONE：INFERENCE PASS within observed scope。旧失败的唯一根因及本轮真实总请求次数仍未独立证明，不宣称完整 Sprint 1 或生产就绪。

English: Read finalized assistant text from content_part.done and output_item.done while retaining strict validation, output bounds and completion criteria. Add closed-schema diagnostics separating HTTP admission failures from stream validation failures. Debug and Release each pass 104 tests; build and lint pass. Developer-provided real-device evidence shows HTTP 200, 62 events, assistant text produced, terminal success validated and no error categories: INFERENCE PASS within observed scope. The original failure's unique cause and campaign-wide request count remain unproven. Overall SIWC compatibility remains conditional; no full Sprint 1 or production-readiness claim, main merge or additional provider requests.
