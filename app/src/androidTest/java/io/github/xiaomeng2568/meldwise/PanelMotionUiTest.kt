// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xiaomeng2568.meldwise.ui.PanelTransition
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local route fixtures only; never launches the product Activity or a provider request. */
@RunWith(AndroidJUnit4::class)
class PanelMotionUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private var nav by mutableStateOf(ChatNavigation().open(ChatPanel.Settings))
    private fun fixture() {
        compose.mainClock.autoAdvance=false
        compose.setContent {MeldwiseTheme {Box(Modifier.width(320.dp).testTag("panelHost")) {
            PanelTransition(nav) {frame ->Text(frame.panel.name,
                Modifier.fillMaxWidth().height(if(frame.panel==ChatPanel.Settings) 80.dp else 200.dp).testTag("route-${frame.panel.name}"))}
        }}}
        compose.mainClock.advanceTimeBy(400)
    }
    @Test fun forwardPageSlidesAndSettles() {
        fixture();val base=compose.onNodeWithTag("route-Settings").getUnclippedBoundsInRoot().left
        compose.runOnIdle {nav=nav.open(ChatPanel.History)};compose.mainClock.advanceTimeBy(64)
        assertTrue(compose.onNodeWithTag("route-History").getUnclippedBoundsInRoot().left>base)
        compose.mainClock.advanceTimeBy(400)
        assertEquals(base,compose.onNodeWithTag("route-History").getUnclippedBoundsInRoot().left)
        compose.onNodeWithTag("route-Settings").assertDoesNotExist()
    }
    @Test fun returningToParentSlidesFromOtherSide() {
        fixture();compose.runOnIdle {nav=nav.open(ChatPanel.History)};compose.mainClock.advanceTimeBy(400)
        val base=compose.onNodeWithTag("route-History").getUnclippedBoundsInRoot().left
        compose.runOnIdle {nav=nav.back()};compose.mainClock.advanceTimeBy(64)
        assertTrue(compose.onNodeWithTag("route-Settings").getUnclippedBoundsInRoot().left<base)
        compose.mainClock.advanceTimeBy(400);compose.onNodeWithTag("route-History").assertDoesNotExist()
    }
    @Test fun pageHeightChangesGradually() {
        fixture();compose.runOnIdle {nav=nav.open(ChatPanel.History)};compose.mainClock.advanceTimeBy(96)
        val bounds=compose.onNodeWithTag("panelHost").getUnclippedBoundsInRoot()
        val height=bounds.bottom-bounds.top
        assertTrue(height>80.dp && height<200.dp)
        compose.mainClock.advanceTimeBy(400);compose.onNodeWithTag("panelHost").assertHeightIsEqualTo(200.dp)
    }
    @Test fun outgoingPageIsHiddenFromActionsAndAccessibility() {
        fixture();compose.runOnIdle {nav=nav.open(ChatPanel.History)};compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("route-Settings").assertDoesNotExist()
        compose.onNodeWithTag("route-History").assertExists()
    }
    @Test fun fastForwardAndBackSettlesOnLatestRoute() {
        fixture();compose.runOnIdle {nav=nav.open(ChatPanel.History)};compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle {nav=nav.back()};compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("route-Settings").assertIsDisplayed();compose.onNodeWithTag("route-History").assertDoesNotExist()
    }
}
