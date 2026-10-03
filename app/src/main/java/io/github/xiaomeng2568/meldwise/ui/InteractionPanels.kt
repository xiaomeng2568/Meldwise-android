package io.github.xiaomeng2568.meldwise.ui

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable internal fun ModePicker(enabled:Boolean,onSingle:()->Unit,onCompare:()->Unit) {
    PanelColumn("开始") {
        ModeRow("对话","和一个模型聊聊",Glyph.Chat,enabled,onSingle)
        ModeRow("对比","看看两个模型怎么回答",Glyph.Compare,enabled,onCompare)
        ModeRow("辩论","暂未开放",Glyph.More,false,{})
    }
}
@Composable private fun ModeRow(title:String,subtitle:String,glyph:Glyph,enabled:Boolean,onClick:()->Unit) {
    Surface(onClick=onClick,enabled=enabled,shape=Radius.bubble,color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(Space.section),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Space.content)) {
            MeldwiseIcon(glyph)
            Column(Modifier.weight(1f)) {Text(title,style=MaterialTheme.typography.titleMedium);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
    }
}
@Composable internal fun HistoryRow(title:String,subtitle:String,busy:Boolean,canUp:Boolean,canDown:Boolean,
    onOpen:()->Unit,onDelete:()->Unit,onMove:(Int)->Unit) {
    var menu by remember {mutableStateOf(false)}
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(enabled=!busy,onClick=onOpen).padding(vertical=Space.medium)) {
            Text(title,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(subtitle,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        Box {
            SoftAction(Glyph.More,"记录操作",{menu=true},enabled=!busy,tonal=false)
            DropdownMenu(expanded=menu,onDismissRequest={menu=false},shape=Radius.surface) {
                DropdownMenuItem(text={Text("上移")},enabled=canUp && !busy,onClick={menu=false;onMove(-1)})
                DropdownMenuItem(text={Text("下移")},enabled=canDown && !busy,onClick={menu=false;onMove(1)})
                DropdownMenuItem(text={Text("删除")},enabled=!busy,onClick={menu=false;onDelete()})
            }
        }
    }
}
@OptIn(ExperimentalAnimationApi::class)
@Composable internal fun CatalogBanner(notice:CatalogNotice?,onDismiss:(Long)->Unit,onDetails:(CatalogNotice)->Unit,modifier:Modifier=Modifier) {
    AnimatedContent(targetState=notice,modifier=modifier.widthIn(max=Sizes.noticeMax),label="catalogNotice",
        transitionSpec={(slideInHorizontally {width ->-width}+fadeIn()) togetherWith fadeOut()}) {current ->
        if(current!=null) {
            val tones=noticeColors(current.kind)
            Surface(shape=Radius.surface,color=tones.first,border=BorderStroke(Sizes.noticeBorder,tones.second),
                modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite}.clickable(role=Role.Button) {onDetails(current)}) {
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
    if(notice==null) return
    val density=LocalDensity.current
    val margin=with(density) {Space.content.roundToPx()}
    val top=WindowInsets.safeDrawing.getTop(density)+margin
    val position=remember(margin,top) {object:PopupPositionProvider {
        override fun calculatePosition(anchorBounds:IntRect,windowSize:IntSize,layoutDirection:LayoutDirection,popupContentSize:IntSize):IntOffset =
            IntOffset(margin.coerceAtMost((windowSize.width-popupContentSize.width).coerceAtLeast(0)),
                top.coerceAtMost((windowSize.height-popupContentSize.height).coerceAtLeast(0)))
    }}
    Popup(popupPositionProvider=position,
        properties=PopupProperties(focusable=false,dismissOnBackPress=false,dismissOnClickOutside=false)) {
        CatalogBanner(notice,onDismiss,onDetails,Modifier.padding(end=Space.content))
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
