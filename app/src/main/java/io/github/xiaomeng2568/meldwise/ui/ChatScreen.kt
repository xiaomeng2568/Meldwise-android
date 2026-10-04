package io.github.xiaomeng2568.meldwise.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.SolidColor
import io.github.xiaomeng2568.meldwise.auth.AuthState
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.network.InferenceDiagnostic
import io.github.xiaomeng2568.meldwise.security.ApiKeyState
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.ui.components.*
import io.github.xiaomeng2568.meldwise.ui.content.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.*

/** All effects belong to the owner; UI carries stable IDs and callbacks only. */
class ChatActions(val chooseProvider: (String)->Unit, val selectModel: (ModelRef)->Unit,
    val loadModels: ()->Unit, val connect: ()->Unit, val disconnect: ()->Unit,
    val configureKey: ()->Unit, val removeKey: ()->Unit, val showLegacy: ()->Unit,
    val send: (String)->Unit, val cancel: ()->Unit,val newChat:()->Unit={},
    val thinking:(ReasoningPreference)->Unit={},val compare:(CompareSubmission)->Unit={},
    val openSingle:(String)->Unit={},val openCompare:(String)->Unit={},val newCompare:()->Unit={},
    val deleteHistory:(String,Boolean)->Unit={_,_->},val moveHistory:(String,Boolean,Int)->Unit={_,_,_->},
    val dismissNotice:(Long)->Unit={},val newCollaborate:()->Unit={},
    val configureCollaborate:(CollaborateSubmission)->Unit={},val retryCollaborate:(String)->Unit={},
    val refreshModels:(String)->Unit={})
class CompareSubmission(val prompt:String,val a:ModelRef,val b:ModelRef,val pa:ReasoningPreference,val pb:ReasoningPreference) {
    override fun toString()="CompareSubmission([REDACTED])"
}
class CollaborateSubmission(val a:ModelRef,val b:ModelRef,val pa:ReasoningPreference,val pb:ReasoningPreference,
    val reviewIntensity:ReviewIntensity=ReviewIntensity.STANDARD,val synthesisRole:SynthesisRole=SynthesisRole.PRIMARY) {
    override fun toString()="CollaborateSubmission([REDACTED])"
}
private typealias Panel = ChatPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ChatScreen(screen: ScreenState, auth: AuthState, apiState: ApiKeyState,
    inference: InferenceDiagnostic?, processing: ProcessingTime, appearance: Appearance,
    onAppearance: (Appearance)->Unit, actions: ChatActions, foundation:FoundationState=FoundationState(),
    accent:Long?=null,onAccent:(Long?)->Unit={}) {
    var navigation by remember {mutableStateOf(ChatNavigation())}
    val panel=navigation.panel
    val sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)
    var retainedNavigation by remember {mutableStateOf(navigation)}
    var retainedLane by remember {mutableStateOf<String?>(null)}
    val showSheet=panel!=Panel.None || retainedNavigation.panel!=Panel.None
    var noticeDetails by remember {mutableStateOf<CatalogNotice?>(null)}
    val catalogNotice=foundation.catalogStatus.notices.firstOrNull()
    // Keep the lifetime independent of switching the active chat/sheet popup host.
    LaunchedEffect(catalogNotice?.id) {catalogNotice?.let {autoDismissCatalogNotice(it.id,actions.dismissNotice)}}
    val showCatalogDetails:(CatalogNotice)->Unit={notice ->
        noticeDetails=notice;navigation=navigation.open(Panel.Diagnostics);actions.dismissNotice(notice.id)
    }
    var input by remember {mutableStateOf("")}
    val compareMode=navigation.compareMode
    val collaborateMode=!compareMode && foundation.mode==ConversationMode.Collaborate
    var pickingLane by remember {mutableStateOf<String?>(null)}
    // Retain only the local page while the native sheet closes, including programmatic dismissal.
    LaunchedEffect(navigation.stack,pickingLane) {
        if(panel!=Panel.None) {retainedNavigation=navigation;retainedLane=pickingLane}
        else if(retainedNavigation.panel!=Panel.None) {sheetState.hide();retainedNavigation=navigation;retainedLane=null}
    }
    var modelA by remember {mutableStateOf<ModelRef?>(null)}
    var modelB by remember {mutableStateOf<ModelRef?>(null)}
    var effortA by remember {mutableStateOf(ReasoningPreference.Auto)}
    var effortB by remember {mutableStateOf(ReasoningPreference.Auto)}
    var reviewIntensity by remember {mutableStateOf(ReviewIntensity.STANDARD)}
    var synthesisRole by remember {mutableStateOf(SynthesisRole.PRIMARY)}
    val chatgpt=screen.providerId==ProviderIds.CHATGPT
    val ready=if(chatgpt) auth is AuthState.Connected && auth.planEnabled else screen.ready
    val current=screen.selected ?: screen.historyRef
    val name=screen.models.firstOrNull {it.id==current.modelId}?.displayName ?: current.modelId
    fun label(ref:ModelRef)=modelLabel(ref,foundation.catalogs[ref.providerId]?.firstOrNull {it.id==ref.modelId}?.displayName ?: ref.modelId)
    fun available(ref:ModelRef?)=ref!=null && foundation.catalogs[ref.providerId]?.any {it.id==ref.modelId}==true &&
        if(ref.providerId==ProviderIds.CHATGPT) auth is AuthState.Connected && auth.planEnabled else apiState==ApiKeyState.CONFIGURED
    fun applyCollaborate(a:ModelRef?=modelA,b:ModelRef?=modelB,pa:ReasoningPreference=effortA,pb:ReasoningPreference=effortB,
        review:ReviewIntensity=reviewIntensity,synthesis:SynthesisRole=synthesisRole) {
        if(collaborateMode && a!=null && b!=null && a!=b) actions.configureCollaborate(CollaborateSubmission(a,b,pa,pb,review,synthesis))
    }
    val list=rememberLazyListState()
    val compareList=rememberLazyListState()
    val compareItems=remember(foundation.run,foundation.catalogs) {foundation.run?.let {compareMessageItems(it,foundation.catalogs)} ?: emptyList()}
    val collaborateItems=remember(foundation.conversation) {foundation.conversation?.takeIf {it.mode==ConversationMode.Collaborate}?.let(::collaborateMessageItems) ?: emptyList()}
    val keyboardVisible=WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)>0
    BackHandler(enabled=panel==Panel.None && navigation.handlesBack && !keyboardVisible) {navigation=navigation.back()}
    LaunchedEffect(foundation.run?.id) {
        foundation.run?.let {run ->navigation=navigation.copy(compareMode=true);modelA=run.outputs.firstOrNull()?.modelRef;modelB=run.outputs.getOrNull(1)?.modelRef
            effortA=run.outputs.firstOrNull()?.preference ?: ReasoningPreference.Auto;effortB=run.outputs.getOrNull(1)?.preference ?: ReasoningPreference.Auto}
    }
    LaunchedEffect(foundation.mode,foundation.conversation?.conversationId,foundation.collaborate) {
        if(foundation.mode==ConversationMode.Collaborate) {
            navigation=navigation.copy(compareMode=false)
            foundation.collaborate?.let {config ->modelA=config.primary.ref;modelB=config.reviewer.ref;effortA=config.primary.preference;effortB=config.reviewer.preference
                reviewIntensity=config.reviewIntensity;synthesisRole=config.synthesisRole}
        }
    }
    var followLatest by remember {mutableStateOf(true)}
    LaunchedEffect(screen.messages.firstOrNull()?.id) {if(!compareMode) input=""}
    LaunchedEffect(screen.messages.lastOrNull {it.role==MessageRole.USER}?.id) {
        if(!compareMode && screen.messages.lastOrNull {it.role==MessageRole.USER}?.text==input) input=""
    }
    LaunchedEffect(list) {
        snapshotFlow {list.isScrollInProgress}.collect {scrolling ->
            if(scrolling) followLatest=false else followLatest=!list.canScrollForward
        }
    }
    // Follow the bottom until the reader scrolls away. Provider messages remain untouched.
    val visibleCount=if(collaborateMode) collaborateItems.size else screen.messages.size
    val lastVisibleLength=if(collaborateMode) foundation.conversation?.rounds?.lastOrNull()?.stages?.sumOf {it.output.length+it.reasoning.text.length} else screen.messages.lastOrNull()?.text?.length
    LaunchedEffect(visibleCount,screen.historyRef) {
        followLatest=true
        if(visibleCount>0) list.scrollToItem(visibleCount-1)
    }
    LaunchedEffect(lastVisibleLength) {
        withFrameNanos { }
        if(followLatest && !list.isScrollInProgress) {
            val last=list.layoutInfo.visibleItemsInfo.lastOrNull()
            if(last?.index==visibleCount-1) {
                val extra=(last.offset+last.size-list.layoutInfo.viewportEndOffset).coerceAtLeast(0)
                if(extra>0) list.scrollBy(extra.toFloat())
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding(),horizontalAlignment=Alignment.CenterHorizontally) {
            Row(Modifier.widthIn(max=Sizes.contentMax).fillMaxWidth().padding(horizontal=Space.small),verticalAlignment=Alignment.CenterVertically) {
                SoftAction(Glyph.Menu,"打开菜单",{navigation=navigation.open(Panel.Settings)},tonal=false)
                ModelTitle(if(compareMode) "对比" else if(collaborateMode) "协作" else if(current.modelId=="UNKNOWN") "Meldwise" else modelLabel(current,name),
                    if(compareMode || collaborateMode) listOfNotNull(modelA,modelB).joinToString(if(collaborateMode) " → " else " × ") {label(it)}.ifEmpty {"选两个模型"} else if(screen.selected==null) "选择模型" else "单模型对话",
                    Modifier.weight(1f),onClick={pickingLane=null;navigation=navigation.open(if(compareMode) Panel.CompareSetup else if(collaborateMode) Panel.CollaborateSetup else Panel.Models)})
                SoftAction(Glyph.Plus,"选择对话模式",{navigation=navigation.open(Panel.Modes)},enabled=!screen.busy,tonal=false)
            }
            if(compareMode) {
                LazyColumn(Modifier.weight(1f).widthIn(max=Sizes.contentMax).fillMaxWidth().testTag("compareMessageFlow"),state=compareList,
                    contentPadding=PaddingValues(horizontal=Space.content,vertical=Space.wide),verticalArrangement=Arrangement.spacedBy(Space.wide)) {
                    if(compareItems.isEmpty()) item {Column(Modifier.padding(vertical=Space.large)) {
                        Text("把同一个问题交给两个模型。",style=MaterialTheme.typography.titleMedium)
                        MeldwiseTextButton(onClick={navigation=navigation.open(Panel.CompareSetup)}) {Text("选择模型")}
                    }}
                    items(compareItems,key={it.key}) {CompareMessage(it)}
                }
            } else if(collaborateMode) {
                LazyColumn(Modifier.weight(1f).widthIn(max=Sizes.contentMax).fillMaxWidth().testTag("collaborateMessageFlow"),state=list,
                    contentPadding=PaddingValues(horizontal=Space.content,vertical=Space.wide),verticalArrangement=Arrangement.spacedBy(Space.large)) {
                    if(collaborateItems.isEmpty()) item {Column(Modifier.padding(vertical=Space.large)) {
                        Text("先回答，再审阅，最后整理。",style=MaterialTheme.typography.titleMedium)
                        MeldwiseTextButton(onClick={navigation=navigation.open(Panel.CollaborateSetup)}) {Text("选择协作模型")}
                    }}
                    items(collaborateItems,key={it.key}) {CollaborateMessage(it)}
                    foundation.conversation?.rounds?.lastOrNull()?.takeIf {!it.lifecycle.active && it.lifecycle!=CollaborateRoundState.Complete}?.let {round ->
                        item {MeldwiseTextButton(enabled=!screen.busy,onClick={actions.retryCollaborate(round.roundId)}) {Text("按原模型重新执行这一轮")}}
                    }
                }
            } else if(screen.messages.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(Space.wide),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                    MeldwiseMark(Modifier.padding(bottom=Space.wide))
                    Text("今天想聊些什么？",style=MaterialTheme.typography.headlineMedium)
                }
            } else {
                LazyColumn(Modifier.weight(1f).widthIn(max=Sizes.contentMax).fillMaxWidth(),state=list,
                    contentPadding=PaddingValues(horizontal=Space.content,vertical=Space.wide),verticalArrangement=Arrangement.spacedBy(Space.large)) {
                    items(screen.messages,key={it.id}) {message ->
                        MessageCard(message,screen.historyRef,name,if(processing.messageId==message.id) processing.seconds else null,
                            onDetails={noticeDetails=null;navigation=navigation.open(Panel.Diagnostics)})
                    }
                }
            }
            Column(Modifier.widthIn(max=Sizes.contentMax).fillMaxWidth().padding(horizontal=Space.content),verticalArrangement=Arrangement.spacedBy(Space.small)) {
                screen.error?.let {
                    Surface(shape=Radius.surface,color=MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(start=Space.content),verticalAlignment=Alignment.CenterVertically) {
                            Text(errorLabel(it),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                            MeldwiseTextButton(onClick={noticeDetails=null;navigation=navigation.open(Panel.Diagnostics)}) {Text("详情")}
                        }
                    }
                }
                if(!compareMode && !collaborateMode && !ready && !screen.busy) MeldwiseTextButton(onClick={navigation=navigation.open(Panel.Providers)},modifier=Modifier.fillMaxWidth()) {
                    Text(if(chatgpt) "连接 ChatGPT 后开始聊天" else "配置 DeepSeek 密钥后开始聊天")
                }
                Composer(input,{if(it.length<=32768) input=it},screen.busy,
                    input.isNotBlank() && if(compareMode || collaborateMode) available(modelA) && available(modelB) && modelA!=modelB &&
                        (!collaborateMode || foundation.collaborate?.primary?.ref==modelA && foundation.collaborate?.reviewer?.ref==modelB) else ready && screen.selected!=null,
                    onSend={if(compareMode) {val a=modelA;val b=modelB;if(a!=null && b!=null) actions.compare(CompareSubmission(input,a,b,effortA,effortB));input=""} else actions.send(input)},
                    onStop=actions.cancel,onThinking={navigation=navigation.open(if(compareMode) Panel.CompareSetup else if(collaborateMode) Panel.CollaborateSetup else Panel.Thinking)},
                    compareMode=compareMode,thinking=foundation.thinking!=ReasoningPreference.Off && chatgpt.not(),
                    options=navigation.composerOptions,onOptions={navigation=navigation.copy(composerOptions=it)},
                    sendLabel=if(compareMode) "同时询问" else if(collaborateMode) "开始协作" else "发送消息")
                Text(if(compareMode || collaborateMode) "按各自服务计费 · 记录留在本机" else if(chatgpt) "ChatGPT 套餐" else "DeepSeek API 计费",
                    style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier=Modifier.align(Alignment.CenterHorizontally).padding(bottom=Space.small))
            }
        }
    }
    if(!showSheet) CatalogNoticeOverlay(catalogNotice,actions.dismissNotice,showCatalogDetails)
    }
    if(showSheet) {
        ModalBottomSheet(onDismissRequest={navigation=navigation.dismiss();pickingLane=null},sheetState=sheetState,
            properties=ModalBottomSheetProperties(shouldDismissOnBackPress=false),containerColor=MaterialTheme.colorScheme.background,shape=Radius.composer) {
            BackHandler(enabled=!keyboardVisible) {if(panel!=Panel.None) {navigation=navigation.back();if(navigation.panel!=Panel.Models) pickingLane=null}}
            PanelTransition(if(panel!=Panel.None) navigation else retainedNavigation,if(panel!=Panel.None) pickingLane else retainedLane,
                interactive=panel!=Panel.None) {frame ->
            val activePanel=frame.panel
            Column(Modifier.fillMaxWidth()) {
            if(frame.depth>1) SoftAction(Glyph.Back,"返回上一步",{navigation=navigation.back();if(navigation.panel!=Panel.Models) pickingLane=null},tonal=false)
            when(activePanel) {
                Panel.Models -> {
                    val lane=frame.modelLane
                    ModelPicker(screen,ready,actions,onSettings={navigation=navigation.open(Panel.Providers)},onSelected={},
                    catalogs=foundation.catalogs,loading=foundation.catalogStatus.loading,
                    scope=if(lane==null) PickerScope.Single else PickerScope.Lane,
                    readyForProvider={id ->if(id==ProviderIds.CHATGPT) auth is AuthState.Connected && auth.planEnabled else apiState==ApiKeyState.CONFIGURED},
                    selection=if(lane=="A") modelA else if(lane=="B") modelB else screen.selected,
                    preference=if(lane=="A") effortA else if(lane=="B") effortB else foundation.thinking,
                    onThinking={p ->if(lane=="A") {effortA=p;applyCollaborate(pa=p)} else if(lane=="B") {effortB=p;applyCollaborate(pb=p)} else actions.thinking(p)},onPick={ref ->
                        if(lane=="A") {modelA=ref;effortA=if(ref.providerId==ProviderIds.DEEPSEEK) ReasoningPreference.Off else ReasoningPreference.Auto;applyCollaborate(a=ref,pa=effortA)}
                        else if(lane=="B") {modelB=ref;effortB=if(ref.providerId==ProviderIds.DEEPSEEK) ReasoningPreference.Off else ReasoningPreference.Auto;applyCollaborate(b=ref,pb=effortB)}
                        else actions.selectModel(ref)
                    })
                }
                Panel.CompareSetup,Panel.CollaborateSetup -> PanelColumn(if(activePanel==Panel.CollaborateSetup) "协作" else "对比") {
                    listOf("A" to modelA,"B" to modelB).forEach {(id,ref) ->
                            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(Space.micro)) {
                                MeldwiseTextButton(enabled=!screen.busy,onClick={pickingLane=id;navigation=navigation.open(Panel.Models)}) {
                                    Text("${if(activePanel==Panel.CollaborateSetup) if(id=="A") "主模型 A" else "审阅模型 B" else "模型 $id"}：${ref?.let {modelLabel(it,foundation.catalogs[it.providerId]?.firstOrNull {m ->m.id==it.modelId}?.displayName ?: it.modelId)} ?: "请选择"}")
                                }
                                ref?.let {ThinkingChoices(it,if(id=="A") effortA else effortB,enabled=!screen.busy) {p ->if(id=="A") {effortA=p;applyCollaborate(pa=p)} else {effortB=p;applyCollaborate(pb=p)}}
                                    if(!available(it)) Text("先配置账号并加载模型。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                    }
                    if(modelA!=null && modelA==modelB) Text("选两个不同的模型。",color=MaterialTheme.colorScheme.error)
                    if(activePanel==Panel.CollaborateSetup) {
                        Text("审阅强度",style=MaterialTheme.typography.labelLarge)
                        Text("只调整审阅要求，不改变模型的思考强度。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(Space.small)) {
                            ReviewIntensity.entries.forEach {value ->MeldwiseFilterChip(value==reviewIntensity,{reviewIntensity=value;applyCollaborate(review=value)},
                                {Text(when(value) {ReviewIntensity.CONCISE->"简洁";ReviewIntensity.STANDARD->"标准";ReviewIntensity.STRICT->"严格"})},enabled=!screen.busy)}
                        }
                        Text("谁来综合",style=MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(Space.small)) {
                            SynthesisRole.entries.forEach {value ->MeldwiseFilterChip(value==synthesisRole,{synthesisRole=value;applyCollaborate(synthesis=value)},
                                {Text(if(value==SynthesisRole.PRIMARY) "主模型 A" else "审阅模型 B")},enabled=!screen.busy)}
                        }
                        Text("A 初答 → B 审阅 → ${if(synthesisRole==SynthesisRole.PRIMARY) "A" else "B"} 综合",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Panel.Thinking -> PanelColumn("思考") {ThinkingChoices(current,foundation.thinking,enabled=!screen.busy,actions.thinking)}
                Panel.History,Panel.HistoryChat,Panel.HistoryCompare,Panel.HistoryCollaborate -> HistoryPanel(frame.historyCategory,
                    foundation.sessions,foundation.runs,screen.busy,
                    onCategory={navigation=navigation.openHistory(it)},
                    onOpen={entry ->
                        input="";navigation=navigation.openHistoryEntry(entry)
                        if(entry.compare) {
                            val run=foundation.runs.single {it.id==entry.id}
                            modelA=run.laneA.modelRef;modelB=run.laneB.modelRef
                            effortA=run.laneA.preference;effortB=run.laneB.preference;actions.openCompare(entry.id)
                        } else actions.openSingle(entry.id)
                    },onDelete={actions.deleteHistory(it.id,it.compare)},onMove={entry,direction->actions.moveHistory(entry.id,entry.compare,direction)})
                Panel.Providers -> ProviderSettings(screen,auth,apiState,actions)
                Panel.Diagnostics -> if(noticeDetails==null) DiagnosticsPanel(screen,inference) else CatalogDetails(requireNotNull(noticeDetails))
                Panel.Appearance -> PanelColumn("外观") {
                    Appearance.entries.forEach {item ->
                        MeldwiseTextButton(onClick={onAppearance(item)},modifier=Modifier.fillMaxWidth().heightIn(min=Sizes.touch)) {
                            Text(when(item) {Appearance.System->"跟随系统";Appearance.Light->"浅色";Appearance.Dark->"深色"},Modifier.weight(1f))
                            if(item==appearance) MeldwiseIcon(Glyph.Check)
                        }
                    }
                    AccentPicker(accent,onAccent)
                }
                Panel.Gallery -> ComponentGallery()
                Panel.Settings -> PanelColumn("设置") {
                    SettingsRow("历史","对话、对比与协作",{navigation=navigation.open(Panel.History)})
                    SettingsRow("提供方与账号","ChatGPT 套餐 / DeepSeek API",{navigation=navigation.open(Panel.Providers)})
                    SettingsRow("外观","主题与重点色",{navigation=navigation.open(Panel.Appearance)})
                    SettingsRow("诊断","只含脱敏状态",{noticeDetails=null;navigation=navigation.open(Panel.Diagnostics)})
                    SettingsRow("组件预览","开发者选项",{navigation=navigation.open(Panel.Gallery)})
                    if(chatgpt) MeldwiseTextButton(enabled=!screen.busy,onClick={actions.showLegacy();navigation=navigation.dismiss()}) {Text("查看旧版 ChatGPT 记录")}
                    Text("Meldwise · ${androidx.compose.ui.res.stringResource(io.github.xiaomeng2568.meldwise.R.string.public_alpha_version)}\n非官方客户端 · SIWC 兼容性：有条件通过",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Panel.Modes -> ModePicker(enabled=!screen.busy,selected=if(compareMode) HistoryCategory.Compare else if(collaborateMode) HistoryCategory.Collaborate else HistoryCategory.Chat,
                    onSingle={input="";actions.newChat();navigation=navigation.selectMode(false)},
                    onCompare={input="";actions.newCompare();modelA=screen.selected;modelB=null;effortA=foundation.thinking;effortB=ReasoningPreference.Off;navigation=navigation.selectMode(true)},
                    onCollaborate={input="";actions.newCollaborate();modelA=screen.selected;modelB=null;effortA=foundation.thinking;effortB=ReasoningPreference.Off;reviewIntensity=ReviewIntensity.STANDARD;synthesisRole=SynthesisRole.PRIMARY;navigation=navigation.dismiss().copy(compareMode=false).open(Panel.CollaborateSetup)})
                Panel.None -> Unit
            }
            }
            }
            CatalogNoticeOverlay(catalogNotice,actions.dismissNotice,showCatalogDetails)
        }
    }
}

@Composable private fun Composer(input: String, onInput: (String)->Unit, busy: Boolean, canSend: Boolean,
    onSend: ()->Unit, onStop: ()->Unit,onThinking:()->Unit,compareMode:Boolean,thinking:Boolean,
    options:Boolean,onOptions:(Boolean)->Unit,sendLabel:String) {
    Box(Modifier.fillMaxWidth().testTag("composer").animateContentSize(tween(Motion.switchMs,easing=Motion.easing))) {
        Surface(Modifier.matchParentSize().padding(vertical=Sizes.composerInset).testTag("composerSurface"),shape=Radius.surface,
            color=MaterialTheme.colorScheme.surfaceContainerLow) {}
        Column {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                SoftAction(Glyph.Plus,"更多输入选项",{onOptions(!options)},enabled=!busy,tonal=false)
                BasicTextField(value=input,onValueChange=onInput,enabled=!busy,textStyle=MaterialTheme.typography.bodyLarge.copy(color=MaterialTheme.colorScheme.onSurface),
                    cursorBrush=SolidColor(MaterialTheme.colorScheme.primary),minLines=1,maxLines=5,
                    modifier=Modifier.weight(1f).heightIn(min=Sizes.composerInputMin,max=Sizes.composerMax).padding(horizontal=Space.small,vertical=Space.micro).semantics {contentDescription="消息输入框"},
                    decorationBox={field -> Box(contentAlignment=Alignment.CenterStart) {
                        if(input.isEmpty()) Text("发消息…",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant);field()
                    }})
                SoftAction(if(busy) Glyph.Stop else Glyph.Send,if(busy) if(compareMode) "取消全部" else "停止当前操作" else sendLabel,
                    if(busy) onStop else onSend,enabled=busy || canSend,primary=true)
            }
            if(options) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(Space.small)) {
                MeldwiseFilterChip(selected=thinking,onClick={onOptions(false);onThinking()},enabled=!busy,label={Text("思考")},shape=Radius.medium,modifier=Modifier.heightIn(min=Sizes.touch),border=null)
                MeldwiseTextButton(enabled=false,onClick={}) {Text("附件")}
            }
        }
    }
}
@Composable internal fun PanelColumn(title: String, content: @Composable ColumnScope.()->Unit) {
    val pixels=androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height
    val height=with(androidx.compose.ui.platform.LocalDensity.current) {pixels.toDp()*Sizes.sheetFraction}
    Column(Modifier.fillMaxWidth().heightIn(max=height).verticalScroll(rememberScrollState()).padding(horizontal=Space.wide).navigationBarsPadding().padding(bottom=Space.wide),
        verticalArrangement=Arrangement.spacedBy(Space.medium)) {
        Text(title,style=MaterialTheme.typography.titleLarge)
        content()
    }
}
@Composable private fun SettingsRow(title: String, subtitle: String, onClick: ()->Unit) {
    MeldwiseSurface(onClick=onClick,shape=Radius.medium,color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(min=Sizes.touch).padding(Space.content)) {
            Text(title,style=MaterialTheme.typography.titleMedium)
            Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ComponentGallery() {
    PanelColumn("组件预览") {
        Notice("本地排版示例。这里的文字、状态与模型标识仅用于预览。")
        ContentRenderer(remember {ContentParser.parse("# 清晰一点\n\n普通段落支持 **重点**、*强调* 和 `inline code`。\n\n- 留出呼吸感\n- 内容优先\n\n> 一句值得记下来的话。\n\n```text\n  原样保留空格\n  **这里是纯文本**\n```\n\n```kotlin\nfun hello(): String {\n    return \"Hello\"\n}\n```")})
        ReasoningPanel(remember {ReasoningSummary(ReasoningState.Completed,"这是本地排版示例。")})
        CompareResultCard(CompareLane(ModelRef(ProviderIds.CHATGPT,"preview-a"),"示例模型 A",LaneState.Completed,"一张卡片只展示自己的回答与状态。"))
        CompareResultCard(CompareLane(ModelRef(ProviderIds.DEEPSEEK,"preview-b"),"示例模型 B",LaneState.Incomplete,"另一张卡片可以独立保持未完成。"))
    }
}
