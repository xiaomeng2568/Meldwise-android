package io.github.xiaomeng2568.meldwise

import java.io.File
import org.junit.Test
import org.junit.Assert.*

/** Static source/config guards, not device or packet-capture evidence. */
class SecondProviderSecurityTests {
    private val root=File(requireNotNull(System.getProperty("projectRoot")))
    private fun source(name:String)=File(root,"app/src/main/java/io/github/xiaomeng2568/meldwise/$name").readText()
    @Test fun separateAliasPurposeAndFile() {
        val s=source("MeldwiseApplication.kt")
        assertTrue(s.contains("AndroidKey(\"meldwise.deepseek.apikey.v1\")"))
        assertTrue(s.contains("File(directory,\"deepseek-apikey.v1\")"));assertTrue(s.contains("deepSeekKey::get,\"meldwise.deepseek.apikey.v1\""))
        assertTrue(s.contains("AndroidKey(\"meldwise.credentials.v1\")"));assertTrue(s.contains("File(directory,\"credentials.v1\")"))
    }
    @Test fun apiKeyInputProtectedAndNotRestoredFromInstanceState() {
        val s=source("MainActivity.kt")
        assertTrue(s.contains("PasswordVisualTransformation()"));assertTrue(s.contains("SecureFlagPolicy.SecureOn"))
        assertFalse(s.contains("rememberSaveable"));assertFalse(s.contains("deepSeekCredentials.read()"))
    }
    @Test fun deepseekHasNoOAuthOrFallbackPath() {
        val s=source("provider/DeepSeekProvider.kt")
        assertFalse(s.contains("TokenManager("));assertFalse(s.contains("ChatGptProvider("))
        assertFalse(s.contains("/chat/completions"));assertFalse(s.contains("/balance"))
        assertFalse(s.contains("delay("));assertFalse(s.contains("retry("))
    }
    @Test fun apiKeyStoreCannotCreateNetworkRequests() {
        val s=source("security/DeepSeekCredentials.kt")
        assertFalse(s.contains("NetworkClient"));assertFalse(s.contains("okhttp"));assertFalse(s.contains("Request.Builder"))
    }
    @Test fun providerSwitchIsLocalOnly() {
        val vm=source("ui/MainViewModel.kt")
        val switch=vm.substringAfter("fun chooseProvider").substringBefore("fun saveApiKey")
        assertFalse(switch.contains("listModels("));assertFalse(switch.contains("streamResponse("));assertFalse(switch.contains("connect("))
        assertTrue(vm.contains("container.providers.ready(model)"));assertTrue(vm.contains("model==container.chat.activeRef()"))
    }
    @Test fun noSecretConfigurationInjection() {
        val build=File(root,"app/build.gradle.kts").readText()+File(root,"gradle/libs.versions.toml").readText()
        assertFalse(build.contains("buildConfigField"));assertFalse(build.contains("DEEPSEEK_API_KEY"));assertFalse(build.contains("System.getenv"))
    }
}
