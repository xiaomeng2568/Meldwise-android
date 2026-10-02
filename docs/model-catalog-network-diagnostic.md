# Phase 2 模型目录网络诊断与中文界面

日期：2026-10-02（Asia/Shanghai）。MODEL CATALOG：**CONDITIONAL**。

## 1. 边界与现有证据

独立分支 `fix/p2-model-catalog-network`，工作目录 `P2-Network-Diagnostic`，起点为生产分支提交 `b8dc1af3b92bb8d72cd2a274f20509ff33cb10d2`。不覆盖生产工作目录，不修改并发测试、TokenManager、OAuth、凭据存储、模型调用或冻结规格。未推送、合并或更改 Draft PR。

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

这属于**已验证的错误分类缺陷**，不是对本次真机具体故障的根因结论。本地模拟 HTTP 200 + 无效 JSON / 目录现在返回 PROTOCOL，不能据此宣称手机已经成功加载模型。

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

尚待用户执行新版唯一的一次“加载模型”并提供脱敏诊断。**未采集则不填造值**。

最初尝试 `adb install -r` 保留数据，但设备返回 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（签名不同）。只读 APK 证书比较确认已安装应用与旧生产 APK 签名相同、新构建签名不同；这是安装环境问题，不是模型请求故障的原因。未通过降低安全性或覆盖签名解决。

用户随后明确要求“把旧的卸载了，直接给新版”，因此仅卸载正式包 `io.github.xiaomeng2568.meldwise`，不卸载 Host A/B。卸载和新版安装均返回 Success，旧本机加密凭据、安装身份和对话随应用数据删除，不能从应用内恢复。未自动启动 OAuth、刷新、模型目录或资源请求。

诊断 APK：`artifacts/Meldwise-P2-Model-Catalog-ZH-Diagnostic.apk`；SHA-256：`5CF64D1CA88FBBD5010B1F90B85265F5E3F88CA120D4D136831B5AE6DB801017`。二进制 APK 不进入 Git。

用户操作：重新安装导致登录状态不保留，需要用户手动连接 ChatGPT。授权完成后确认“已连接 · ChatGPT 套餐”，仅点击“加载模型”一次，不发送聊天内容。助手不自动重新授权或代发请求。现有 TokenManager 在显式请求中可能因令牌临近到期触发既有续期逻辑；该逻辑没有为本任务新增或更改。

收到结果后，仅记录固定诊断字段，不收集账号、凭据、回调 URL、响应正文或原始模型 JSON。不要为补证反复请求。

阶段说明：CREDENTIAL = 取得凭据；DNS = 域名解析；CONNECT = 网络连接；TLS = 安全连接；HTTP = 请求/服务状态；BODY_READ = 正文读取；JSON = JSON 语法；MODEL_CATALOG = 模型目录结构。NONE 表示无失败。

## 6. 结论与限制

MODEL CATALOG：**CONDITIONAL**。本地分类与解析回归通过，但新版实际失败层或真实目录成功结果尚未收到。VPN 未被归因；是否到达 HTTP 和目录解析仍以新版真机诊断为准。

没有硬编码模型来绕过发现，没有关闭证书/主机名校验，没有新增 API-key 路径或自动重试。没有宣称完整 Sprint 1 PASS、SIWC 生产就绪或模型目录真机 PASS。后续集成由人工决定。
