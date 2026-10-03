// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.ui.components.ModeIcons
import io.github.xiaomeng2568.meldwise.ui.presentation.HistoryCategory
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.PathNode
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.hypot

class VisualIdentityTests {
    private val root=File(requireNotNull(System.getProperty("projectRoot")))
    private val namespace="http://schemas.android.com/apk/res/android"
    private fun xml(relative:String)=DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware=true;setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
    }.newDocumentBuilder().parse(File(root,relative)).documentElement
    private fun resource(relative:String)=xml("app/src/main/res/$relative")
    private fun Element.android(name:String)=getAttributeNS(namespace,name)
    private fun paths(relative:String)=resource(relative).getElementsByTagName("path").let {nodes->(0 until nodes.length).map {nodes.item(it) as Element}}
    @Test fun fourModeIconsAreDistinctOriginalVectors() {
        val icons=HistoryCategory.entries.map(ModeIcons::forCategory)
        assertEquals(4,icons.map {it.name}.distinct().size);assertTrue(icons.all {it.name.startsWith("Meldwise.")})
        assertEquals(4,icons.map {(it.root[0] as VectorPath).pathData}.distinct().size)
    }
    @Test fun modeIconsHave24dpCanvas() {HistoryCategory.entries.map(ModeIcons::forCategory).forEach {
        assertEquals(24f,it.viewportWidth);assertEquals(24f,it.viewportHeight);assertEquals(24f,it.defaultWidth.value)}}
    @Test fun modeIconsShareRoundOpticalStroke() {HistoryCategory.entries.map(ModeIcons::forCategory).forEach {
        val p=it.root[0] as VectorPath;assertEquals(1.75f,p.strokeLineWidth);assertEquals(StrokeCap.Round,p.strokeLineCap);assertEquals(StrokeJoin.Round,p.strokeLineJoin);assertNull(p.fill)}}
    @Test fun collaboratorIsNotCompareOrChat() {assertNotSame(ModeIcons.compare,ModeIcons.collaborate);assertNotEquals((ModeIcons.chat.root[0] as VectorPath).pathData,(ModeIcons.collaborate.root[0] as VectorPath).pathData)}
    @Test fun compareHasOneClosedLensAndSeparateHandle() {
        val path=(ModeIcons.compare.root[0] as VectorPath).pathData
        assertEquals(1,path.count {it==PathNode.Close});assertEquals(2,path.count {it is PathNode.MoveTo})
        assertEquals(PathNode.MoveTo(15f,15f),path[path.lastIndex-1]);assertEquals(PathNode.LineTo(21f,21f),path.last())
    }
    @Test fun collaborateRearOutlineStopsBeforeForegroundBox() {
        val path=(ModeIcons.collaborate.root[0] as VectorPath).pathData
        val front=path.indexOf(PathNode.MoveTo(12f,9f));assertTrue(front>0)
        assertEquals(PathNode.VerticalTo(7f),path[front-1]);assertFalse(path.take(front).contains(PathNode.Close))
        assertEquals(1,path.count {it==PathNode.Close});assertEquals(2,path.count {it is PathNode.MoveTo})
    }
    @Test fun launcherManifestUsesNormalAndRoundNativeResources() {val app=xml("app/src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("@mipmap/ic_launcher",app.android("icon"));assertEquals("@mipmap/ic_launcher_round",app.android("roundIcon"))}
    @Test fun adaptiveNormalAndRoundResolveOn26() {listOf("ic_launcher","ic_launcher_round").forEach {name->
        val icon=resource("mipmap-anydpi/$name.xml");assertEquals("adaptive-icon",icon.tagName)
        assertEquals("@drawable/ic_meldwise_mark",(icon.getElementsByTagName("foreground").item(0) as Element).android("drawable"))
        assertEquals("@color/meldwise_launcher_background",(icon.getElementsByTagName("background").item(0) as Element).android("drawable"))}}
    @Test fun android13NormalAndRoundContainMonochrome() {listOf("ic_launcher","ic_launcher_round").forEach {name->
        val icon=resource("mipmap-anydpi/$name.xml");assertEquals("adaptive-icon",icon.tagName)
        assertEquals("@drawable/ic_meldwise_monochrome",(icon.getElementsByTagName("monochrome").item(0) as Element).android("drawable"))}}
    @Test fun oldTemporaryRectangleIconIsNotShipped() {assertFalse(File(root,"app/src/main/res/drawable/ic_launcher.xml").exists())}
    @Test fun allBrandVariantsHaveSameGeometry() {val colored=paths("drawable/ic_meldwise_mark.xml").single();val mono=paths("drawable/ic_meldwise_monochrome.xml").single()
        assertEquals(colored.android("pathData"),mono.android("pathData"))
        listOf(colored,mono).forEach {assertEquals("13",it.android("strokeWidth"));assertEquals("round",it.android("strokeLineCap"));assertEquals("round",it.android("strokeLineJoin"))}}
    @Test fun foregroundAndMonochromeUseAdaptive108Canvas() {listOf("ic_meldwise_mark","ic_meldwise_monochrome").forEach {name->val vector=resource("drawable/$name.xml")
        assertEquals("108",vector.android("viewportWidth"));assertEquals("108",vector.android("viewportHeight"))}}
    @Test fun geometricMarkAndRoundStrokeFit66dpSafeCircle() {val p=paths("drawable/ic_meldwise_mark.xml").single()
        val coordinates=Regex("[0-9.]+").findAll(p.android("pathData")).map {it.value.toDouble()}.toList().chunked(2)
        val radius=p.android("strokeWidth").toDouble()/2
        // The Bezier hull plus round stroke lies inside the common safe circle. Circle, square and squircle masks retain it.
        coordinates.forEach {point->assertTrue("outside adaptive safe zone",hypot(point[0]-54,point[1]-54)+radius<=33)}}
    @Test fun monochromeIsSingleOpaqueColorWithoutGradient() {val mono=paths("drawable/ic_meldwise_monochrome.xml").single()
        assertEquals("#FFFFFFFF",mono.android("strokeColor"));assertEquals(0,resource("drawable/ic_meldwise_monochrome.xml").getElementsByTagName("gradient").length)}
    @Test fun brandGradientIsNativeAndNoRasterAssetAdded() {assertEquals(1,resource("drawable/ic_meldwise_mark.xml").getElementsByTagName("gradient").length)
        assertTrue(File(root,"app/src/main/res").walkTopDown().filter {it.isFile && it.name.contains("meldwise",ignoreCase=true)}.all {it.extension=="xml"})}
    @Test fun providerOfficialLogoResourcesRemainAbsent() {val names=File(root,"app/src/main/res").walkTopDown().filter {it.isFile}.map {it.name.lowercase()}.toList()
        assertFalse(names.any {it.contains("openai") || it.contains("deepseek") || it.contains("chatgpt")})}
}
