package io.github.xiaomeng2568.meldwise.provider

import io.github.xiaomeng2568.meldwise.network.*
import kotlinx.serialization.json.*

internal class ResponseProtocolFailure(val stage:InferenceStage,val protocol:InferenceProtocol):Exception(protocol.name)

/** Stream output items, rather than a possibly empty completed envelope, own assistant extraction. */
class ResponsesReader(private val trace:InferenceTrace=InferenceTrace()) {
    private val assistants=mutableSetOf<Int>()
    private val items=mutableMapOf<Int,String>()
    private val text=mutableMapOf<Pair<Int,Int>,StringBuilder>()
    var terminal=false; private set
    var produced=false; private set
    private var chars=0
    private fun fail(protocol:InferenceProtocol,stage:InferenceStage=InferenceStage.EVENT_STRUCTURE):Nothing =
        throw ResponseProtocolFailure(stage,protocol)
    private fun check(ok:Boolean,protocol:InferenceProtocol,stage:InferenceStage=InferenceStage.EVENT_STRUCTURE) {
        if(!ok) fail(protocol,stage)
    }
    private fun string(obj:JsonObject,name:String):String? =
        (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun append(key:Pair<Int,Int>,value:String,itemId:String?):List<LlmEvent> {
        check(key.first in assistants && key.first in 0..1024 && key.second in 0..1024,
            InferenceProtocol.ASSISTANT_ITEM_REQUIRED,InferenceStage.ASSISTANT_ASSOCIATION)
        if(itemId!=null) check(items[key.first]==itemId,InferenceProtocol.ITEM_CONFLICT,InferenceStage.ASSISTANT_ASSOCIATION)
        check(value.length<=4_194_304-chars,InferenceProtocol.OUTPUT_LIMIT,InferenceStage.TEXT_EXTRACTION)
        chars+=value.length; text.getOrPut(key) { StringBuilder() }.append(value)
        if(value.isEmpty()) return emptyList()
        produced=true; trace.update { it.copy(assistantTextProduced=true) }
        return listOf(LlmEvent.TextDelta(value))
    }
    private fun finalized(key:Pair<Int,Int>,value:String,itemId:String?):List<LlmEvent> {
        // A done event is a full value, never an additional delta. Keep strict conflict rejection.
        val seen=text[key]?.toString()
        if(seen==null) return append(key,value,itemId)
        check(key.first in assistants,InferenceProtocol.ASSISTANT_ITEM_REQUIRED,InferenceStage.ASSISTANT_ASSOCIATION)
        if(itemId!=null) check(items[key.first]==itemId,InferenceProtocol.ITEM_CONFLICT,InferenceStage.ASSISTANT_ASSOCIATION)
        check(seen==value,InferenceProtocol.TEXT_CONFLICT,InferenceStage.TEXT_EXTRACTION)
        return emptyList()
    }
    private fun assistant(item:JsonObject,index:Int):Boolean {
        check(index in 0..1024,InferenceProtocol.INDEX_MISSING)
        if(string(item,"type")!="message" || string(item,"role")!="assistant") {
            check(index !in assistants,InferenceProtocol.ITEM_CONFLICT,InferenceStage.ASSISTANT_ASSOCIATION)
            return false
        }
        val id=string(item,"id")?.takeIf { it.isNotBlank() } ?: fail(InferenceProtocol.ITEM_ID_MISSING)
        check(items[index]==null || items[index]==id,InferenceProtocol.ITEM_CONFLICT,InferenceStage.ASSISTANT_ASSOCIATION)
        assistants+=index; items[index]=id
        trace.update { it.copy(assistantOutputItemSeen=true) }
        return true
    }
    private fun content(item:JsonObject,index:Int):List<LlmEvent> {
        val parts=item["content"] ?: return emptyList()
        if(parts !is JsonArray) fail(InferenceProtocol.UNKNOWN_EVENT_STRUCTURE)
        check(parts.size<=1025,InferenceProtocol.OUTPUT_LIMIT,InferenceStage.TEXT_EXTRACTION)
        return parts.flatMapIndexed { j,part ->
            val obj=part as? JsonObject ?: fail(InferenceProtocol.UNKNOWN_EVENT_STRUCTURE)
            if(string(obj,"type")=="output_text") finalized(index to j,
                string(obj,"text") ?: fail(InferenceProtocol.UNKNOWN_EVENT_STRUCTURE),items[index]) else emptyList()
        }
    }
    fun consume(frame:SseFrame):List<LlmEvent> {
        if(terminal) return emptyList()
        val root=try { Json.parseToJsonElement(frame.data) } catch(_:Exception) {
            fail(InferenceProtocol.JSON_INVALID,InferenceStage.EVENT_JSON)
        }
        val obj=root as? JsonObject ?: fail(InferenceProtocol.UNKNOWN_EVENT_STRUCTURE)
        try { return consumeObject(frame.event,obj) }
        catch(failure:ResponseProtocolFailure) { throw failure }
        catch(_:Exception) { fail(InferenceProtocol.UNKNOWN_EVENT_STRUCTURE) }
    }
    private fun consumeObject(named:String?,obj:JsonObject):List<LlmEvent> {
        val typed=string(obj,"type")
        trace.event(typed ?: named ?: "")
        if(obj.containsKey("type") && typed==null) fail(InferenceProtocol.UNKNOWN_EVENT_STRUCTURE)
        check(named==null || typed==null || named==typed,InferenceProtocol.EVENT_TYPE_CONFLICT)
        val type=typed ?: named ?: fail(InferenceProtocol.EVENT_TYPE_MISSING)
        fun index()=(obj["output_index"] as? JsonPrimitive)?.intOrNull ?: fail(InferenceProtocol.INDEX_MISSING)
        fun key()=index() to ((obj["content_index"] as? JsonPrimitive)?.intOrNull ?: fail(InferenceProtocol.CONTENT_INDEX_MISSING))
        fun itemId():String? {
            if(obj["item_id"]==null) return null
            return string(obj,"item_id") ?: fail(InferenceProtocol.ITEM_ID_MISSING)
        }
        return when(type) {
            "response.created","response.in_progress","response.content_part.added"->emptyList()
            "response.content_part.done"->{
                val part=obj["part"] as? JsonObject ?: fail(InferenceProtocol.UNKNOWN_EVENT_STRUCTURE)
                if(string(part,"type")=="output_text") finalized(key(),
                    string(part,"text") ?: fail(InferenceProtocol.UNKNOWN_EVENT_STRUCTURE),itemId()) else emptyList()
            }
            "response.output_item.added","response.output_item.done"->{
                val item=obj["item"] as? JsonObject ?: fail(InferenceProtocol.ITEM_MISSING)
                val i=index()
                if(assistant(item,i) && type=="response.output_item.done") content(item,i) else emptyList()
            }
            "response.output_text.delta"->append(key(),string(obj,"delta") ?: fail(InferenceProtocol.UNKNOWN_EVENT_STRUCTURE),itemId())
            "response.output_text.done"->finalized(key(),string(obj,"text") ?: fail(InferenceProtocol.UNKNOWN_EVENT_STRUCTURE),itemId())
            "response.completed"->{
                val response=obj["response"] as? JsonObject ?: fail(InferenceProtocol.UNKNOWN_EVENT_STRUCTURE)
                check(string(response,"status")=="completed",InferenceProtocol.TERMINAL_STATUS_INVALID,InferenceStage.TERMINAL_VALIDATION)
                check(!string(response,"id").isNullOrBlank(),InferenceProtocol.TERMINAL_ID_MISSING,InferenceStage.TERMINAL_VALIDATION)
                val events=mutableListOf<LlmEvent>()
                if(!produced) {
                    response["output"]?.jsonArray?.forEachIndexed { i,item ->
                        val message=item.jsonObject
                        if(assistant(message,i)) events+=content(message,i)
                    }
                }
                check(produced,InferenceProtocol.ASSISTANT_OUTPUT_TEXT_NOT_DETECTED,InferenceStage.TERMINAL_VALIDATION)
                val usage=response["usage"]?.takeIf { it is JsonObject }?.jsonObject?.let {
                    Usage(it["input_tokens"]?.jsonPrimitive?.longOrNull,it["output_tokens"]?.jsonPrimitive?.longOrNull,it["total_tokens"]?.jsonPrimitive?.longOrNull) }
                terminal=true; trace.finish(success=true)
                events+LlmEvent.Completed(usage)
            }
            "response.failed","response.incomplete","error"->{
                val code=(obj["error"] as? JsonObject)?.let { string(it,"code") }
                    ?: (obj["response"] as? JsonObject)?.get("error")?.let { it as? JsonObject }?.let { string(it,"code") }
                    ?: if(type=="error") string(obj,"code") else null
                val error=ProviderErrors.map(200,code,produced)
                terminal=true; trace.update { it.copy(providerCode=providerCode(code)) }
                trace.finish(InferenceStage.PROVIDER_FAILED,result=error.kind)
                listOf(if(produced) LlmEvent.Incomplete(error) else LlmEvent.Failed(error))
            }
            "response.refusal.delta","response.refusal.done"->{
                terminal=true;trace.finish(InferenceStage.PROVIDER_FAILED,result=ErrorKind.CONTENT_REJECTED)
                listOf(LlmEvent.Failed(LlmError(ErrorKind.CONTENT_REJECTED)))
            }
            else->emptyList() // Unused reasoning/metadata events are not assistant text.
        }
    }
}

internal fun providerCode(code:String?):ProviderCode=when(code) {
    null->ProviderCode.NONE
    "subscription_sharing_invalid_user"->ProviderCode.AUTHENTICATION
    "insufficient_scope","chatpass_v2_scope_not_authorized","chatpass_v2_invalid_authorization_context",
    "subscription_sharing_route_not_supported"->ProviderCode.AUTHORIZATION
    "insufficient_quota","plan_usage_limit","usage_limit_reached","subscription_sharing_usage_limit_exceeded"->ProviderCode.PLAN_USAGE_LIMIT
    "rate_limit_exceeded"->ProviderCode.RATE_LIMIT
    "context_length_exceeded"->ProviderCode.CONTEXT_OVERFLOW
    "model_not_found","model_not_available"->ProviderCode.MODEL_UNAVAILABLE
    "subscription_sharing_unsupported_capability"->ProviderCode.UNSUPPORTED_CAPABILITY
    "subscription_sharing_user_not_eligible"->ProviderCode.POLICY_UNAVAILABLE
    "subscription_sharing_usage_unavailable","subscription_sharing_user_unavailable","server_error"->ProviderCode.SERVER
    else->ProviderCode.UNKNOWN
}
object ProviderErrors {
    fun map(status:Int,code:String?,produced:Boolean=false):LlmError {
        val kind=when(providerCode(code)) {
            ProviderCode.AUTHENTICATION->ErrorKind.AUTHENTICATION
            ProviderCode.AUTHORIZATION,ProviderCode.POLICY_UNAVAILABLE->ErrorKind.AUTHORIZATION
            ProviderCode.PLAN_USAGE_LIMIT->ErrorKind.PLAN_USAGE_LIMIT
            ProviderCode.RATE_LIMIT->ErrorKind.RATE_LIMIT
            ProviderCode.CONTEXT_OVERFLOW->ErrorKind.CONTEXT_OVERFLOW
            ProviderCode.MODEL_UNAVAILABLE->ErrorKind.MODEL_UNAVAILABLE
            ProviderCode.UNSUPPORTED_CAPABILITY->ErrorKind.UNSUPPORTED_CAPABILITY
            ProviderCode.SERVER->ErrorKind.SERVER
            else->when(status) { 401->ErrorKind.AUTHENTICATION;403->ErrorKind.AUTHORIZATION;429->ErrorKind.RATE_LIMIT
                in 500..599->ErrorKind.SERVER; in 400..499->ErrorKind.UNKNOWN;else->ErrorKind.PROTOCOL }
        }
        return LlmError(kind,isRetryable=kind in setOf(ErrorKind.SERVER,ErrorKind.RATE_LIMIT),
            requiresReauth=kind==ErrorKind.AUTHENTICATION || code=="insufficient_scope",mayHaveProducedOutput=produced)
    }
}
