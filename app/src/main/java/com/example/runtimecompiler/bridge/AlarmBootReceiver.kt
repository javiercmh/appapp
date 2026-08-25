package com.example.runtimecompiler.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Android drops all pending alarms on reboot and on app replacement, so pending
 * scheduled notifications are re-armed from the persisted list.
 */
class AlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> NativeStorageBridge.reschedulePersistedAlarms(context)
        }
    }
}
