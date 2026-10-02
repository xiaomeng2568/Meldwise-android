package io.github.xiaomeng2568.meldwise.ui

/** Fixed local labels, never provider error text or exception messages. */
fun errorLabel(code:String):String = when(code) {
    "NETWORK"->"网络连接失败"
    "TIMEOUT"->"请求超时"
    "PROTOCOL"->"服务响应格式不兼容"
    "AUTHENTICATION","TOKEN_REJECTED"->"登录凭据已失效，请重新连接"
    "AUTHORIZATION","SCOPE_CHANGED"->"当前授权权限不足"
    "SIGNED_OUT"->"已在本机断开连接"
    "INVALID_REFRESH","INVALID_CLIENT"->"登录凭据无法续期，请重新连接"
    "INTERRUPTED_REFRESH","UNCERTAIN_ROTATION"->"登录续期中断，请重新连接"
    "IDENTITY_INVALID","ACCOUNT_MISMATCH"->"登录身份校验失败"
    "STORAGE","STORAGE_UNAVAILABLE","LOCAL_STORAGE_UNAVAILABLE"->"本机安全存储不可用"
    "CANCELLED"->"已取消"
    "BROWSER_UNAVAILABLE"->"无法打开浏览器"
    "REFRESH_TOO_EARLY"->"尚未到可续期时间，请稍后再试"
    "RATE_LIMIT"->"请求过于频繁，请稍后再试"
    "PLAN_USAGE_LIMIT"->"ChatGPT 套餐用量已达限制"
    "BILLING"->"账户计费受限"
    "CONTEXT_OVERFLOW"->"对话内容超过模型限制"
    "MODEL_UNAVAILABLE"->"所选模型当前不可用"
    "UNSUPPORTED_CAPABILITY"->"当前请求包含不支持的选项"
    "CONTENT_REJECTED"->"服务拒绝了此内容"
    "SERVER"->"服务暂时不可用"
    "STREAM_INTERRUPTED"->"回答传输中断，内容可能不完整"
    "AUTH_UNAVAILABLE"->"暂时无法登录"
    "MODEL_CATALOG_UNAVAILABLE"->"暂时无法加载模型列表"
    "LOCAL_STORAGE_OR_STREAM_FAILURE"->"本机存储或回答传输失败"
    else->"操作失败"
}

fun roleLabel(role:String):String = when(role) { "USER"->"你";"ASSISTANT"->"助手";"SYSTEM"->"系统";else->"消息" }
fun messageStateLabel(state:String):String = when(state) {
    "STREAMING"->"接收中";"COMPLETED"->"已完成";"INCOMPLETE"->"不完整";"FAILED"->"失败"
    "CANCELLED"->"已取消";else->"已保存"
}
