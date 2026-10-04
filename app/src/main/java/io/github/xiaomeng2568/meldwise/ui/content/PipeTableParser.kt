// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.content

enum class TableAlignment { Left, Center, Right }

/** Reject the entire candidate on overflow, rather than silently dropping cells/rows. */
object TableBounds {
    const val COLUMNS = 12
    const val BODY_ROWS = 64
    const val CHARACTERS = RenderBounds.SURFACE_CHARS
    const val CELL_CHARACTERS = 4096
    // Many tiny/empty tables must not create tens of thousands of native text nodes.
    const val DOCUMENT_CELLS = 1024
}

/** A rejected, otherwise table-shaped region remains one ordinary text block. */
class TableCandidate(val source:String,val endExclusive:Int,val block:TableBlock?) {
    override fun toString()="TableCandidate(end=$endExclusive, content=[REDACTED])"
}

/**
 * Strict pipe-table subset: 2..12 columns, header + delimiter + at least one body row.
 * Every delimiter cell is :?-{3,}:?; every body row has exactly the header's column count.
 * Fences/display math/headings/lists/quotes retain the outer parser's precedence.
 * No padded/truncated rows, HTML, multiline cells, recursive block parsing or persistence.
 */
object PipeTableParser {
    private val delimiter = Regex(":?-{3,}:?")

    fun candidate(lines:List<String>,start:Int,isBoundary:(String)->Boolean):TableCandidate? {
        if(start<0 || start+1>=lines.size) return null
        val headerLine=lines[start].removeSuffix("\r")
        val separatorLine=lines[start+1].removeSuffix("\r")
        if(isBoundary(headerLine) || isBoundary(separatorLine)) return null
        val header=splitRow(headerLine) ?: return null
        val separator=splitRow(separatorLine) ?: return null
        if(header.size !in 2..TableBounds.COLUMNS || header.all {it.isBlank()} ||
            header.size!=separator.size || separator.any {!delimiter.matches(it)}) return null
        val alignments=separator.map {
            when {it.startsWith(':') && it.endsWith(':')->TableAlignment.Center
                it.endsWith(':')->TableAlignment.Right
                else->TableAlignment.Left}
        }
        val rows=mutableListOf<List<String>>()
        var end=start+2
        var characters=lines[start].length+1+lines[start+1].length
        var rejected=header.any {it.length>TableBounds.CELL_CHARACTERS}
        var bodyCount=0
        while(end<lines.size) {
            val line=lines[end].removeSuffix("\r")
            if(line.isBlank() || isBoundary(line) || '|' !in line) break
            characters+=1+lines[end].length
            val cells=splitRow(line)
            bodyCount++
            if(cells==null || cells.size!=header.size || cells.any {it.length>TableBounds.CELL_CHARACTERS} ||
                bodyCount>TableBounds.BODY_ROWS || characters>TableBounds.CHARACTERS) rejected=true
            if(!rejected && cells!=null) rows+=cells
            end++
        }
        val source=lines.subList(start,end).joinToString("\n")
        val block=if(rejected || bodyCount==0 || characters>TableBounds.CHARACTERS) null
            else TableBlock(source,header,rows,alignments)
        return TableCandidate(source,end,block)
    }

    /** Complete inline code/math shields its pipes; only unescaped, outside pipes divide cells. */
    internal fun splitRow(line:String):List<String>? {
        if(line.startsWith('\t') || line.takeWhile {it==' '}.length>3) return null
        val pipes=mutableListOf<Int>()
        var i=0
        while(i<line.length) {
            if(line[i]=='`' && !MathDelimiters.escaped(line,i)) {
                val end=MathDelimiters.codeEnd(line,i)
                if(end<=i) return null // Ambiguous unclosed code span: keep the original text.
                i=end;continue
            }
            if(line[i]=='$' || line[i]=='\\') {
                val math=MathDelimiters.at(line,i)
                if(math!=null) {i=math.end;continue}
            }
            if(line[i]=='|' && !MathDelimiters.escaped(line,i)) {
                pipes+=i
                if(pipes.size>TableBounds.COLUMNS+1) return null
            }
            i++
        }
        if(pipes.isEmpty()) return null
        val cuts=mutableListOf(-1).apply {addAll(pipes);add(line.length)}
        val cells=cuts.zipWithNext {a,b->line.substring(a+1,b).trim(' ','\t')}.toMutableList()
        if(line.substring(0,pipes.first()).isBlank()) cells.removeAt(0)
        if(line.substring(pipes.last()+1).isBlank()) cells.removeAt(cells.lastIndex)
        return cells.takeIf {it.size in 2..TableBounds.COLUMNS}
    }

    /** Remove only a structural pipe escape outside code/math. Cell source itself stays exact. */
    fun inlineSource(source:String):String {
        val result=StringBuilder()
        var i=0
        while(i<source.length) {
            if(source[i]=='`' && !MathDelimiters.escaped(source,i)) {
                val end=MathDelimiters.codeEnd(source,i)
                if(end>i) {result.append(source,i,end);i=end;continue}
            }
            if(source[i]=='$' || source[i]=='\\') {
                val math=MathDelimiters.at(source,i)
                if(math!=null) {result.append(source,i,math.end);i=math.end;continue}
            }
            if(source[i]=='\\' && i+1<source.length && source[i+1]=='|' && MathDelimiters.escaped(source,i+1)) {
                result.append('|');i+=2
            } else {result.append(source[i]);i++}
        }
        return result.toString()
    }
}
