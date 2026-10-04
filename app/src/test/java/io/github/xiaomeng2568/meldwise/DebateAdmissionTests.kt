// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.AtomicBlob
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebateAdmissionTests {
    private fun altered(p:PreparedDebate,cid:String=p.conversationId,previous:String?=p.previousId,
        config:DebateConfig=p.config,context:VisibleContext=p.context,sharing:Boolean=p.requiresSharing,
        revision:Long=p.revision,retryOf:String?=p.retryOf,inputRevision:Long=p.inputRevision)=
        PreparedDebate(cid,previous,p.text,config,context,sharing,revision,retryOf,inputRevision)
    private suspend fun rejects(h:DebateHarness,p:PreparedDebate=h.repository.prepareDebate("q"),allow:Boolean=true,
        type:Class<out Exception> = IllegalArgumentException::class.java,kind:ErrorKind?=null) {
        val before=h.blob.read();val history=h.repository.sessions().size
        val failure=try {h.executor().execute(p,allow);error("EXPECTED_REJECTION")} catch(f:Exception) {f}
        assertTrue("wrong failure ${failure.javaClass.simpleName}",type.isInstance(failure))
        if(kind!=null) assertEquals(kind,(failure as ProviderFailure).error.kind)
        assertEquals(0,h.calls.size);assertArrayEquals(before,h.blob.read());assertEquals(history,h.repository.sessions().size)
    }
    @Test fun missingConsentZeroCallsAdmissionAndGrant()=runTest {val h=DebateHarness(this);rejects(h,allow=false,type=ContextSharingRequired::class.java);assertNull(h.repository.activeConversation());assertEquals(0,h.readyChecks())}
    @Test fun sameProviderNoSharingPrompt()=runTest {val h=DebateHarness(this,DebateFixtures.same);h.automatic={it.complete()};val p=h.repository.prepareDebate("q");assertFalse(p.requiresSharing);h.executor().execute(p);assertTrue(h.round().config.providers.size==1);assertTrue(h.repository.activeConversation()!!.debateGrants.isEmpty())}
    @Test fun allowedSharingAtomicBeforeFirstRequest()=runTest {val h=DebateHarness(this);h.automatic={assertEquals(setOf("chatgpt|deepseek"),h.repository.activeConversation()!!.debateGrants);assertEquals(5,h.round().stages.size);assertEquals(1,h.repository.activeConversation()!!.messages.size);it.complete()};h.executor().execute(h.repository.prepareDebate("q"),true);assertEquals(5,h.calls.size)}
    @Test fun grantNotGlobalAcrossConversations()=runTest {val h=DebateHarness(this);h.automatic={it.complete()};h.executor().execute(h.repository.prepareDebate("q"),true);h.calls.clear();h.repository.newDebate();h.repository.configureDebate(h.config);rejects(h,allow=false,type=ContextSharingRequired::class.java)}
    @Test fun providerSetChangeRequiresGrant()=runTest {val h=DebateHarness(this,DebateFixtures.config.copy(judge=DebateFixtures.b));h.automatic={it.complete()};h.executor().execute(h.repository.prepareDebate("q"),true);h.calls.clear();h.repository.configureDebate(DebateFixtures.same);rejects(h,allow=false,type=ContextSharingRequired::class.java)}
    @Test fun forgedConsentFlagCannotBypassConfirmation()=runTest {val h=DebateHarness(this);rejects(h,altered(h.repository.prepareDebate("q"),sharing=false),allow=false)}
    @Test fun unavailableAZeroRequests()=runTest {val h=DebateHarness(this);h.readiness={ref,_->ref.providerId!="deepseek"};rejects(h,type=ProviderFailure::class.java,kind=ErrorKind.AUTHENTICATION)}
    @Test fun unavailableBZeroRequests()=runTest {val h=DebateHarness(this);h.readiness={ref,_->ref.providerId!="chatgpt"};rejects(h,type=ProviderFailure::class.java,kind=ErrorKind.AUTHENTICATION)}
    @Test fun unavailableJudgePreflightZeroRequests()=runTest {val config=DebateFixtures.same.copy(judge=DebateFixtures.b);val h=DebateHarness(this,config);h.readiness={ref,_->ref.providerId!="chatgpt"};rejects(h,type=ProviderFailure::class.java,kind=ErrorKind.AUTHENTICATION)}
    @Test fun readinessExceptionSafeAuthentication()=runTest {val h=DebateHarness(this);h.readiness={_,_->error("PRIVATE_CREDENTIAL_EXCEPTION")};rejects(h,type=ProviderFailure::class.java,kind=ErrorKind.AUTHENTICATION)}
    @Test fun allUniqueModelsPreflightBeforeAdmission()=runTest {val h=DebateHarness(this);h.automatic={assertTrue(h.readyChecks()>=4);it.complete()};h.executor().execute(h.repository.prepareDebate("q"),true)}
    @Test fun configAEqualsBRejectedBeforeRequests()=runTest {val h=DebateHarness(this);rejects(h,altered(h.repository.prepareDebate("q"),config=h.config.copy(modelB=h.config.modelA)))}
    @Test fun unsupportedPreferenceRejectedBeforeRequests()=runTest {val h=DebateHarness(this);rejects(h,altered(h.repository.prepareDebate("q"),config=h.config.copy(modelB=h.config.modelB.copy(preference=ReasoningPreference.Max))))}
    @Test fun invalidProviderRejectedBeforeRequests()=runTest {val h=DebateHarness(this);rejects(h,altered(h.repository.prepareDebate("q"),config=h.config.copy(judge=h.config.judge.copy(ref=ModelRef("unknown","j")))))}
    @Test fun revisionMismatchRejected()=runTest {val h=DebateHarness(this);rejects(h,altered(h.repository.prepareDebate("q"),revision=-1))}
    @Test fun wrongConversationRejected()=runTest {val h=DebateHarness(this);rejects(h,altered(h.repository.prepareDebate("q"),cid="wrong"))}
    @Test fun wrongPreviousMessageRejected()=runTest {val h=DebateHarness(this);rejects(h,altered(h.repository.prepareDebate("q"),previous="wrong"))}
    @Test fun staleConfigRejected()=runTest {val h=DebateHarness(this);val p=h.repository.prepareDebate("q");h.repository.configureDebate(DebateFixtures.same);rejects(h,p)}
    @Test fun wrongModeRejected()=runTest {val h=DebateHarness(this);val p=h.repository.prepareDebate("q");h.repository.newSession(DebateFixtures.a.ref);rejects(h,p)}
    @Test fun activeRoundRejected()=runTest {val h=DebateHarness(this);h.repository.beginDebate(h.repository.prepareDebate("old"),true);rejects(h)}
    @Test fun oversizedMandatoryContextRejected()=runTest {val h=DebateHarness(this);val p=h.repository.prepareDebate("q");val context=VisibleContext(listOf(LlmMessage(MessageRole.USER,"X".repeat(131072)),LlmMessage(MessageRole.ASSISTANT,"a"),LlmMessage(MessageRole.USER,p.text)),emptySet());rejects(h,altered(p,context=context),type=ProviderFailure::class.java,kind=ErrorKind.CONTEXT_OVERFLOW)}
    @Test fun malformedFrozenRolesRejected()=runTest {val h=DebateHarness(this);val p=h.repository.prepareDebate("q");rejects(h,altered(p,context=VisibleContext(listOf(LlmMessage(MessageRole.DEVELOPER,p.text)),emptySet())))}
    @Test fun forgedRetryRejected()=runTest {val h=DebateHarness(this);rejects(h,altered(h.repository.prepareDebate("q"),retryOf="unknown"))}
    @Test fun recordCapRejectionPreservesHistory()=runTest {val h=DebateHarness(this);repeat(33) {val c=h.repository.beginDebate(h.repository.prepareDebate("q$it"),true);h.repository.cancelDebateRound(c.debateRounds.last().roundId)};rejects(h)}
    @Test fun conversationCapRejectionPreservesHistory()=runTest {val h=DebateHarness(this);repeat(32) {h.repository.newDebate();h.repository.configureDebate(h.config);val c=h.repository.beginDebate(h.repository.prepareDebate("q$it"),true);h.repository.cancelDebateRound(c.debateRounds.last().roundId)};h.repository.newDebate();h.repository.configureDebate(h.config);rejects(h)}
    @Test fun revisionRecheckedAfterSuspendedReadiness()=runTest {val h=DebateHarness(this);val p=h.repository.prepareDebate("q");h.readiness={_,n->if(n==1) h.repository.configureDebate(h.config);true};rejects(h,p)}
    @Test fun cancelledBeforeAdmissionZeroRequests()=runTest {val h=DebateHarness(this);val task=h.start();task.cancelAndJoin();assertNull(h.repository.activeConversation());assertEquals(0,h.calls.size)}
    @Test fun admissionDiskFailureZeroRequestsAndNoGrant()=runTest {var bytes:ByteArray?=null;var reject=false;val blob=object:AtomicBlob {override fun read()=bytes;override fun write(value:ByteArray) {if(reject) throw java.io.IOException("synthetic");bytes=value.copyOf()}};val h=DebateHarness(this,blob=blob);reject=true;rejects(h,type=ProviderFailure::class.java,kind=ErrorKind.STORAGE);assertNull(h.repository.activeConversation())}
}
