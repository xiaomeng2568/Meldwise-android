package io.github.xiaomeng2568.meldwise

import org.junit.Test
import org.junit.Assert.*
import java.io.File

class SecurityBoundaryTests {
    private val root=File(requireNotNull(System.getProperty("projectRoot")))
    @Test fun backupsAndTransferExcluded() {
        val manifest=File(root,"app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""));assertTrue(manifest.contains("dataExtractionRules"))
        val rules=File(root,"app/src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue(rules.contains("cloud-backup"));assertTrue(rules.contains("device-transfer"))
        listOf("root","file","database","sharedpref","external").forEach {assertTrue(rules.contains("domain=\"$it\""))}
    }
    @Test fun noExtraProcessOrForegroundService() {
        val manifest=File(root,"app/src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("android:process="));assertFalse(manifest.contains("<service"))
    }
    @Test fun sourceDoesNotLogTokensOrPersistPendingOAuth() {
        val source=File(root,"app/src/main/java").walkTopDown().filter {it.extension=="kt"}.map {it.readText()}.joinToString("\n")
        listOf("android.util.Log","println(","printStackTrace(","HttpLoggingInterceptor","SavedStateHandle",
            "rememberSaveable","androidx.room","dagger.hilt",".spike.").forEach {assertFalse(source.contains(it))}
        assertTrue(source.contains("retryOnConnectionFailure(false)"));assertTrue(source.contains("followRedirects(false)"))
    }
    @Test fun cleartextExceptionIsLoopbackOnly() {
        val xml=File(root,"app/src/main/res/xml/network_security_config.xml").readText()
        assertTrue(xml.contains("127.0.0.1"));assertFalse(xml.contains("includeSubdomains=\"true\""))
        assertTrue(xml.contains("<base-config cleartextTrafficPermitted=\"false\""))
    }
}
