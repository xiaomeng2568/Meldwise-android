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
import io.github.xiaomeng2568.meldwise.auth.*
import io.github.xiaomeng2568.meldwise.ui.MainViewModel
import io.github.xiaomeng2568.meldwise.ui.errorLabel
import io.github.xiaomeng2568.meldwise.ui.roleLabel
import io.github.xiaomeng2568.meldwise.ui.messageStateLabel

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
                var input by remember { mutableStateOf("") }
                var expanded by remember { mutableStateOf(false) }
                val ready=auth is AuthState.Connected && (auth as AuthState.Connected).planEnabled
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Meldwise · 生产基础验证",style=MaterialTheme.typography.titleLarge)
                        Text("非官方客户端 · 第 1 轮验证版。SIWC 兼容性仍为有条件通过。",style=MaterialTheme.typography.bodySmall)
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
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(enabled=ready && !screen.busy,onClick=viewModel::loadModels) { Text("加载模型") }
                            Box {
                                OutlinedButton(enabled=screen.models.isNotEmpty() && !screen.busy,onClick={expanded=true}) {
                                    Text(screen.models.firstOrNull { it.id==screen.selected }?.displayName ?: "选择模型") }
                                DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                                    screen.models.forEach { model->DropdownMenuItem(text={Text(model.displayName)},onClick={ viewModel.select(model.id); expanded=false }) }
                                }
                            }
                        }
                        screen.error?.let { Text(errorLabel(it),color=MaterialTheme.colorScheme.error) }
                        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            screen.catalogDiagnostic?.let { diagnostic ->
                                item(key="model-catalog-diagnostic") { Text(diagnostic.summary(),style=MaterialTheme.typography.bodySmall) }
                            }
                            items(screen.messages,key={it.id}) { message->
                                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                    Text("${roleLabel(message.role.name)} · ${messageStateLabel(message.state.name)}",style=MaterialTheme.typography.labelSmall)
                                    Text(message.text)
                                } }
                            }
                        }
                        Text("对话加密保存在此设备。发送时，内容会传给 OpenAI 并按其政策处理，使用你的 ChatGPT 套餐。不自动切换 API 密钥或模型。本机断开不会撤销远端会话。",style=MaterialTheme.typography.bodySmall)
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
