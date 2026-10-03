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

/** Real encrypted repositories; all records are synthetic and no provider is involved. */
class HistoryIntegrationTests {
    private val a=ModelRef("chatgpt","a");private val b=ModelRef("chatgpt","b")
    private val config=CollaborateConfig(CollaborateModel(a,"A"),CollaborateModel(b,"B"))
    private fun single(r:ChatRepository,text:String):String {
        r.newSession(a);val output=r.begin(text).first;r.update(output,"visible",MessageState.COMPLETED);return r.conversationId()
    }
    private fun collab(r:ChatRepository,text:String):String {
        r.newCollaborate();r.configureCollaborate(config);val c=r.beginCollaborate(r.prepareCollaborate(text))
        val id=c.rounds.single().roundId
        repeat(3) {index ->val s=r.startCollaborateStage(id,index).rounds.single().stages[index]
            r.updateCollaborateStage(id,s.copy(output="visible stage $index",state=CollaborateStageState.Complete))}
        return r.conversationId()
    }
    private fun compare(id:String)=CompareRun(id,"synthetic $id",CompareLaneRecord("A",a,state=CompareLaneState.Completed,modelDisplayName="Original A"),
        CompareLaneRecord("B",b,state=CompareLaneState.Completed,modelDisplayName="Original B"),CompareRunState.Completed)
    private class Fixture {
        val blob=MemoryBlob();val box=testBox();val chat=ChatRepository(blob,box)
        val compareBlob=MemoryBlob();val compareBox=testBox("compare");val compare=CompareRepository(compareBlob,compareBox)
    }
    private fun fixture()=Fixture().also {f ->single(f.chat,"single one");collab(f.chat,"collab one")
        single(f.chat,"single two");collab(f.chat,"collab two");f.compare.upsert(compare("c1"));f.compare.upsert(compare("c2"))}
    private fun entries(f:Fixture,category:HistoryCategory)=historyEntries(category,f.chat.sessions(),f.compare.load().reversed())
    private fun count(f:Fixture,category:HistoryCategory)=historySummaries(f.chat.sessions(),f.compare.load()).single {it.category==category}.count

    @Test fun singleCountOnlyIncludesSingle() {assertEquals(2,count(fixture(),HistoryCategory.Chat))}
    @Test fun compareCountUsesSeparateStore() {assertEquals(2,count(fixture(),HistoryCategory.Compare))}
    @Test fun collaborateCountOnlyIncludesCollaborate() {assertEquals(2,count(fixture(),HistoryCategory.Collaborate))}
    @Test fun debateIsUnavailableNotEmptyStore() {val f=fixture();assertTrue(entries(f,HistoryCategory.Debate).isEmpty());assertFalse(HistoryCategory.Debate.available)
        assertEquals("暂未开放",historySummaries(f.chat.sessions(),f.compare.load()).last().label)}
    @Test fun emptyRealCategoriesHaveConsistentZeroLabel() {assertEquals(listOf("0 条记录","0 条记录","0 条记录","暂未开放"),historySummaries(emptyList(),emptyList()).map {it.label})}
    @Test fun categoryOrderMatchesModeChooser() {assertEquals(listOf(HistoryCategory.Chat,HistoryCategory.Compare,HistoryCategory.Collaborate,HistoryCategory.Debate),historySummaries(emptyList(),emptyList()).map {it.category})}
    @Test fun singleEntryOpensSingleMode() {val f=fixture();val e=entries(f,HistoryCategory.Chat).first();f.chat.activateSession(e.id)
        assertEquals(ConversationMode.Single,f.chat.mode());assertFalse(ChatNavigation(compareMode=true).openHistoryEntry(e).compareMode)}
    @Test fun compareEntryOpensCompareMode() {val f=fixture();val e=entries(f,HistoryCategory.Compare).first()
        assertEquals("c2",f.compare.load().single {it.id==e.id}.id);assertTrue(ChatNavigation().openHistoryEntry(e).compareMode);assertNull(e.category.conversationMode)}
    @Test fun collaborateEntryOpensCollaborateMode() {val f=fixture();val e=entries(f,HistoryCategory.Collaborate).first();f.chat.activateSession(e.id)
        assertEquals(ConversationMode.Collaborate,f.chat.mode());assertFalse(ChatNavigation(compareMode=true).openHistoryEntry(e).compareMode);assertEquals(config,f.chat.collaborateConfig())}
    @Test fun singleCategoryBackReturnsToHistoryRoot() {assertEquals(ChatPanel.History,ChatNavigation().open(ChatPanel.History).openHistory(HistoryCategory.Chat).back().panel)}
    @Test fun compareCategoryBackReturnsToHistoryRoot() {assertEquals(ChatPanel.History,ChatNavigation().open(ChatPanel.History).openHistory(HistoryCategory.Compare).back().panel)}
    @Test fun collaborateCategoryBackReturnsToHistoryRoot() {assertEquals(ChatPanel.History,ChatNavigation().open(ChatPanel.History).openHistory(HistoryCategory.Collaborate).back().panel)}
    @Test fun historyRootBackRestoresPreviousScreen() {val n=ChatNavigation(compareMode=true).open(ChatPanel.Settings).open(ChatPanel.History).openHistory(HistoryCategory.Chat)
        assertEquals(ChatPanel.Settings,n.back().back().panel);assertEquals(ChatPanel.None,n.back().back().back().panel);assertTrue(n.back().back().back().compareMode)}
    @Test fun debateCannotOpenCategoryOrEntry() {val n=ChatNavigation().open(ChatPanel.History);assertEquals(n,n.openHistory(HistoryCategory.Debate))
        assertThrows(IllegalArgumentException::class.java) {n.openHistoryEntry(HistoryEntry("none",HistoryCategory.Debate,"",""))}}
    @Test fun openCategoryAddsHistoryParentWhenNeeded() {val n=ChatNavigation().openHistory(HistoryCategory.Compare);assertEquals(HistoryCategory.Compare,n.historyCategory);assertEquals(ChatPanel.History,n.back().panel)}
    @Test fun openingCategoryTwiceDoesNotDuplicatePanel() {val n=ChatNavigation().openHistory(HistoryCategory.Chat);assertEquals(n,n.openHistory(HistoryCategory.Chat))}
    @Test fun switchingCategoriesStillReturnsToHistoryRoot() {val n=ChatNavigation().open(ChatPanel.Settings).openHistory(HistoryCategory.Chat).openHistory(HistoryCategory.Collaborate)
        assertEquals(ChatPanel.History,n.back().panel);assertEquals(ChatPanel.Settings,n.back().back().panel)}
    @Test fun deleteSingleOnlyChangesSingle() {val f=fixture();val old=f.compareBlob.read();f.chat.deleteSession(entries(f,HistoryCategory.Chat).first().id)
        assertEquals(1,count(f,HistoryCategory.Chat));assertEquals(2,count(f,HistoryCategory.Collaborate));assertArrayEquals(old,f.compareBlob.read())}
    @Test fun deleteCompareOnlyChangesCompare() {val f=fixture();val old=f.blob.read();f.compare.delete(entries(f,HistoryCategory.Compare).first().id)
        assertEquals(1,count(f,HistoryCategory.Compare));assertArrayEquals(old,f.blob.read())}
    @Test fun deleteCollaborateOnlyChangesCollaborate() {val f=fixture();val old=f.compareBlob.read();f.chat.deleteSession(entries(f,HistoryCategory.Collaborate).first().id)
        assertEquals(1,count(f,HistoryCategory.Collaborate));assertEquals(2,count(f,HistoryCategory.Chat));assertArrayEquals(old,f.compareBlob.read());assertEquals(ConversationMode.Single,f.chat.mode())}
    @Test fun singleMoveSkipsOtherModesAndPreservesTheirSlots() {val f=fixture();val before=f.chat.sessions().map {it.id};val singles=entries(f,HistoryCategory.Chat).map {it.id}
        f.chat.moveSession(singles.last(),-1,ConversationMode.Single);val after=f.chat.sessions().map {it.id}
        assertEquals(singles.reversed(),entries(f,HistoryCategory.Chat).map {it.id});before.indices.filter {before[it] !in singles}.forEach {assertEquals(before[it],after[it])}}
    @Test fun collaborateMoveSkipsSinglesAndPersists() {val f=fixture();val singles=entries(f,HistoryCategory.Chat).map {it.id};val collabs=entries(f,HistoryCategory.Collaborate).map {it.id}
        f.chat.moveSession(collabs.first(),1,ConversationMode.Collaborate);val restored=ChatRepository(f.blob,f.box).sessions()
        assertEquals(collabs.reversed(),historyEntries(HistoryCategory.Collaborate,restored,emptyList()).map {it.id});assertEquals(singles,historyEntries(HistoryCategory.Chat,restored,emptyList()).map {it.id})}
    @Test fun compareMovePreservesConversationCiphertext() {val f=fixture();val old=f.blob.read();f.compare.move("c1",-1)
        assertEquals(listOf("c1","c2"),entries(f,HistoryCategory.Compare).map {it.id});assertArrayEquals(old,f.blob.read())}
    @Test fun categoryEdgeMoveDoesNotMoveThroughOtherModes() {val f=fixture();val order=f.chat.sessions().map {it.id};f.chat.moveSession(entries(f,HistoryCategory.Chat).first().id,-1,ConversationMode.Single)
        assertEquals(order,f.chat.sessions().map {it.id})}
    @Test fun wrongCategoryCannotMoveRecord() {val f=fixture();val before=f.blob.read();assertThrows(IllegalArgumentException::class.java) {
        f.chat.moveSession(entries(f,HistoryCategory.Chat).first().id,-1,ConversationMode.Collaborate)};assertArrayEquals(before,f.blob.read())}
    @Test fun manualCategoryOrderSurvivesNewMessage() {val f=fixture();val e=entries(f,HistoryCategory.Chat).last();f.chat.moveSession(e.id,-1,ConversationMode.Single)
        f.chat.activateSession(e.id);val output=f.chat.begin("follow up").first;f.chat.update(output,"reply",MessageState.COMPLETED)
        assertEquals(e.id,entries(f,HistoryCategory.Chat).first().id);assertEquals(2,count(f,HistoryCategory.Chat))}
    @Test fun openingSingleDoesNotDuplicateRecord() {val f=fixture();val e=entries(f,HistoryCategory.Chat).first();repeat(5) {f.chat.activateSession(e.id)};assertEquals(2,count(f,HistoryCategory.Chat))}
    @Test fun openingCompareDoesNotDuplicateRecord() {val f=fixture();repeat(5) {assertEquals("c2",f.compare.load().last().id)};assertEquals(2,count(f,HistoryCategory.Compare))}
    @Test fun openingCollaborateKeepsStagesWithoutDuplication() {val f=fixture();val e=entries(f,HistoryCategory.Collaborate).last();repeat(5) {f.chat.activateSession(e.id)}
        assertEquals(2,count(f,HistoryCategory.Collaborate));assertEquals(3,f.chat.activeConversation()!!.rounds.single().stages.size)}
    @Test fun restartPreservesClassificationAndCounts() {val f=fixture();val summaries=historySummaries(f.chat.sessions(),f.compare.load())
        assertEquals(summaries,historySummaries(ChatRepository(f.blob,f.box).sessions(),CompareRepository(f.compareBlob,f.compareBox).load()))}
    @Test fun migratedAlpha2IsClassifiedAsSingleAndReadable() {val blob=MemoryBlob();val box=testBox();val pair=listOf(ChatMessage("u",null,MessageRole.USER,"old question",MessageState.COMPLETED),ChatMessage("a","u",MessageRole.ASSISTANT,"old answer",MessageState.COMPLETED))
        val source=buildJsonObject {put("version",2);put("activeProviderId","chatgpt");put("activeModelId","a");put("activeSessionId","old")
            putJsonArray("sessions") {add(buildJsonObject {put("providerId","chatgpt");put("modelId","a");put("sessionId","old");put("messages",Json.parseToJsonElement(Json.encodeToString(pair)))})}}
        blob.write(box.seal(source.toString().toByteArray()));val r=ChatRepository(blob,box)
        assertEquals("old",historyEntries(HistoryCategory.Chat,r.sessions(),emptyList()).single().id);assertEquals(listOf("old question","old answer"),r.load().map {it.text});assertTrue(historyEntries(HistoryCategory.Collaborate,r.sessions(),emptyList()).isEmpty())}
    @Test fun legacyArrayHistoryStillOpensAsSingle() {val blob=MemoryBlob();val box=testBox();blob.write(box.seal(Json.encodeToString(listOf(ChatMessage("u",null,MessageRole.USER,"legacy",MessageState.COMPLETED))).toByteArray()))
        val r=ChatRepository(blob,box);assertEquals("legacy",historyEntries(HistoryCategory.Chat,r.sessions(),emptyList()).single().title);assertEquals(ConversationMode.Single,r.mode())}
    @Test fun failedCategoryMovePreservesCiphertextAndOrdering() {val memory=MemoryBlob();var reject=false;val box=testBox()
        val blob=object:AtomicBlob {override fun read()=memory.read();override fun write(value:ByteArray) {if(reject) error("synthetic write failure");memory.write(value)}}
        val r=ChatRepository(blob,box);single(r,"s1");collab(r,"c");single(r,"s2");val old=memory.read();val order=r.sessions().map {it.id};reject=true
        assertThrows(IllegalStateException::class.java) {r.moveSession(order.last(),-1,ConversationMode.Single)};assertArrayEquals(old,memory.read());assertEquals(order,r.sessions().map {it.id})}
    @Test fun categoryPermutationRejectsDuplicateIds() {assertThrows(IllegalArgumentException::class.java) {movedCategoryHistory(listOf("a","a"),setOf("a"),"a",-1)}}
    @Test fun entrySnapshotsUseCorrectProviderAndCompareModelNames() {val f=fixture();assertEquals("ChatGPT-Original A × ChatGPT-Original B",entries(f,HistoryCategory.Compare).first().subtitle)
        assertEquals("ChatGPT-a",entries(f,HistoryCategory.Chat).first().subtitle)}
    @Test fun historyPresentationToStringDoesNotExposeTitle() {assertFalse(HistoryEntry("id",HistoryCategory.Chat,"PRIVATE_SYNTHETIC","PRIVATE_MODEL").toString().contains("PRIVATE"))}
    @Test fun presentationReadingDoesNotWriteEitherStore() {val f=fixture();val chat=f.blob.read();val compare=f.compareBlob.read();HistoryCategory.entries.forEach {entries(f,it)}
        assertArrayEquals(chat,f.blob.read());assertArrayEquals(compare,f.compareBlob.read())}
}
