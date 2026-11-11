package com.wynh.appapp.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Register during activity initialization; only the chooser result releases an outstanding launch. */
class ShareLauncher(activity: ComponentActivity) {
    private val state = ShareLaunchState()
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        state.chooserOpen = false
    }
    init {
        activity.savedStateRegistry.registerSavedStateProvider("app2.share") {
            Bundle().apply { putBoolean("chooserOpen", state.chooserOpen) }
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                state.chooserOpen = activity.savedStateRegistry.consumeRestoredStateForKey("app2.share")?.getBoolean("chooserOpen") ?: false
            }
        })
    }
    /** Read by device tests to observe the activity-owned chooser state. */
    internal val chooserOpen get() = state.chooserOpen
    fun acquire(owner: Any) = state.acquire(owner)
    fun release(owner: Any) = state.release(owner)
    fun launch(owner: Any, intent: Intent) {
        state.launched(owner)
        try { launcher.launch(intent) }
        catch (error: Exception) { state.chooserOpen = false; throw error }
    }
}
