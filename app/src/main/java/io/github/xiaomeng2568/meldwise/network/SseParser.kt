package io.github.xiaomeng2568.meldwise.network

import io.github.xiaomeng2568.meldwise.security.utf8
import okio.BufferedSource

class SseFrame(val event:String?,internal val data:String) {
    override fun toString()="SseFrame([REDACTED])"
}
/** SSE framing is independent of HTTP Content-Type, which was absent in Phase 1 evidence. */
class SseParser(private val source:BufferedSource) {
    fun next():SseFrame? {
        var event:String?=null; val data=StringBuilder(); var lines=0
        while(true) {
            val line=readLine() ?: return null // Unterminated frame is not successful completion.
            if(++lines>4096) throw NetworkFault(Outcome.PROTOCOL,true)
            if(line.isEmpty()) {
                if(data.isNotEmpty()) return SseFrame(event,data.dropLast(1).toString())
                event=null; lines=0; continue
            }
            if(line.startsWith(':')) continue
            val field=line.substringBefore(':'); val value=line.substringAfter(':',"").removePrefix(" ")
            when(field) {
                "event"-> { require(value.length<=256) { "EVENT_LIMIT" }; event=value }
                "data"-> { if(data.length+value.length>262144) throw NetworkFault(Outcome.PROTOCOL,true); data.append(value).append('\n') }
            }
        }
    }
    private fun readLine():String? {
        val index=source.indexOf('\n'.code.toByte(),0,32769)
        if(index<0) {
            if(source.exhausted()) return null
            throw NetworkFault(Outcome.PROTOCOL,true)
        }
        require(index<=32768) { "LINE_LIMIT" }
        val bytes=source.readByteArray(index); source.skip(1)
        return utf8(bytes).removeSuffix("\r").removePrefix("\uFEFF")
    }
}
