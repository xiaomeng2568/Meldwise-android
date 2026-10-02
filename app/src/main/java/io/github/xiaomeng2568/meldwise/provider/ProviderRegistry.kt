package io.github.xiaomeng2568.meldwise.provider

import kotlinx.serialization.Serializable

object ProviderIds { const val CHATGPT="chatgpt"; const val DEEPSEEK="deepseek" }
@Serializable data class ModelRef(val providerId:String,val modelId:String)

/** Exact lookup only: an unavailable provider never selects another provider. */
class ProviderRegistry(providers:List<LlmProvider>) {
    val all:List<LlmProvider> = providers.toList()
    private val byId=all.associateBy { it.id }
    init { require(byId.size==all.size); require(all.all { it.id in setOf(ProviderIds.CHATGPT,ProviderIds.DEEPSEEK) }) }
    fun get(id:String):LlmProvider = byId[id] ?: throw ProviderFailure(LlmError(ErrorKind.UNSUPPORTED_CAPABILITY))
    suspend fun ready(ref:ModelRef):Boolean = get(ref.providerId).validateConnection()==ProviderStatus.READY
}
