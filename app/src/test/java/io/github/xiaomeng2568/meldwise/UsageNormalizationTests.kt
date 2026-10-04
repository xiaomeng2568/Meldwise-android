// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class UsageNormalizationTests(private val label:String,private val input:Usage?,private val expected:Usage?) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}") fun cases():List<Array<Any?>> = listOf(
            arrayOf("missing",null,null),arrayOf("all unknown",Usage(null,null,null),null),
            arrayOf("input",Usage(7,null,null),Usage(7,null,null)),
            arrayOf("output",Usage(null,8,null),Usage(null,8,null)),
            arrayOf("total",Usage(null,null,9),Usage(null,null,9)),
            arrayOf("input output no derived total",Usage(7,8,null),Usage(7,8,null)),
            arrayOf("all independent",Usage(7,8,42),Usage(7,8,42)),
            arrayOf("zero",Usage(0,0,0),Usage(0,0,0)),
            arrayOf("input zero",Usage(0,null,null),Usage(0,null,null)),
            arrayOf("total zero",Usage(null,null,0),Usage(null,null,0)),
            arrayOf("max input",Usage(Long.MAX_VALUE,null,null),Usage(Long.MAX_VALUE,null,null)),
            arrayOf("max all",Usage(Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE),Usage(Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE)),
            arrayOf("negative input",Usage(-1,8,9),null),
            arrayOf("negative output",Usage(7,-1,9),null),
            arrayOf("negative total",Usage(7,8,-1),null),
            arrayOf("min input",Usage(Long.MIN_VALUE,null,null),null),
            arrayOf("only negative",Usage(null,-42,null),null),
            arrayOf("input total",Usage(7,null,19),Usage(7,null,19)))
    }
    @Test fun normalizedWithoutInventingValues() {assertEquals(expected,UsageNormalization.normalize(input))}
    @Test fun requestUsesExactlyNormalizedSnapshot()=runBlocking {
        val owner=RuntimeUsage();val op=owner.begin(UsageOperationId(UsageMode.SINGLE,"message"))
        op.stream(UsageSlot.SINGLE,ModelRef("chatgpt","exact")) {flowOf(LlmEvent.Completed(input))}.collect()
        op.finish(UsageSlot.SINGLE,RequestOutcome.COMPLETED)
        val record=op.snapshot().records.single()
        assertEquals(expected?.inputTokens,record.inputTokens);assertEquals(expected?.outputTokens,record.outputTokens)
        assertEquals(expected?.totalTokens,record.totalTokens)
        assertEquals(if(expected==null) UsageSource.UNAVAILABLE else UsageSource.PROVIDER_REPORTED,record.source)
        assertEquals(RequestOutcome.COMPLETED,record.outcome)
    }
}
