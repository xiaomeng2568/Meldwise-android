package io.github.xiaomeng2568.meldwise.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable enum class ReasoningPreference { Off, Auto, Low, High, Max }
@Serializable enum class ReasoningContent { Summary, ProviderVisibleReasoning }
@Serializable enum class ReasoningPhase { Unavailable, Waiting, Streaming, Completed, Interrupted }
@Serializable class ReasoningRecord(val text:String="",val kind:ReasoningContent=ReasoningContent.Summary,
    val phase:ReasoningPhase=ReasoningPhase.Unavailable) {
    override fun toString()="ReasoningRecord(phase=$phase, content=[REDACTED])"
}
object ReasoningPolicy {
    // The current SIWC guide does not establish effort/summary admission for this exact route.
    fun supported(ref:ModelRef,preference:ReasoningPreference)=ref.providerId==ProviderIds.DEEPSEEK || preference==ReasoningPreference.Auto
    fun effort(providerId:String,preference:ReasoningPreference):String? {
        require(supported(ModelRef(providerId,"capability"),preference)) {"UNSUPPORTED_REASONING"}
        return when(preference) {ReasoningPreference.Auto->null;ReasoningPreference.Off->"none"
            ReasoningPreference.Low->"low";ReasoningPreference.High->"high";ReasoningPreference.Max->"max"}
    }
}
enum class ReasoningReadMode { None, DeepSeekVisible, OpenAiSummary }

/** Separate bounded reasoning channel. None never decodes provider reasoning into display content. */
class ReasoningReader(private val mode:ReasoningReadMode) {
    private val ids=mutableMapOf<Int,String>()
    private val parts=mutableMapOf<Pair<Int,Int>,StringBuilder>()
    private val finished=mutableSetOf<Pair<Int,Int>>()
    private var chars=0
    private fun str(o:JsonObject,k:String)=(o[k] as? JsonPrimitive)?.takeIf {it.isString}?.content
    fun consume(type:String,o:JsonObject):List<LlmEvent> {
        if(mode==ReasoningReadMode.None) return emptyList()
        fun bad():Nothing=throw ResponseProtocolFailure(io.github.xiaomeng2568.meldwise.network.InferenceStage.EVENT_STRUCTURE,
            io.github.xiaomeng2568.meldwise.network.InferenceProtocol.UNKNOWN_EVENT_STRUCTURE)
        fun emit(i:Int,j:Int,value:String,done:Boolean,itemId:String?):List<LlmEvent> {
            if(i !in 0..1024 || j !in 0..1024 || ids[i]==null || (itemId!=null && ids[i]!=itemId)) bad()
            val key=i to j;val previous=parts[key]
            if(previous==null && parts.size>=256) bad()
            if(key in finished && (!done || previous.toString()!=value)) bad()
            if(done && previous!=null) {
                if(previous.toString()!=value) bad()
                if(!finished.add(key)) return emptyList()
                return listOf(LlmEvent.ReasoningDone(kind))
            }
            if(value.length>MAX_CHARS-chars) bad()
            chars+=value.length;parts.getOrPut(key) {StringBuilder()}.append(value)
            if(done) finished+=key
            return buildList {
                if(value.isNotEmpty()) add(LlmEvent.ReasoningDelta(value,kind))
                if(done) add(LlmEvent.ReasoningDone(kind))
            }
        }
        val i=(o["output_index"] as? JsonPrimitive)?.intOrNull
        val events=mutableListOf<LlmEvent>()
        if(type=="response.completed") {
            val response=o["response"] as? JsonObject ?: bad()
            val output=response["output"] ?: return emptyList()
            if(output !is JsonArray || output.size>1025) bad()
            output.forEachIndexed {index,value ->
                val item=value as? JsonObject ?: bad()
                if(str(item,"type")=="reasoning") events+=consume("response.output_item.done",buildJsonObject {
                    put("output_index",index);put("item",item)
                })
            }
            return events
        }
        if(type=="response.output_item.added" || type=="response.output_item.done") {
            val item=o["item"] as? JsonObject ?: return emptyList()
            if(str(item,"type")!="reasoning") return emptyList()
            val index=i ?: bad(); val id=str(item,"id")?.takeIf {it.isNotBlank()} ?: bad()
            if(index !in 0..1024 || ids[index]!=null && ids[index]!=id) bad()
            if(ids[index]==null && ids.size>=128) bad()
            ids[index]=id
            if(type.endsWith(".done")) {
                val field=if(mode==ReasoningReadMode.OpenAiSummary) "summary" else "content"
                val array=item[field] as? JsonArray
                if(item.containsKey(field) && array==null) bad()
                if(array!=null && array.size>256) bad()
                array?.forEachIndexed {j,v -> val p=v as? JsonObject ?: bad()
                    if(str(p,"type")==if(mode==ReasoningReadMode.OpenAiSummary) "summary_text" else "reasoning_text")
                        events+=emit(index,j,str(p,"text") ?: bad(),true,id)
                }
            }
            return events
        }
        if(type=="response.content_part.done" && mode==ReasoningReadMode.DeepSeekVisible) {
            val part=o["part"] as? JsonObject ?: return emptyList()
            if(str(part,"type")=="reasoning_text") return emit(i ?: bad(),
                (o["content_index"] as? JsonPrimitive)?.intOrNull ?: 0,str(part,"text") ?: bad(),true,str(o,"item_id"))
        }
        val prefix=if(mode==ReasoningReadMode.OpenAiSummary) "response.reasoning_summary_text." else "response.reasoning_text."
        if(type!=prefix+"delta" && type!=prefix+"done") return emptyList()
        val index=i ?: bad()
        val indexField=if(mode==ReasoningReadMode.OpenAiSummary) "summary_index" else "content_index"
        val j=if(!o.containsKey(indexField)) 0 else (o[indexField] as? JsonPrimitive)?.takeIf {!it.isString}?.intOrNull ?: bad()
        if(o.containsKey("item_id") && str(o,"item_id")==null) bad()
        return emit(index,j,str(o,if(type.endsWith(".done")) "text" else "delta") ?: bad(),type.endsWith(".done"),str(o,"item_id"))
    }
    private val kind get()=if(mode==ReasoningReadMode.OpenAiSummary) ReasoningContent.Summary else ReasoningContent.ProviderVisibleReasoning
    companion object {const val MAX_CHARS=262144}
}
