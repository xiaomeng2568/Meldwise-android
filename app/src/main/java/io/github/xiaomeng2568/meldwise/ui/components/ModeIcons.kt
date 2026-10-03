// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import io.github.xiaomeng2568.meldwise.ui.presentation.HistoryCategory

/** Original Meldwise geometry, drawn on a 24dp canvas. No provider artwork or baked tint. */
object ModeIcons {
    private fun outline(name:String,draw:PathBuilder.()->Unit)=ImageVector.Builder(name,24.dp,24.dp,24f,24f)
        .apply {path(fill=null,stroke=SolidColor(Color.Black),strokeLineWidth=1.75f,
            strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round, pathBuilder=draw)}.build()

    val chat=outline("Meldwise.Chat") {
        moveTo(7f,4f);horizontalLineTo(17f);quadTo(21f,4f,21f,8f);verticalLineTo(14f)
        quadTo(21f,18f,17f,18f);horizontalLineTo(8f);lineTo(4.5f,21f);lineTo(5.3f,17.7f)
        quadTo(3f,17f,3f,14f);verticalLineTo(8f);quadTo(3f,4f,7f,4f);close()
    }
    val compare=outline("Meldwise.Compare") {
        // Original magnifying glass: one lens and a short rounded handle.
        moveTo(17f,10f);curveTo(17f,13.866f,13.866f,17f,10f,17f)
        curveTo(6.134f,17f,3f,13.866f,3f,10f)
        curveTo(3f,6.134f,6.134f,3f,10f,3f)
        curveTo(13.866f,3f,17f,6.134f,17f,10f);close()
        moveTo(15f,15f);lineTo(21f,21f)
    }
    val collaborate=outline("Meldwise.Collaborate") {
        // Omit the covered lower-right part of the back box. No theme-dependent masking fill.
        moveTo(7f,15f);horizontalLineTo(6f);quadTo(3f,15f,3f,12f);verticalLineTo(6f)
        quadTo(3f,3f,6f,3f);horizontalLineTo(12f);quadTo(15f,3f,15f,6f);verticalLineTo(7f)
        // Full front box, separated from the rear endpoints by a small optical gap.
        moveTo(12f,9f);horizontalLineTo(18f);quadTo(21f,9f,21f,12f);verticalLineTo(18f)
        quadTo(21f,21f,18f,21f);horizontalLineTo(12f);quadTo(9f,21f,9f,18f)
        verticalLineTo(12f);quadTo(9f,9f,12f,9f);close()
    }
    val debate=outline("Meldwise.Debate") {
        moveTo(5f,3.5f);horizontalLineTo(12f);quadTo(14f,3.5f,14f,5.5f);verticalLineTo(8f)
        quadTo(14f,10f,12f,10f);horizontalLineTo(10f);lineTo(13f,12f);lineTo(7f,10f)
        horizontalLineTo(5f);quadTo(3f,10f,3f,8f);verticalLineTo(5.5f);quadTo(3f,3.5f,5f,3.5f);close()
        moveTo(19f,14f);horizontalLineTo(17f);lineTo(11f,12f);lineTo(14f,14f);horizontalLineTo(12f)
        quadTo(10f,14f,10f,16f);verticalLineTo(18.5f);quadTo(10f,20.5f,12f,20.5f)
        horizontalLineTo(19f);quadTo(21f,20.5f,21f,18.5f);verticalLineTo(16f);quadTo(21f,14f,19f,14f);close()
    }
    fun forCategory(category:HistoryCategory)=when(category) {
        HistoryCategory.Chat->chat;HistoryCategory.Compare->compare
        HistoryCategory.Collaborate->collaborate;HistoryCategory.Debate->debate
    }
}
