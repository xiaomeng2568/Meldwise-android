# Phase 2 模型目录网络诊断与中文界面

日期：2026-10-02（Asia/Shanghai）。MODEL CATALOG：**PASS（仅模型目录加载范围）**。整体 SIWC 兼容性仍为 CONDITIONAL；聊天响应协议问题未解决。

## 1. 边界与现有证据

独立分支 `fix/p2-model-catalog-network`，工作目录 `P2-Network-Diagnostic`，起点为生产分支提交 `b8dc1af3b92bb8d72cd2a274f20509ff33cb10d2`。不覆盖生产工作目录，不修改并发测试、TokenManager、OAuth、凭据存储、模型调用或冻结规格。第一轮诊断提交为 `9c52744df323f92d7c2d03a5b4e79439031479b7`，当时未推送。本次用户明确要求将后续小更新推送到此诊断分支；不合并或推送 main，不更改现有 Draft PR。

用户截图 `Screenshot_20261002_145327.jpg`：vivo V2458A / Android 16；应用显示 Connected · ChatGPT Plan；点击 Load models 后显示 NETWORK，无法选择模型。截图包含 VPN 标志，但**没有证据证明 VPN 是原因**。

旧版没有分层诊断，以下事实不可从截图推断：

| 项目 | 原始真机证据 |
| --- | --- |
| 模型请求是否取得令牌 | 未知；Connected 状态本身不证明请求取得令牌 |
| 是否到达 HTTP 响应 | 未知 |
| HTTP 状态 | 未知 |
| 是否完成正文读取 | 未知 |
| 是否进入 JSON / 模型目录解析 | 未知 |
| DNS / 连接 / TLS 是否失败 | 未知 |

## 2. 已确认的代码缺陷

`ChatGptProvider.listModels()` 的兜底捕获将非预期异常统一映射为 NETWORK。HTTP 200 后 JSON 语法、models 数组或可见模型字段校验失败，因而可能被错误显示为网络失败。

这属于**已验证的错误分类缺陷**；第一轮本地模拟 HTTP 200 + 无效 JSON / 目录返回 PROTOCOL，当时尚不能判断真机根因。随后收到的真实脱敏诊断明确定位到 BODY_READ / BODY_LIMIT，见下文。

共享读取路径原先也把正文读取时的 IOException 归为 PROTOCOL；现在保留 HTTP 已收到这一事实，并区分 BODY_READ 的网络、超时或正文格式限制。

## 3. 修正与诊断

- 将网络异常按类型分类为 NETWORK_DNS、NETWORK_CONNECT、NETWORK_TLS、NETWORK_IO、TIMEOUT 或 CANCELLED；不导出异常消息或原因链。
- 在网络事件里只记录阶段枚举，不记录域名、IP、代理地址或证书信息。
- 保留原领域错误：NETWORK、TIMEOUT、PROTOCOL、AUTHENTICATION、AUTHORIZATION、RATE_LIMIT 等。HTTP 状态和具体故障类别只进入封闭结构的诊断。
- HTTP 200 的 JSON 语法错误为 PROTOCOL_JSON；目录结构/字段错误为 PROTOCOL_MODEL_CATALOG；不会伪装成 NETWORK。
- 每次显式加载只保留一份内存诊断：令牌可用、请求创建/启动、HTTP 响应/状态、正文读取、JSON 解析、models 数组、可见数量、成功解析数量和固定故障分类。没有新增诊断文件存储、日志或远端上传。
- 取消后冻结终态，避免迟到回调覆盖结果。请求仍无自动重试、重定向、认证器重放或模型/API-key 回退；TLS 校验未削弱。
- 用户追加要求的汉化：按钮、登录状态、错误提示、隐私说明、对话状态、诊断字段均使用中文。服务返回的模型显示名、聊天正文不改写。诊断保留固定英文枚举以便排障，并解释 true/false/NONE。
- 在原滚动区域显示诊断，不重设计界面；不新增自动网络操作。忽略 Kotlin 生成缓存目录。

模型目录契约按 [OpenAI 官方 Models and inference 文档](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)核对：继续使用 SIWC 令牌访问 `/v1/models`，读取 `models[]`，仅展示 `visibility == "list"` 的项，保持服务顺序；显示 `display_name`，选择 `slug`。不使用普通 API-key 的 data[] 格式作为回退，不猜测模型能力。非字符串 slug/name、非法或重复 slug 仍拒绝。

## 4. 本地验证（全部 SYNTHETIC）

新增独立 `ModelCatalogNetworkTests.kt`，不修改 FoundationTests.kt。

24 项新测试覆盖：DNS、连接/路由、TLS/证书类型、超时、通用 IO 的分类和领域映射；HTTP 401、403/insufficient_scope、429；无效 JSON、缺少/错误 models 数组、非对象项、缺字段、数字/非法/重复 slug；正常目录、可见过滤、顺序、空目录；无凭据不发请求；正文中断、大小边界；确定性取消；敏感合成标记不进入诊断；中文错误提示不回显任意输入。

网络异常类型使用本地合成异常测试；HTTP/正文/解析/取消使用本地 MockWebServer。**没有进行真实 DNS/TLS 故障注入或任何自动真实提供方请求。**

| 验证 | 结果 |
| --- | --- |
| Debug 全量单元测试 | 68/68 PASS，0 failure/error/skipped |
| Release 全量单元测试 | 68/68 PASS，0 failure/error/skipped |
| assembleDebug | PASS |
| lintDebug | PASS，0 errors，1 warning |
| 冻结规格 SHA-256 | 与原值相同：2F64D6C93FEF56184A4EA38B819389397C920B2D4268D888683D3033C0116A78 |

复用 JDK 17、已有 Gradle 8.13、SDK 35 与锁定缓存，离线构建。以单次 Gradle 进程内 Kotlin 编译避免本机编译守护进程目录权限问题，不修改工程编译配置。命令选择 Debug/Release 全量测试、assembleDebug、lintDebug；不是仅运行筛选测试或跳过测试。

## 5. 新版真机诊断

已收到用户首次中文诊断版截图 `Screenshot_20261002_154545.jpg`（手机显示 15:45）；REAL DEVICE / REAL PROVIDER 元数据如下。只转录闭合字段，不保存正文或凭据。

| 字段 | 本次真实观测 |
| --- | --- |
| token available / request created / request started | true / true / true |
| HTTP response received / status | true / 200 |
| body read completed / JSON parsed / models array found | false / false / false |
| visible / parsed model count | 0 / 0（尚未解析，不代表账号没有模型） |
| failure stage | BODY_READ |
| transport category | NONE |
| protocol category / result category | BODY_LIMIT / PROTOCOL |

结论：此次请求的网络路径可达 HTTP 200，未观测到网络异常；本机解压后正文读取超过旧 262,144 字节上限。阻塞发生在 JSON 解析之前，不是已证实的目录字段兼容问题，也不是证据支持的 VPN 失败。不能从这次观测推广“所有网络/VPN 场景都正常”，或声称目录 JSON 已验证。

### 独立正文策略修正后的真实重测

REAL DEVICE / REAL PROVIDER：用户返回“可以加载模型，模型可以选择”，并提供 `Screenshot_20261002_160134.jpg`（手机显示 16:01）。应用显示已连接 ChatGPT 套餐并能选择模型；脱敏元数据如下：

| 字段 | 修正后真实观测 |
| --- | --- |
| token available / request created / request started | true / true / true |
| HTTP response received / status | true / 200 |
| body read completed / JSON parsed / models array found | true / true / true |
| visible / parsed model count | 5 / 5 |
| failure stage / transport category / protocol category | NONE / NONE / NONE |
| result category | NONE（封闭错误枚举的无错误值） |
| MODEL CATALOG acceptance | PASS（满足 HTTP、解析与正数量验收） |

在现有诊断中 NONE 表示无错误，不改枚举或强制结果来获得 PASS。没有原始模型 JSON、响应正文、账号或凭据值进入报告。

用户另外自行发送了聊天，反馈“服务响应格式不兼容”；截图显示助手失败，而目录诊断仍全部成功。只记录分类 PROTOCOL，不转录聊天内容。该问题不属于本次模型目录修复的通过范围；其 HTTP 状态、SSE/事件结构及准确失败点尚未收集，不能猜测为网络、模型或服务方原因。助手没有发送、重放或继续测试聊天请求。

最初尝试 `adb install -r` 保留数据，但设备返回 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（签名不同）。只读 APK 证书比较确认已安装应用与旧生产 APK 签名相同、新构建签名不同；这是安装环境问题，不是模型请求故障的原因。未通过降低安全性或覆盖签名解决。

用户随后明确要求“把旧的卸载了，直接给新版”，因此仅卸载正式包 `io.github.xiaomeng2568.meldwise`，不卸载 Host A/B。卸载和新版安装均返回 Success，旧本机加密凭据、安装身份和对话随应用数据删除，不能从应用内恢复。未自动启动 OAuth、刷新、模型目录或资源请求。

诊断 APK：`artifacts/Meldwise-P2-Model-Catalog-ZH-Diagnostic.apk`；SHA-256：`5CF64D1CA88FBBD5010B1F90B85265F5E3F88CA120D4D136831B5AE6DB801017`。二进制 APK 不进入 Git。

用户操作：重新安装导致登录状态不保留，需要用户手动连接 ChatGPT。授权完成后确认“已连接 · ChatGPT 套餐”，仅点击“加载模型”一次，不发送聊天内容。助手不自动重新授权或代发请求。现有 TokenManager 在显式请求中可能因令牌临近到期触发既有续期逻辑；该逻辑没有为本任务新增或更改。

收到结果后，仅记录固定诊断字段，不收集账号、凭据、回调 URL、响应正文或原始模型 JSON。不要为补证反复请求。

阶段说明：CREDENTIAL = 取得凭据；DNS = 域名解析；CONNECT = 网络连接；TLS = 安全连接；HTTP = 请求/服务状态；BODY_READ = 正文读取；JSON = JSON 语法；MODEL_CATALOG = 模型目录结构。NONE 表示无失败。

## 6. 结论与限制

MODEL CATALOG：**PASS within tested scope**。首轮真实根因为本机 BODY_LIMIT；修正版真实重测收到 HTTP 200，正文/JSON/models 均完成，5 个可见模型全部解析，故障类别均 NONE。正文预算修正已通过本次真实目录验收。

没有硬编码模型来绕过发现，没有关闭证书/主机名校验，没有新增 API-key 路径或自动重试。只宣称本次模型目录真机 PASS，不宣称完整 Sprint 1 PASS 或 SIWC 生产就绪。聊天 PROTOCOL 问题保持未解决，需单独评审/授权排查；后续集成由人工决定。

## 7. 最小正文策略修正 / Operation-specific body policy

新增封闭的 `BoundedBodyPolicy`，只按已有操作枚举和 HTTP 状态决定字节上限，没有任意上限参数或无限缓冲选项。

| 响应类型 | 明确上限 |
| --- | --- |
| MODELS 且 HTTP 200 | 2 MiB = 2,097,152 字节 |
| 元数据 / 授权相关 / 令牌 / 续期等普通缓冲响应 | 原 256 KiB = 262,144 字节 |
| 模型请求 HTTP 非 200 的错误响应 | 原 256 KiB |
| 流式 Responses | 保持原 SSE 按行/帧限制，不整流缓冲 |

选择 2 MiB：已知真实目录超过 256 KiB，但没有记录其原始内容/完整大小；2 MiB 提供 8 倍、仍有限的元数据空间，避免仅增至 1 MiB 后无依据地多轮提供方试错。这是客户端安全预算，不是官方目录大小保证。限制实际读取、透明解压后的字节，不信任 Content-Length 或仅限制压缩体积；超过上限仍为 BODY_READ / BODY_LIMIT / PROTOCOL。最大字节数的等值边界允许通过，超过一字节拒绝。

原目录校验仍最多 1,024 项，slug/name 规则及重复检测不变。原始字节的有限缓冲不等于应用总堆大小保证：UTF-8 解码、JSON 对象和临时字节副本仍有开销。没有为此更改网络/TLS、认证、刷新、持久化、UI、模型契约或 SSE 读取路径。

增加本地边界测试：普通正文等于/超过 256 KiB；只成功目录获得额外空间；目录错误响应保留保守上限；512 KiB 合法目录；恰好 2 MiB 合法目录；超过 2 MiB 拒绝；大型坏 JSON 和坏目录都为 PROTOCOL；gzip 解压后超限仍拒绝；各类模型诊断不导出合成正文标记。

本次已完成 Debug / Release 全量单元测试：各 77/77 PASS，0 failure/error/skipped；assembleDebug PASS；lintDebug PASS（0 errors、1 条既有 UseKtx warning）。相比第一轮增加 9 项测试，共 33 项模型目录测试；原 44 项基础回归均通过。自动化仅访问本地模拟服务。

新版 APK：`artifacts/Meldwise-P2-Model-Catalog-ZH-BodyPolicy-v2.apk`。构建时已比较签名，确认与上一版已安装诊断 APK 相同。第一次 `adb install -r` 被设备以 `INSTALL_FAILED_ABORTED: User rejected permissions` 拒绝；用户确认解锁后，第二次覆盖安装返回 Success。这是安装确认的重试，不是提供方/模型请求重试；没有卸载或清数据。用户真实重测已返回目录 PASS，当前登录数据保留，未重新授权。按用户要求，仅提交/推送 `fix/p2-model-catalog-network`；不合并、不推送 main、不修改生产分支或现有 Draft PR。远端交付以提交 SHA 和实际 push/远端 HEAD 确认为准。

新版 APK SHA-256：`11A0614666E0EE084CBB43C55E91499AC739226D6AD458ED2AEB3D70BFBEF26E`。

### 更新说明（中文）

修复模型目录超过 256 KiB 时被本机读取上限阻塞的问题：仅成功的模型目录响应允许最多 2 MiB，其余普通/错误响应维持 256 KiB。保留 BODY_LIMIT、安全校验、有限 SSE 解析和脱敏诊断；加入大小边界与大目录回归测试。Debug/Release 各 77 项测试通过，构建和静态检查通过；真机 HTTP 200、5/5 模型解析成功，MODEL CATALOG PASS。聊天协议错误仍待单独排查，不宣称完整 Sprint 1 或生产就绪。

### Update summary (English)

Fix the client-side body limit blocking SIWC model discovery: only successful model-catalog responses may read up to 2 MiB; ordinary and error responses retain the 256 KiB limit. BODY_LIMIT failures, TLS validation, bounded SSE parsing and sanitized diagnostics remain intact. Add large-catalog and boundary regression tests. Debug and Release each pass 77 tests; build and lint pass. The real-device rerun receives HTTP 200 and parses all five visible models: MODEL CATALOG PASS. The separately reported chat protocol error remains unresolved; this is not full Sprint 1 acceptance or a production-readiness claim.
