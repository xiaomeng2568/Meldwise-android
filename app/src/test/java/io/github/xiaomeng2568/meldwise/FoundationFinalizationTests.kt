package io.github.xiaomeng2568.meldwise

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.ui.theme.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.content.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class FoundationFinalizationTests {
    @Test fun compactDefaultGeometry() {assertEquals(44f,Sizes.composerMin.value);assertEquals(48f,Sizes.touch.value);assertEquals(40f,Sizes.actionVisual.value)}
    @Test fun growthHasExplicitCap() {assertTrue(Sizes.composerMax.value+16 in 120f..140f)}
    @Test fun radiusVocabularyConsistent() {assertEquals(20f,Radius.surface.topStart.toPx(androidx.compose.ui.geometry.Size(100f,100f),androidx.compose.ui.unit.Density(1f)))}
    @Test fun hexColorStrict() {assertEquals(0x336699L,parseAccent("#336699"));assertEquals(0x336699L,parseAccent("336699"));listOf("x","#12","FFFFFFFF","#FFFFFFF","00ZZ00").forEach {assertNull(parseAccent(it))}}
    @Test fun accentLightTextContrast() {contrast(false)}
    @Test fun accentDarkTextContrast() {contrast(true)}
    private fun contrast(dark:Boolean) {
        fun ratio(a:Color,b:Color):Float {val x=a.luminance();val y=b.luminance();return (maxOf(x,y)+.05f)/(minOf(x,y)+.05f)}
        listOf(0L,0xFFFFFFL,0xFF0000L,0x00FF00L,0x0000FFL,0x366BD5L,0xB45B7DL).forEach {rgb ->
            val c=accentColors(dark,rgb);listOf(c.primary to c.background,c.primary to c.surface,c.onPrimary to c.primary,c.onPrimaryContainer to c.primaryContainer)
                .forEach {(a,b)->assertTrue(ratio(a,b)>=4.5f)}
        }
    }
    @Test fun invalidAccentFallsBack() {assertEquals(themeColors(false),accentColors(false,-1));assertEquals(themeColors(true),accentColors(true,null))}
    @Test fun thinkingContentIndependentOfFinalAnswer() {
        val lane=CompareLaneRecord("B",ModelRef("deepseek","m"),output="ANSWER",state=CompareLaneState.Completed,
            reasoning=ReasoningRecord("THOUGHT",ReasoningContent.ProviderVisibleReasoning,ReasoningPhase.Completed))
        val p=comparePresentation(lane,"M");assertEquals("ANSWER",p.answer);assertEquals("THOUGHT",p.reasoning.text)
    }
    @Test fun noReasoningNoFabrication() {assertFalse(reasoningPresentation(ReasoningRecord()).visible)}
    @Test fun rawAnswerDoesNotBecomeReasoning() {assertFalse(presentMessage(ChatMessage("x",null,MessageRole.ASSISTANT,"I think",MessageState.COMPLETED),ModelRef("deepseek","m"),"M").reasoning.visible)}
    @Test fun interruptedReasoningMapping() {assertEquals(ReasoningState.Interrupted,reasoningPresentation(ReasoningRecord("kept",phase=ReasoningPhase.Interrupted)).state)}
    @Test fun liveReasoningMapping() {assertEquals(ReasoningState.Streaming,reasoningPresentation(ReasoningRecord("live",phase=ReasoningPhase.Streaming)).state)}
    @Test fun finalizedSummaryMapping() {assertEquals(ReasoningKind.Summary,reasoningPresentation(ReasoningRecord("summary",ReasoningContent.Summary,ReasoningPhase.Completed)).kind)}
    @Test fun newSessionKeepsPreviousHistory() {
        val blob=MemoryBlob();val box=testBox();val repo=ChatRepository(blob,box);val ref=ModelRef("deepseek","m")
        repo.activate(ref);val (id,_)=repo.begin("old prompt");repo.update(id,"old answer",MessageState.COMPLETED)
        val old=repo.sessions().single();repo.newSession(ref);assertTrue(repo.load().isEmpty());assertEquals(2,repo.sessions().size)
        repo.begin("new prompt");val reopened=ChatRepository(blob,box);reopened.load();assertEquals(ref,reopened.activeRef())
        assertEquals("old answer",reopened.activateSession(old.id).last().text)
    }
    @Test fun sameModelSessionsKeepDistinctIds() {val r=ChatRepository(MemoryBlob(),testBox());val ref=ModelRef("chatgpt","m");repeat(3) {r.newSession(ref)};assertEquals(3,r.sessions().map {it.id}.distinct().size)}
    @Test fun optionalSprint2FieldsMigrateWithoutLoss() {
        val blob=MemoryBlob();val box=testBox();blob.write(box.seal("""{"version":2,"activeProviderId":"deepseek","activeModelId":"m","sessions":[{"providerId":"deepseek","modelId":"m","messages":[{"id":"old","parentMessageId":null,"role":"USER","text":"OLD","state":"COMPLETED"}]}]}""".toByteArray()))
        val repo=ChatRepository(blob,box);assertEquals("OLD",repo.load().single().text);assertEquals(ModelRef("deepseek","m"),repo.activeRef())
        assertEquals(ReasoningPhase.Unavailable,repo.load().single().reasoning.phase)
    }
    @Test fun singleReasoningEncryptedAndRestored() {
        val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box);r.activate(ModelRef("deepseek","m"));val (id,_)=r.begin("input")
        r.update(id,"ANSWER",MessageState.COMPLETED,ReasoningRecord("THOUGHT",ReasoningContent.ProviderVisibleReasoning,ReasoningPhase.Completed))
        assertFalse(String(blob.bytes!!).contains("THOUGHT"));val m=ChatRepository(blob,box).load().last();assertEquals("THOUGHT",m.reasoning.text);assertEquals("ANSWER",m.text)
    }
    @Test fun restartInterruptsActiveReasoning() {val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box);r.activate(ModelRef("deepseek","m"));val (id,_)=r.begin("input");r.update(id,"partial",MessageState.STREAMING,ReasoningRecord("thought",ReasoningContent.ProviderVisibleReasoning,ReasoningPhase.Streaming));val m=ChatRepository(blob,box).load().last();assertEquals(MessageState.INCOMPLETE,m.state);assertEquals(ReasoningPhase.Interrupted,m.reasoning.phase)}
    @Test fun reasoningCompletionIndependentOfAnswerRecovery() {val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box);r.activate(ModelRef("deepseek","m"));val (id,_)=r.begin("input");r.update(id,"partial",MessageState.STREAMING,ReasoningRecord("thought",ReasoningContent.ProviderVisibleReasoning,ReasoningPhase.Completed));val m=ChatRepository(blob,box).load().last();assertEquals(MessageState.INCOMPLETE,m.state);assertEquals(ReasoningPhase.Completed,m.reasoning.phase)}
    @Test fun newSessionNeverBuildsProviderHistoryFromPreviousSession() {val r=ChatRepository(MemoryBlob(),testBox());val ref=ModelRef("deepseek","m");r.activate(ref);val (id,_)=r.begin("OLD");r.update(id,"OLD ANSWER",MessageState.COMPLETED);r.newSession(ref);assertEquals(listOf("NEW"),r.begin("NEW").second.map {it.text})}
    @Test fun newSessionCountBoundPreservesOldRecords() {val r=ChatRepository(MemoryBlob(),testBox());repeat(32) {r.newSession(ModelRef("deepseek","m"))};assertThrows(IllegalArgumentException::class.java) {r.newSession(ModelRef("deepseek","m"))};assertEquals(32,r.sessions().size)}
    @Test fun singleAndCompareUseSameRenderer() {
        val source=File(System.getProperty("projectRoot"),"app/src/main/java/io/github/xiaomeng2568/meldwise/ui/components/MessageCard.kt").readText()
        assertTrue(source.contains("else AssistantOutput(CompareLane("));assertTrue(source.contains("CompareResultCard(lane:CompareLane,modifier:Modifier=Modifier)=AssistantOutput(lane,modifier)"))
        assertEquals(1,Regex("ContentRenderer\\(").findAll(source).count());assertTrue(source.contains("MessageActions(lane.answer)"))
    }
}
