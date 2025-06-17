package com.example.berryharvest

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.berryharvest.util.CoroutineHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Enhanced Base Fragment class that handles coroutine lifecycle management and memory optimization
 * to prevent memory leaks and improve performance
 */
abstract class BaseFragment : Fragment(), LifecycleEventObserver {

    // Track all launched coroutines to ensure they're cancelled
    private val coroutineJobs = ConcurrentHashMap<String, JobWrapper>()

    // Track fragment lifecycle state
    private val isViewDestroyed = AtomicBoolean(false)
    private val isFragmentDestroyed = AtomicBoolean(false)
    private val isBackHandlingEnabled = AtomicBoolean(true)

    // Back press handling
    private var backPressedCallback: OnBackPressedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Register lifecycle observer to handle all lifecycle events
        lifecycle.addObserver(this)

        // Setup back press handling for better navigation
        setupBackPressHandling()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isViewDestroyed.set(false)
        // Clear any previously tracked jobs that might have leaked
        cancelAllJobs()

        // Enable back press callback when view is created
        backPressedCallback?.isEnabled = isBackHandlingEnabled.get()
    }

    private fun setupBackPressHandling() {
        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Allow fragments to override back press behavior
                if (!handleBackPress()) {
                    // If fragment doesn't handle it, use default behavior
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }

        // Add callback to activity's back press dispatcher
        requireActivity().onBackPressedDispatcher.addCallback(this, backPressedCallback!!)
    }

    /**
     * Override this method in child fragments to handle back press
     * @return true if the fragment handled the back press, false otherwise
     */
    protected open fun handleBackPress(): Boolean {
        return false
    }

    /**
     * Enable or disable back press handling for this fragment
     */
    protected fun setBackHandlingEnabled(enabled: Boolean) {
        isBackHandlingEnabled.set(enabled)
        backPressedCallback?.isEnabled = enabled
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_PAUSE -> {
                // Cancel non-essential jobs when fragment is paused
                cancelNonEssentialJobs()
                // Disable back handling when paused
                backPressedCallback?.isEnabled = false
            }
            Lifecycle.Event.ON_RESUME -> {
                // Re-enable back handling when resumed
                backPressedCallback?.isEnabled = isBackHandlingEnabled.get()
            }
            Lifecycle.Event.ON_STOP -> {
                // Cancel more jobs when fragment is stopped
                cancelMostJobs()
            }
            Lifecycle.Event.ON_DESTROY -> {
                isFragmentDestroyed.set(true)
                cancelAllJobs()
                lifecycle.removeObserver(this)
                // Remove back press callback
                backPressedCallback?.remove()
                backPressedCallback = null
            }
            else -> {
                // Other lifecycle events don't require action
            }
        }
    }

    /**
     * Launch a coroutine that respects the fragment's lifecycle and prevents memory leaks
     * Enhanced version with better lifecycle management and memory optimization
     */
    protected fun launchWhenStarted(
        key: String = "",
        essential: Boolean = false,
        block: suspend CoroutineScope.() -> Unit
    ): Job? {
        // Don't launch if fragment/view is destroyed
        if (isFragmentDestroyed.get() || (isViewDestroyed.get() && !essential)) {
            return null
        }

        // Cancel existing job with the same key if it exists
        cancelJob(key)

        // Launch new job that respects lifecycle
        return try {
            viewLifecycleOwner.lifecycleScope.launch(CoroutineHandler.exceptionHandler) {
                // Only run the coroutine when the lifecycle is at least STARTED
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    // Double-check that we haven't been destroyed during launch
                    if (!isViewDestroyed.get() && !isFragmentDestroyed.get()) {
                        block()
                    }
                }
            }.also { job ->
                // Track the job for cleanup with memory optimization
                if (key.isNotEmpty()) {
                    coroutineJobs[key] = JobWrapper(job, essential)
                }

                // Add completion handler to clean up automatically
                job.invokeOnCompletion { exception ->
                    if (key.isNotEmpty()) {
                        coroutineJobs.remove(key)
                    }
                    if (exception != null && exception !is kotlinx.coroutines.CancellationException) {
                        // Log non-cancellation exceptions
                        android.util.Log.e("BaseFragment", "Coroutine completed with exception: $key", exception)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BaseFragment", "Error launching coroutine: $key", e)
            null
        }
    }

    /**
     * Launch a coroutine that should survive fragment pauses (for essential operations)
     */
    protected fun launchEssential(
        key: String = "",
        block: suspend CoroutineScope.() -> Unit
    ): Job? {
        return launchWhenStarted(key, essential = true, block)
    }

    /**
     * Cancel a specific job by key
     */
    protected fun cancelJob(key: String) {
        val jobWrapper = coroutineJobs.remove(key)
        if (jobWrapper != null) {
            CoroutineHandler.cancelSafely(jobWrapper.job)
        }
    }

    /**
     * Cancel all non-essential jobs (called on pause)
     */
    private fun cancelNonEssentialJobs() {
        val keysToRemove = mutableListOf<String>()

        coroutineJobs.forEach { (key, jobWrapper) ->
            if (!jobWrapper.essential) {
                CoroutineHandler.cancelSafely(jobWrapper.job)
                keysToRemove.add(key)
            }
        }

        keysToRemove.forEach { key ->
            coroutineJobs.remove(key)
        }

        // Force garbage collection of cancelled jobs
        System.gc()
    }

    /**
     * Cancel most jobs except critical ones (called on stop)
     */
    private fun cancelMostJobs() {
        val keysToRemove = mutableListOf<String>()

        coroutineJobs.forEach { (key, jobWrapper) ->
            // Only keep absolutely critical jobs
            val isCritical = jobWrapper.essential && key.contains("critical", ignoreCase = true)
            if (!isCritical) {
                CoroutineHandler.cancelSafely(jobWrapper.job)
                keysToRemove.add(key)
            }
        }

        keysToRemove.forEach { key ->
            coroutineJobs.remove(key)
        }

        // Force garbage collection of cancelled jobs
        System.gc()
    }

    /**
     * Cancel all tracked jobs
     */
    protected fun cancelAllJobs() {
        coroutineJobs.forEach { (_, jobWrapper) ->
            CoroutineHandler.cancelSafely(jobWrapper.job)
        }
        coroutineJobs.clear()

        // Force garbage collection to free memory
        System.gc()
    }

    /**
     * Check if a job is still active
     */
    protected fun isJobActive(key: String): Boolean {
        return coroutineJobs[key]?.job?.isActive ?: false
    }

    /**
     * Get the number of active jobs
     */
    protected fun getActiveJobCount(): Int {
        return coroutineJobs.values.count { it.job.isActive }
    }

    override fun onDestroyView() {
        isViewDestroyed.set(true)
        // Cancel all jobs when view is destroyed to prevent memory leaks
        cancelAllJobs()
        super.onDestroyView()
    }

    override fun onDestroy() {
        isFragmentDestroyed.set(true)
        // Final cleanup - ensure everything is cancelled
        cancelAllJobs()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // Cancel non-essential jobs on low memory
        cancelNonEssentialJobs()
    }

    /**
     * Wrapper class to track job metadata
     */
    private data class JobWrapper(
        val job: Job,
        val essential: Boolean = false
    )
}