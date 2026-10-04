// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.xiaomeng2568.meldwise.ui.presentation.AnswerRole
import io.github.xiaomeng2568.meldwise.ui.theme.*

/** Screen-owned, transient IDs/flags only. Survives lazy-item disposal, never stores answer text
 * or writes instance state/history. A newly opened conversation starts expanded again. */
@Stable class AnswerDisclosures {
    private val collapsed=mutableStateMapOf<String,Unit>()
    fun expanded(key:String)=key !in collapsed
    fun toggle(key:String) {if(key in collapsed) collapsed.remove(key) else collapsed[key]=Unit}
}
val LocalAnswerDisclosures=staticCompositionLocalOf<AnswerDisclosures?> {null}
fun answerDisclosureAvailable(role:AnswerRole,answer:String,key:String?) = !key.isNullOrBlank() &&
    answer.isNotBlank() && role in setOf(AnswerRole.Independent,AnswerRole.Initial,AnswerRole.Review)
fun answerDisclosureLabel(expanded:Boolean)=if(expanded) "收起" else "展开"

@Composable internal fun AnswerDisclosureButton(key:String,heading:String,expanded:Boolean,onToggle:()->Unit) {
    val angle by animateFloatAsState(if(expanded) 180f else 0f,
        tween(Motion.switchMs,easing=Motion.easing),label="answerDisclosureAngle")
    MeldwiseTextButton(onClick=onToggle,modifier=Modifier.testTag("answerDisclosure/$key").semantics {
        contentDescription=answerDisclosureLabel(expanded)+heading
        stateDescription=if(expanded) "已展开" else "已收起"
    }) {
        Text(answerDisclosureLabel(expanded),style=MaterialTheme.typography.labelMedium,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Space.micro))
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            MeldwiseIcon(Glyph.Chevron,Modifier.graphicsLayer {rotationZ=angle},opticalSize=18.dp)
        }
    }
}
@Composable internal fun AnswerDisclosureBody(key:String,expanded:Boolean,content:@Composable ()->Unit) {
    AnimatedVisibility(expanded,
        enter=expandVertically(tween(Motion.switchMs,easing=Motion.easing),expandFrom=Alignment.Top)+fadeIn(tween(Motion.fadeInMs)),
        exit=shrinkVertically(tween(Motion.switchMs,easing=Motion.easing),shrinkTowards=Alignment.Top)+fadeOut(tween(Motion.fadeOutMs))) {
        // The shrinking visual is not an active copy/menu/selection target after collapse.
        val inactive=if(expanded) Modifier else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
            awaitPointerEventScope {while(true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach {it.consume()}}
        }
        Column(Modifier.fillMaxWidth().testTag("answerBody/$key").then(inactive),
            verticalArrangement=Arrangement.spacedBy(MeldwiseContentMetrics.bodyGap)) {content()}
    }
}
