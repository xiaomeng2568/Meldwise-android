# Meldwise Production Foundation Sprint 1 Report

记录日期：2026-10-02（Asia/Shanghai）。结论：**Sprint 1 基础实现及本地工程验证完成；正式真机验收待执行。不是生产发布批准。**

Phase 1 决策：GO TO PRODUCTION IMPLEMENTATION；SIWC compatibility：CONDITIONAL，二者不互相替代。

## 1. 工程隔离与环境

- 独立工作目录：P2-Production-Foundation（机器本地绝对路径不纳入版本管理）。
- 独立本地 checkout，分支 production/foundation-sprint-1；从已提交基线 dccaab36328c60975e5515198ef680a5413039a2 建立，没有复制旧工程未提交的 spike 架构。
- JDK 17 / Gradle 8.13 / AGP 8.9.2 / Kotlin 2.1.20；compile/target SDK 35，min SDK 26。既有 SDK 未重新安装。项目与 SDK 路径均为无空格 ASCII 路径。
- 单 app 模块、Kotlin + Compose Material 3、手动依赖组装；无 Room/Hilt。
- 用户许可从官方 Google/Maven Central 下载工程依赖。版本目录与依赖锁已生成，Gradle Wrapper 使用官方 SHA-256 校验值。
- 原始冻结标准文件未改动，SHA-256：2F64D6C93FEF56184A4EA38B819389397C920B2D4268D888683D3033C0116A78。
- 旧工程既有修改和所有 Gate-1-Report 文件保留。没有远程推送、手机安装、生产发布或提供商账号操作。

## 2. 已实现内容

| 用户要求 | 本次实现 | 边界 |
| --- | --- | --- |
| 干净 production branch | 独立 checkout 和 production/foundation-sprint-1 | 不带入旧脏目录 |
| 正式 Auth | OAuthCoordinator、PKCE、loopback、可信 discovery、ID token/JWKS、TokenManager、scopes、ReauthRequired | 单选账号、单进程；无客户端 secret/API key |
| Credential | Android Keystore AES-256-GCM、AtomicFile、fsync、完整记录 readback、刷新前事务标记 | 旋转结果未知则隔离并要求重新授权，不重放旧 RT |
| 备份/恢复 | noBackupFilesDir、备份/设备转移排除、allowBackup=false | OEM/实际断电行为仍需真机验证 |
| Provider domain | LlmProvider / LlmRequest / LlmEvent / LlmError / ProviderCapability | 只实现 ChatGPT Plan 文本流适配器，不做 DeepSeek/协作层 |
| Network | OkHttp、大小受限 SSE、超时、取消、脱敏诊断 | 无自动连接重试、POST 重放、模型/API-key 回退 |
| 最小 UI | Connect/status/catalog/selector/Single chat/Cancel | 模型加载、选择、发送均需明确操作；无自动请求 |
| 本地会话恢复 | 独立密钥加密单会话 journal、UUIDv7、parentMessageId、200 ms 周期提交与最终提交 | 重启后的 Pending/Streaming 标为 Incomplete，不自动恢复请求 |

OpenAI Docs 用于确定协议、模型目录字段及信任边界；不是把 spike 类改名后作为生产代码。官方 Models 目录的 models/visibility/display_name/slug 被正确使用；不发送当前 SIWC preview 不支持的 temperature/top_p/max_output_tokens 等参数。[协议与来源详情](security-and-recovery.md)。

## 3. 实际验证结果

最终对锁定依赖执行离线全量检查，退出码 0，BUILD SUCCESSFUL：

| 检查 | Debug | Release |
| --- | --- | --- |
| APK 构建 | PASS | PASS，unsigned，仅工程验证 |
| JVM 单元/本机模拟服务测试 | 44/44 PASS，0 failure/error | 44/44 PASS，0 failure/error |
| Android lint | 0 error / 15 warning | 0 error / 9 warning |

另：Android instrumentation APK 已编译，**未在手机运行**。CI workflow 已配置，**未在远端执行**。

44 项 JUnit 测试由 FoundationTests（34）、ProviderTransportTests（6）、SecurityBoundaryTests（4）组成。全部数据为合成数据；MockWebServer 只监听本机测试接口。覆盖：AES-GCM 新 nonce/篡改/AAD、完整记录持久化、存储失败不发布、3 调用者单 owner、排队失败不重试、冷加载过期检查、invalid_grant、未知旋转隔离、未完成事务恢复、取消、scope 限制、账号连续性、安装身份复用、SSE framing/大小/UTF-8、终态空 output 的流提取、错误 role/type/text、partial journal、闭合诊断、HTTP 取消/跳转、PKCE RFC 向量、错误 state、JWT 声明/签名/未知 key 刷新、一次资源 POST、401 不重放、EOF Incomplete、参数拒绝、能力交集及备份/进程/日志静态边界。另专门验证 503 Retry-After: 0 不产生隐式第二次请求，以及请求 body 的 one-shot 标记。

JUnit/HTML 原始报告位于 app/build/test-results 和 app/build/reports。静态扫描不是动态“不泄漏”证明；本地单元测试不是实际 Android Keystore、断电或提供商行为证据。

剩余 lint 警告为锁定依赖的可升级提示与 String.toUri 风格建议，没有压制安全错误。升级提示不代表本轮完成依赖安全审计。Gradle 还提示插件存在面向 Gradle 9 的弃用行为；本轮固定 Gradle 8.13。

## 4. 下载/修复记录

- 初次编译发现 SSE StringBuilder.dropLast 返回 CharSequence 与 String 不匹配，已修正并重跑。
- 首轮依赖下载遇到 jsr305 2.0.2 的 TLS handshake 错误；没有降低 TLS/关闭证书校验。测试注解依赖显式固定为已有官方缓存的 3.0.2，剩余官方 lint 依赖随后正常下载。
- 首次离线 Release 检查因 lint-gradle 31.9.2 尚未缓存而停止；下载后最终离线 Debug/Release 全量验证通过。
- PowerShell 首次未加引号的 Gradle -D 超时参数被拆分成任务名，未执行构建；修正引号后重跑。
- 加固刷新等待者共享失败、跨线程取消可见性、空 terminal 输出读取、scope 拒绝处理、后台取消及周期持久化失败边界。所有最终源码均重新构建/测试。
- 防止 OkHttp 特定状态的 follow-up：POST body 标为 one-shot，503 的内部自动重试被禁止，网络 interceptor 拦截同一 call 的第二次 exchange。新增两项测试，最终离线全量检查再次通过。

## 5. 安装包与证据范围

应用包名 io.github.xiaomeng2568.meldwise，显示名称 Meldwise，版本 0.3-p2-foundation-sprint1。不再沿用 Gate 1C-B 安装包名称。

- artifacts/Meldwise-P2-Foundation-Sprint1-debug.apk
  - SHA-256：1A102E41BEF9DC62F522C4F2C29E01BEE301564075945C77A281B4F1CCA9DC60
- artifacts/Meldwise-P2-Foundation-Sprint1-release-unsigned.apk
  - SHA-256：12204593088A535926B4A0B5D353D090F0066AAC8364AE29CA76511FE8F0401E

安装包属于验证构建，Release 未签署生产签名，不是可发布版本。APK 不进入 Git；源码、依赖锁、工作流、报告进入本地生产分支。构建哈希对应本次完成的构建，不保证重新构建字节完全一致。

本轮真实提供商 authorization/refresh/resource/model 请求数：**0**。没有采集真实 token、回调 URL、账号身份或响应正文。

## 6. 未完成的验收 / 下一步

正式代码的真实 authorization -> ID validation -> encrypted persistence -> 重开恢复 -> 一次 resource invocation 尚未验证；Keystore instrumentation、OEM backup exclusion、真实 process death/commit、独立安全与合规评审亦未完成。

Phase 1 的四项 NOT TESTED 继续保留：两台物理设备、长时间自然过期、真实提供商旋转期间网络中断、跨进程刷新协调。单进程生产基础不能把这些空缺改为 PASS。

建议先执行[本地设备与正式链路验收清单](production-device-validation.md)，任何提供商调用需另行确认。停在 Sprint 1 交付，不自动开展下一个功能冲刺或发布。
