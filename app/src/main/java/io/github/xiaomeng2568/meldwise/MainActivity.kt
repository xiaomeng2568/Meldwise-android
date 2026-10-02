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
                        Text("Meldwise · Production Foundation",style=MaterialTheme.typography.titleLarge)
                        Text("Unofficial client · Sprint 1 validation build. SIWC compatibility remains CONDITIONAL.",style=MaterialTheme.typography.bodySmall)
                        Text("Auth: "+when(val a=auth) {
                            AuthState.Restoring->"Restoring"; AuthState.Disconnected->"Disconnected"
                            AuthState.Authenticating->"Authorizing"; AuthState.Refreshing->"Refreshing"
                            AuthState.StorageUnavailable->"Secure storage unavailable"
                            is AuthState.ReauthRequired->"ReauthRequired · ${a.reason.name}"
                            is AuthState.Connected->if(a.planEnabled) "Connected · ChatGPT Plan" else "Connected · identity only"
                        })
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(enabled=!screen.busy && auth!=AuthState.StorageUnavailable,onClick={ viewModel.connect { url ->
                                startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                            } }) { Text("Continue with ChatGPT") }
                            TextButton(enabled=!screen.busy && auth is AuthState.Connected,onClick=viewModel::disconnect) { Text("Disconnect locally") }
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(enabled=ready && !screen.busy,onClick=viewModel::loadModels) { Text("Load models") }
                            Box {
                                OutlinedButton(enabled=screen.models.isNotEmpty() && !screen.busy,onClick={expanded=true}) {
                                    Text(screen.models.firstOrNull { it.id==screen.selected }?.displayName ?: "Select model") }
                                DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                                    screen.models.forEach { model->DropdownMenuItem(text={Text(model.displayName)},onClick={ viewModel.select(model.id); expanded=false }) }
                                }
                            }
                        }
                        screen.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            items(screen.messages,key={it.id}) { message->
                                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                    Text("${message.role} · ${message.state}",style=MaterialTheme.typography.labelSmall)
                                    Text(message.text)
                                } }
                            }
                        }
                        Text("Chats are stored encrypted on this device. Sending transmits content to OpenAI under its policies and uses your ChatGPT Plan. No API-key or model fallback. Local disconnect does not revoke the remote session.",style=MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value=input,onValueChange={if(it.length<=32768) input=it},enabled=!screen.busy,
                            label={Text("Single chat")},maxLines=4,modifier=Modifier.fillMaxWidth())
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(enabled=ready && screen.selected!=null && !screen.busy && input.isNotBlank(),onClick={viewModel.send(input);input=""}) { Text("Send") }
                            OutlinedButton(enabled=screen.busy,onClick=viewModel::cancel) { Text("Cancel") }
                        }
                    }
                }
            }
        }
    }
    override fun onStop() { super.onStop(); viewModel.foregroundStopped() }
}
