package io.github.xiaomeng2568.meldwise.provider

import io.github.xiaomeng2568.meldwise.network.SseFrame
import kotlinx.serialization.json.*

/** Stream output items, rather than a possibly empty completed envelope, own assistant extraction. */
class ResponsesReader {
    private val assistants=mutableSetOf<Int>()
    private val items=mutableMapOf<Int,String>()
    private val text=mutableMapOf<Pair<Int,Int>,StringBuilder>()
    var terminal=false; private set
    var produced=false; private set
    private var chars=0
    fun consume(frame:SseFrame):List<LlmEvent> {
        if(terminal) return emptyList()
        val obj=Json.parseToJsonElement(frame.data).jsonObject
        val named=frame.event
        val typed=obj["type"]?.jsonPrimitive?.content
        require(named==null || typed==null || named==typed) { "EVENT_TYPE_CONFLICT" }
        val type=typed ?: named ?: error("EVENT_TYPE_MISSING")
        fun index()=obj["output_index"]?.jsonPrimitive?.int ?: error("INDEX_MISSING")
        fun key()=index() to (obj["content_index"]?.jsonPrimitive?.int ?: error("CONTENT_INDEX_MISSING"))
        fun append(key:Pair<Int,Int>,value:String):List<LlmEvent> {
            require(key.first in assistants && key.first>=0 && key.second>=0) { "ASSISTANT_ITEM_REQUIRED" }
            obj["item_id"]?.jsonPrimitive?.content?.let { require(items[key.first]==it) { "ITEM_CONFLICT" } }
            require(chars+value.length<=4_194_304) { "OUTPUT_LIMIT" }
            chars+=value.length; text.getOrPut(key) { StringBuilder() }.append(value)
            if(value.isEmpty()) return emptyList()
            produced=true; return listOf(LlmEvent.TextDelta(value))
        }
        return when(type) {
            "response.created","response.in_progress","response.content_part.added","response.content_part.done"->emptyList()
            "response.output_item.added","response.output_item.done"->{
                val item=obj["item"]?.jsonObject ?: error("ITEM_MISSING")
                val i=index(); require(i>=0 && i<=1024)
                if(item["type"]?.jsonPrimitive?.content=="message" && item["role"]?.jsonPrimitive?.content=="assistant") {
                    val id=item["id"]?.jsonPrimitive?.content ?: error("ITEM_ID_MISSING")
                    require(items[i]==null || items[i]==id)
                    assistants+=i; items[i]=id
                }
                emptyList()
            }
            "response.output_text.delta"->append(key(),requireNotNull(obj["delta"]).jsonPrimitive.content)
            "response.output_text.done"->{
                val k=key(); val done=requireNotNull(obj["text"]).jsonPrimitive.content
                val seen=text[k]?.toString()
                if(seen==null) append(k,done) else { require(seen==done) { "TEXT_CONFLICT" }; emptyList() }
            }
            "response.completed"->{
                val response=requireNotNull(obj["response"]).jsonObject
                require(response["status"]?.jsonPrimitive?.content=="completed")
                require(!response["id"]?.jsonPrimitive?.content.isNullOrBlank())
                val events=mutableListOf<LlmEvent>()
                if(!produced) {
                    response["output"]?.jsonArray?.forEachIndexed { i,item ->
                        val message=item.jsonObject
                        if(message["role"]?.jsonPrimitive?.content=="assistant" && message["type"]?.jsonPrimitive?.content=="message") {
                            assistants+=i
                            message["content"]?.jsonArray?.forEachIndexed { j,part ->
                                val content=part.jsonObject
                                if(content["type"]?.jsonPrimitive?.content=="output_text") events+=append(i to j,requireNotNull(content["text"]).jsonPrimitive.content)
                            }
                        }
                    }
                }
                require(produced) { "ASSISTANT_OUTPUT_TEXT_NOT_DETECTED" }
                val usage=response["usage"]?.takeIf { it is JsonObject }?.jsonObject?.let {
                    Usage(it["input_tokens"]?.jsonPrimitive?.longOrNull,it["output_tokens"]?.jsonPrimitive?.longOrNull,it["total_tokens"]?.jsonPrimitive?.longOrNull) }
                terminal=true; events+LlmEvent.Completed(usage)
            }
            "response.failed","response.incomplete","error"->{
                terminal=true
                val code=obj["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
                    ?: obj["response"]?.jsonObject?.get("error")?.takeIf { it is JsonObject }?.jsonObject?.get("code")?.jsonPrimitive?.content
                val error=ProviderErrors.map(200,code,produced)
                listOf(if(produced) LlmEvent.Incomplete(error) else LlmEvent.Failed(error))
            }
            "response.refusal.delta","response.refusal.done"->{terminal=true;listOf(LlmEvent.Failed(LlmError(ErrorKind.CONTENT_REJECTED)))}
            else->emptyList() // Unused reasoning/metadata events are not treated as assistant text.
        }
    }
}
object ProviderErrors {
    fun map(status:Int,code:String?,produced:Boolean=false):LlmError {
        val kind=when(code) {
            "insufficient_quota","plan_usage_limit","usage_limit_reached"->ErrorKind.PLAN_USAGE_LIMIT
            "context_length_exceeded"->ErrorKind.CONTEXT_OVERFLOW
            "model_not_found","model_not_available"->ErrorKind.MODEL_UNAVAILABLE
            "insufficient_scope"->ErrorKind.AUTHORIZATION
            else->when(status) { 401->ErrorKind.AUTHENTICATION;403->ErrorKind.AUTHORIZATION;429->ErrorKind.RATE_LIMIT
                in 500..599->ErrorKind.SERVER;else->ErrorKind.PROTOCOL }
        }
        return LlmError(kind,isRetryable=kind in setOf(ErrorKind.SERVER,ErrorKind.RATE_LIMIT),
            requiresReauth=kind==ErrorKind.AUTHENTICATION || code=="insufficient_scope",mayHaveProducedOutput=produced)
    }
}
