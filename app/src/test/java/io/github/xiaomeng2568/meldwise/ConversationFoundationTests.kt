// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConversationFoundationTests {
    private val a=ModelRef("chatgpt","a")
    private val b=ModelRef("deepseek","b")
    private val hidden="SYNTHETIC_REASONING_ONLY"
    private fun turn(r:ChatRepository,u:String="question",answer:String="answer",state:MessageState=MessageState.COMPLETED):String {
        val id=r.begin(u).first
        val kind=if(r.activeRef().providerId=="chatgpt") ReasoningContent.Summary else ReasoningContent.ProviderVisibleReasoning
        r.update(id,answer,state,ReasoningRecord(hidden,kind,ReasoningPhase.Completed));return id
    }
    private fun repository():ChatRepository=ChatRepository(MemoryBlob(),testBox()).also {it.activate(a)}
    private fun legacy(blob:MemoryBlob,box:AesGcmBox,messages:List<ChatMessage>,ref:ModelRef=a,id:String="old-id") {
        val source=buildJsonObject {
            put("version",2);put("activeProviderId",ref.providerId);put("activeModelId",ref.modelId);put("activeSessionId",id)
            putJsonArray("sessions") {add(buildJsonObject {put("providerId",ref.providerId);put("modelId",ref.modelId);put("sessionId",id)
                put("messages",Json.parseToJsonElement(Json.encodeToString(messages)))})}
        }
        blob.write(box.seal(source.toString().toByteArray()))
    }
    private fun oldPair(state:MessageState=MessageState.COMPLETED)=listOf(
        ChatMessage("u",null,MessageRole.USER,"old question",MessageState.COMPLETED),
        ChatMessage("a","u",MessageRole.ASSISTANT,"old answer",state,ReasoningRecord(hidden,ReasoningContent.Summary,ReasoningPhase.Completed)))
    @Test fun tenTurnsAppendToOneConversation() {val r=repository();repeat(10) {turn(r,"q$it","a$it")};assertEquals(20,r.load().size);assertEquals(1,r.sessions().size)}
    @Test fun conversationIdStableAcrossTurns() {val r=repository();turn(r);val id=r.conversationId();turn(r,"second");assertEquals(id,r.conversationId())}
    @Test fun historyTitleStaysFirstQuestion() {val r=repository();turn(r,"first");turn(r,"second");assertEquals("first",r.sessions().single().title)}
    @Test fun emptyDraftDoesNotEnterHistory() {val r=repository();repeat(50) {r.newSession(a)};assertTrue(r.sessions().isEmpty())}
    @Test fun newDraftDoesNotDiscardOldMessages() {val r=repository();turn(r);val id=r.conversationId();r.newSession(a);assertTrue(r.load().isEmpty());assertEquals(2,r.activateSession(id).size)}
    @Test fun newDraftIsNotBoundToAnOldPendingConsent() {val r=repository();val p=r.prepare("draft");r.newSession(a);assertThrows(IllegalArgumentException::class.java) {r.beginPrepared(p)}}
    @Test fun reopenedConversationContinues() {val r=repository();turn(r);val old=r.conversationId();r.newSession(a);turn(r,"another");r.activateSession(old);turn(r,"continued");assertEquals(4,r.load().size);assertEquals(2,r.sessions().size)}
    @Test fun restartAndTurnEleven() {val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box);r.activate(a);repeat(10) {turn(r,"q$it")};val id=r.conversationId();val restored=ChatRepository(blob,box);assertEquals(20,restored.load().size);turn(restored,"q11");assertEquals(id,restored.conversationId());assertEquals(22,restored.load().size)}
    @Test fun titleWhitespaceNormalized() {assertEquals("hello world",conversationTitle(" \n hello\t\u00a0world \n"))}
    @Test fun titleBoundedByCodePoint() {val title=conversationTitle("😺".repeat(100));assertEquals(48,title.codePointCount(0,title.length));assertTrue(title.endsWith("😺"))}
    @Test fun titleBlankFallback() {assertEquals("新对话",conversationTitle(" \t\n"))}
    @Test fun titleDeterministicWithoutProvider() {val r=repository();turn(r,"local title");assertEquals(conversationTitle("local title"),r.activeConversation()!!.title)}
    @Test fun sameProviderSwitchKeepsConversation() {val r=repository();turn(r);val id=r.conversationId();r.activate(ModelRef("chatgpt","next"));assertEquals(id,r.conversationId());assertFalse(r.prepare("next").requiresSharing)}
    @Test fun assistantSnapshotDoesNotChangeWithSelection() {val r=repository();turn(r);r.activate(ModelRef("chatgpt","next"));assertEquals(a,r.load()[1].modelRef)}
    @Test fun displayNameSnapshotSurvivesSwitchAndRestore() {val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box);r.activate(a);val id=r.beginPrepared(r.prepare("q","Original Name")).first;r.update(id,"a",MessageState.COMPLETED);r.activate(b);assertEquals("Original Name",ChatRepository(blob,box).load()[1].modelDisplayName)}
    @Test fun presentationUsesSnapshotRatherThanCurrentCatalog() {val r=repository();turn(r);r.activate(b);assertEquals("ChatGPT-a",presentMessage(r.load()[1],b,"Renamed").metadata)}
    @Test fun switchingProviderKeepsAllVisibleMessages() {val r=repository();turn(r);assertEquals(2,r.activate(b).size)}
    @Test fun crossProviderNeedsConsent() {val r=repository();turn(r);r.activate(b);assertTrue(r.prepare("next").requiresSharing)}
    @Test fun refusingConsentDoesNotAppendOrGrant() {val r=repository();turn(r);r.activate(b);val p=r.prepare("next");assertThrows(ContextSharingRequired::class.java) {r.beginPrepared(p)};assertEquals(2,r.load().size);assertTrue(r.activeConversation()!!.contextGrants.isEmpty())}
    @Test fun consentThenVisibleContextOnly() {val r=repository();turn(r);r.activate(b);val context=r.beginPrepared(r.prepare("next"),true).second;assertEquals(listOf("question","answer","next"),context.map {it.text});assertFalse(context.any {it.text.contains(hidden)})}
    @Test fun grantSurvivesRestartOnlyForThisConversation() {val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box);r.activate(a);turn(r);r.activate(b);val id=r.beginPrepared(r.prepare("b"),true).first;r.update(id,"b reply",MessageState.COMPLETED);val restored=ChatRepository(blob,box);assertFalse(restored.prepare("again").requiresSharing);restored.newSession(a);turn(restored);restored.activate(b);assertTrue(restored.prepare("separate").requiresSharing)}
    @Test fun switchingBackRequiresTargetSpecificGrant() {val r=repository();turn(r);r.activate(b);val id=r.beginPrepared(r.prepare("b"),true).first;r.update(id,"b reply",MessageState.COMPLETED);r.activate(a);assertTrue(r.prepare("back").requiresSharing)}
    @Test fun newEmptyProviderDraftNeedsNoConsent() {val r=repository();r.activate(b);assertFalse(r.prepare("first").requiresSharing)}
    @Test fun trimmedAwayForeignContextNeedsNoSharingGrant() {val r=ChatRepository(MemoryBlob(),testBox(),ConversationContextBuilder(ConversationContextPolicy(0)));r.activate(a);turn(r);r.activate(b);assertFalse(r.prepare("only current").requiresSharing)}
    @Test fun sameProviderReasoningNeverEntersContext() {val r=repository();turn(r);assertFalse(r.begin("next").second.any {it.text.contains(hidden)})}
    @Test fun modelSwitchReasoningNeverEntersContext() {val r=repository();turn(r);r.activate(ModelRef("chatgpt","new"));assertFalse(r.begin("next").second.any {it.text.contains(hidden)})}
    @Test fun restoredReasoningNeverEntersContext() {val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box);r.activate(b);turn(r);val restored=ChatRepository(blob,box);assertFalse(restored.begin("next").second.any {it.text.contains(hidden)});assertEquals(hidden,restored.load()[1].reasoning.text)}
    @Test fun cancelledPartialAndReasoningStayLocal() {val r=repository();turn(r,answer="PARTIAL",state=MessageState.CANCELLED);assertEquals(listOf("next"),r.begin("next").second.map {it.text});assertEquals("PARTIAL",r.load()[1].text)}
    @Test fun failedPartialStaysLocal() {val r=repository();turn(r,answer="PARTIAL",state=MessageState.FAILED);assertEquals(1,r.begin("next").second.size)}
    @Test fun incompletePartialStaysLocal() {val r=repository();turn(r,answer="PARTIAL",state=MessageState.INCOMPLETE);assertEquals(1,r.begin("next").second.size)}
    @Test fun processInterruptionDistinctFromCancel() {val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box);r.activate(b);turn(r,answer="PARTIAL",state=MessageState.STREAMING);val restored=ChatRepository(blob,box);assertEquals(MessageState.INTERRUPTED,restored.load()[1].state);assertEquals(listOf("next"),restored.begin("next").second.map {it.text})}
    @Test fun waitingOperationRestoresInterrupted() {val blob=MemoryBlob();val box=testBox();ChatRepository(blob,box).begin("pending");assertEquals(MessageState.INTERRUPTED,ChatRepository(blob,box).load()[1].state)}
    @Test fun recoveryIsIdempotentAndDoesNotResume() {val blob=MemoryBlob();val box=testBox();ChatRepository(blob,box).begin("pending");val r=ChatRepository(blob,box);r.load();val bytes=blob.read();r.load();ChatRepository(blob,box).load();assertArrayEquals(bytes,blob.read())}
    @Test fun completedAndCancelledStatesSurviveRecovery() {val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box);turn(r);turn(r,"cancel",state=MessageState.CANCELLED);assertEquals(listOf(MessageState.COMPLETED,MessageState.CANCELLED),ChatRepository(blob,box).load().filter {it.role==MessageRole.ASSISTANT}.map {it.state})}
    @Test fun activeReasoningRecoversInterruptedSeparately() {val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box);r.activate(b);val id=r.begin("q").first;r.update(id,"partial",MessageState.STREAMING,ReasoningRecord(hidden,ReasoningContent.ProviderVisibleReasoning,ReasoningPhase.Streaming));assertEquals(ReasoningPhase.Interrupted,ChatRepository(blob,box).load()[1].reasoning.phase)}
    @Test fun newMessageOrderStableAndTimestampsPresent() {val r=repository();turn(r);turn(r);assertEquals(listOf(0,1,2,3),r.load().map {it.order});assertTrue(r.load().all {it.timestamp!=null});assertEquals(4,r.load().map {it.id}.distinct().size)}
    @Test fun oldAlpha2RecordMigratesInSameEncryptedBlob() {val blob=MemoryBlob();val box=testBox();legacy(blob,box,oldPair());val r=ChatRepository(blob,box);assertEquals(2,r.load().size);assertEquals("old-id",r.conversationId());assertEquals(a,r.load()[1].modelRef);assertEquals(4,r.activeConversation()!!.schemaVersion);assertEquals(4,Json.parseToJsonElement(utf8(box.open(blob.read()!!))).jsonObject["version"]!!.jsonPrimitive.int)}
    @Test fun migrationRetainsIdsAndUnknownTimestamps() {val blob=MemoryBlob();val box=testBox();legacy(blob,box,oldPair());val r=ChatRepository(blob,box);assertEquals(listOf("u","a"),r.load().map {it.id});assertTrue(r.load().all {it.timestamp==null});assertNull(r.activeConversation()!!.createdAt)}
    @Test fun migratedOneShotCanContinue() {val blob=MemoryBlob();val box=testBox();legacy(blob,box,oldPair());val r=ChatRepository(blob,box);turn(r,"second");assertEquals(4,r.load().size);assertEquals(1,r.sessions().size)}
    @Test fun migrationPreservesReasoningOnlyAsMetadata() {val blob=MemoryBlob();val box=testBox();legacy(blob,box,oldPair());val r=ChatRepository(blob,box);assertEquals(hidden,r.load()[1].reasoning.text);assertFalse(r.begin("next").second.any {it.text.contains(hidden)})}
    @Test fun migrationDoesNotRewriteOnRepeatedLoad() {val blob=MemoryBlob();val box=testBox();legacy(blob,box,oldPair());ChatRepository(blob,box).load();val first=blob.read();ChatRepository(blob,box).load();assertArrayEquals(first,blob.read())}
    @Test fun failedAtomicMigrationRetainsOldCiphertext() {val memory=MemoryBlob();val box=testBox();legacy(memory,box,oldPair());val original=memory.read();val failing=object:AtomicBlob {override fun read()=memory.read();override fun write(value:ByteArray){error("synthetic disk failure")}};val r=ChatRepository(failing,box);assertThrows(Exception::class.java) {r.load()};assertThrows(Exception::class.java) {r.begin("not sent")};assertArrayEquals(original,memory.read());assertEquals(2,ChatRepository(memory,box).load().size)}
    @Test fun malformedMigrationRetainsCiphertext() {val memory=MemoryBlob();val box=testBox();memory.write(box.seal("""{"version":2,"sessions":[]} """.toByteArray()));val before=memory.read();assertThrows(Exception::class.java) {ChatRepository(memory,box).load()};assertArrayEquals(before,memory.read())}
    @Test fun duplicateLegacyMessageIdsFailClosed() {val blob=MemoryBlob();val box=testBox();legacy(blob,box,listOf(oldPair()[0],oldPair()[0]));val before=blob.read();assertThrows(Exception::class.java) {ChatRepository(blob,box).load()};assertArrayEquals(before,blob.read())}
    @Test fun unknownNewSchemaCannotResetStorage() {val blob=MemoryBlob();val box=testBox();blob.write(box.seal("""{"version":999}""".toByteArray()));val before=blob.read();assertThrows(Exception::class.java) {ChatRepository(blob,box).load()};assertArrayEquals(before,blob.read())}
    @Test fun expiredPreparedTurnCannotReplay() {val r=repository();val plan=r.prepare("q");r.beginPrepared(plan);assertThrows(IllegalArgumentException::class.java) {r.beginPrepared(plan)}}
    @Test fun changedModelInvalidatesPreparedTurn() {val r=repository();val plan=r.prepare("q");r.activate(b);assertThrows(IllegalArgumentException::class.java) {r.beginPrepared(plan,true)}}
    @Test fun changedHistoryInvalidatesPreparedTurn() {val r=repository();turn(r);val plan=r.prepare("q");r.update(r.load()[1].id,"changed",MessageState.COMPLETED);assertThrows(IllegalArgumentException::class.java) {r.beginPrepared(plan)}}
    @Test fun concurrentPendingTurnNotAllowed() {val r=repository();r.begin("q");assertThrows(IllegalArgumentException::class.java) {r.begin("duplicate")}}
    @Test fun manualOrderNotUndoneByContinuation() {val r=repository();turn(r,"first");val first=r.conversationId();r.newSession(a);turn(r,"second");r.moveSession(first,-1);r.activateSession(first);turn(r,"continued");assertEquals(first,r.sessions().first().id)}
    @Test fun deletingActiveLeavesDraftNotAnotherHistory() {val r=repository();turn(r,"first");r.newSession(a);turn(r,"second");r.deleteSession(r.conversationId());assertTrue(r.load().isEmpty());assertEquals(1,r.sessions().size)}
    @Test fun migrationDoesNotTouchSeparateCompareCiphertext() {val chat=MemoryBlob();val compare=MemoryBlob();val box=testBox();val store=CompareRepository(compare,box);store.upsert(CompareRun("compare","prompt",CompareLaneRecord("A",a,state=CompareLaneState.Completed),CompareLaneRecord("B",b,state=CompareLaneState.Completed),CompareRunState.Completed));val before=compare.read();legacy(chat,box,oldPair());ChatRepository(chat,box).load();assertArrayEquals(before,compare.read());assertEquals("compare",CompareRepository(compare,box).load().single().id)}
    @Test fun conversationStorageDoesNotTouchApiCredentialBlob() {val cb=MemoryBlob();val key=DeepSeekCredentials(cb,testBox("credentials"));key.replace("synthetic_key");val before=cb.read();val r=repository();turn(r);assertArrayEquals(before,cb.read());assertEquals(ApiKeyState.CONFIGURED,key.state())}
    @Test fun historyLimitDoesNotEraseData() {val r=repository();repeat(100) {turn(r,"q$it")};assertThrows(IllegalArgumentException::class.java) {r.begin("overflow")};assertEquals(200,r.load().size)}
    @Test fun historyCountBoundDoesNotCreateBlankRecords() {val r=repository();repeat(32) {r.newSession(a);turn(r,"q$it")};r.newSession(a);assertThrows(IllegalArgumentException::class.java) {r.begin("overflow")};assertEquals(32,r.sessions().size)}
    @Test fun privateContentAndReasoningNotInToString() {val r=repository();turn(r);val values=listOf(r.activeConversation(),r.prepare("private"),r.load()[1]);assertTrue(values.all {!it.toString().contains(hidden) && !it.toString().contains("question")})}
    @Test fun historyEncryptedNotPlaintext() {val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box);r.activate(b);turn(r,"SYNTHETIC_PRIVATE_QUESTION");assertFalse(String(blob.bytes!!,Charsets.ISO_8859_1).contains("SYNTHETIC_PRIVATE"));assertFalse(String(blob.bytes!!,Charsets.ISO_8859_1).contains(hidden))}
}
