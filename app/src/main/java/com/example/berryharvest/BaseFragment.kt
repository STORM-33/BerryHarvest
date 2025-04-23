package com.example.berryharvest

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.berryharvest.util.CoroutineHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Base Fragment class that handles coroutine lifecycle management to prevent memory leaks
 */
abstract class BaseFragment : Fragment() {

    // Track all launched coroutines to ensure they're cancelled
    private val coroutineJobs = ConcurrentHashMap<String, Job>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Clear any previously tracked jobs that might have leaked
        cancelAllJobs()
    }

    /**
     * Launch a coroutine that respects the fragment's lifecycle and prevents memory leaks
     */
    protected fun launchWhenStarted(
        key: String = "",
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        // Cancel existing job with the same key if it exists
        cancelJob(key)

        // Launch new job that respects lifecycle
        return viewLifecycleOwner.lifecycleScope.launch(CoroutineHandler.exceptionHandler) {
            // Only run the coroutine when the lifecycle is at least STARTED
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                block()
            }
        }.also {
            // Track the job for cleanup
            if (key.isNotEmpty()) {
                coroutineJobs[key] = it
            }
        }
    }

    /**
     * Cancel a specific job by key
     */
    protected fun cancelJob(key: String) {
        val job = coroutineJobs.remove(key)
        CoroutineHandler.cancelSafely(job)
    }

    /**
     * Cancel all tracked jobs
     */
    protected fun cancelAllJobs() {
        coroutineJobs.forEach { (_, job) ->
            CoroutineHandler.cancelSafely(job)
        }
        coroutineJobs.clear()
    }

    override fun onDestroyView() {
        // Cancel all jobs when view is destroyed to prevent memory leaks
        cancelAllJobs()
        super.onDestroyView()
    }
}