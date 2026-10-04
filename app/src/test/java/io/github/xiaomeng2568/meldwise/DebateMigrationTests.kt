// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DebateMigrationTests {
    private val a=ModelRef("deepseek","a")
    private val b=ModelRef("chatgpt","b")
    private val config=CollaborateConfig(CollaborateModel(a,"Old A",ReasoningPreference.High),CollaborateModel(b,"Old B"),ReviewIntensity.STRICT,SynthesisRole.REVIEWER)
    private class CountingBlob:AtomicBlob {
        val memory=MemoryBlob();var writes=0;var fail=false
        override fun read()=memory.read()
        override fun write(value:ByteArray) {writes++;if(fail) error("SYNTHETIC_DISK_FAILURE");memory.write(value)}
    }
    private fun root(blob:AtomicBlob,box:AesGcmBox):JsonObject {
        val bytes=box.open(blob.read()!!)
        try {return Json.parseToJsonElement(utf8(bytes)).jsonObject} finally {bytes.fill(0)}
    }
    private fun asV3(blob:AtomicBlob,box:AesGcmBox):JsonObject {
        val old=root(blob,box)
        val next=JsonObject(old+mapOf("version" to JsonPrimitive(3),"conversations" to JsonArray(old.getValue("conversations").jsonArray.map {
            JsonObject(it.jsonObject.filterKeys {k->k !in setOf("debate","debateRounds","debateGrants")}+mapOf("schemaVersion" to JsonPrimitive(3)))
        })))
        blob.write(box.seal(next.toString().toByteArray()));return next
    }
    private fun equivalent(value:JsonElement):JsonElement=when(value) {
        is JsonObject->JsonObject(value.filterKeys {it !in setOf("version","schemaVersion","debate","debateRounds","debateGrants")}.mapValues {equivalent(it.value)})
        is JsonArray->JsonArray(value.map(::equivalent))
        else->value
    }
    private fun single(r:ChatRepository,active:Boolean=false):String {
        r.newSession(a);val id=r.begin("  old 用户\n${'$'}x ").first
        r.update(id,"original answer \\path\n",if(active) MessageState.STREAMING else MessageState.COMPLETED,
            ReasoningRecord("LOCAL_ONLY",ReasoningContent.ProviderVisibleReasoning,if(active) ReasoningPhase.Streaming else ReasoningPhase.Completed))
        return r.conversationId()
    }
    private fun collaborate(r:ChatRepository,finish:Int=3,failure:Boolean=false):String {
        r.newCollaborate();r.configureCollaborate(config);val round=r.beginCollaborate(r.prepareCollaborate("old collaborate"),true).rounds.last()
        repeat(finish) {i->val s=r.startCollaborateStage(round.roundId,i).rounds.last().stages[i];r.updateCollaborateStage(round.roundId,s.copy(output="EXACT_VISIBLE_$i",
            state=if(failure && i==finish-1) CollaborateStageState.Failed else CollaborateStageState.Complete,
            error=if(failure && i==finish-1) ErrorKind.PLAN_USAGE_LIMIT else null,
            reasoning=ReasoningRecord("LOCAL_REASONING_$i",ReasoningContent.Summary,ReasoningPhase.Completed),processingDuration=3,endedAt=20))}
        return r.conversationId()
    }
    @Test fun singleV3AllFieldsPreserved() {val blob=MemoryBlob();val box=testBox();single(ChatRepository(blob,box,clock={20}));val old=asV3(blob,box);ChatRepository(blob,box).load();assertEquals(equivalent(old),equivalent(root(blob,box)));assertEquals(4,root(blob,box)["version"]!!.jsonPrimitive.int)}
    @Test fun collaborateV3AllFieldsPreserved() {val blob=MemoryBlob();val box=testBox();collaborate(ChatRepository(blob,box,clock={20}));val old=asV3(blob,box);ChatRepository(blob,box).load();assertEquals(equivalent(old),equivalent(root(blob,box)))}
    @Test fun mixedOrderAndActiveIdPreserved() {val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box,clock={20});val s=single(r);collaborate(r);r.moveSession(s,-1);r.activateSession(s);val old=asV3(blob,box);val reopened=ChatRepository(blob,box);reopened.load();assertEquals(s,reopened.conversationId());assertEquals(equivalent(old),equivalent(root(blob,box)))}
    @Test fun grantsAndSelectedRefPreserved() {val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box,clock={20});single(r);r.activate(b);val id=r.beginPrepared(r.prepare("share"),true).first;r.update(id,"shared",MessageState.COMPLETED);val old=asV3(blob,box);val reopened=ChatRepository(blob,box);reopened.load();assertEquals(setOf("chatgpt"),reopened.activeConversation()!!.contextGrants);assertEquals(b,reopened.activeRef());assertEquals(equivalent(old),equivalent(root(blob,box)))}
    @Test fun stageConfigPreferencesAndIntensityPreserved() {val blob=MemoryBlob();val box=testBox();collaborate(ChatRepository(blob,box,clock={20}));asV3(blob,box);val r=ChatRepository(blob,box);r.load();assertEquals(config,r.collaborateConfig());assertEquals(SynthesisRole.REVIEWER,r.activeConversation()!!.rounds.single().synthesisRole);assertEquals(ReviewIntensity.STRICT,r.activeConversation()!!.rounds.single().reviewIntensity)}
    @Test fun emptyDebateDefaultsAdded() {val blob=MemoryBlob();val box=testBox();single(ChatRepository(blob,box));asV3(blob,box);val r=ChatRepository(blob,box);r.load();assertNull(r.activeConversation()!!.debate);assertTrue(r.activeConversation()!!.debateRounds.isEmpty());assertTrue(r.activeConversation()!!.debateGrants.isEmpty())}
    @Test fun oneAtomicWriteForMigration() {val blob=CountingBlob();val box=testBox();single(ChatRepository(blob,box));asV3(blob,box);blob.writes=0;ChatRepository(blob,box).load();assertEquals(1,blob.writes)}
    @Test fun v4RepeatLoadDoesNotRewrite() {val blob=CountingBlob();val box=testBox();single(ChatRepository(blob,box));asV3(blob,box);ChatRepository(blob,box).load();val bytes=blob.read();blob.writes=0;ChatRepository(blob,box).load();assertEquals(0,blob.writes);assertArrayEquals(bytes,blob.read())}
    @Test fun failedMigrationWritePreservesV3Ciphertext() {val blob=CountingBlob();val box=testBox();single(ChatRepository(blob,box));asV3(blob,box);val bytes=blob.read();blob.fail=true;val r=ChatRepository(blob,box);assertThrows(Exception::class.java) {r.load()};assertArrayEquals(bytes,blob.read());assertThrows(Exception::class.java) {r.begin("no reset")};assertArrayEquals(bytes,blob.read());blob.fail=false;assertEquals(2,ChatRepository(blob,box).load().size)}
    @Test fun malformedV3Preserved() {val blob=MemoryBlob();val box=testBox();blob.write(box.seal("""{"version":3,"conversations":[]} """.toByteArray()));val before=blob.read();assertThrows(Exception::class.java) {ChatRepository(blob,box).load()};assertArrayEquals(before,blob.read())}
    @Test fun malformedV4CannotResetHistory() {val blob=MemoryBlob();val box=testBox();blob.write(box.seal("""{"version":4}""".toByteArray()));val before=blob.read();assertThrows(Exception::class.java) {ChatRepository(blob,box).load()};assertArrayEquals(before,blob.read())}
    @Test fun invalidV3IdsPreserved() {val blob=MemoryBlob();val box=testBox();single(ChatRepository(blob,box));val old=asV3(blob,box);val c=old["conversations"]!!.jsonArray.single().jsonObject;val messages=c["messages"]!!.jsonArray;val broken=JsonObject(old+mapOf("conversations" to JsonArray(listOf(JsonObject(c+mapOf("messages" to JsonArray(listOf(messages[0],messages[0]))))))));blob.write(box.seal(broken.toString().toByteArray()));val before=blob.read();assertThrows(Exception::class.java) {ChatRepository(blob,box).load()};assertArrayEquals(before,blob.read())}
    @Test fun v3CannotSmuggleDebateState() {val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box);r.newDebate();r.configureDebate(DebateFixtures.same);r.beginDebate(r.prepareDebate("q"));val old=root(blob,box);val broken=JsonObject(old+mapOf("version" to JsonPrimitive(3),"conversations" to JsonArray(old["conversations"]!!.jsonArray.map {JsonObject(it.jsonObject+mapOf("schemaVersion" to JsonPrimitive(3)))})));blob.write(box.seal(broken.toString().toByteArray()));val before=blob.read();assertThrows(Exception::class.java) {ChatRepository(blob,box).load()};assertArrayEquals(before,blob.read())}
    @Test fun encryptedEnvelopeCorruptionPreserved() {val blob=MemoryBlob();val box=testBox();single(ChatRepository(blob,box));asV3(blob,box);val bytes=blob.read()!!;bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();blob.write(bytes);assertThrows(Exception::class.java) {ChatRepository(blob,box).load()};assertArrayEquals(bytes,blob.read())}
    @Test fun oversizedEncryptedV3RejectedBeforeWrite() {val blob=MemoryBlob();val box=testBox();val plain="""{"version":3,"activeProviderId":"chatgpt","activeModelId":"UNKNOWN","activeConversationId":"","conversations":[]} """+" ".repeat(8_388_608);blob.write(box.seal(plain.toByteArray()));val bytes=blob.read();assertThrows(Exception::class.java) {ChatRepository(blob,box).load()};assertArrayEquals(bytes,blob.read())}
    @Test fun oversizedConversationCountRejected() {val blob=MemoryBlob();val box=testBox();single(ChatRepository(blob,box));val old=asV3(blob,box);val c=old["conversations"]!!.jsonArray.single().jsonObject;val broken=JsonObject(old+mapOf("activeConversationId" to JsonPrimitive(""),"conversations" to JsonArray((0..32).map {JsonObject(c+mapOf("conversationId" to JsonPrimitive("id$it")))})));blob.write(box.seal(broken.toString().toByteArray()));val bytes=blob.read();assertThrows(Exception::class.java) {ChatRepository(blob,box).load()};assertArrayEquals(bytes,blob.read())}
    @Test fun activeSingleMigratesAndRecoversTruthfully() {val blob=CountingBlob();val box=testBox();single(ChatRepository(blob,box),true);asV3(blob,box);blob.writes=0;val r=ChatRepository(blob,box);assertEquals(MessageState.INTERRUPTED,r.load()[1].state);assertEquals("original answer \\path\n",r.load()[1].text);assertEquals(ReasoningPhase.Interrupted,r.load()[1].reasoning.phase);assertEquals(1,blob.writes)}
    @Test fun activeCollaborateMigratesAndKeepsCompletedStage() {val blob=CountingBlob();val box=testBox();val r=ChatRepository(blob,box,clock={20});collaborate(r,1);val id=r.activeConversation()!!.rounds.single().roundId;r.startCollaborateStage(id,1);asV3(blob,box);blob.writes=0;val restored=ChatRepository(blob,box);restored.load();val stages=restored.activeConversation()!!.rounds.single().stages;assertEquals(CollaborateStageState.Complete,stages[0].state);assertEquals("EXACT_VISIBLE_0",stages[0].output);assertEquals(CollaborateStageState.Interrupted,stages[1].state);assertEquals(CollaborateStageState.NotRun,stages[2].state);assertEquals(1,blob.writes)}
    @Test fun failedCollaborateRoundUnchanged() {val blob=MemoryBlob();val box=testBox();collaborate(ChatRepository(blob,box,clock={20}),2,true);val old=asV3(blob,box);ChatRepository(blob,box).load();assertEquals(equivalent(old),equivalent(root(blob,box)))}
    @Test fun interruptedCollaborateRoundUnchanged() {val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box,clock={20});collaborate(r,1);r.startCollaborateStage(r.activeConversation()!!.rounds.single().roundId,1);ChatRepository(blob,box).load();val old=asV3(blob,box);ChatRepository(blob,box).load();assertEquals(equivalent(old),equivalent(root(blob,box)))}
    @Test fun separateCompareJournalNeverWritten() {val blob=MemoryBlob();val compare=MemoryBlob();val box=testBox();CompareRepository(compare,box).upsert(CompareRun("c","prompt",CompareLaneRecord("A",a,state=CompareLaneState.Completed),CompareLaneRecord("B",b,state=CompareLaneState.Completed),CompareRunState.Completed));val before=compare.read();single(ChatRepository(blob,box));asV3(blob,box);ChatRepository(blob,box).load();assertArrayEquals(before,compare.read());assertEquals("c",CompareRepository(compare,box).load().single().id)}
    @Test fun credentialsNeverTouchedByMigration() {val credentials=MemoryBlob();val store=DeepSeekCredentials(credentials,testBox("credentials"));store.replace("synthetic-key");val bytes=credentials.read();val blob=MemoryBlob();val box=testBox();single(ChatRepository(blob,box));asV3(blob,box);ChatRepository(blob,box).load();assertArrayEquals(bytes,credentials.read())}
    @Test fun migratedHistoryDeleteReorderActivateStillWorks() {val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box,clock={20});val s=single(r);val c=collaborate(r);asV3(blob,box);val next=ChatRepository(blob,box);next.load();next.moveSession(s,-1,ConversationMode.Single);next.activateSession(s);assertEquals(ConversationMode.Single,next.mode());next.deleteSession(c);assertEquals(listOf(s),next.sessions().map {it.id})}
    @Test fun migrationDoesNotRewriteSourceTextOrTitle() {val blob=MemoryBlob();val box=testBox();single(ChatRepository(blob,box));val old=asV3(blob,box);val before=old["conversations"]!!.jsonArray.single().jsonObject;val r=ChatRepository(blob,box);r.load();val after=root(blob,box)["conversations"]!!.jsonArray.single().jsonObject;assertEquals(before["title"],after["title"]);assertEquals(before["messages"],after["messages"])}
    @Test fun migrationDoesNotManufactureDebateHistoryIn7C() {val blob=MemoryBlob();val box=testBox();single(ChatRepository(blob,box));asV3(blob,box);val r=ChatRepository(blob,box);r.load();assertTrue(io.github.xiaomeng2568.meldwise.ui.presentation.historyEntries(io.github.xiaomeng2568.meldwise.ui.presentation.HistoryCategory.Debate,r.sessions(),emptyList()).isEmpty())}
}
