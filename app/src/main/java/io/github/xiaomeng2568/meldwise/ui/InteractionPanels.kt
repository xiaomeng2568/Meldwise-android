package io.github.xiaomeng2568.meldwise.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.xiaomeng2568.meldwise.ui.components.*
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.ui.theme.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*

@Composable internal fun ContextSharingDialog(providerId:String,onContinue:()->Unit,onCancel:()->Unit) {
    val name=providerLabel(providerId)
    AlertDialog(onDismissRequest=onCancel,
        title={Text("用 $name 继续聊？")},
        text={Text("继续会把这段对话中选入上下文的消息和回答发给 $name。思考过程只留在本机。这个选择只用于当前对话。")},
        confirmButton={MeldwiseTextButton(onClick=onContinue) {Text("继续")}},
        dismissButton={MeldwiseTextButton(onClick=onCancel) {Text("取消")}})
}

@Composable internal fun CollaborateSharingDialog(config:CollaborateConfig,onContinue:()->Unit,onCancel:()->Unit) {
    val names=config.providers.sorted().joinToString("、") {providerLabel(it)}
    AlertDialog(onDismissRequest=onCancel,title={Text("让 $names 一起协作？")},
        text={Text("协作会把选入上下文的消息，以及参与模型的可见回答发给所选服务商，用于审阅和综合。思考过程只留在本机。这个选择只用于当前对话和这组服务商。")},
        confirmButton={MeldwiseTextButton(onClick=onContinue) {Text("继续")}},dismissButton={MeldwiseTextButton(onClick=onCancel) {Text("取消")}})
}

@Composable internal fun ModePicker(enabled:Boolean,onSingle:()->Unit,onCompare:()->Unit,onCollaborate:()->Unit={},selected:HistoryCategory?=null) {
    PanelColumn("开始") {
        HistoryCategory.entries.forEach {mode ->ModeRow(mode,mode.description,enabled && mode.available,
            onClick=when(mode) {HistoryCategory.Chat->onSingle;HistoryCategory.Compare->onCompare
                HistoryCategory.Collaborate->onCollaborate;HistoryCategory.Debate->({})},selected=selected==mode)}
    }
}
@Composable private fun ModeRow(mode:HistoryCategory,subtitle:String,enabled:Boolean,onClick:()->Unit,
    selected:Boolean=false,forward:Boolean=false) {
    val colors=MaterialTheme.colorScheme
    MeldwiseSurface(onClick=onClick,enabled=enabled,shape=Radius.medium,
        color=if(selected) colors.primaryContainer else Color.Transparent,
        modifier=Modifier.testTag("modeRow-${mode.name}").semantics {this.selected=selected}) {
        Row(Modifier.fillMaxWidth().heightIn(min=Sizes.modeRowMin).padding(horizontal=Space.small,vertical=Space.medium),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Space.content)) {
            CompositionLocalProvider(LocalContentColor provides if(enabled) colors.primary else colors.onSurfaceVariant.copy(alpha=.45f)) {
                val glyph=when(mode) {HistoryCategory.Chat->Glyph.Chat;HistoryCategory.Compare->Glyph.Compare
                    HistoryCategory.Collaborate->Glyph.Collaborate;HistoryCategory.Debate->Glyph.Debate}
                MeldwiseIcon(glyph,Modifier.testTag("modeIcon-${mode.name}"))
            }
            Column(Modifier.weight(1f)) {
                Text(mode.title,style=MaterialTheme.typography.titleMedium,color=if(enabled) colors.onSurface else colors.onSurfaceVariant)
                Text(subtitle,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
            }
            if(forward && mode.available) MeldwiseIcon(Glyph.Forward,opticalSize=18.dp)
            else if(selected) MeldwiseIcon(Glyph.Check,opticalSize=18.dp)
        }
    }
}
@Composable internal fun HistoryPanel(category:HistoryCategory?,sessions:List<SingleSessionInfo>,runs:List<CompareRun>,busy:Boolean,
    onCategory:(HistoryCategory)->Unit,onOpen:(HistoryEntry)->Unit,onDelete:(HistoryEntry)->Unit,onMove:(HistoryEntry,Int)->Unit) {
    PanelColumn(category?.title ?: "历史") {
        if(category==null) historySummaries(sessions,runs).forEach {summary ->
            ModeRow(summary.category,summary.label,!busy && summary.category.available,{onCategory(summary.category)},forward=true)
        } else {
            val entries=historyEntries(category,sessions,runs)
            if(entries.isEmpty()) Text("还没有记录",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            entries.forEachIndexed {index,entry ->key(entry.id) {
                HistoryRow(entry.title,entry.subtitle,busy,index>0,index<entries.lastIndex,
                    {onOpen(entry)},{onDelete(entry)},{onMove(entry,it)})
            }}
        }
    }
}
@Composable internal fun HistoryRow(title:String,subtitle:String,busy:Boolean,canUp:Boolean,canDown:Boolean,
    onOpen:()->Unit,onDelete:()->Unit,onMove:(Int)->Unit) {
    var menu by remember {mutableStateOf(false)}
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f).meldwiseClickable(enabled=!busy,onClick=onOpen).padding(vertical=Space.medium)) {
            Text(title,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        Box {
            SoftAction(Glyph.More,"记录操作",{menu=true},enabled=!busy,tonal=false)
            DropdownMenu(expanded=menu,onDismissRequest={menu=false},shape=Radius.surface) {
                MeldwiseMenuItem(text={Text("上移")},enabled=canUp && !busy,onClick={menu=false;onMove(-1)})
                MeldwiseMenuItem(text={Text("下移")},enabled=canDown && !busy,onClick={menu=false;onMove(1)})
                MeldwiseMenuItem(text={Text("删除")},enabled=!busy,onClick={menu=false;onDelete()})
            }
        }
    }
}
@OptIn(ExperimentalAnimationApi::class)
@Composable internal fun CatalogBanner(notice:CatalogNotice?,onDismiss:(Long)->Unit,onDetails:(CatalogNotice)->Unit,modifier:Modifier=Modifier,
    availableWidth:Dp=MeldwiseContentMetrics.readableMax) {
    AnimatedContent(targetState=notice,modifier=modifier.widthIn(max=MeldwiseContentMetrics.noticeWidth(availableWidth)),label="catalogNotice",
        transitionSpec={fadeIn(tween(Motion.fadeInMs)) togetherWith fadeOut(tween(Motion.fadeOutMs))}) {current ->
        if(current!=null) {
            val tones=noticeColors(current.kind)
            Surface(shape=Radius.surface,color=tones.first,border=BorderStroke(Sizes.noticeBorder,tones.second),
                modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite}.meldwiseClickable(role=Role.Button) {onDetails(current)}) {
                Row(Modifier.padding(start=Space.content,top=Space.medium,bottom=Space.medium),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(Space.medium)) {
                    if(current.kind==CatalogNoticeKind.Success) MeldwiseIcon(Glyph.Check) else Text("!",style=MaterialTheme.typography.titleMedium)
                    Column(Modifier.weight(1f)) {
                        Text(current.title,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurface)
                        current.detail?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurface)}
                    }
                    SoftAction(Glyph.Close,"关闭加载提示",{onDismiss(current.id)},tonal=false)
                }
            }
        }
    }
}
/** Attach to the active window (chat or modal sheet) so the sheet cannot cover the notice. */
@Composable internal fun CatalogNoticeOverlay(notice:CatalogNotice?,onDismiss:(Long)->Unit,onDetails:(CatalogNotice)->Unit) {
    val visible=remember {MutableTransitionState(false)}
    var retained by remember {mutableStateOf<CatalogNotice?>(null)}
    if(notice!=null) retained=notice
    visible.targetState=notice!=null
    if(!visible.currentState && !visible.targetState && visible.isIdle) return
    val density=LocalDensity.current
    // Measure in the owning app/sheet window, not in a wrap-content popup window.
    val availableWidth=with(density) {androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp()}
    if(availableWidth<=0.dp) return
    val margin=with(density) {Space.content.roundToPx()}
    val top=WindowInsets.safeDrawing.getTop(density)+margin
    val position=remember(margin,top,density) {object:PopupPositionProvider {
        override fun calculatePosition(anchorBounds:IntRect,windowSize:IntSize,layoutDirection:LayoutDirection,popupContentSize:IntSize):IntOffset =
            IntOffset(with(density) {MeldwiseContentMetrics.leadingInset(windowSize.width.toDp()).roundToPx()}
                .coerceAtMost((windowSize.width-popupContentSize.width).coerceAtLeast(0)),
                top.coerceAtMost((windowSize.height-popupContentSize.height).coerceAtLeast(0)))
    }}
    Popup(popupPositionProvider=position,
        properties=PopupProperties(focusable=false,dismissOnBackPress=false,dismissOnClickOutside=false,clippingEnabled=false)) {
        AnimatedVisibility(visibleState=visible,
            enter=slideInHorizontally(tween(Motion.switchMs,easing=Motion.easing)) {-margin*2}+fadeIn(tween(Motion.fadeInMs)),
            exit=slideOutHorizontally(tween(Motion.switchMs,easing=Motion.easing)) {-margin}+fadeOut(tween(Motion.fadeOutMs))) {
            CatalogBanner(retained,onDismiss,onDetails,Modifier.padding(end=Space.content),availableWidth)
        }
    }
}
@Composable internal fun CatalogDetails(notice:CatalogNotice) {
    PanelColumn("模型加载详情") {
        Text(notice.title,style=MaterialTheme.typography.bodyMedium)
        notice.detail?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
        Text("providerId=${notice.providerId}",style=MaterialTheme.typography.labelSmall)
        notice.diagnostic?.let {Text(it.summary(),style=MaterialTheme.typography.bodySmall)}
    }
}
