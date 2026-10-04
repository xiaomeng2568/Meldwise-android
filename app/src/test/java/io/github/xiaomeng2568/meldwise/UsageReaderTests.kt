// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.network.SseFrame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class UsageReaderTests(private val label:String,private val raw:String,private val expected:Usage?) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}") fun cases():List<Array<Any?>> = listOf(
            arrayOf("object", """{"input_tokens":1,"output_tokens":2,"total_tokens":8}""",Usage(1,2,8)),
            arrayOf("partial", """{"total_tokens":0}""",Usage(null,null,0)),
            arrayOf("input output", """{"input_tokens":1,"output_tokens":2}""",Usage(1,2,null)),
            arrayOf("null field", """{"input_tokens":null,"total_tokens":7}""",Usage(null,null,7)),
            arrayOf("empty","{}",null),arrayOf("null","null",null),
            arrayOf("array","[]",null),arrayOf("string","\"usage\"",null),
            arrayOf("negative", """{"input_tokens":-1,"total_tokens":7}""",null),
            arrayOf("quoted", """{"input_tokens":"12"}""",null),
            arrayOf("boolean", """{"input_tokens":true}""",null),
            arrayOf("fraction", """{"input_tokens":1.5}""",null),
            arrayOf("nested", """{"input_tokens":{},"total_tokens":7}""",null),
            arrayOf("overflow", """{"total_tokens":9223372036854775808}""",null),
            arrayOf("max", """{"total_tokens":9223372036854775807}""",Usage(null,null,Long.MAX_VALUE))
        )
    }
    @Test fun badOptionalMetadataNeverDestroysAnswer() {
        val reader=ResponsesReader()
        val data="""{"type":"response.completed","response":{"id":"local","status":"completed","output":[{"type":"message","role":"assistant","id":"item","content":[{"type":"output_text","text":"answer"}]}],"usage":$raw}}"""
        val events=reader.consume(SseFrame(null,data))
        assertEquals("answer",events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") {it.text})
        assertEquals(expected,events.filterIsInstance<LlmEvent.Completed>().single().usage)
        assertTrue(reader.terminal)
    }
}
