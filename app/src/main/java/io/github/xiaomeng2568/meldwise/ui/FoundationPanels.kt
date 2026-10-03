package io.github.xiaomeng2568.meldwise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.ui.components.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.*

class FoundationState(val catalogs:Map<String,List<LlmModel>> = emptyMap(),val thinking:ReasoningPreference=ReasoningPreference.Auto,
    val run:CompareRun?=null,val runs:List<CompareRun> = emptyList(),val sessions:List<SingleSessionInfo> = emptyList(),
    val catalogStatus:CatalogUiState=CatalogUiState(),val conversation:Conversation?=null,
    val mode:ConversationMode=ConversationMode.Single,val collaborate:CollaborateConfig?=null) {
    override fun toString()="FoundationState([REDACTED])"
}
fun preferenceLabel(p:ReasoningPreference)=when(p) {ReasoningPreference.Off->"关闭";ReasoningPreference.Auto->"默认"
    ReasoningPreference.Low->"轻度";ReasoningPreference.High->"深入";ReasoningPreference.Max->"尽力"}
@Composable internal fun ThinkingChoices(ref:ModelRef,selected:ReasoningPreference,enabled:Boolean=true,onSelect:(ReasoningPreference)->Unit) {
    if(ref.providerId==ProviderIds.CHATGPT) {
        Text("ChatGPT 使用模型默认设置。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(Space.micro)) {
            listOf(ReasoningPreference.Off,ReasoningPreference.Low,ReasoningPreference.High,ReasoningPreference.Max).forEach {p ->
                Surface(onClick={onSelect(p)},enabled=enabled,shape=CircleShape,color=Color.Transparent,
                    modifier=Modifier.heightIn(min=Sizes.touch).semantics {this.selected=selected==p}) {
                    Box(Modifier.padding(vertical=Space.small).background(if(selected==p) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,CircleShape)
                        .padding(horizontal=Space.medium,vertical=Space.micro),contentAlignment=androidx.compose.ui.Alignment.Center) {
                        Text(preferenceLabel(p),style=MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        Text("按 DeepSeek API 计费",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable internal fun CompareContent(run:CompareRun,catalogs:Map<String,List<LlmModel>>) {
    Column(verticalArrangement=Arrangement.spacedBy(Space.wide)) {
        compareMessageItems(run,catalogs).forEach {item ->key(item.key) {CompareMessage(item)}}
    }
}
@Composable internal fun AccentPicker(accent:Long?,onAccent:(Long?)->Unit) {
    var hex by remember(accent) {mutableStateOf(accent?.let {"%06X".format(it)} ?: "6550A4")}
    val presets=listOf(0x6550A4L,0x366BD5L,0x218675L,0xA55F30L,0xB45B7DL)
    Text("重点色",style=MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement=Arrangement.spacedBy(Space.micro)) {
        presets.forEachIndexed {index,value ->
            Surface(onClick={onAccent(value)},shape=CircleShape,color=Color.Transparent,modifier=Modifier.size(Sizes.touch).semantics {
                contentDescription=listOf("紫色","蓝色","绿色","暖棕","玫瑰色")[index]+"重点色"
                selected=accent==value
            }) {
                Box(Modifier.padding(Space.small).background(Color(0xFF000000L or value),CircleShape))
            }
        }
    }
    OutlinedTextField(hex,{if(it.length<=7) hex=it},label={Text("自定义色号")},prefix={Text("#")},singleLine=true,
        shape=Radius.medium,modifier=Modifier.fillMaxWidth())
    Row {
        TextButton(enabled=parseAccent(hex)!=null,onClick={onAccent(parseAccent(hex))}) {Text("应用")}
        TextButton(onClick={onAccent(null)}) {Text("恢复默认")}
    }
    Text("文字和按钮会自动调整深浅，保持清楚。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
