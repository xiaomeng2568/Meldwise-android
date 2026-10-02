package io.github.xiaomeng2568.meldwise

import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xiaomeng2568.meldwise.auth.*
import io.github.xiaomeng2568.meldwise.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual cold Activity startup and protected local reads; never taps a provider action. */
@RunWith(AndroidJUnit4::class)
class ColdLaunchRestorationTest {
    @Test fun coldActivityRestoresLocalStateWithoutProviderRequest() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as MeldwiseApplication
        val network=app.container.network
        assertEquals(0,network.startedCallCount)
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            lateinit var model:MainViewModel
            scenario.onActivity {model=ViewModelProvider(it)[MainViewModel::class.java]}
            runBlocking {withTimeout(10000) {model.localRestorationFinished.first {it}}}
            instrumentation.waitForIdleSync()
            assertEquals(0,network.startedCallCount)
            assertTrue(network.diagnostics.snapshot().isEmpty())
            assertFalse(model.auth.value==AuthState.Restoring || model.auth.value==AuthState.StorageUnavailable)
            assertFalse(model.screen.value.error=="LOCAL_STORAGE_UNAVAILABLE")
            // Only fixed categories/booleans/counts leave the process. Never export real records.
            instrumentation.sendStatus(0,Bundle().apply {
                putBoolean("startup_no_provider_requests",network.startedCallCount==0)
                putBoolean("local_credential_connected",model.auth.value is AuthState.Connected)
                putBoolean("local_journal_read_success",model.screen.value.error==null)
                putInt("restored_completed_assistant_count",model.screen.value.messages.count {it.role==io.github.xiaomeng2568.meldwise.provider.MessageRole.ASSISTANT && it.state==io.github.xiaomeng2568.meldwise.data.MessageState.COMPLETED})
                putInt("restored_partial_assistant_count",model.screen.value.messages.count {it.role==io.github.xiaomeng2568.meldwise.provider.MessageRole.ASSISTANT && it.state in setOf(io.github.xiaomeng2568.meldwise.data.MessageState.INCOMPLETE,io.github.xiaomeng2568.meldwise.data.MessageState.CANCELLED)})
            })
        }
    }
}
