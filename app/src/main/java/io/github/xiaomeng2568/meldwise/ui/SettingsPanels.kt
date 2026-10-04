package io.github.xiaomeng2568.meldwise.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import io.github.xiaomeng2568.meldwise.auth.*
import io.github.xiaomeng2568.meldwise.network.InferenceDiagnostic
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.ApiKeyState
import io.github.xiaomeng2568.meldwise.ui.components.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.*

fun authCaption(auth: AuthState): String = when(auth) {
    AuthState.Restoring -> "恢复中";AuthState.Disconnected -> "未连接";AuthState.Authenticating -> "授权中"
    AuthState.Refreshing -> "凭据续期中";AuthState.StorageUnavailable -> "安全存储不可用"
    is AuthState.ReauthRequired -> "需要重新连接 · ${errorLabel(auth.reason.name)}"
    is AuthState.Connected -> if(auth.planEnabled) "已连接 · ChatGPT 套餐" else "已连接 · 仅身份授权"
}
fun keyCaption(state: ApiKeyState): String = when(state) {
    ApiKeyState.CONFIGURED -> "已配置";ApiKeyState.MISSING -> "未配置";ApiKeyState.UNAVAILABLE -> "安全存储不可用"
}
@Composable internal fun ProviderSettings(screen: ScreenState, auth: AuthState, apiState: ApiKeyState, actions: ChatActions) {
    PanelColumn("提供方与账号") {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(Space.small)) {
            listOf(ProviderIds.CHATGPT,ProviderIds.DEEPSEEK).forEach {id ->
                MeldwiseFilterChip(selected=screen.providerId==id,onClick={actions.chooseProvider(id)},enabled=!screen.busy,
                    label={Text(providerLabel(id))},shape=Radius.medium,border=null,modifier=Modifier.weight(1f).heightIn(min=Sizes.touch))
            }
        }
        if(screen.providerId==ProviderIds.CHATGPT) {
            Text("使用 ChatGPT 登录",style=MaterialTheme.typography.titleMedium)
            Text(authCaption(auth),style=MaterialTheme.typography.bodyMedium)
            Text("消息使用 ChatGPT 套餐额度。发送的内容会传给 OpenAI。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            MeldwiseButton(enabled=!screen.busy && auth!=AuthState.StorageUnavailable,onClick=actions.connect) {Text("连接 ChatGPT")}
            MeldwiseTextButton(enabled=!screen.busy && auth is AuthState.Connected,onClick=actions.disconnect) {Text("本机断开")}
            Notice("本机断开会清除本地连接，远端会话需在服务商处管理。SIWC 兼容性仍为有条件通过。")
        } else {
            Text("使用 DeepSeek API Key",style=MaterialTheme.typography.titleMedium)
            Text("密钥状态：${keyCaption(apiState)}",style=MaterialTheme.typography.bodyMedium)
            Text("按 DeepSeek API 计费。发送的内容会传给 DeepSeek。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            MeldwiseButton(enabled=!screen.busy && apiState!=ApiKeyState.UNAVAILABLE,onClick=actions.configureKey) {Text(if(apiState==ApiKeyState.CONFIGURED) "替换密钥" else "配置密钥")}
            MeldwiseTextButton(enabled=!screen.busy && apiState!=ApiKeyState.MISSING,onClick=actions.removeKey) {Text("删除本机密钥")}
            Notice("密钥加密保存在这台设备。保存后的完整密钥不会重新显示。删除本机密钥后，历史对话会保留。")
        }
        screen.error?.let {Notice(errorLabel(it),"操作提示",error=true)}
        Text("对话加密保存在本机。模型列表会在需要时后台更新；消息由你发送。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable internal fun ModelPicker(screen: ScreenState, ready: Boolean, actions: ChatActions,
    onSettings: ()->Unit, onSelected: ()->Unit,catalogs:Map<String,List<LlmModel>> = emptyMap(),onPick:(ModelRef)->Unit=actions.selectModel,
    loading:Set<String> = emptySet(),selection:ModelRef?=screen.selected,preference:ReasoningPreference=ReasoningPreference.Auto,
    onThinking:(ReasoningPreference)->Unit=actions.thinking,scope:PickerScope=PickerScope.Single,
    readyForProvider:(String)->Boolean={it==screen.providerId && ready}) {
    // Selection changes and cached-catalog updates must not expand another provider implicitly.
    var browser by remember(scope) {mutableStateOf(ModelPickerState.initial(selection,screen.providerId))}
    PanelColumn("选择模型") {
        listOf(ProviderIds.CHATGPT,ProviderIds.DEEPSEEK).forEach {id ->
            val active=browser.shows(id)
                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(Space.micro)) {
                    MeldwiseTextButton(enabled=!screen.busy,onClick={browser=browser.browse(id)},modifier=Modifier.fillMaxWidth().heightIn(min=Sizes.touch).semantics {stateDescription=if(active) "已展开" else "已折叠"}) {
                        Text(providerLabel(id),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        if(active) MeldwiseIcon(Glyph.Check)
                    }
                    Text(if(id==ProviderIds.CHATGPT) "ChatGPT 套餐" else "DeepSeek API 计费",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier=Modifier.padding(horizontal=Space.medium))
                    val listed=if(active) catalogs[id] ?: if(screen.providerId==id) screen.models else emptyList() else emptyList()
                    listed.forEach {model ->
                            val ref=ModelRef(id,model.id)
                            MeldwiseTextButton(enabled=!screen.busy,onClick={onPick(ref);onSelected()},
                                modifier=Modifier.fillMaxWidth().heightIn(min=Sizes.touch).semantics {selected=selectedModel(selection,ref)}) {
                                Text(modelLabel(ref,model.displayName),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                                if(selectedModel(selection,ref)) MeldwiseIcon(Glyph.Check)
                            }
                            if(selectedModel(selection,ref)) ThinkingChoices(ref,preference,enabled=!screen.busy,onThinking)
                        }
                    if(active) {
                        if(listed.isEmpty()) Text(if(id in loading) "正在更新模型…" else if(readyForProvider(id)) "还没有可用模型。" else "先连接账号或配置密钥。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        MeldwiseTextButton(enabled=readyForProvider(id) && !screen.busy && id !in loading,
                            onClick={actions.refreshModels(id)}) {Text(if(id in loading) "更新中…" else "更新模型")}
                    }
                }
        }
        screen.error?.let {Notice(errorLabel(it),"操作提示",error=true)}
        MeldwiseTextButton(onClick=onSettings) {Text("管理提供方与账号")}
    }
}
@Composable internal fun DiagnosticsPanel(screen: ScreenState, inference: InferenceDiagnostic?) {
    PanelColumn("诊断") {
        Notice("这里仅显示状态、计数和固定错误类别。凭据、账号、回调地址与聊天正文都不会进入诊断。")
        Text("providerId=${screen.providerId}",style=MaterialTheme.typography.labelMedium)
        if(inference==null && screen.catalogDiagnostic==null) Text("本次还没有请求诊断。",style=MaterialTheme.typography.bodyMedium)
        inference?.let {Text("RESPONSE",style=MaterialTheme.typography.labelLarge);Text(it.summary(),style=MaterialTheme.typography.bodySmall)}
        screen.catalogDiagnostic?.let {
            Text("MODELS",style=MaterialTheme.typography.labelLarge)
            Text(if(screen.providerId==ProviderIds.DEEPSEEK) it.summary().replace("models 数组","data 数组") else it.summary(),style=MaterialTheme.typography.bodySmall)
        }
    }
}
