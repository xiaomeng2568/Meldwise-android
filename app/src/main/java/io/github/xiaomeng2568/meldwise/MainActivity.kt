package io.github.xiaomeng2568.meldwise

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.xiaomeng2568.meldwise.auth.*
import io.github.xiaomeng2568.meldwise.ui.MainViewModel
import io.github.xiaomeng2568.meldwise.ui.errorLabel
import io.github.xiaomeng2568.meldwise.ui.roleLabel
import io.github.xiaomeng2568.meldwise.ui.messageStateLabel
import io.github.xiaomeng2568.meldwise.ui.modelLabel
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.ApiKeyState

class MainActivity:ComponentActivity() {
    private val viewModel by lazy { ViewModelProvider(this,object:ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T =
            MainViewModel((application as MeldwiseApplication).container) as T
    })[MainViewModel::class.java] }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val screen by viewModel.screen.collectAsStateWithLifecycle()
                val auth by viewModel.auth.collectAsStateWithLifecycle()
                val inference by viewModel.inferenceDiagnostic.collectAsStateWithLifecycle(initialValue=null)
                val apiState by viewModel.deepSeekState.collectAsStateWithLifecycle()
                var input by remember { mutableStateOf("") }
                var expanded by remember { mutableStateOf(false) }
                var configure by remember { mutableStateOf(false) }
                val chatgpt=screen.providerId==ProviderIds.CHATGPT
                val ready=if(chatgpt) auth is AuthState.Connected && (auth as AuthState.Connected).planEnabled else screen.ready
                LaunchedEffect(screen.providerId,screen.historyRef) { input="";expanded=false }
                if(configure) {
                    var key by remember { mutableStateOf("") }
                    AlertDialog(onDismissRequest={key="";configure=false},properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn),
                        title={Text("配置 DeepSeek API Key")},
                        text={Column {
                            Text("私下输入密钥，仅加密保存在这台设备。保存后只显示配置状态。")
                            OutlinedTextField(value=key,onValueChange={if(it.length<=4096) key=it},label={Text("API Key")},
                                visualTransformation=PasswordVisualTransformation(),singleLine=true,
                                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
                        }},
                        confirmButton={TextButton(enabled=key.isNotBlank() && !screen.busy,onClick={viewModel.saveApiKey(key);key="";configure=false}) {Text("保存")}},
                        dismissButton={TextButton(onClick={key="";configure=false}) {Text("取消")}})
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Meldwise · 生产基础验证",style=MaterialTheme.typography.titleLarge)
                        Text("非官方客户端 · 第 2 轮验证版。SIWC 兼容性仍为有条件通过。",style=MaterialTheme.typography.bodySmall)
                        Text("提供方")
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled=!screen.busy,onClick={viewModel.chooseProvider(ProviderIds.CHATGPT)}) {Text(if(chatgpt) "ChatGPT ✓" else "ChatGPT")}
                            OutlinedButton(enabled=!screen.busy,onClick={viewModel.chooseProvider(ProviderIds.DEEPSEEK)}) {Text(if(!chatgpt) "DeepSeek ✓" else "DeepSeek")}
                        }
                        if(chatgpt) {
                        Text("使用 ChatGPT 登录 / ChatGPT 套餐",style=MaterialTheme.typography.bodySmall)
                        Text("登录状态："+when(val a=auth) {
                            AuthState.Restoring->"恢复中"; AuthState.Disconnected->"未连接"
                            AuthState.Authenticating->"授权中"; AuthState.Refreshing->"凭据续期中"
                            AuthState.StorageUnavailable->"安全存储不可用"
                            is AuthState.ReauthRequired->"需要重新授权 · ${errorLabel(a.reason.name)}"
                            is AuthState.Connected->if(a.planEnabled) "已连接 · ChatGPT 套餐" else "已连接 · 仅身份授权"
                        })
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(enabled=!screen.busy && auth!=AuthState.StorageUnavailable,onClick={ viewModel.connect { url ->
                                startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                            } }) { Text("连接 ChatGPT") }
                            TextButton(enabled=!screen.busy && auth is AuthState.Connected,onClick=viewModel::disconnect) { Text("本机断开") }
                            TextButton(enabled=!screen.busy,onClick=viewModel::showLegacy) {Text("旧记录")}
                        }
                        } else {
                            Text("使用 DeepSeek API Key / DeepSeek API 计费",style=MaterialTheme.typography.bodySmall)
                            Text("密钥状态："+when(apiState) {ApiKeyState.CONFIGURED->"已配置";ApiKeyState.MISSING->"未配置，请先添加密钥";ApiKeyState.UNAVAILABLE->"安全存储不可用"})
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Button(enabled=!screen.busy,onClick={configure=true}) {Text(if(apiState==ApiKeyState.CONFIGURED) "替换密钥" else "配置密钥")}
                                TextButton(enabled=!screen.busy && apiState!=ApiKeyState.MISSING,onClick=viewModel::removeApiKey) {Text("删除密钥")}
                            }
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(enabled=ready && !screen.busy,onClick=viewModel::loadModels) { Text("加载模型") }
                            Box {
                                OutlinedButton(enabled=screen.models.isNotEmpty() && !screen.busy,onClick={expanded=true}) {
                                    Text(screen.models.firstOrNull { it.id==screen.selected?.modelId }?.let {
                                        modelLabel(ModelRef(screen.providerId,it.id),it.displayName) } ?: "选择模型") }
                                DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                                    screen.models.forEach { model->DropdownMenuItem(text={Text(modelLabel(ModelRef(screen.providerId,model.id),model.displayName))},onClick={ viewModel.select(model.id); expanded=false }) }
                                }
                            }
                        }
                        screen.error?.let { Text(errorLabel(it),color=MaterialTheme.colorScheme.error) }
                        if(screen.messages.isNotEmpty()) Text("聊天归属："+modelLabel(screen.historyRef,
                            screen.models.firstOrNull { it.id==screen.historyRef.modelId }?.displayName ?: screen.historyRef.modelId),style=MaterialTheme.typography.labelSmall)
                        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            inference?.let { diagnostic ->
                                item(key="inference-diagnostic") { Text("providerId=${screen.providerId} · RESPONSE\n"+diagnostic.summary(),style=MaterialTheme.typography.bodySmall) }
                            }
                            screen.catalogDiagnostic?.let { diagnostic ->
                                item(key="model-catalog-diagnostic") { Text("providerId=${screen.providerId} · MODELS\n"+diagnostic.summary().let {
                                    if(chatgpt) it else it.replace("models 数组","data 数组") },style=MaterialTheme.typography.bodySmall) }
                            }
                            items(screen.messages,key={it.id}) { message->
                                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                    Text("${roleLabel(message.role.name)} · ${messageStateLabel(message.state.name)}",style=MaterialTheme.typography.labelSmall)
                                    Text(message.text)
                                } }
                            }
                        }
                        Text(if(chatgpt) "对话加密保存在此设备。发送时内容传给 OpenAI，使用你的 ChatGPT 套餐。本机断开不会撤销远端会话。"
                            else "对话加密保存在此设备。发送时内容传给 DeepSeek，按 DeepSeek API 计费。删除本机密钥不会撤销服务端密钥。",style=MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value=input,onValueChange={if(it.length<=32768) input=it},enabled=!screen.busy,
                            label={Text("单模型对话")},maxLines=4,modifier=Modifier.fillMaxWidth())
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(enabled=ready && screen.selected!=null && !screen.busy && input.isNotBlank(),onClick={viewModel.send(input);input=""}) { Text("发送") }
                            OutlinedButton(enabled=screen.busy,onClick=viewModel::cancel) { Text("取消") }
                        }
                    }
                }
            }
        }
    }
    override fun onStop() { super.onStop(); viewModel.foregroundStopped() }
}
