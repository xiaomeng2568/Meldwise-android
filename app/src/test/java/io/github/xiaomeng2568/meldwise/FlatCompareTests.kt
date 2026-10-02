package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.Sizes
import io.github.xiaomeng2568.meldwise.security.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FlatCompareTests {
    private val a=CompareLaneRecord("A",ModelRef("chatgpt","actual-a"),"PRIVATE_ANSWER_A",CompareLaneState.Completed,modelDisplayName="5.6-Luna",processingDuration=2)
    private val b=CompareLaneRecord("B",ModelRef("deepseek","actual-b"),"PRIVATE_ANSWER_B",CompareLaneState.Completed,
        reasoning=ReasoningRecord("PRIVATE_REASONING",ReasoningContent.ProviderVisibleReasoning,ReasoningPhase.Completed),modelDisplayName="V4.1-Flash",processingDuration=4)
    private fun run()=CompareRun("fixture","PRIVATE_PROMPT",a,b,CompareRunState.Completed,123)
    private fun repo(blob:MemoryBlob=MemoryBlob(),box:AesGcmBox=testBox())=CompareRepository(blob,box)
    private fun extended():CompareRun {
        val summary=a.copy(laneId="summary",output="PRIVATE_SUMMARY",role=CompareOutputRole.Summary)
        return run().copy(outputs=listOf(a,b,summary),stages=listOf(CompareStage("answers",0,listOf("A","B")),CompareStage("summary",1,listOf("summary"))))
    }
    @Test fun flowHasOnePromptAndTwoSiblings() {val items=compareMessageItems(run());assertEquals(3,items.size);assertTrue(items[0] is CompareMessageItem.Prompt);assertTrue(items.drop(1).all {it is CompareMessageItem.Output})}
    @Test fun promptIsLiteralAndPreserved() {assertEquals("PRIVATE_PROMPT",(compareMessageItems(run())[0] as CompareMessageItem.Prompt).item.content)}
    @Test fun outputIdentityNeverUsesLabel() {val output=compareMessageItems(run())[1] as CompareMessageItem.Output;assertEquals(ModelRef("chatgpt","actual-a"),output.record.modelRef);assertEquals("5.6-Luna",output.presentation.displayName)}
    @Test fun cachedLabelCannotRewriteSavedSnapshot() {val items=compareMessageItems(run(),mapOf("chatgpt" to listOf(LlmModel("actual-a","Changed","fixture",ProviderCapability(emptySet())))));assertEquals("5.6-Luna",(items[1] as CompareMessageItem.Output).presentation.displayName)}
    @Test fun legacyNameIsNotInvented() {val p=compareMessageItems(run().withLanes(laneA=a.copy(modelDisplayName=null)))[1] as CompareMessageItem.Output;assertEquals("actual-a",p.presentation.displayName)}
    @Test fun keysSurviveStreamingUpdates() {assertEquals(compareMessageItems(run()).map {it.key},compareMessageItems(run().withLanes(laneB=b.copy(output="changed"))).map {it.key})}
    @Test fun keysUniqueEvenWhenPromptOutputIdsMatch() {assertEquals(3,compareMessageItems(run().copy(userPrompt=ComparePromptItem("A","input"))).map {it.key}.distinct().size)}
    @Test fun reasoningAvailabilityExplicit() {assertFalse(a.reasoningAvailable);assertTrue(b.reasoningAvailable);assertFalse(b.copy(reasoning=ReasoningRecord()).reasoningAvailable)}
    @Test fun answerAndReasoningNeverMerge() {val p=(compareMessageItems(run())[2] as CompareMessageItem.Output).presentation;assertEquals("PRIVATE_ANSWER_B",p.answer);assertEquals("PRIVATE_REASONING",p.reasoning.text)}
    @Test fun completedDoesNotAddRedundantCaption() {assertNull(assistantStatus(LaneState.Completed,null))}
    @Test fun elapsedLabelIsProcessingEstimate() {assertEquals("已处理 4 秒",assistantStatus(LaneState.Completed,4));assertFalse(assistantStatus(LaneState.Thinking,4)!!.contains("已思考"))}
    @Test fun cancelledFailedIncompleteStillVisible() {listOf(LaneState.Cancelled,LaneState.Failed,LaneState.Incomplete).forEach {assertEquals(laneLabel(it),assistantStatus(it,null))}}
    @Test fun visualThicknessIsApproximatelyTwoThirds() {assertTrue(Sizes.composerMin.value/64 in .65f.. .72f);assertEquals(48f,Sizes.touch.value)}
    @Test fun orderedStagesNotBackingListControlPresentation() {val r=extended().copy(outputs=extended().outputs.reversed(),stages=extended().stages.reversed());assertEquals(listOf("A","B","summary"),r.orderedOutputs.map {it.laneId})}
    @Test fun multipleOutputsAndSummaryRoundtripEncrypted() {val blob=MemoryBlob();val box=testBox();repo(blob,box).upsert(extended());val saved=repo(blob,box).load().single();assertEquivalent(extended(),saved);listOf("PRIVATE_PROMPT","PRIVATE_ANSWER_A","PRIVATE_REASONING","PRIVATE_SUMMARY").forEach {assertFalse(String(blob.bytes!!).contains(it))}}
    @Test fun modelSnapshotsSurviveRestoreWithoutCatalog() {val blob=MemoryBlob();val box=testBox();repo(blob,box).upsert(run());assertEquals(listOf("5.6-Luna","V4.1-Flash"),repo(blob,box).load().single().outputs.map {it.modelDisplayName})}
    @Test fun activeFutureStageRestoresIncompleteWithoutSending() {val r=extended();val blob=MemoryBlob();val box=testBox();repo(blob,box).upsert(r.copy(outputs=r.outputs.map {if(it.role==CompareOutputRole.Summary) it.copy(state=CompareLaneState.Streaming) else it},lifecycle=CompareRunState.Running));val restored=repo(blob,box).load().single();assertEquals(CompareLaneState.Incomplete,restored.orderedOutputs.last().state);assertEquals(CompareLaneState.Completed,restored.laneA.state);assertEquals(CompareRunState.Partial,restored.lifecycle)}
    @Test fun moreThanEightOutputsRejected() {val outputs=(0..8).map {a.copy(laneId="lane$it",modelRef=ModelRef("chatgpt","model$it"))};assertThrows(IllegalArgumentException::class.java) {repo().upsert(run().copy(outputs=outputs,stages=listOf(CompareStage("s",0,outputs.map {it.laneId}))))}}
    @Test fun missingStageReferenceRejected() {assertThrows(IllegalArgumentException::class.java) {repo().upsert(run().copy(stages=listOf(CompareStage("s",0,listOf("A")))))}}
    @Test fun duplicateStageReferenceRejected() {assertThrows(IllegalArgumentException::class.java) {repo().upsert(run().copy(stages=listOf(CompareStage("s",0,listOf("A","A")))))}}
    @Test fun duplicateStageOrderRejected() {assertThrows(IllegalArgumentException::class.java) {repo().upsert(run().copy(stages=listOf(CompareStage("a",0,listOf("A")),CompareStage("b",0,listOf("B")))))}}
    @Test fun summaryMustBeLast() {val r=extended();assertThrows(IllegalArgumentException::class.java) {repo().upsert(r.copy(stages=listOf(CompareStage("s",0,listOf("summary")),CompareStage("a",1,listOf("A","B")))))}}
    @Test fun displayNameBoundAndProviderNameStrict() {assertThrows(IllegalArgumentException::class.java) {repo().upsert(run().withLanes(laneA=a.copy(modelDisplayName="x".repeat(257))))};assertThrows(IllegalArgumentException::class.java) {repo().upsert(run().withLanes(laneA=a.copy(providerDisplayName="DeepSeek")))}}
    @Test fun corruptedVersionTwoFailsClosed() {val blob=MemoryBlob();val box=testBox();repo(blob,box).upsert(run());blob.bytes!![blob.bytes!!.lastIndex]=(blob.bytes!!.last().toInt() xor 1).toByte();assertThrows(Exception::class.java) {repo(blob,box).load()}}
    @Test fun legacyV1MigratesAtomicallyWithoutLostText() {
        val blob=MemoryBlob();val box=testBox()
        val original="""{"version":1,"runs":[{"id":"old","prompt":"OLD_PROMPT","laneA":{"laneId":"A","modelRef":{"providerId":"chatgpt","modelId":"a"},"output":"OLD_A","state":"Completed"},"laneB":{"laneId":"B","modelRef":{"providerId":"deepseek","modelId":"b"},"output":"OLD_B","state":"Cancelled"},"lifecycle":"Partial","createdAt":12}]}"""
        blob.write(box.seal(original.toByteArray()));val restored=repo(blob,box).load().single()
        assertEquals("OLD_PROMPT",restored.userPrompt.content);assertEquals(listOf("OLD_A","OLD_B"),restored.outputs.map {it.output});assertEquals(CompareLaneState.Cancelled,restored.laneB.state)
        assertNull(restored.laneA.modelDisplayName);assertEquivalent(restored,repo(blob,box).load().single())
        val decoded=box.open(blob.bytes!!);try {val json=String(decoded);assertTrue(json.contains("\"version\":2"));assertTrue(json.contains("\"outputs\""));assertFalse(json.contains("\"laneA\""))} finally {decoded.fill(0)}
    }
    @Test fun migrationWriteFailureKeepsOldCiphertext() {
        val memory=MemoryBlob();val box=testBox();memory.write(box.seal("""{"version":1,"runs":[]}""".toByteArray()));val original=memory.bytes!!.clone()
        val failing=object:AtomicBlob {override fun read()=memory.read();override fun write(value:ByteArray) {throw java.io.IOException("synthetic")}}
        assertThrows(Exception::class.java) {repoProxy(failing,box).load()};assertArrayEquals(original,memory.bytes)
    }
    private fun repoProxy(blob:AtomicBlob,box:AesGcmBox)=CompareRepository(blob,box)
    private fun assertEquivalent(expected:CompareRun,actual:CompareRun) {
        // ReasoningRecord deliberately has identity equality; compare persisted values instead.
        assertTrue("Persisted compare values differ",Json.encodeToString(expected)==Json.encodeToString(actual))
    }
    @Test fun futureDataDoesNotEnableFutureOrchestration() {
        val executor=CompareExecutor(ProviderRegistry(emptyList()),repo())
        assertThrows(IllegalArgumentException::class.java) {runBlocking {executor.execute(extended()) {}}}
    }
    @Test fun formalMessageToStringIsRedacted() {listOf(run(),run().userPrompt,a,b,compareMessageItems(run())[1]).forEach {val text=it.toString();assertFalse(text.contains("PRIVATE"));assertFalse(text.contains("5.6-Luna"))}}
    @Test fun compareNoLongerRendersAnOuterCard() {val root=File(System.getProperty("projectRoot"),"app/src/main/java/io/github/xiaomeng2568/meldwise/ui");val screen=File(root,"ChatScreen.kt").readText();assertTrue(screen.contains("items(compareItems,key={it.key}) {CompareMessage(it)}"));assertFalse(screen.contains("CompareContent(it,foundation.catalogs)"))}
    @Test fun reasoningIsLightDisclosureNotCodeSurface() {val text=File(System.getProperty("projectRoot"),"app/src/main/java/io/github/xiaomeng2568/meldwise/ui/components/ContentRenderer.kt").readText().substringAfter("@Composable fun ReasoningPanel");assertFalse(text.contains("LiteralSurface"));assertTrue(text.contains("mutableStateOf(false)"));assertTrue(text.contains("收起"))}
    @Test fun bothProviderMarksArePackagedNativeVectors() {
        val root=File(System.getProperty("projectRoot"),"app/src/main/res/drawable")
        listOf("provider_openai.xml","provider_deepseek.xml").forEach {name ->
            val text=File(root,name).readText();assertTrue(text.contains("<vector"));assertTrue(text.contains("android:pathData=\"M"));assertTrue(text.length<12000)
        }
    }
    @Test fun providerMarkDoesNotNeedRuntimeDownloadOrLetterFallback() {
        val text=File(System.getProperty("projectRoot"),"app/src/main/java/io/github/xiaomeng2568/meldwise/ui/components/MessageCard.kt").readText().substringAfter("@Composable private fun ProviderMark").substringBefore("@Composable private fun MessageActions")
        assertTrue(text.contains("R.drawable.provider_openai"));assertTrue(text.contains("R.drawable.provider_deepseek"));assertFalse(text.contains("http"));assertFalse(text.contains("Text("))
    }
}
