package com.wynh.appapp.ai

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.wynh.appapp.BuildConfig
import com.wynh.appapp.R
import com.wynh.appapp.SubappRuntimeActivity
import androidx.appcompat.app.AppCompatActivity
import com.wynh.appapp.host.RuntimeHost
import com.wynh.appapp.updates.UpdateController
import com.wynh.appapp.workspace.SubappRecord

class AppAiApplication : Application(), RuntimeHost {
    override lateinit var ai: AppAiManager
        private set

    override val appLinkScheme: String get() = BuildConfig.APP_LINK_SCHEME
    override val appIconResource: Int get() = R.mipmap.ic_launcher_round

    override fun subappIntent(context: Context, subappId: String): Intent =
        Intent(context, SubappRuntimeActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("$appLinkScheme://subapp/$subappId"))

    override val supportsSubappUpdates: Boolean get() = true

    override fun openUpdateReview(activity: AppCompatActivity, record: SubappRecord) = UpdateController(activity, record).open()

    override fun onCreate() {
        super.onCreate()
        ai = AppAiManager(this)
        Thread({ runCatching { com.wynh.appapp.share.ShareCache.sweep(cacheDir) } }, "share-cache-sweep").start()
        // At process start no save write or staging job of this process exists: interrupted ones are unknown and discarded.
        Thread({ runCatching { com.wynh.appapp.saveas.SaveAsStagingRoot.of(this).sweep(startup = true) } }, "save-staging-sweep").start()
        // No review or update of this new process owns a staged download yet, so any left are orphans.
        Thread({ runCatching { com.wynh.appapp.updates.GitHubUpdateDownloader.sweepStaged(cacheDir) } }, "update-cache-sweep").start()
    }
}
