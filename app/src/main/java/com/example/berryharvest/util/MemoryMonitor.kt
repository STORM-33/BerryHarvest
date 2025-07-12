// utils/MemoryMonitor.kt
package com.example.berryharvest.util

import android.app.ActivityManager
import android.content.Context
import android.util.Log

class MemoryMonitor {
    companion object {
        private const val TAG = "MemoryMonitor"

        fun logMemoryUsage(tag: String) {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val percentage = (usedMemory * 100) / maxMemory

            Log.d(TAG, "[$tag] Memory: ${usedMemory / 1024 / 1024}MB / ${maxMemory / 1024 / 1024}MB ($percentage%)")

            // Critical threshold warning
            if (percentage > 80) {
                Log.w(TAG, "⚠️ HIGH MEMORY USAGE: $percentage%")
            }
        }

        fun getDetailedMemoryReport(context: Context): String {
            val runtime = Runtime.getRuntime()
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)

            return buildString {
                appendLine("=== MEMORY REPORT ===")
                appendLine("App Memory: ${(runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024}MB")
                appendLine("App Max: ${runtime.maxMemory() / 1024 / 1024}MB")
                appendLine("Device Available: ${memInfo.availMem / 1024 / 1024}MB")
                appendLine("Device Total: ${memInfo.totalMem / 1024 / 1024}MB")
                appendLine("Low Memory: ${memInfo.lowMemory}")
                appendLine("Memory Percentage: ${((runtime.totalMemory() - runtime.freeMemory()) * 100) / runtime.maxMemory()}%")
            }
        }

        // Quick memory check without context (for simple logging)
        fun getSimpleMemoryInfo(): String {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val percentage = (usedMemory * 100) / maxMemory

            return "Memory: ${usedMemory / 1024 / 1024}MB/${maxMemory / 1024 / 1024}MB ($percentage%)"
        }
    }
}