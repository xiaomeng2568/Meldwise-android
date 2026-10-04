// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.content

import ru.wertik.orcex.core.LatexParser
import ru.wertik.orcex.core.MathNode
import ru.wertik.orcex.core.ParserConfig
import ru.wertik.orcex.layout.MathLayout

/** Unsupported, incomplete or oversized TeX stays verbatim. Original history is never rewritten. */
object MathSafety {
    const val maxChars=2048
    const val maxDepth=24
    const val maxCommands=256
    const val maxDrawCommands=4096
    fun bounded(expression:String):Boolean {
        if(expression.isBlank() || expression.length>maxChars) return false
        var depth=0;var commands=0
        expression.forEachIndexed {i,c ->if(!MathDelimiters.escaped(expression,i)) {
            if(c=='{') {depth++;if(depth>maxDepth) return false}
            if(c=='}') {depth--;if(depth<0) return false}
            if(c=='\\') {commands++;if(commands>maxCommands) return false}
        }}
        return depth==0
    }
    fun parse(expression:String):MathNode? {
        if(!bounded(expression)) return null
        return try {LatexParser(ParserConfig(strictCommands=true)).parse(expression)} catch(_:RuntimeException) {null}
    }
    fun renderable(layout:MathLayout)=layout.commands.isNotEmpty() && layout.commands.size<=maxDrawCommands &&
        layout.width.isFinite() && layout.height.isFinite() && layout.baseline.isFinite() &&
        layout.width>0f && layout.width<=32768f && layout.height>0f && layout.height<=8192f && layout.baseline in 0f..layout.height
}
