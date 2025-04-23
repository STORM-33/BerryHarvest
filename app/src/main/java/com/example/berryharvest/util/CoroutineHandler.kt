package com.example.berryharvest.util

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job

object CoroutineHandler {
    val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("CoroutineHandler", "Uncaught exception", throwable)
        // You could integrate with a crash reporting service here
    }

    /**
     * Safely cancels a job without throwing exceptions
     */
    fun cancelSafely(job: Job?) {
        try {
            if (job?.isActive == true) {
                job.cancel()
            }
        } catch (e: Exception) {
            Log.e("CoroutineHandler", "Error cancelling job", e)
        }
    }
}