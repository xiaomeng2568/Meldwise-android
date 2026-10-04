// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.Motion

/** Route-only snapshot: stream updates and selection changes do not start a page transition. */
internal data class PanelDestination(val panel:ChatPanel,val depth:Int,val modelLane:String?=null,val debatePicker:Boolean=false) {
    val historyCategory get()=when(panel) {
        ChatPanel.HistoryChat->HistoryCategory.Chat
        ChatPanel.HistoryCompare->HistoryCategory.Compare
        ChatPanel.HistoryCollaborate->HistoryCategory.Collaborate
        ChatPanel.HistoryDebate->HistoryCategory.Debate
        else->null
    }
    fun directionFrom(previous:PanelDestination)=if(depth<previous.depth) -1 else 1
}

/** Shared by every modal route, with a reversed motion when returning to a parent. */
@Composable internal fun PanelTransition(navigation:ChatNavigation,modelLane:String?=null,interactive:Boolean=true,
    content:@Composable (PanelDestination)->Unit) {
    val destination=PanelDestination(navigation.panel,navigation.stack.size,
        modelLane.takeIf {navigation.panel==ChatPanel.Models},
        navigation.panel==ChatPanel.Models && ChatPanel.DebateSetup in navigation.stack)
    val distance=with(LocalDensity.current) {Motion.panelShift.roundToPx()}
    AnimatedContent(targetState=destination,modifier=Modifier.fillMaxWidth().clipToBounds(),
        contentKey={it.panel},label="panelNavigation",transitionSpec={
            val direction=targetState.directionFrom(initialState)
            ((slideInHorizontally(tween(Motion.switchMs,easing=Motion.easing)) {direction*distance}+
                fadeIn(tween(Motion.fadeInMs))) togetherWith
                (slideOutHorizontally(tween(Motion.switchMs,easing=Motion.easing)) {-direction*distance}+
                    fadeOut(tween(Motion.fadeOutMs))))
                .using(SizeTransform(clip=true,sizeAnimationSpec={_,_->tween(Motion.switchMs,easing=Motion.easing)}))
        }) {frame ->
        val current=frame==destination && interactive
        // The outgoing page is visual-only; it must not receive a second tap or TalkBack action.
        val inactive=if(current) Modifier else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
            awaitPointerEventScope {while(true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach {it.consume()}}
        }
        Box(Modifier.fillMaxWidth().zIndex(if(current) 1f else 0f).then(inactive)) {content(frame)}
    }
}
