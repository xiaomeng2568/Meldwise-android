package io.github.xiaomeng2568.meldwise.ui

import io.github.xiaomeng2568.meldwise.provider.*

/** Display only. The actual catalog ID remains unchanged in requests and persistence. */
fun modelLabel(ref:ModelRef,name:String):String {
    val prefix=if(ref.providerId==ProviderIds.CHATGPT) "ChatGPT" else "DeepSeek"
    if(ref.modelId=="UNKNOWN") return "$prefix-未知模型（旧记录）"
    val suffix=name.replace(Regex("^(ChatGPT|GPT|DeepSeek)[ -]?",RegexOption.IGNORE_CASE),"")
    return "$prefix-$suffix"
}
