// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xiaomeng2568.meldwise.ui.components.ContentRenderer
import io.github.xiaomeng2568.meldwise.ui.content.ContentParser
import io.github.xiaomeng2568.meldwise.ui.theme.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic content only. Gate compiles/packages these tests; it does not execute them on a device. */
@RunWith(AndroidJUnit4::class)
class BetaMarkdownUiTest {
    @get:Rule val compose=createComposeRule()
    private val sample="- [ ] pending task\n- [x] completed task\n  - nested bullet\n    1. nested number\n\n---\n~~deleted~~\n\nA|B\n---|---:\nvalue|123\n\n```kotlin\nval x = 1\n```"
    @Test fun readOnlyTasksAndDividerHaveAccessibleDescriptions() {
        compose.setContent {MeldwiseTheme {ContentRenderer(ContentParser.parse(sample))}}
        compose.onNodeWithContentDescription("未完成任务",useUnmergedTree=true).assertExists()
        compose.onNodeWithContentDescription("已完成任务",useUnmergedTree=true).assertExists()
        compose.onNodeWithContentDescription("分隔线",useUnmergedTree=true).assertExists()
        compose.onNodeWithText("deleted").assertExists()
    }
    @Test fun supportedWidthsThemesAndLargeFontsRetainNativeContentNodes() {
        var width by mutableStateOf(320.dp);var appearance by mutableStateOf(Appearance.Light)
        var scale by mutableStateOf(1f)
        compose.setContent {MeldwiseTheme(appearance,0x336699L) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,scale)) {
                Box(Modifier.requiredWidth(width).height(640.dp).verticalScroll(rememberScrollState())) {
                    ContentRenderer(ContentParser.parse(sample))
                }
            }
        }}
        listOf(Appearance.Light,Appearance.Dark).forEach {theme ->
            listOf(320,360,412,600,840).forEach {dp ->
                compose.runOnIdle {appearance=theme;width=dp.dp;scale=2f};compose.waitForIdle()
                compose.onNodeWithTag("markdownTable").assertExists()
                compose.onNodeWithTag("codeBlock").assertExists()
                compose.onNodeWithText("nested bullet").assertExists()
                compose.onNodeWithText("nested number").assertExists()
            }
        }
    }
}
