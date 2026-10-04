// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.content

/** Widths are content-driven and bounded per cell, never divided by the phone's width. */
object TablePresentation {
    const val MIN_COLUMN_DP = 96
    const val MAX_TEXT_DP = 280
    const val HORIZONTAL_PADDING_DP = 12
    const val VERTICAL_PADDING_DP = 8

    fun description(block:TableBlock)="表格，${block.header.size} 列，${block.rows.size} 行数据"
    fun horizontalOffset(alignment:TableAlignment,available:Int):Int=when(alignment) {
        TableAlignment.Left->0
        TableAlignment.Center->available.coerceAtLeast(0)/2
        TableAlignment.Right->available.coerceAtLeast(0)
    }

    /** Pixel geometry only; contains no provider text. One measurement per native cell. */
    fun geometry(columns:Int,widths:List<Int>,heights:List<Int>,minimumColumn:Int,padX:Int,padY:Int):TableGeometry {
        require(columns in 2..TableBounds.COLUMNS && widths.size==heights.size && widths.size%columns==0)
        require(widths.size/columns in 2..TableBounds.BODY_ROWS+1 && minimumColumn>=0 && padX>=0 && padY>=0)
        val columnWidths=List(columns) {col ->
            (col until widths.size step columns).maxOf {widths[it].coerceAtLeast(0)+padX*2}.coerceAtLeast(minimumColumn)
        }
        val rowHeights=heights.chunked(columns).map {row ->row.maxOf {it.coerceAtLeast(0)}+padY*2}
        return TableGeometry(columnWidths,rowHeights)
    }
}

data class TableGeometry(val columnWidths:List<Int>,val rowHeights:List<Int>) {
    val columnEdges=columnWidths.runningFold(0,Int::plus)
    val rowEdges=rowHeights.runningFold(0,Int::plus)
    val width get()=columnEdges.last()
    val height get()=rowEdges.last()
}
