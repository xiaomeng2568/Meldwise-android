package io.github.xiaomeng2568.meldwise

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.view.WindowCompat
import androidx.core.content.edit
import io.github.xiaomeng2568.meldwise.ui.*
import io.github.xiaomeng2568.meldwise.ui.components.*
import io.github.xiaomeng2568.meldwise.ui.theme.*

class MainActivity:ComponentActivity() {
    private val viewModel by lazy { ViewModelProvider(this,object:ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T =
            MainViewModel((application as MeldwiseApplication).container) as T
    })[MainViewModel::class.java] }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        // Appearance enum and RGB preference only; private content stays in encrypted journals.
        val preferences=getSharedPreferences("meldwise.appearance",MODE_PRIVATE)
        setContent {
            var appearance by remember {mutableStateOf(Appearance.entries.firstOrNull {it.name==preferences.getString("mode",null)} ?: Appearance.System)}
            var accent by remember {mutableStateOf(preferences.getLong("accent",-1L).takeIf {it in 0..0xFFFFFF})}
            val dark=appearance==Appearance.Dark || (appearance==Appearance.System && isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window,window.decorView).apply {
                    isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
                }
            }
            MeldwiseTheme(appearance,accent) {
                val screen by viewModel.screen.collectAsStateWithLifecycle()
                val auth by viewModel.auth.collectAsStateWithLifecycle()
                val inference by viewModel.inferenceDiagnostic.collectAsStateWithLifecycle(initialValue=null)
                val apiState by viewModel.deepSeekState.collectAsStateWithLifecycle()
                val processing by viewModel.processingTime.collectAsStateWithLifecycle()
                val catalogs by viewModel.catalogs.collectAsStateWithLifecycle()
                val thinking by viewModel.thinking.collectAsStateWithLifecycle()
                val compare by viewModel.compareRun.collectAsStateWithLifecycle()
                val runs by viewModel.compareHistory.collectAsStateWithLifecycle()
                val sessions by viewModel.singleHistory.collectAsStateWithLifecycle()
                val catalogStatus by viewModel.catalogStatus.collectAsStateWithLifecycle()
                val sharingProvider by viewModel.sharingRequest.collectAsStateWithLifecycle()
                val conversation by viewModel.conversation.collectAsStateWithLifecycle()
                val conversationMode by viewModel.conversationMode.collectAsStateWithLifecycle()
                val collaborateConfig by viewModel.collaborateConfig.collectAsStateWithLifecycle()
                val collaborateSharing by viewModel.collaborateSharing.collectAsStateWithLifecycle()
                val debate by viewModel.debateState.collectAsStateWithLifecycle()
                var configure by remember {mutableStateOf(false)}
                val actions=remember {
                    ChatActions(viewModel::chooseProvider,viewModel::selectRef,viewModel::loadModels,{
                        viewModel.connect {url -> startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))}
                    },viewModel::disconnect,{configure=true},viewModel::removeApiKey,viewModel::showLegacy,viewModel::send,viewModel::cancel,
                        viewModel::newChat,viewModel::setThinking,{s ->viewModel.startCompare(s.prompt,s.a,s.b,s.pa,s.pb)},
                        viewModel::openSession,viewModel::openCompare,viewModel::clearCompareDraft,
                        viewModel::deleteHistory,viewModel::moveHistory,viewModel::dismissCatalogNotice,
                        viewModel::newCollaborate,viewModel::configureCollaborate,viewModel::retryCollaborate,viewModel::refreshProviderModels,
                        viewModel::newDebate,viewModel::chooseDebateModel,viewModel::setDebateThinking,viewModel::retryDebate)
                }
                ChatScreen(screen,auth,apiState,inference,processing,appearance,{mode ->
                    appearance=mode;preferences.edit {putString("mode",mode.name)}
                },actions,FoundationState(catalogs,thinking,compare,runs,sessions,catalogStatus,conversation,conversationMode,collaborateConfig,debate),accent,{value ->
                    accent=value;preferences.edit {if(value==null) remove("accent") else putLong("accent",value)}
                })
                sharingProvider?.let {providerId ->
                    ContextSharingDialog(providerId,viewModel::continueSharing,viewModel::cancelSharing)
                }
                collaborateSharing?.let {config ->CollaborateSharingDialog(config,viewModel::continueCollaborateSharing,viewModel::cancelSharing)}
                debate.sharingProviders?.let {providers ->DebateSharingDialog(providers,viewModel::continueDebateSharing,viewModel::cancelSharing)}
                if(configure) {
                    // Input stays only in dialog memory, with screenshot protection.
                    var key by remember {mutableStateOf("")}
                    AlertDialog(onDismissRequest={key="";configure=false},properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn),
                        title={Text("配置 DeepSeek API Key")},
                        text={Column {
                            Text("私下输入密钥，仅加密保存在这台设备。保存后只显示配置状态。")
                            OutlinedTextField(value=key,onValueChange={if(it.length<=4096) key=it},label={Text("API Key")},
                                visualTransformation=PasswordVisualTransformation(),singleLine=true,
                                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
                        }},
                        confirmButton={MeldwiseTextButton(enabled=key.isNotBlank() && !screen.busy,onClick={viewModel.saveApiKey(key);key="";configure=false}) {Text("保存")}},
                        dismissButton={MeldwiseTextButton(onClick={key="";configure=false}) {Text("取消")}})
                }
            }
        }
    }
    override fun onStop() {super.onStop();viewModel.foregroundStopped()}
}
