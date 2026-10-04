// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.components

import android.graphics.Typeface
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.wertik.orcex.font.stix2.StixTwoMath
import ru.wertik.orcex.layout.*
import ru.wertik.orcex.render.android.*
import io.github.xiaomeng2568.meldwise.ui.content.*
import kotlin.math.ceil

private class NativeMath(val typeface:Typeface,val layout:MathLayout)
private object MathWorker {
    val dispatcher=Dispatchers.Default.limitedParallelism(1)
    @Volatile var font:Typeface?=null
}
@Composable private fun rememberMath(expression:String,fontSize:Float):NativeMath? {
    val context=LocalContext.current.applicationContext
    val parsed by produceState<NativeMath?>(null,expression,fontSize) {
        value=null
        value=withContext(MathWorker.dispatcher) {
            try {
                val node=MathSafety.parse(expression) ?: return@withContext null
                val face=MathWorker.font ?: StixTwoMath.load(context).also {MathWorker.font=it}
                val layout=MathLayoutEngine(AndroidFontMetrics(face)).layout(node,MathStyle(fontSize=fontSize))
                if(!MathSafety.renderable(layout)) null else NativeMath(face,layout.copy(commands=layout.commands.map {
                    when(it) {is DrawCommand.Text->it.copy(style=it.style.copy(color=null));is DrawCommand.Line->it.copy(color=null)}
                }))
            } catch(cancel:kotlinx.coroutines.CancellationException) {throw cancel} catch(_:RuntimeException) {null} catch(_:java.io.IOException) {null}
        }
    }
    return parsed
}
@Composable private fun MathCanvas(math:NativeMath,source:String,modifier:Modifier=Modifier) {
    val color=MaterialTheme.colorScheme.onSurface.toArgb()
    val renderer=remember(math,color) {CanvasMathRenderer(math.typeface,color)}
    Canvas(modifier.semantics {contentDescription=source}.testTag("nativeMath")) {
        renderer.draw(drawContext.canvas.nativeCanvas,math.layout,0f,0f)
    }
}
@Composable fun DisplayMath(block:MathBlock) {
    val density=LocalDensity.current
    val size=with(density) {MaterialTheme.typography.bodyLarge.fontSize.toPx()}
    val math=rememberMath(block.expression,size)
    if(math==null) {SelectionContainer {Text(block.source,style=MaterialTheme.typography.bodyLarge)};return}
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        MathCanvas(math,block.source,Modifier.size(with(density) {ceil(math.layout.width).toDp()},with(density) {ceil(math.layout.height).toDp()}))
    }
}

/** Native inline objects keep prose in one text flow. Oversized inline math stays literal. */
@Composable fun MathRichText(text:String,style:TextStyle,modifier:Modifier=Modifier) {
    val density=LocalDensity.current
    val colors=MaterialTheme.colorScheme
    val runs=remember(text) {InlineParser.parse(text)}
    BoxWithConstraints(modifier) {
        val inline=mutableMapOf<String,InlineTextContent>()
        val layouts=mutableMapOf<Int,NativeMath>()
        runs.forEachIndexed {index,run ->if(run.expression!=null && index<128) {
            val math=rememberMath(run.expression,with(density) {style.fontSize.toPx()})
            if(math!=null && with(density) {math.layout.width.toDp()}<=maxWidth && math.layout.height<=with(density) {128.dp.toPx()}) layouts[index]=math
        }}
        val annotated=buildAnnotatedString {
            runs.forEachIndexed {index,run ->
                val math=layouts[index]
                if(math!=null) {
                    val key="math$index"
                    inline[key]=InlineTextContent(Placeholder(with(density) {ceil(math.layout.width).toSp()},with(density) {ceil(math.layout.height).toSp()},PlaceholderVerticalAlign.TextCenter)) {
                        MathCanvas(math,run.text,Modifier.fillMaxSize())
                    }
                    appendInlineContent(key,run.text)
                } else withStyle(when(run.style) {
                    InlineStyle.Strong->SpanStyle(fontWeight=FontWeight.Bold)
                    InlineStyle.Emphasis->SpanStyle(fontStyle=FontStyle.Italic)
                    InlineStyle.Code->SpanStyle(fontFamily=FontFamily.Monospace,background=colors.surfaceVariant)
                    else->SpanStyle()
                }) {append(run.text)}
            }
        }
        SelectionContainer {Text(annotated,style=style,inlineContent=inline)}
    }
}
