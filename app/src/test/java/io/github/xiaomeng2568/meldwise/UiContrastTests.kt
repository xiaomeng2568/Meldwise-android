package io.github.xiaomeng2568.meldwise

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.xiaomeng2568.meldwise.ui.theme.themeColors
import org.junit.Test
import org.junit.Assert.assertTrue

class UiContrastTests {
    private fun contrast(a:Color,b:Color):Float {
        val x=a.luminance();val y=b.luminance()
        return (maxOf(x,y)+.05f)/(minOf(x,y)+.05f)
    }
    private fun check(dark:Boolean) {
        val c=themeColors(dark)
        listOf(c.onBackground to c.background,c.onSurface to c.surface,c.onSurfaceVariant to c.surfaceVariant,
            c.onPrimary to c.primary,c.onPrimaryContainer to c.primaryContainer,c.onErrorContainer to c.errorContainer,
            c.error to c.background,c.primary to c.background).forEach {(text,surface)->assertTrue("TEXT_CONTRAST_BELOW_4_5",contrast(text,surface)>=4.5f)}
    }
    @Test fun lightTextPaletteMeetsContrastBound() {check(false)}
    @Test fun darkTextPaletteMeetsContrastBound() {check(true)}
}
