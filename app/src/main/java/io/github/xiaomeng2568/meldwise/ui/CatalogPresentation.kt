package io.github.xiaomeng2568.meldwise.ui

import io.github.xiaomeng2568.meldwise.data.CatalogNotice
import io.github.xiaomeng2568.meldwise.data.CatalogNoticeKind
import io.github.xiaomeng2568.meldwise.network.CatalogProtocol
import io.github.xiaomeng2568.meldwise.network.TransportCategory
import io.github.xiaomeng2568.meldwise.provider.ErrorKind
import io.github.xiaomeng2568.meldwise.ui.presentation.providerLabel

val CatalogNotice.title: String
    get() = if (kind == CatalogNoticeKind.Warning) "模型缓存暂不可用" else
        "${providerLabel(providerId)} · " + if (kind == CatalogNoticeKind.Success) "模型列表已更新" else "模型加载失败"

/** Only fixed enums and numeric HTTP status enter this transient UI. */
val CatalogNotice.detail: String?
    get() = if (kind == CatalogNoticeKind.Success) null else buildList {
        diagnostic?.httpStatus?.let { add("HTTP $it") }
        add(when (error) {
            ErrorKind.AUTHENTICATION -> "需要重新连接"
            ErrorKind.AUTHORIZATION -> "权限不足"
            ErrorKind.RATE_LIMIT -> "请求太频繁"
            ErrorKind.SERVER -> "服务暂时不可用"
            ErrorKind.TIMEOUT -> "连接超时"
            ErrorKind.NETWORK -> "网络连接失败"
            ErrorKind.PROTOCOL -> "响应格式不兼容"
            ErrorKind.STORAGE -> "本地存储不可用"
            ErrorKind.MODEL_UNAVAILABLE -> "模型暂不可用"
            else -> "暂时无法加载"
        })
        diagnostic?.transportCategory?.takeIf { it != TransportCategory.NONE }?.let { add(it.name) }
        diagnostic?.protocolCategory?.takeIf { it != CatalogProtocol.NONE }?.let { add(it.name) }
    }.joinToString(" · ")
