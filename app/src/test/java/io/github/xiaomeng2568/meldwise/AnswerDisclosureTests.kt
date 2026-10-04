// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.ui.components.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.Motion
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** UI-only contracts and real transient-state operations. No transport or persisted-state writes. */
class AnswerDisclosureTests {
    private fun ui(path:String)=File(System.getProperty("projectRoot"),"app/src/main/java/io/github/xiaomeng2568/meldwise/ui/$path").readText()
    @Test fun answersStartExpanded() {val s=AnswerDisclosures();assertTrue(s.expanded("a"));assertTrue(s.expanded("b"))}
    @Test fun collapseAndExpandAreExplicit() {val s=AnswerDisclosures();s.toggle("a");assertFalse(s.expanded("a"));s.toggle("a");assertTrue(s.expanded("a"))}
    @Test fun siblingsAreIndependent() {val s=AnswerDisclosures();s.toggle("a");assertTrue(s.expanded("b"));s.toggle("b");s.toggle("a");assertTrue(s.expanded("a"));assertFalse(s.expanded("b"))}
    @Test fun roundKeysAreIndependent() {val s=AnswerDisclosures();s.toggle("round1/initial");assertTrue(s.expanded("round2/initial"))}
    @Test fun modeKeysAreIndependent() {val s=AnswerDisclosures();s.toggle("debate/a");assertTrue(s.expanded("compare/a"));assertTrue(s.expanded("collaborate/a"))}
    @Test fun newScreenStateStartsExpanded() {val old=AnswerDisclosures();old.toggle("a");assertTrue(AnswerDisclosures().expanded("a"))}
    @Test fun repeatedReadsDoNotResetCollapsedState() {val s=AnswerDisclosures();s.toggle("a");repeat(1000) {assertFalse(s.expanded("a"));assertTrue(s.expanded("b"))}}
    @Test fun rapidTogglesSettleToLatestIntent() {val s=AnswerDisclosures();repeat(101) {s.toggle("a")};assertFalse(s.expanded("a"));s.toggle("a");assertTrue(s.expanded("a"))}
    @Test fun manyCollapsedSiblingsDoNotOverwriteEachOther() {val s=AnswerDisclosures();repeat(200) {s.toggle("s$it")};repeat(200) {assertFalse(s.expanded("s$it"))};s.toggle("s100");assertTrue(s.expanded("s100"));assertFalse(s.expanded("s99"))}
    @Test fun independentCompareCanCollapse() {assertTrue(answerDisclosureAvailable(AnswerRole.Independent,"answer","compare/run/A"))}
    @Test fun initialCanCollapse() {assertTrue(answerDisclosureAvailable(AnswerRole.Initial,"answer","initial"))}
    @Test fun reviewCanCollapseWhenOptedIn() {assertTrue(answerDisclosureAvailable(AnswerRole.Review,"review","collaborate/review"))}
    @Test fun finalSynthesisNeverCollapses() {assertFalse(answerDisclosureAvailable(AnswerRole.Synthesis,"final","judge"))}
    @Test fun singleConversationIsUnchanged() {assertFalse(answerDisclosureAvailable(AnswerRole.Single,"answer","single"))}
    @Test fun emptyOutputHasNoDeadControl() {assertFalse(answerDisclosureAvailable(AnswerRole.Initial,"","s"))}
    @Test fun whitespaceOutputHasNoDeadControl() {assertFalse(answerDisclosureAvailable(AnswerRole.Initial," \n\t","s"))}
    @Test fun noOptInMeansNoDisclosure() {assertFalse(answerDisclosureAvailable(AnswerRole.Review,"review",null))}
    @Test fun blankIdentityCannotShareCollapseState() {assertFalse(answerDisclosureAvailable(AnswerRole.Initial,"answer"," "))}
    @Test fun streamingTextDoesNotChangeDisclosureState() {val s=AnswerDisclosures();s.toggle("stage");listOf("a","ab","abc").forEach {assertTrue(answerDisclosureAvailable(AnswerRole.Initial,it,"stage"));assertFalse(s.expanded("stage"))}}
    @Test fun buttonLabelsDescribeNextAction() {assertEquals("收起",answerDisclosureLabel(true));assertEquals("展开",answerDisclosureLabel(false))}
    @Test fun togglingDoesNotAlterCompareRecordOrCopySource() {
        val record=CompareLaneRecord("A",ModelRef("deepseek","a"),"  original\\source\n",CompareLaneState.Completed)
        val s=AnswerDisclosures();s.toggle("compare/a");s.toggle("compare/a")
        assertEquals("  original\\source\n",record.output);assertEquals(CompareLaneState.Completed,record.state)
    }
    @Test fun togglingDoesNotAlterCollaborateSnapshot() {
        val stage=CollaborateStage("s",CollaborateStageType.INITIAL,0,CollaborateModel(ModelRef("deepseek","a")),"original",state=CollaborateStageState.Complete)
        val s=AnswerDisclosures();s.toggle(stage.stageId);assertEquals("original",stage.output);assertEquals(CollaborateStageState.Complete,stage.state)
    }
    @Test fun togglingDoesNotAlterDebateSnapshot() {
        val stage=DebateStage("s",DebateStageType.INITIAL_A,DebateModel(ModelRef("deepseek","a")),output="original",state=DebateStageState.Complete)
        val s=AnswerDisclosures();s.toggle(stage.stageId);assertEquals("original",stage.output);assertEquals(DebateStageState.Complete,stage.state)
    }
    @Test fun sharedAnimationUsesExistingMotionAndTopAnchor() {
        val source=ui("components/AnswerDisclosure.kt")
        listOf("expandVertically(tween(Motion.switchMs","shrinkVertically(tween(Motion.switchMs","Alignment.Top","fadeIn(tween(Motion.fadeInMs))","fadeOut(tween(Motion.fadeOutMs))").forEach {assertTrue(source.contains(it))}
        assertEquals(220,Motion.switchMs);assertFalse(source.contains("spring("))
    }
    @Test fun shrinkingBodyIsNotAnAccessibleGhostAction() {val s=ui("components/AnswerDisclosure.kt");assertTrue(s.contains("clearAndSetSemantics {}"));assertTrue(s.contains("PointerEventPass.Initial"));assertTrue(s.contains("it.consume()"))}
    @Test fun disclosureReusesAccessibleCustomPressButton() {val s=ui("components/AnswerDisclosure.kt");assertTrue(s.contains("MeldwiseTextButton("));assertTrue(s.contains("contentDescription=answerDisclosureLabel(expanded)+heading"));assertTrue(s.contains("stateDescription="));assertTrue(ui("components/MeldwisePress.kt").contains("heightIn(min=Sizes.touch)"))}
    @Test fun hostOwnsOnlyTransientFlagsAcrossLazyRecycling() {val s=ui("ChatScreen.kt");assertTrue(s.contains("remember(compareMode,foundation.mode,disclosureOwner) {AnswerDisclosures()}"));assertTrue(s.contains("LocalAnswerDisclosures provides answerDisclosures"));val component=ui("components/AnswerDisclosure.kt");assertTrue(component.contains("mutableStateMapOf<String,Unit>()"));assertFalse(component.contains("SavedStateHandle"));assertFalse(component.contains("rememberSaveable"))}
    @Test fun optInIsWiredToAllRequestedModesNotFinals() {
        assertTrue(ui("components/MessageCard.kt").contains("disclosureKey=\"compare/\${item.key}\""))
        assertTrue(ui("CollaboratePresentation.kt").contains("if(s.type!=CollaborateStageType.SYNTHESIS)"))
        assertTrue(ui("DebatePanels.kt").contains("setOf(DebateStageType.INITIAL_A,DebateStageType.INITIAL_B)"))
    }
    @Test fun modelAndStatusStayOutsideTheCollapsibleBody() {val s=ui("components/MessageCard.kt");assertTrue(s.indexOf("modelLabel(lane.ref")<s.indexOf("val answerContent:"));assertTrue(s.indexOf("statusLabel?.let")<s.indexOf("val answerContent:"));assertTrue(s.contains("ReasoningPanel(lane.reasoning)"));assertTrue(s.contains("ContentRenderer(remember(lane.answer)"))}
    @Test fun stageErrorRemainsOutsideAssistantDisclosure() {listOf("DebatePanels.kt","CollaboratePresentation.kt").forEach {val s=ui(it);assertTrue(s.indexOf("s.error?")>s.indexOf("disclosureKey="))}}
}
