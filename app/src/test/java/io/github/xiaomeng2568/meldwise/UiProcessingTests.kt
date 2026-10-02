package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.network.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.ui.ScreenState
import io.github.xiaomeng2568.meldwise.ui.presentation.ProcessingObserver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UiProcessingTests {
    private fun pending(text: String="",state: MessageState=MessageState.PENDING)=ChatMessage("synthetic-local-id",null,MessageRole.ASSISTANT,text,state)
    @Test fun observesHttpWithoutIssuingRequests() = runTest {
        val screen=MutableStateFlow(ScreenState());val diagnostics=SafeDiagnostics()
        val observer=ProcessingObserver(screen,backgroundScope,diagnostics) {testScheduler.currentTime}
        runCurrent();screen.value=ScreenState(messages=listOf(pending()),busy=true);runCurrent()
        diagnostics.record(Operation.RESPONSE,Outcome.HTTP,200);advanceTimeBy(3100);runCurrent()
        assertEquals(3L,observer.state.value.seconds);assertTrue(observer.state.value.waiting)
        screen.value=ScreenState(messages=listOf(pending("answer",MessageState.STREAMING)),busy=true)
        advanceTimeBy(100);runCurrent();val stopped=observer.state.value.seconds
        advanceTimeBy(3000);runCurrent();assertEquals(stopped,observer.state.value.seconds);assertFalse(observer.state.value.waiting)
        assertEquals(1,diagnostics.snapshot().size)
    }
    @Test fun oldHttpDoesNotStartNewTimer() = runTest {
        val screen=MutableStateFlow(ScreenState());val diagnostics=SafeDiagnostics();diagnostics.record(Operation.RESPONSE,Outcome.HTTP,200)
        val observer=ProcessingObserver(screen,backgroundScope,diagnostics) {testScheduler.currentTime}
        runCurrent();screen.value=ScreenState(messages=listOf(pending()),busy=true);runCurrent();advanceTimeBy(1000);runCurrent()
        assertNull(observer.state.value.seconds)
    }
    @Test fun identicalHttpAtRingBoundStillStartsTimer() = runTest {
        val screen=MutableStateFlow(ScreenState());val diagnostics=SafeDiagnostics();repeat(100) {diagnostics.record(Operation.RESPONSE,Outcome.HTTP,200)}
        val observer=ProcessingObserver(screen,backgroundScope,diagnostics) {testScheduler.currentTime}
        runCurrent();screen.value=ScreenState(messages=listOf(pending()),busy=true);runCurrent()
        diagnostics.record(Operation.RESPONSE,Outcome.HTTP,200);advanceTimeBy(1200);runCurrent()
        assertEquals(1L,observer.state.value.seconds);assertEquals(100,diagnostics.snapshot().size)
    }
    @Test fun cancellationFreezesTimer() = runTest {
        val screen=MutableStateFlow(ScreenState());val diagnostics=SafeDiagnostics()
        val observer=ProcessingObserver(screen,backgroundScope,diagnostics) {testScheduler.currentTime}
        runCurrent();screen.value=ScreenState(messages=listOf(pending()),busy=true);runCurrent()
        diagnostics.record(Operation.RESPONSE,Outcome.HTTP,200);advanceTimeBy(2100);runCurrent()
        screen.value=ScreenState(messages=listOf(pending(state=MessageState.CANCELLED)),busy=false);runCurrent()
        val ended=observer.state.value.seconds;advanceTimeBy(4000);runCurrent();assertEquals(ended,observer.state.value.seconds);assertFalse(observer.state.value.waiting)
    }
    @Test fun modelLoadingDoesNotInventMessageTimer() = runTest {
        val screen=MutableStateFlow(ScreenState());val diagnostics=SafeDiagnostics()
        val observer=ProcessingObserver(screen,backgroundScope,diagnostics) {testScheduler.currentTime}
        runCurrent();screen.value=ScreenState(busy=true);runCurrent();diagnostics.record(Operation.MODELS,Outcome.HTTP,200)
        advanceTimeBy(1000);runCurrent();assertNull(observer.state.value.seconds);assertNull(observer.state.value.messageId)
    }
}
