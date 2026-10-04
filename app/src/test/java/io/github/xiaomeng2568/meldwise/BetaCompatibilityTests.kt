// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.auth.*
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.*
import io.github.xiaomeng2568.meldwise.ui.content.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Alpha 4 encrypted schema-4 fixtures. No account/provider/device access. */
class BetaCompatibilityTests {
    private val a=ModelRef("deepseek","a");private val b=ModelRef("deepseek","b")
    private val source="## Old answer\n\nA|B\n---|---:\nx|1\n\n---\n~~old~~\n- [ ] next\n- [x] done\n  - nested\n\n`ModelRef`"
    private class Fixture {
        val chat=MemoryBlob();val compare=MemoryBlob();val cache=MemoryBlob();val oauth=MemoryBlob();val api=MemoryBlob()
        val box=testBox();val otherBox=testBox("other")
        val single:String;val collab:String;val debate:String
        init {
            val r=ChatRepository(chat,box,clock={20})
            val a=ModelRef("deepseek","a");val b=ModelRef("deepseek","b")
            r.newSession(a);val message=r.begin("old question").first;r.update(message,BetaCompatibilityTests().source,MessageState.COMPLETED)
            single=r.conversationId()
            r.newCollaborate();r.configureCollaborate(CollaborateConfig(CollaborateModel(a,"A"),CollaborateModel(b,"B"),ReviewIntensity.STRICT,SynthesisRole.REVIEWER))
            val round=r.beginCollaborate(r.prepareCollaborate("old collab")).rounds.last()
            for(i in 0..2) {val s=r.startCollaborateStage(round.roundId,i).rounds.last().stages[i]
                r.updateCollaborateStage(round.roundId,s.copy(output="collab-$i",state=CollaborateStageState.Complete,endedAt=20))}
            collab=r.conversationId()
            r.newDebate();r.configureDebate(DebateFixtures.same)
            val dr=r.beginDebate(r.prepareDebate("old debate")).debateRounds.last()
            DebateStageType.entries.forEach {type ->val s=r.startDebateStage(dr.roundId,type).debateRounds.last().stage(type)
                r.updateDebateStage(dr.roundId,s.copy(output="debate-$type",state=DebateStageState.Complete,endedAt=20))}
            debate=r.conversationId()
            CompareRepository(compare,otherBox).upsert(CompareRun("old-compare","q",
                CompareLaneRecord("A",a,output="lane-a",state=CompareLaneState.Completed),
                CompareLaneRecord("B",b,output="lane-b",state=CompareLaneState.Completed),CompareRunState.Completed))
            val catalog=ModelCatalogCache(cache,otherBox)
            listOf("chatgpt","deepseek").forEach {id ->catalog.save(id,listOf(LlmModel("a","Old model","provider-catalog",ProviderCapability(setOf(Capability.USAGE)))),20)}
            DeepSeekCredentials(api,otherBox).replace("SYNTHETIC_ONLY_NOT_A_PROVIDER_KEY")
            EncryptedCredentialStore(oauth,otherBox).write(StoredSession(
                Registration(Siwc.ISSUER,Secret("synthetic-client"),Secret("synthetic-subject"),"synthetic-host"),
                CredentialSet(Secret("synthetic-access"),Secret("synthetic-refresh"),Secret("synthetic-id"),1000,Scopes.requested,0),
                1,CredentialPhase.ACTIVE))
        }
        fun reopened()=ChatRepository(chat,box)
    }
    @Test fun completeAlpha4JournalLoadsWithoutRewrite() {
        val f=Fixture();val before=f.chat.read();val r=f.reopened();r.load()
        assertEquals(3,r.sessions().size);assertArrayEquals(before,f.chat.read())
        val plain=f.box.open(f.chat.read()!!);try {assertEquals(4,Json.parseToJsonElement(utf8(plain)).jsonObject["version"]!!.jsonPrimitive.int)} finally {plain.fill(0)}
    }
    @Test fun oldSingleSourceGainsMarkdownWithoutMigration() {
        val f=Fixture();val r=f.reopened();r.activateSession(f.single)
        val answer=r.load().last().text;assertEquals(source,answer)
        val parsed=ContentParser.parse(answer)
        assertTrue(parsed.blocks.any {it is TableBlock});assertTrue(parsed.blocks.any {it is ThematicBreakBlock})
        assertEquals(listOf(false,true),parsed.blocks.filterIsInstance<TextBlock>().mapNotNull {it.taskChecked})
        assertTrue(InlineParser.parse(answer).any {it.strike});assertEquals(source,r.load().last().text)
    }
    @Test fun collabConfigAndFinalOutputsSurviveBetaReload() {
        val f=Fixture();val r=f.reopened();r.activateSession(f.collab);val c=r.activeConversation()!!
        assertEquals(ConversationMode.Collaborate,r.mode());assertEquals(ReviewIntensity.STRICT,c.collaborate!!.reviewIntensity)
        assertEquals(SynthesisRole.REVIEWER,c.collaborate.synthesisRole)
        assertEquals(listOf("collab-0","collab-1","collab-2"),c.rounds.single().stages.map {it.output})
    }
    @Test fun debateFiveStagesConfigAndFinalSurviveBetaReload() {
        val f=Fixture();val r=f.reopened();r.activateSession(f.debate);val c=r.activeConversation()!!
        assertEquals(ConversationMode.Debate,r.mode());assertEquals(DebateFixtures.same,r.debateConfig())
        assertEquals(5,c.debateRounds.single().stages.size)
        assertTrue(c.debateRounds.single().stages.all {it.state==DebateStageState.Complete})
        assertEquals("debate-JUDGE",c.debateRounds.single().stage(DebateStageType.JUDGE).output)
    }
    @Test fun separateCompareJournalUnchanged() {
        val f=Fixture();val before=f.compare.read();f.reopened().load()
        val run=CompareRepository(f.compare,f.otherBox).load().single()
        assertEquals("old-compare",run.id);assertEquals("lane-a",run.laneA.output);assertEquals("lane-b",run.laneB.output)
        assertArrayEquals(before,f.compare.read())
    }
    @Test fun catalogRetainedWithoutRefreshOrRewrite() {
        val f=Fixture();val before=f.cache.read();f.reopened().load()
        val catalogs=ModelCatalogCache(f.cache,f.otherBox).load()
        assertEquals(setOf("chatgpt","deepseek"),catalogs.keys);assertEquals(20L,catalogs.getValue("deepseek").updatedAt)
        assertEquals("Old model",catalogs.getValue("chatgpt").models.single().displayName);assertArrayEquals(before,f.cache.read())
    }
    @Test fun credentialsRemainInExistingEncryptedAbstractions() {
        val f=Fixture();val api=f.api.read();val oauth=f.oauth.read();f.reopened().load()
        assertEquals(ApiKeyState.CONFIGURED,DeepSeekCredentials(f.api,f.otherBox).state())
        val session=EncryptedCredentialStore(f.oauth,f.otherBox).read()!!
        assertEquals(CredentialPhase.ACTIVE,session.phase)
        assertFalse(session.toString().contains("synthetic-access"))
        assertArrayEquals(api,f.api.read());assertArrayEquals(oauth,f.oauth.read())
    }
    @Test fun openingAllHistoryCreatesNoRuntimeRequestRecord() {
        val f=Fixture();val owner=RuntimeUsage();val r=f.reopened()
        listOf(f.single,f.collab,f.debate).forEach {r.activateSession(it);r.load()}
        assertNull(owner.snapshot());assertEquals(3,r.sessions().size)
    }
    @Test fun singleStreamingRestoreRetainsPartialNoReplay() {
        val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box);r.newSession(a)
        val id=r.begin("q").first;r.update(id,"partial",MessageState.STREAMING)
        val restored=ChatRepository(blob,box);assertEquals(MessageState.INTERRUPTED,restored.load().last().state)
        assertEquals("partial",restored.load().last().text);assertEquals(2,restored.load().size)
    }
    @Test fun collabRestorePreservesCompletedSiblingAndStops() {
        val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box,clock={20})
        r.newCollaborate();r.configureCollaborate(CollaborateConfig(CollaborateModel(a,"A"),CollaborateModel(b,"B")))
        val round=r.beginCollaborate(r.prepareCollaborate("q")).rounds.last()
        val s=r.startCollaborateStage(round.roundId,0).rounds.last().stages[0]
        r.updateCollaborateStage(round.roundId,s.copy(output="initial",state=CollaborateStageState.Complete,endedAt=20))
        r.startCollaborateStage(round.roundId,1)
        val restored=ChatRepository(blob,box);restored.load();val stages=restored.activeConversation()!!.rounds.single().stages
        assertEquals(listOf(CollaborateStageState.Complete,CollaborateStageState.Interrupted,CollaborateStageState.NotRun),stages.map {it.state})
        assertEquals("initial",stages[0].output)
    }
    @Test fun debateRestorePreservesCompletedSiblingAndDoesNotResume() {
        val blob=MemoryBlob();val box=testBox();val r=ChatRepository(blob,box,clock={20})
        r.newDebate();r.configureDebate(DebateFixtures.same)
        val round=r.beginDebate(r.prepareDebate("q")).debateRounds.last()
        val s=r.startDebateStage(round.roundId,DebateStageType.INITIAL_A).debateRounds.last().stage(DebateStageType.INITIAL_A)
        r.updateDebateStage(round.roundId,s.copy(output="initial-a",state=DebateStageState.Complete,endedAt=20))
        r.startDebateStage(round.roundId,DebateStageType.INITIAL_B)
        val restored=ChatRepository(blob,box);restored.load();val next=restored.activeConversation()!!.debateRounds.single()
        assertEquals(DebateStageState.Complete,next.stage(DebateStageType.INITIAL_A).state)
        assertEquals(DebateStageState.Interrupted,next.stage(DebateStageType.INITIAL_B).state)
        assertTrue(next.stages.drop(2).all {it.state==DebateStageState.NotRun})
        assertEquals("initial-a",next.stage(DebateStageType.INITIAL_A).output)
    }
}
