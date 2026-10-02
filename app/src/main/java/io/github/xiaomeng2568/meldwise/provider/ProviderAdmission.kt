package io.github.xiaomeng2568.meldwise.provider

import io.github.xiaomeng2568.meldwise.network.*
import kotlinx.serialization.json.*

internal data class ProviderAdmission(val shape:ProviderBodyShape,val code:ProviderCode,
    val error:LlmError,val scopeChanged:Boolean)

/** A bounded error-body prefix is inspected locally, never retained in diagnostics. */
internal fun inspectAdmission(status:Int,body:String):ProviderAdmission {
    // The JSON library accepts arbitrary unquoted top-level primitives. Check their JSON lexical form.
    val trimmed=body.trim()
    val validStart=trimmed.startsWith('{') || trimmed.startsWith('[') || trimmed.startsWith('"') ||
        trimmed in setOf("true","false","null") || trimmed.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))
    val root=if(validStart) runCatching { Json.parseToJsonElement(body) }.getOrNull() else null
    val obj=root as? JsonObject
    val error=obj?.get("error") as? JsonObject
    val shape=when {
        root==null->ProviderBodyShape.NON_JSON
        error!=null->ProviderBodyShape.STANDARD_ERROR_OBJECT
        obj?.containsKey("detail")==true->ProviderBodyShape.DETAIL_OBJECT
        else->ProviderBodyShape.OTHER_JSON
    }
    val code=(error?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content
    return ProviderAdmission(shape,providerCode(code),ProviderErrors.map(status,code),code=="insufficient_scope")
}
