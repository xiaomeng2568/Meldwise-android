package io.github.xiaomeng2568.meldwise

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewTreeObserver
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xiaomeng2568.meldwise.auth.*
import io.github.xiaomeng2568.meldwise.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual cold Activity startup and protected local reads; never taps a provider action. */
@RunWith(AndroidJUnit4::class)
class ColdLaunchRestorationTest {
    @Test(timeout=60000) fun coldActivityRestoresLocalStateWithoutProviderRequest() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as MeldwiseApplication
        val network=app.container.network
        assertEquals(0,network.startedCallCount)
        val main=Handler.createAsync(Looper.getMainLooper())
        fun <T> onMain(action:()->T):T {
            val result=CompletableDeferred<Result<T>>()
            assertTrue(main.post {result.complete(runCatching(action))})
            return runBlocking {withTimeout(10000) {result.await().getOrThrow()}}
        }
        val resumed=CompletableDeferred<MainActivity>()
        val drawn=CompletableDeferred<Unit>()
        var target:MainActivity?=null
        var firstDraw:ViewTreeObserver.OnDrawListener?=null
        val lifecycle=object:Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity:Activity,state:Bundle?) {
                if(activity is MainActivity) {
                    target=activity
                    firstDraw=ViewTreeObserver.OnDrawListener {drawn.complete(Unit)}
                    activity.window.decorView.viewTreeObserver.addOnDrawListener(firstDraw)
                }
            }
            override fun onActivityResumed(activity:Activity) {if(activity is MainActivity) resumed.complete(activity)}
            override fun onActivityStarted(activity:Activity)=Unit
            override fun onActivityPaused(activity:Activity)=Unit
            override fun onActivityStopped(activity:Activity)=Unit
            override fun onActivitySaveInstanceState(activity:Activity,state:Bundle)=Unit
            override fun onActivityDestroyed(activity:Activity)=Unit
        }
        app.registerActivityLifecycleCallbacks(lifecycle)
        try {
            // Observe the real Activity and first draw, not global MessageQueue idleness.
            // ActivityScenario.launch did not return on this device; never turn that into PASS.
            app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val activity=runBlocking {withTimeout(30000) {resumed.await().also {drawn.await()}}}
            instrumentation.sendStatus(0,Bundle().apply {putBoolean("real_activity_resumed_and_drawn",true)})
            val model=onMain {ViewModelProvider(activity)[MainViewModel::class.java]}
            runBlocking {withTimeout(10000) {model.localRestorationFinished.first {it}}}
            onMain {assertFalse(activity.isFinishing || activity.isDestroyed)}
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
        } finally {
            app.unregisterActivityLifecycleCallbacks(lifecycle)
            main.post {
                target?.let {activity ->
                    val tree=activity.window.decorView.viewTreeObserver
                    firstDraw?.let {if(tree.isAlive) tree.removeOnDrawListener(it)}
                    if(!activity.isFinishing) activity.finish()
                }
            }
        }
    }
}
