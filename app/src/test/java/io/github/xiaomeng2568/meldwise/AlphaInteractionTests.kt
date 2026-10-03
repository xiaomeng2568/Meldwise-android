package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.AtomicBlob
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import org.junit.Assert.*
import org.junit.Test

class AlphaInteractionTests {
    private val ref=ModelRef("deepseek","m")
    private fun compare(id:String)=CompareRun(id,"prompt $id",CompareLaneRecord("A",ModelRef("chatgpt","a"),state=CompareLaneState.Completed),
        CompareLaneRecord("B",ref,state=CompareLaneState.Completed),CompareRunState.Completed)
    @Test fun backReturnsToParentPanelInsteadOfExiting() {
        var nav=ChatNavigation().open(ChatPanel.Settings).open(ChatPanel.History)
        nav=nav.back();assertEquals(ChatPanel.Settings,nav.panel);nav=nav.back();assertEquals(ChatPanel.None,nav.panel);assertFalse(nav.handlesBack)
    }
    @Test fun backFromLanePickerPreservesCompareMode() {
        val nav=ChatNavigation().selectMode(true).open(ChatPanel.Models).back()
        assertEquals(ChatPanel.CompareSetup,nav.panel);assertTrue(nav.compareMode)
    }
    @Test fun backClosesComposerOptionsBeforeMode() {
        val nav=ChatNavigation(compareMode=true,composerOptions=true).back()
        assertFalse(nav.composerOptions);assertTrue(nav.compareMode);assertFalse(nav.back().compareMode)
    }
    @Test fun outsideDismissClosesAllPanelsButKeepsMode() {
        val nav=ChatNavigation().selectMode(true).open(ChatPanel.Models).dismiss()
        assertEquals(ChatPanel.None,nav.panel);assertTrue(nav.compareMode)
    }
    @Test fun openingSamePanelDoesNotStackItTwice() {val n=ChatNavigation().open(ChatPanel.Models);assertEquals(n,n.open(ChatPanel.Models))}
    @Test fun modeChooserDoesNotStartAnOperation() {assertEquals(ChatPanel.Modes,ChatNavigation().open(ChatPanel.Modes).panel)}
    @Test fun deletingSingleKeepsOtherSessions() {
        val r=ChatRepository(MemoryBlob(),testBox());r.newSession(ref);r.begin("one");val id=r.sessions().single().id
        r.newSession(ref);r.begin("two");r.deleteSession(id);assertEquals(1,r.sessions().size);assertEquals("two",r.load().first().text)
    }
    @Test fun deletingCurrentSingleSelectsRemainingTruthfully() {
        val r=ChatRepository(MemoryBlob(),testBox());r.newSession(ModelRef("chatgpt","a"));r.begin("first")
        r.newSession(ref);r.begin("second");r.deleteSession(r.sessions().first().id)
        assertEquals(ModelRef("chatgpt","a"),r.activeRef());assertEquals("first",r.load().first().text)
    }
    @Test fun deletingLastSingleLeavesEmptyLocalDraft() {
        val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box);r.newSession(ref);r.begin("last")
        r.deleteSession(r.sessions().single().id);assertTrue(r.sessions().isEmpty());assertTrue(ChatRepository(blob,box).load().isEmpty())
    }
    @Test fun legacyRecordCanBeDeletedWithoutFabricatingIds() {
        val r=ChatRepository(MemoryBlob(),testBox());r.activate(ref);r.begin("legacy");val id=r.sessions().single().id
        assertTrue(id.startsWith("legacy:"));r.deleteSession(id);assertTrue(r.sessions().isEmpty())
    }
    @Test fun unknownSingleIdDoesNotDeleteAnything() {val r=ChatRepository(MemoryBlob(),testBox());r.newSession(ref);assertThrows(IllegalArgumentException::class.java) {r.moveSession("missing",-1)};assertEquals(1,r.sessions().size)}
    @Test fun singleOrderPersistsAcrossRestart() {
        val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box);repeat(3) {r.newSession(ref)}
        val order=r.sessions().map {it.id};r.moveSession(order.last(),-1)
        assertEquals(listOf(order[0],order[2],order[1]),ChatRepository(blob,box).sessions().map {it.id})
    }
    @Test fun updatingSingleDoesNotUndoManualOrder() {
        val r=ChatRepository(MemoryBlob(),testBox());r.newSession(ref);val (message,_)=r.begin("first");val first=r.sessions().first().id
        r.newSession(ref);r.moveSession(first,-1);r.activateSession(first);r.update(message,"answer",MessageState.COMPLETED)
        assertEquals(first,r.sessions().first().id)
    }
    @Test fun edgeMoveDoesNotWrapAround() {assertEquals(listOf("a","b"),movedHistory(listOf("a","b"),"a",-1));assertEquals(listOf("a","b"),movedHistory(listOf("a","b"),"b",1))}
    @Test fun invalidMoveCannotShuffleHistory() {assertThrows(IllegalArgumentException::class.java) {movedHistory(listOf("a","b"),"a",2)}}
    @Test fun deletingCompareKeepsOtherRuns() {
        val r=CompareRepository(MemoryBlob(),testBox());r.upsert(compare("one"));r.upsert(compare("two"));r.delete("one")
        assertEquals(listOf("two"),r.load().map {it.id})
    }
    @Test fun compareOrderPersistsAndUpdatesDoNotShuffleIt() {
        val blob=MemoryBlob();val box=testBox();val r=CompareRepository(blob,box)
        r.upsert(compare("one"));r.upsert(compare("two"));r.move("one",-1);r.upsert(compare("two"))
        assertEquals(listOf("one","two"),CompareRepository(blob,box).load().reversed().map {it.id})
    }
    @Test fun unknownCompareCannotDeleteOtherRuns() {val r=CompareRepository(MemoryBlob(),testBox());r.upsert(compare("one"));assertThrows(IllegalArgumentException::class.java) {r.delete("missing")};assertEquals(1,r.load().size)}
    @Test fun failedDeleteWriteRetainsSingleHistory() {
        val memory=MemoryBlob();var fail=false;val blob=object:AtomicBlob {override fun read()=memory.read();override fun write(value:ByteArray) {if(fail) throw IllegalStateException("synthetic failure");memory.write(value)}}
        val r=ChatRepository(blob,testBox());r.newSession(ref);val id=r.sessions().single().id;fail=true
        assertThrows(IllegalStateException::class.java) {r.deleteSession(id)};assertEquals(id,r.sessions().single().id)
    }
}
