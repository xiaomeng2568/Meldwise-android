// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.*
import io.github.xiaomeng2568.meldwise.ui.theme.*

object PressFeedbackPolicy {
    const val pressMs=100
    const val releaseMs=140
    const val pressedAlpha=.04f
    const val focusAlpha=.02f
    fun alpha(enabled:Boolean,pressed:Boolean,focused:Boolean=false)=when {
        !enabled->0f;pressed->pressedAlpha;focused->focusAlpha;else->0f
    }
}
val MeldwisePressedKey=SemanticsPropertyKey<Boolean>("MeldwisePressed")
var SemanticsPropertyReceiver.meldwisePressed by MeldwisePressedKey

/** Uniform neutral state layer. Compose animation honors the host animator-duration scale.
 * Click handling remains with the original clickable/button; there is no extra gesture handler.
 */
@Composable fun Modifier.meldwisePressFeedback(source:MutableInteractionSource,enabled:Boolean=true):Modifier {
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    val alpha by animateFloatAsState(PressFeedbackPolicy.alpha(enabled,pressed,focused || hovered),
        tween(if(pressed && enabled) PressFeedbackPolicy.pressMs else PressFeedbackPolicy.releaseMs),label="meldwisePress")
    val tint=MaterialTheme.colorScheme.onSurface
    return this.semantics {meldwisePressed=enabled && pressed}.drawWithContent {
        drawContent()
        if(alpha>0f) drawRect(tint,alpha=alpha)
    }
}
@Composable fun Modifier.meldwiseClickable(enabled:Boolean=true,role:Role?=Role.Button,onClick:()->Unit):Modifier {
    val source=remember {MutableInteractionSource()}
    return heightIn(min=Sizes.touch).clip(Radius.medium).meldwisePressFeedback(source,enabled)
        .clickable(interactionSource=source,indication=null,enabled=enabled,role=role,onClick=onClick)
}
@Composable fun MeldwiseTextButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    content:@Composable RowScope.()->Unit) {
    val source=remember {MutableInteractionSource()}
    TextButton(onClick,modifier.heightIn(min=Sizes.touch).clip(Radius.medium).meldwisePressFeedback(source,enabled),
        enabled=enabled,interactionSource=source,content=content)
}
@Composable fun MeldwiseButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    content:@Composable RowScope.()->Unit) {
    val source=remember {MutableInteractionSource()}
    Button(onClick,modifier.heightIn(min=Sizes.touch).clip(Radius.bubble).meldwisePressFeedback(source,enabled),
        enabled=enabled,interactionSource=source,content=content)
}
@Composable fun MeldwiseFilterChip(selected:Boolean,onClick:()->Unit,label:@Composable ()->Unit,
    modifier:Modifier=Modifier,enabled:Boolean=true,shape:Shape=Radius.medium,border:BorderStroke?=null) {
    val source=remember {MutableInteractionSource()}
    FilterChip(selected,onClick,label,modifier.heightIn(min=Sizes.touch).clip(shape).meldwisePressFeedback(source,enabled),
        enabled=enabled,shape=shape,border=border,interactionSource=source)
}
@Composable fun MeldwiseSurface(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    shape:Shape=Radius.medium,color:Color=MaterialTheme.colorScheme.surface,
    content:@Composable ()->Unit) {
    val source=remember {MutableInteractionSource()}
    Surface(onClick,modifier.heightIn(min=Sizes.touch).clip(shape).meldwisePressFeedback(source,enabled),
        enabled=enabled,shape=shape,color=color,interactionSource=source,content=content)
}
@Composable fun MeldwiseMenuItem(text:@Composable ()->Unit,onClick:()->Unit,enabled:Boolean=true) {
    val source=remember {MutableInteractionSource()}
    DropdownMenuItem(text,onClick,Modifier.heightIn(min=Sizes.touch).clip(Radius.small).meldwisePressFeedback(source,enabled),
        enabled=enabled,interactionSource=source)
}
