package com.wynh.appapp.ai

import android.app.Application

class AppAiApplication : Application() {
    lateinit var ai: AppAiManager
        private set

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
