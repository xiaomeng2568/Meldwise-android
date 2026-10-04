// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import io.github.xiaomeng2568.meldwise.ui.content.*

/** Native bounded table. The transcript owns vertical scrolling; selection remains per cell. */
@Composable fun MarkdownTable(block:TableBlock) {
    val colors=MaterialTheme.colorScheme
    val bodyStyle=MaterialTheme.typography.bodyMedium.copy(color=colors.onSurface)
    val headerStyle=bodyStyle.copy(fontWeight=FontWeight.Medium)
    val rows=remember(block) {listOf(block.header)+block.rows}
    var geometry by remember(block) {mutableStateOf<TableGeometry?>(null)}
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("markdownTable")
        .semantics {
            contentDescription=TablePresentation.description(block)
            collectionInfo=CollectionInfo(rows.size,block.header.size)
        }) {
        Layout(content={
            rows.forEachIndexed {row,cells -> cells.forEachIndexed {column,source ->
                Box(Modifier.testTag("tableCell-$row-$column").semantics {
                    collectionItemInfo=CollectionItemInfo(row,1,column,1)
                    if(row==0) heading()
                    if(source.isEmpty()) contentDescription="空单元格"
                }) {
                    val inline=remember(source) {PipeTableParser.inlineSource(source)}
                    val align=when(block.alignments[column]) {
                        TableAlignment.Left->TextAlign.Left
                        TableAlignment.Center->TextAlign.Center
                        TableAlignment.Right->TextAlign.Right
                    }
                    MathRichText(inline,(if(row==0) headerStyle else bodyStyle).copy(textAlign=align))
                }
            }}
        },modifier=Modifier.testTag("tableGrid").drawWithContent {
            val grid=geometry
            if(grid!=null) drawRect(colors.surfaceContainerLow,size=Size(grid.width.toFloat(),grid.rowHeights.first().toFloat()))
            drawContent()
            if(grid!=null) {
                val rule=colors.outlineVariant.copy(alpha=.6f)
                val stroke=1.dp.toPx()
                // Thin internal rules, no elevated cards, per-cell surfaces or thick outer frame.
                grid.rowEdges.drop(1).forEach {y ->drawLine(rule,Offset(0f,y.toFloat()),Offset(grid.width.toFloat(),y.toFloat()),stroke)}
                grid.columnEdges.drop(1).dropLast(1).forEach {x ->drawLine(rule,Offset(x.toFloat(),0f),Offset(x.toFloat(),grid.height.toFloat()),stroke)}
            }
        }) {measurables,constraints ->
            val padX=TablePresentation.HORIZONTAL_PADDING_DP.dp.roundToPx()
            val padY=TablePresentation.VERTICAL_PADDING_DP.dp.roundToPx()
            val cells=measurables.map {it.measure(Constraints(maxWidth=TablePresentation.MAX_TEXT_DP.dp.roundToPx()))}
            val grid=TablePresentation.geometry(block.header.size,cells.map {it.width},cells.map {it.height},
                TablePresentation.MIN_COLUMN_DP.dp.roundToPx(),padX,padY)
            geometry=grid // Read in draw phase only; async native math can safely change measured cell size.
            layout(constraints.constrainWidth(grid.width),constraints.constrainHeight(grid.height)) {
                cells.forEachIndexed {index,cell ->
                    val column=index%block.header.size
                    val row=index/block.header.size
                    val available=grid.columnWidths[column]-padX*2-cell.width
                    val x=grid.columnEdges[column]+padX+TablePresentation.horizontalOffset(block.alignments[column],available)
                    cell.place(x,grid.rowEdges[row]+padY)
                }
            }
        }
    }
}
