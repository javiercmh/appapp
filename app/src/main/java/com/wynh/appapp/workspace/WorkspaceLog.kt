package com.wynh.appapp.workspace

import android.content.Context
import java.io.File

object WorkspaceLog {
    private val executor = java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(256),
        java.util.concurrent.ThreadFactory { task -> Thread(task, "workspace-log").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy())
    fun append(context: Context, root: File, message: String) {
        val app = context.applicationContext
        executor.execute { if (root.isDirectory) WorkspaceManager(app, workspaceDir = root).appendToFile("app.log", "$message\n") }
    }
}
