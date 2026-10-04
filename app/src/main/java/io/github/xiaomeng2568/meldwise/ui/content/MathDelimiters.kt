// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.content

class MathMatch(val source:String,val expression:String,val end:Int,val display:Boolean) {
    override fun toString()="MathMatch([REDACTED])"
}
/** Bounded delimiter recognition only, not a TeX interpreter. Code takes precedence. */
object MathDelimiters {
    fun displayStart(text:String)=text.startsWith("$$") || text.startsWith("\\[")
    fun escaped(text:String,index:Int):Boolean {var n=0;var i=index-1;while(i>=0 && text[i]=='\\') {n++;i--};return n%2==1}
    fun codeEnd(text:String,start:Int):Int {
        val n=text.substring(start).takeWhile {it=='`'}.length
        if(n==0) return -1
        val end=text.indexOf("`".repeat(n),start+n)
        return if(end>=start+n) end+n else -1
    }
    fun at(text:String,start:Int):MathMatch? {
        if(start !in text.indices || escaped(text,start)) return null
        if(text[start]=='$' && start>0 && text[start-1]=='$') return null
        val open=when {text.startsWith("$$",start)->"$$";text.startsWith("\\[",start)->"\\["
            text.startsWith("\\(",start)->"\\(";text[start]=='$'->"$";else->return null}
        val close=when(open) {"\\["->"\\]";"\\("->"\\)";else->open}
        val begin=start+open.length
        if(begin>=text.length || open=="$" && text[begin].isWhitespace()) return null
        var end=text.indexOf(close,begin)
        while(end>=0 && escaped(text,end)) end=text.indexOf(close,end+close.length)
        if(end<=begin) return null
        if(open=="$" && (text[end-1].isWhitespace() || end+1<text.length && text[end+1].isDigit() || text.substring(begin,end).contains('\n'))) return null
        val expression=text.substring(begin,end)
        if(expression.isBlank()) return null
        return MathMatch(text.substring(start,end+close.length),expression,end+close.length,open=="$$" || open=="\\[")
    }
}
