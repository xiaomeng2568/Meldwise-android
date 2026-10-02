package io.github.xiaomeng2568.meldwise

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xiaomeng2568.meldwise.provider.ProviderIds
import io.github.xiaomeng2568.meldwise.security.ApiKeyState
import io.github.xiaomeng2568.meldwise.ui.MainViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit device acceptance against existing encrypted records. Never creates credentials/messages. */
@RunWith(AndroidJUnit4::class)
class DeepSeekLocalAcceptanceTest {
    @Test(timeout=60000) fun existingEncryptedSessionRestoresWithoutProviderRequest() {
        assumeTrue("EXISTING_DEEPSEEK_STATE_ACCEPTANCE_OPT_IN",
            InstrumentationRegistry.getArguments().getString("existingDeepSeekAcceptance")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val baseline=AppContainer(context)
        val before=baseline.chat.load()
        val ref=baseline.chat.activeRef()
        assertTrue("DEEPSEEK_CREDENTIAL_NOT_CONFIGURED",baseline.deepSeekCredentials.state()==ApiKeyState.CONFIGURED)
        assertTrue("DEEPSEEK_SESSION_NOT_PRESENT",ref.providerId==ProviderIds.DEEPSEEK && ref.modelId!="UNKNOWN" && before.isNotEmpty())
        // Fresh container forces independent disk/Keystore reads, rather than reuse of memory state.
        val reopened=AppContainer(context)
        val main=Handler.createAsync(Looper.getMainLooper())
        fun <T> onMain(action:()->T):T {
            val result=CompletableDeferred<Result<T>>()
            assertTrue(main.post {result.complete(runCatching(action))})
            return runBlocking {withTimeout(10000) {result.await().getOrThrow()}}
        }
        val owner=ViewModelStore()
        val model=onMain {MainViewModel(reopened).also {owner.put("acceptance-only",it)}}
        try {
            runBlocking {withTimeout(10000) {model.localRestorationFinished.first {it}}}
            onMain {
                val restored=model.screen.value
                assertTrue("DEEPSEEK_CREDENTIAL_NOT_RESTORED",model.deepSeekState.value==ApiKeyState.CONFIGURED)
                assertTrue("DEEPSEEK_BINDING_NOT_RESTORED",restored.providerId==ref.providerId && restored.historyRef==ref)
                assertTrue("DEEPSEEK_SESSION_NOT_RESTORED",restored.messages.size==before.size && restored.messages.zip(before).all { (after,prior) ->
                    after.id==prior.id && after.parentMessageId==prior.parentMessageId && after.role==prior.role && after.text==prior.text && after.state==prior.state
                })
                assertTrue("UNEXPECTED_AUTOMATIC_OPERATION",restored.ready && !restored.busy && restored.error==null && restored.models.isEmpty() && restored.selected==null && restored.catalogDiagnostic==null)
                assertTrue("UNEXPECTED_PROVIDER_REQUEST",baseline.network.startedCallCount==0 && reopened.network.startedCallCount==0 && reopened.network.diagnostics.snapshot().isEmpty())
            }
            // Fixed booleans only. Assertion messages never contain actual IDs, keys or chat text.
            instrumentation.sendStatus(0,Bundle().apply {
                putBoolean("deepseek_credential_configured_restored",true)
                putBoolean("deepseek_existing_session_restored",true)
                putBoolean("provider_model_binding_preserved",true)
                putBoolean("startup_no_provider_requests",true)
                putBoolean("automatic_model_load_inference_continuation_retry_fallback",false)
            })
        } finally { onMain {owner.clear()} }
    }
}
