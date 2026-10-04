// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BetaObserverTests(private val name:String,private val events:List<LlmEvent>) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}") fun cases():List<Array<Any>> = listOf(
            arrayOf("completed",listOf(LlmEvent.HttpReady,LlmEvent.TextDelta("answer"),LlmEvent.Completed(Usage(1,2,3)))),
            arrayOf("unknown",listOf(LlmEvent.TextDelta("answer"),LlmEvent.Completed(null))),
            arrayOf("delta final terminal",listOf(LlmEvent.UsageDelta(Usage(1,null,null)),LlmEvent.UsageFinal(Usage(2,null,null)),LlmEvent.Completed(Usage(3,null,null)))),
            arrayOf("conflict",listOf(LlmEvent.UsageFinal(Usage(1,null,null)),LlmEvent.Completed(Usage(9,null,null)))),
            arrayOf("malformed optional",listOf(LlmEvent.TextDelta("answer"),LlmEvent.Completed(Usage(-1,2,3)))),
            arrayOf("max",listOf(LlmEvent.Completed(Usage(Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE)))),
            arrayOf("zero",listOf(LlmEvent.Completed(Usage(0,0,0)))),
            arrayOf("failed",listOf(LlmEvent.TextDelta("partial"),LlmEvent.Failed(LlmError(ErrorKind.PLAN_USAGE_LIMIT)))),
            arrayOf("interrupted",listOf(LlmEvent.TextDelta("partial"),LlmEvent.Incomplete(LlmError(ErrorKind.STREAM_INTERRUPTED)))),
            arrayOf("cancelled",listOf(LlmEvent.TextDelta("partial"),LlmEvent.Cancelled)),
            arrayOf("late events forwarded unchanged",listOf(LlmEvent.Completed(null),LlmEvent.TextDelta("late"),LlmEvent.UsageFinal(Usage(7,8,9)),LlmEvent.Failed(LlmError(ErrorKind.PROTOCOL)))),
            arrayOf("reasoning",listOf(LlmEvent.ReasoningDelta("visible summary",ReasoningContent.Summary),LlmEvent.ReasoningDone(ReasoningContent.Summary),LlmEvent.TextDelta("answer"),LlmEvent.Completed(null))),
            arrayOf("empty EOF",emptyList<LlmEvent>())
        )
    }
    @Test fun identicalEventsOrderInvocationCountAndConsumerTerminal()=runTest {
        val op=RuntimeUsage().begin(UsageOperationId(UsageMode.SINGLE,"message"))
        var directCalls=0;var observedCalls=0
        val direct=flow {directCalls++;events.forEach {emit(it)}}.toList()
        val observed=op.stream(UsageSlot.SINGLE,ModelRef("chatgpt","a")) {observedCalls++;events.asFlow()}.toList()
        assertEquals(direct,observed);direct.indices.forEach {assertSame(direct[it],observed[it])}
        assertEquals(1,directCalls);assertEquals(1,observedCalls)
        fun terminal(list:List<LlmEvent>)=list.filter {it is LlmEvent.Completed || it is LlmEvent.Failed || it is LlmEvent.Incomplete || it==LlmEvent.Cancelled}.lastOrNull()
        assertSame(terminal(direct),terminal(observed))
    }
    @Test fun synchronousConsumerWritesHaveSameVirtualSchedule()=runTest {
        suspend fun trace(observed:Boolean):List<Pair<Int,Long>> {
            val start=testScheduler.currentTime
            val source=flow {events.forEach {delay(1);emit(it)}}
            val op=RuntimeUsage().begin(UsageOperationId(UsageMode.SINGLE,"message"))
            val stream=if(observed) op.stream(UsageSlot.SINGLE,ModelRef("chatgpt","a")) {source} else source
            val writes=mutableListOf<Pair<Int,Long>>()
            stream.collect {writes.add(writes.size to testScheduler.currentTime-start)}
            return writes
        }
        assertEquals(trace(false),trace(true))
    }
}
