package com.wynh.appapp.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.wynh.appapp.ai.AppAiManager
import com.wynh.appapp.workspace.SubappRecord

/**
 * What the shared runtime needs from the app that embeds it (App², or a standalone app built
 * from one subapp). The embedding app's `Application` implements it; runtime code reaches it
 * through [Context.runtimeHost] instead of naming the app's own activities or BuildConfig.
 */
interface RuntimeHost {
    /** The process-wide AI request manager. */
    val ai: AppAiManager

    /** Scheme of `<scheme>://subapp/<id>` links (`appapp`, `appapp-dev`, ...). */
    val appLinkScheme: String

    /** Launcher icon used where a subapp has no icon of its own. */
    val appIconResource: Int

    /** Opens [subappId] in its standalone screen. Callers add their own flags. */
    fun subappIntent(context: Context, subappId: String): Intent

    /** Where a running request's notification returns to from [activity]; defaults to the standalone screen. */
    fun returnIntent(activity: Activity, subappId: String): Intent =
        subappIntent(activity, subappId).addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS)

    /** Whether pages get `window.AppUpdates` (native GitHub update review). */
    val supportsSubappUpdates: Boolean get() = false

    /** Opens the native update review for [record]; only called when [supportsSubappUpdates]. */
    fun openUpdateReview(activity: AppCompatActivity, record: SubappRecord) {}
}

/** The embedding app's host, or null in a process without one (for example plain unit tests). */
val Context.runtimeHost: RuntimeHost? get() = applicationContext as? RuntimeHost
