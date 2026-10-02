package io.github.xiaomeng2568.meldwise.auth

import kotlinx.coroutines.*
import java.net.*
import java.io.ByteArrayOutputStream
import java.io.Closeable

class AuthorizationReply(internal val code:Secret,internal val client:Secret?) {
    override fun toString()="AuthorizationReply([REDACTED])"
}
/** Fresh ephemeral loopback listener; bounded input; invalid state never consumes the valid callback. */
class LoopbackReceiver(private val expectedState:Secret) : Closeable {
    private val server=ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"),0)); soTimeout=500 }
    internal val redirect="http://127.0.0.1:${server.localPort}/auth/callback"
    suspend fun await():AuthorizationReply = withContext(Dispatchers.IO) {
        var attempts=0
        while(attempts<32) {
            currentCoroutineContext().ensureActive()
            val socket=try { server.accept() } catch (_:SocketTimeoutException) { continue }
            attempts++
            socket.use {
                it.soTimeout=1000
                var reply:AuthorizationReply?=null
                try {
                    val input=it.getInputStream(); val bytes=ByteArrayOutputStream()
                    while (bytes.size()<8192) {
                        val byte=input.read(); if(byte<0) break
                        bytes.write(byte)
                        val block=bytes.toByteArray()
                        if(block.size>=4 && block.takeLast(4)==listOf<Byte>(13,10,13,10)) break
                    }
                    val text=bytes.toString(Charsets.US_ASCII.name())
                    require(text.endsWith("\r\n\r\n") && text.all { c->c.code<=127 })
                    val lines=text.split("\r\n"); val first=lines[0].split(' ')
                    require(first.size==3 && first[0]=="GET" && first[2]=="HTTP/1.1")
                    val host=lines.drop(1).filter { line->line.startsWith("Host:",true) }
                    require(host.size==1 && host[0].substringAfter(':').trim()=="127.0.0.1:${server.localPort}")
                    val uri=URI(first[1]); require(!uri.isAbsolute && uri.rawPath=="/auth/callback" && uri.rawFragment==null)
                    val pairs=(uri.rawQuery ?: "").split('&').map { field ->
                        URLDecoder.decode(field.substringBefore('='),"UTF-8") to URLDecoder.decode(field.substringAfter('=',""),"UTF-8") }
                    require(pairs.map { p->p.first }.distinct().size==pairs.size)
                    val params=pairs.toMap()
                    require(constantEqual(params["state"] ?: "",expectedState.value))
                    if(params.containsKey("error")) throw AuthFailure(AuthReason.CANCELLED)
                    reply=AuthorizationReply(Secret(requireNotNull(params["code"])),params["client_id"]?.let(::Secret))
                } catch (failure:AuthFailure) { throw failure }
                catch (_:Exception) { /* Respond with a fixed non-secret body; continue bounded listener. */ }
                val message=if(reply!=null) "Authorization received. Return to Meldwise." else "Callback rejected."
                runCatching { it.getOutputStream().write(("HTTP/1.1 ${if(reply!=null) "200 OK" else "400 Bad Request"}\r\n"+
                    "Content-Type: text/plain\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${message.length}\r\n\r\n$message").toByteArray(Charsets.US_ASCII)) }
                if(reply!=null) return@withContext reply
            }
        }
        throw AuthFailure(AuthReason.TIMEOUT)
    }
    override fun close() { runCatching { server.close() } }
}
