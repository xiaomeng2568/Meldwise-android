// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.ModelRef
import io.github.xiaomeng2568.meldwise.ui.*
import io.github.xiaomeng2568.meldwise.ui.presentation.*
import io.github.xiaomeng2568.meldwise.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic component host only, never MainActivity, provider execution, or OEM cold-start automation. */
@RunWith(AndroidJUnit4::class)
class HistoryVisualUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val a=ModelRef("chatgpt","a");private val b=ModelRef("deepseek","b")
    private val sessions=listOf(SingleSessionInfo("s",a,"Synthetic chat"),SingleSessionInfo("co",b,"Synthetic collaboration",ConversationMode.Collaborate))
    private val runs=listOf(CompareRun("cmp","Synthetic comparison",CompareLaneRecord("A",a),CompareLaneRecord("B",b)))
    @Test fun chooserHasFourDistinctEnabledIconRows() {
        compose.setContent {MeldwiseTheme {ModePicker(true,{},{},{})}}
        HistoryCategory.entries.forEach {compose.onNodeWithTag("modeRow-${it.name}").assertExists();compose.onNodeWithTag("modeIcon-${it.name}",useUnmergedTree=true).assertExists()}
        compose.onNodeWithTag("modeRow-Debate").assertIsEnabled();compose.onNodeWithText("暂未开放").assertDoesNotExist()
    }
    @Test fun activeModeHasSelectedSemantics() {compose.setContent {MeldwiseTheme {ModePicker(true,{},{},{},HistoryCategory.Collaborate)}}
        compose.onNodeWithTag("modeRow-Collaborate").assertIsSelected();compose.onNodeWithTag("modeRow-Compare").assertIsNotSelected()}
    @Test fun historyRootShowsCountsAndNoPrivateTitles() {
        compose.setContent {MeldwiseTheme {HistoryPanel(null,sessions,runs,false,{},{},{},{_,_->})}}
        compose.onAllNodesWithText("1 条记录").assertCountEquals(3);compose.onNodeWithText("0 条记录").assertExists()
        compose.onNodeWithText("Synthetic chat").assertDoesNotExist();compose.onNodeWithTag("modeRow-Debate").assertIsEnabled()
    }
    @Test fun emptyCategoriesShowFourRealZeroCounts() {compose.setContent {MeldwiseTheme {HistoryPanel(null,emptyList(),emptyList(),false,{},{},{},{_,_->})}}
        compose.onAllNodesWithText("0 条记录").assertCountEquals(4);compose.onNodeWithText("暂未开放").assertDoesNotExist()}
    @Test fun chatCategoryShowsOnlyChatAndOpensCorrectId() {var opened:HistoryEntry?=null
        compose.setContent {MeldwiseTheme {HistoryPanel(HistoryCategory.Chat,sessions,runs,false,{},{opened=it},{},{_,_->})}}
        compose.onNodeWithText("Synthetic chat").performClick();compose.onNodeWithText("Synthetic collaboration").assertDoesNotExist()
        compose.runOnIdle {assertEquals("s",opened!!.id);assertEquals(ConversationMode.Single,opened!!.category.conversationMode)}
    }
    @Test fun compareCategoryUsesCompareCallback() {var opened:HistoryEntry?=null
        compose.setContent {MeldwiseTheme {HistoryPanel(HistoryCategory.Compare,sessions,runs,false,{},{opened=it},{},{_,_->})}}
        compose.onNodeWithText("Synthetic comparison").performClick();compose.runOnIdle {assertTrue(opened!!.compare);assertEquals("cmp",opened!!.id)}
    }
    @Test fun collaborateCategoryDoesNotBecomeSingle() {var opened:HistoryEntry?=null
        compose.setContent {MeldwiseTheme {HistoryPanel(HistoryCategory.Collaborate,sessions,runs,false,{},{opened=it},{},{_,_->})}}
        compose.onNodeWithText("Synthetic collaboration").performClick();compose.onNodeWithText("Synthetic chat").assertDoesNotExist()
        compose.runOnIdle {assertEquals(ConversationMode.Collaborate,opened!!.category.conversationMode)}
    }
    @Test fun categorySelectionAndBackPreserveHistoryRoot() {var nav by mutableStateOf(ChatNavigation().open(ChatPanel.History))
        compose.setContent {MeldwiseTheme {HistoryPanel(nav.historyCategory,sessions,runs,false,{nav=nav.openHistory(it)},{},{},{_,_->})}}
        compose.onNodeWithTag("modeRow-Collaborate").performClick();compose.onNodeWithText("Synthetic collaboration").assertExists()
        compose.runOnIdle {nav=nav.back()};compose.onNodeWithTag("modeRow-Chat").assertExists()
    }
    @Test fun narrowWidthAndLargeFontKeepAllModesReachable() {
        compose.setContent {val density=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
            MeldwiseTheme(Appearance.Dark,0x366BD5L) {Box(Modifier.width(320.dp)) {ModePicker(true,{},{},{})}}
        }}
        compose.onNodeWithTag("modeRow-Debate").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("modeRow-Chat").performScrollTo().assertHeightIsAtLeast(48.dp)
    }
    @Test fun launcherResourcesResolveAsAdaptiveDrawables() {compose.runOnIdle {
        assertTrue(compose.activity.getDrawable(R.mipmap.ic_launcher) is AdaptiveIconDrawable)
        assertTrue(compose.activity.getDrawable(R.mipmap.ic_launcher_round) is AdaptiveIconDrawable)
    }}
    @SdkSuppress(minSdkVersion=33)
    @Test fun android13AdaptiveIconHasMonochrome() {compose.runOnIdle {
        if(Build.VERSION.SDK_INT>=33) assertNotNull((compose.activity.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable).monochrome)
    }}
    @Test fun vectorMarkRendersInsideSafeZoneWithoutRasterAssets() {compose.runOnIdle {
        val bitmap=Bitmap.createBitmap(108,108,Bitmap.Config.ARGB_8888)
        compose.activity.getDrawable(R.drawable.ic_meldwise_mark)!!.apply {setBounds(0,0,108,108);draw(Canvas(bitmap))}
        var painted=0
        for(y in 0 until 108) for(x in 0 until 108) if(android.graphics.Color.alpha(bitmap.getPixel(x,y))>0) {
            painted++;assertTrue((x-54)*(x-54)+(y-54)*(y-54)<=34*34)
        }
        assertTrue(painted>0);bitmap.recycle()
    }}
}
