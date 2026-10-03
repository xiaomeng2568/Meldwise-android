package io.github.xiaomeng2568.meldwise.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import io.github.xiaomeng2568.meldwise.ui.theme.Sizes

enum class Glyph { Menu, Plus, Chevron, Send, Stop, Copy, Back, More, Close, Check, Chat, Compare }
/** Original geometric icons. Accessibility labels belong to their interactive parent. */
@Composable fun MeldwiseIcon(glyph: Glyph, modifier: Modifier = Modifier) {
    val color = androidx.compose.material3.LocalContentColor.current
    Canvas(modifier.size(Sizes.icon)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(color, Offset(x,y), Offset(a,b), 1.8f, StrokeCap.Round)
            when(glyph) {
                Glyph.Menu -> { line(4f,6f,20f,6f);line(4f,12f,16f,12f);line(4f,18f,20f,18f) }
                Glyph.Plus -> { line(12f,5f,12f,19f);line(5f,12f,19f,12f) }
                Glyph.Chevron -> { line(6f,9f,12f,15f);line(12f,15f,18f,9f) }
                Glyph.Send -> { line(12f,19f,12f,5f);line(6f,11f,12f,5f);line(12f,5f,18f,11f) }
                Glyph.Stop -> drawRoundRect(color,Offset(6f,6f),Size(12f,12f),androidx.compose.ui.geometry.CornerRadius(2f))
                Glyph.Copy -> {
                    drawRoundRect(color,Offset(8f,8f),Size(12f,12f),androidx.compose.ui.geometry.CornerRadius(3f),style=Stroke(1.7f))
                    val back=Path().apply {moveTo(16f,4f);lineTo(7f,4f);quadraticTo(4f,4f,4f,7f);lineTo(4f,16f)}
                    drawPath(back,color,style=Stroke(1.7f,cap=StrokeCap.Round))
                }
                Glyph.Back -> { line(20f,12f,4f,12f);line(4f,12f,11f,5f);line(4f,12f,11f,19f) }
                Glyph.More -> { listOf(5f,12f,19f).forEach { drawCircle(color,1.6f,Offset(it,12f)) } }
                Glyph.Close -> { line(6f,6f,18f,18f);line(18f,6f,6f,18f) }
                Glyph.Check -> { line(5f,12f,10f,17f);line(10f,17f,20f,7f) }
                Glyph.Chat -> {drawRoundRect(color,Offset(3f,4f),Size(18f,14f),androidx.compose.ui.geometry.CornerRadius(4f),style=Stroke(1.7f));line(7f,18f,5f,21f)}
                Glyph.Compare -> {drawRoundRect(color,Offset(2f,3f),Size(14f,12f),androidx.compose.ui.geometry.CornerRadius(3f),style=Stroke(1.7f));
                    drawRoundRect(color,Offset(8f,10f),Size(14f,11f),androidx.compose.ui.geometry.CornerRadius(3f),style=Stroke(1.7f))}
            }
        }
    }
}
@Composable fun MeldwiseMark(modifier: Modifier = Modifier) {
    val color=MaterialTheme.colorScheme.primary
    Canvas(modifier.size(Sizes.touch)) {
        val path=Path().apply {
            moveTo(size.width*.12f,size.height*.74f);lineTo(size.width*.12f,size.height*.28f)
            quadraticTo(size.width*.22f,size.height*.12f,size.width*.36f,size.height*.28f)
            lineTo(size.width*.5f,size.height*.5f);lineTo(size.width*.64f,size.height*.28f)
            quadraticTo(size.width*.78f,size.height*.12f,size.width*.88f,size.height*.28f)
            lineTo(size.width*.88f,size.height*.74f)
        }
        drawPath(path,color,style=Stroke(size.width*.075f,cap=StrokeCap.Round))
    }
}
