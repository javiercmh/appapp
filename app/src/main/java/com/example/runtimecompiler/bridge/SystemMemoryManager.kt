package com.example.runtimecompiler.bridge

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import org.json.JSONObject

/**
 * Queries physical Android device RAM, low memory thresholds, JVM heap, and native heap allocations.
 */
class SystemMemoryManager(private val context: Context) {

    fun getMemoryStatsJson(): String {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val runtime = Runtime.getRuntime()
        val jvmMaxMemory = runtime.maxMemory()
        val jvmTotalMemory = runtime.totalMemory()
        val jvmFreeMemory = runtime.freeMemory()
        val jvmUsedMemory = jvmTotalMemory - jvmFreeMemory

        val nativeHeapAllocated = Debug.getNativeHeapAllocatedSize()
        val nativeHeapFree = Debug.getNativeHeapFreeSize()
        val nativeHeapTotal = Debug.getNativeHeapSize()

        val json = JSONObject().apply {
            // Physical Device RAM in bytes
            put("deviceTotalRam", memoryInfo.totalMem)
            put("deviceAvailRam", memoryInfo.availMem)
            put("deviceLowMemory", memoryInfo.lowMemory)
            put("deviceThreshold", memoryInfo.threshold)

            // JVM Heap in bytes
            put("jvmMax", jvmMaxMemory)
            put("jvmTotal", jvmTotalMemory)
            put("jvmUsed", jvmUsedMemory)
            put("jvmFree", jvmFreeMemory)

            // Native C++ / Direct Memory Heap in bytes
            put("nativeHeapAllocated", nativeHeapAllocated)
            put("nativeHeapFree", nativeHeapFree)
            put("nativeHeapTotal", nativeHeapTotal)
        }

        return json.toString()
    }
}
