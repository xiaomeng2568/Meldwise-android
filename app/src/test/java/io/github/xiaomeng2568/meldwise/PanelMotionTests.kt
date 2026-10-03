// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.ui.PanelDestination
import io.github.xiaomeng2568.meldwise.ui.autoDismissCatalogNotice
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PanelMotionTests {
    @Test fun childPageSlidesForward() {assertEquals(1,PanelDestination(ChatPanel.History,2).directionFrom(PanelDestination(ChatPanel.Settings,1)))}
    @Test fun returningToParentReversesDirection() {assertEquals(-1,PanelDestination(ChatPanel.History,2).directionFrom(PanelDestination(ChatPanel.HistoryChat,3)))}
    @Test fun siblingPageUsesSameSmallForwardMotion() {assertEquals(1,PanelDestination(ChatPanel.CollaborateSetup,1).directionFrom(PanelDestination(ChatPanel.Modes,1)))}
    @Test fun outgoingChatCategoryRetainsItsOwnRoute() {
        val old=PanelDestination(ChatPanel.HistoryChat,3);val new=PanelDestination(ChatPanel.History,2)
        assertEquals(HistoryCategory.Chat,old.historyCategory);assertNull(new.historyCategory)
    }
    @Test fun compareRouteRestoresCompareCategory() {assertEquals(HistoryCategory.Compare,PanelDestination(ChatPanel.HistoryCompare,3).historyCategory)}
    @Test fun collaborateRouteRestoresCollaborateCategory() {assertEquals(HistoryCategory.Collaborate,PanelDestination(ChatPanel.HistoryCollaborate,3).historyCategory)}
    @Test fun unrelatedRoutesHaveNoHistoryCategory() {listOf(ChatPanel.Settings,ChatPanel.Models,ChatPanel.Modes,ChatPanel.Appearance).forEach {assertNull(PanelDestination(it,1).historyCategory)}}
    @Test fun laneSelectionIsFrozenInOutgoingFrame() {val frame=PanelDestination(ChatPanel.Models,2,"A");assertEquals("A",frame.modelLane);assertNotEquals(frame,frame.copy(modelLane="B"))}
    @Test fun sharedMotionIsShortAndNoticeLifetimeIs1500ms() {
        assertEquals(1500L,Motion.noticeLifetimeMs);assertTrue(Motion.switchMs in 180..250)
        assertTrue(Motion.fadeInMs<=Motion.switchMs);assertTrue(Motion.fadeOutMs<=Motion.switchMs)
    }
    @Test fun noticeExpiresOnceAt1500ms()=runTest {
        val dismissed=mutableListOf<Long>();launch {autoDismissCatalogNotice(7,dismissed::add)}
        runCurrent();advanceTimeBy(1499);runCurrent();assertTrue(dismissed.isEmpty())
        advanceTimeBy(1);runCurrent();assertEquals(listOf(7L),dismissed)
        advanceTimeBy(5000);runCurrent();assertEquals(1,dismissed.size)
    }
    @Test fun dismissedOrReplacedNoticeCancelsItsOldLifetime()=runTest {
        val dismissed=mutableListOf<Long>();val old=launch {autoDismissCatalogNotice(7,dismissed::add)}
        runCurrent();advanceTimeBy(600);old.cancel();launch {autoDismissCatalogNotice(8,dismissed::add)}
        runCurrent();advanceTimeBy(900);runCurrent();assertTrue(dismissed.isEmpty())
        advanceTimeBy(600);runCurrent();assertEquals(listOf(8L),dismissed)
    }
    @Test fun sheetNavigationDoesNotResetNoticeLifetime()=runTest {
        var nav=ChatNavigation().open(ChatPanel.Settings);var dismissed:Long?=null
        launch {autoDismissCatalogNotice(9) {dismissed=it}};runCurrent();advanceTimeBy(700)
        nav=nav.open(ChatPanel.History).openHistory(HistoryCategory.Compare)
        assertEquals(ChatPanel.HistoryCompare,nav.panel);advanceTimeBy(500);nav=nav.back()
        assertEquals(ChatPanel.History,nav.panel);advanceTimeBy(300);runCurrent();assertEquals(9L,dismissed)
    }
}
