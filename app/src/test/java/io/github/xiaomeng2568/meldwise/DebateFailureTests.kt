// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Each of five stages against every required provider failure category, with both sibling states. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class DebateFailureTests(private val type:DebateStageType,private val kind:ErrorKind) {
    companion object {@JvmStatic @Parameterized.Parameters(name="{0}-{1}") fun cases()=
        DebateStageType.entries.flatMap {t->listOf(ErrorKind.AUTHENTICATION,ErrorKind.PLAN_USAGE_LIMIT,
            ErrorKind.CONTEXT_OVERFLOW,ErrorKind.PROTOCOL,ErrorKind.NETWORK,ErrorKind.STREAM_INTERRUPTED,
            ErrorKind.STORAGE,ErrorKind.UNKNOWN).map {e->arrayOf<Any>(t,e)}}}
    private fun setup(h:DebateHarness) {when(type) {DebateStageType.INITIAL_A,DebateStageType.INITIAL_B->h.scope.runCurrent();DebateStageType.REVIEW_A_OF_B,DebateStageType.REVIEW_B_OF_A->h.toReviews();DebateStageType.JUDGE->h.toJudge()}}
    private fun sibling()=when(type) {DebateStageType.INITIAL_A->DebateStageType.INITIAL_B;DebateStageType.INITIAL_B->DebateStageType.INITIAL_A;DebateStageType.REVIEW_A_OF_B->DebateStageType.REVIEW_B_OF_A;DebateStageType.REVIEW_B_OF_A->DebateStageType.REVIEW_A_OF_B;else->null}
    private fun expectedRequests()=when(type) {DebateStageType.INITIAL_A,DebateStageType.INITIAL_B->2;DebateStageType.REVIEW_A_OF_B,DebateStageType.REVIEW_B_OF_A->4;else->5}
    @Test fun failingStageStopsRunningSiblingAndDownstream()=runTest {
        val h=DebateHarness(this);val task=h.start();setup(h);h.call(type).fail(kind);runCurrent();val r=task.await().debateRounds.last()
        assertEquals(DebateRoundState.Failed,r.lifecycle);assertEquals(DebateStageState.Failed,r.stage(type).state);assertEquals(kind,r.stage(type).error)
        sibling()?.let {assertEquals(DebateStageState.Cancelled,r.stage(it).state)}
        assertEquals(expectedRequests(),h.calls.size);assertTrue(h.calls.all {it.closed.isCompleted});assertTrue(DebateDag.readyStages(r).isEmpty())
        r.stages.filter {it.type.ordinal>type.ordinal && it.type!=sibling()}.forEach {assertEquals(DebateStageState.NotRun,it.state)}
    }
    @Test fun failingStageKeepsDurablyCompletedSibling()=runTest {
        val h=DebateHarness(this);val task=h.start();setup(h);sibling()?.let {h.call(it).complete();runCurrent()}
        h.call(type).fail(kind);runCurrent();val r=task.await().debateRounds.last()
        assertEquals(kind,r.stage(type).error);assertEquals(DebateRoundState.Failed,r.lifecycle)
        sibling()?.let {assertEquals(DebateStageState.Complete,r.stage(it).state);assertEquals("visible-$it",r.stage(it).output)}
        DebateDag.dependencies(type).forEach {assertEquals(DebateStageState.Complete,r.stage(it).state)}
        assertEquals(expectedRequests(),h.calls.size);assertTrue(h.calls.all {it.closed.isCompleted})
    }
}
