package io.github.xiaomeng2568.meldwise.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Appearance { System, Light, Dark }
object Space { val micro=4.dp; val small=8.dp; val medium=12.dp; val section=20.dp; val content=16.dp; val wide=24.dp; val large=32.dp; val hero=48.dp }
object Radius { val small=RoundedCornerShape(12.dp); val medium=RoundedCornerShape(16.dp); val surface=RoundedCornerShape(20.dp); val bubble=RoundedCornerShape(24.dp); val composer=RoundedCornerShape(28.dp) }
object Elevation { val flat=0.dp; val raised=2.dp; val floating=6.dp }
/** Small shared transitions; Compose honors the device's animator duration scale. */
object Motion {
    const val switchMs=220
    const val fadeInMs=180
    const val fadeOutMs=150
    const val noticeLifetimeMs=1500L
    val panelShift=16.dp
    val easing=FastOutSlowInEasing
}
object Sizes { val touch=48.dp; val actionVisual=40.dp; val icon=24.dp; val providerMark=28.dp
    val composerMax=120.dp; val composerMin=44.dp; val composerInputMin=40.dp; val composerInset=2.dp
    val contentMax=760.dp;val noticeMax=360.dp;val noticeBorder=1.dp;val modeRowMin=84.dp; const val userFraction=.86f; const val sheetFraction=.85f }
private val light = lightColorScheme(
    primary=Color(0xFF6550A4), onPrimary=Color.White, primaryContainer=Color(0xFFEEE8FA), onPrimaryContainer=Color(0xFF322152),
    background=Color(0xFFFAFAFC), onBackground=Color(0xFF24232A), surface=Color(0xFFFFFFFF), onSurface=Color(0xFF24232A),
    surfaceContainer=Color(0xFFF0EFF4),surfaceContainerLow=Color(0xFFF6F5F8),surfaceContainerHigh=Color(0xFFECEBF0),
    surfaceVariant=Color(0xFFF0EFF4), onSurfaceVariant=Color(0xFF64616D), outline=Color(0xFF7C7785),
    outlineVariant=Color(0xFFE6E3EC), error=Color(0xFFAC3036), errorContainer=Color(0xFFFFEBEB), onErrorContainer=Color(0xFF721E24))
private val dark = darkColorScheme(
    primary=Color(0xFFD1BCFF), onPrimary=Color(0xFF35225B), primaryContainer=Color(0xFF342A48), onPrimaryContainer=Color(0xFFECE1FF),
    background=Color(0xFF16161B), onBackground=Color(0xFFE9E7EE), surface=Color(0xFF202027), onSurface=Color(0xFFE9E7EE),
    surfaceContainer=Color(0xFF25252D),surfaceContainerLow=Color(0xFF202027),surfaceContainerHigh=Color(0xFF2B2A33),
    surfaceVariant=Color(0xFF2B2A33), onSurfaceVariant=Color(0xFFBBB6C5), outline=Color(0xFF928D9C),
    outlineVariant=Color(0xFF3B3845), error=Color(0xFFFFB3B5), errorContainer=Color(0xFF4D292E), onErrorContainer=Color(0xFFFFDADC))
private val type = Typography(
    headlineMedium=TextStyle(fontSize=28.sp,lineHeight=38.sp,fontWeight=FontWeight.SemiBold),
    titleLarge=TextStyle(fontSize=22.sp,lineHeight=30.sp,fontWeight=FontWeight.SemiBold),
    titleMedium=TextStyle(fontSize=17.sp,lineHeight=25.sp,fontWeight=FontWeight.Medium),
    bodyLarge=TextStyle(fontSize=17.sp,lineHeight=28.sp), bodyMedium=TextStyle(fontSize=15.sp,lineHeight=24.sp),
    bodySmall=TextStyle(fontSize=13.sp,lineHeight=20.sp), labelLarge=TextStyle(fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium),
    labelMedium=TextStyle(fontSize=12.sp,lineHeight=18.sp,fontWeight=FontWeight.Medium), labelSmall=TextStyle(fontSize=11.sp,lineHeight=16.sp))
internal fun themeColors(darkMode: Boolean) = if(darkMode) dark else light
fun parseAccent(value:String):Long?=value.removePrefix("#").takeIf {it.matches(Regex("[0-9a-fA-F]{6}"))}?.toLong(16)
internal fun accentColors(darkMode:Boolean,rgb:Long?):ColorScheme {
    val base=themeColors(darkMode)
    if(rgb==null || rgb !in 0..0xFFFFFF) return base
    var primary=Color(0xFF000000L or rgb)
    val target=if(darkMode) Color.White else Color.Black
    fun luminance(c:Color)=c.luminance().toDouble()
    fun contrast(a:Color,b:Color):Double {val x=luminance(a);val y=luminance(b);return (maxOf(x,y)+.05)/(minOf(x,y)+.05)}
    var step=0
    while(contrast(primary,base.background)<4.5 || contrast(primary,base.surface)<4.5) {
        primary=androidx.compose.ui.graphics.lerp(Color(0xFF000000L or rgb),target,(++step/100f).coerceAtMost(1f))
        if(step>=100) break
    }
    val onPrimary=if(contrast(primary,Color.White)>=4.5) Color.White else Color.Black
    val container=androidx.compose.ui.graphics.lerp(base.background,primary,if(darkMode) .18f else .10f)
    return base.copy(primary=primary,onPrimary=onPrimary,primaryContainer=container,onPrimaryContainer=base.onBackground)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MeldwiseTheme(appearance: Appearance = Appearance.System, accent:Long?=null, content: @Composable () -> Unit) {
    val isDark = when(appearance) { Appearance.System->isSystemInDarkTheme(); Appearance.Light->false; Appearance.Dark->true }
    MaterialTheme(colorScheme=remember(isDark,accent) {accentColors(isDark,accent)}, typography=type,
        shapes=Shapes(small=Radius.small,medium=Radius.medium,large=Radius.bubble,extraLarge=Radius.composer)) {
        // Project controls supply a shared, non-radial state layer using the same interaction source.
        CompositionLocalProvider(LocalRippleConfiguration provides null,content=content)
    }
}
@Composable fun codeStyle() = MaterialTheme.typography.bodyMedium.copy(fontFamily=FontFamily.Monospace)
@Composable internal fun noticeColors(kind:io.github.xiaomeng2568.meldwise.data.CatalogNoticeKind):Pair<Color,Color> {
    val darkMode=MaterialTheme.colorScheme.background.luminance()<.5f
    return when(kind) {
        io.github.xiaomeng2568.meldwise.data.CatalogNoticeKind.Success->if(darkMode) Color(0xFF1A3323) to Color(0xFF76C58D) else Color(0xFFEAF7EE) to Color(0xFF37824B)
        io.github.xiaomeng2568.meldwise.data.CatalogNoticeKind.Warning->if(darkMode) Color(0xFF3A301B) to Color(0xFFD2B66C) else Color(0xFFFFF5DC) to Color(0xFFAD8430)
        io.github.xiaomeng2568.meldwise.data.CatalogNoticeKind.Failure->MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
    }
}
