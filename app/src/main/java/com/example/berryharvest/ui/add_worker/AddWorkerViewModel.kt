package com.example.berryharvest.ui.add_worker

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.data.model.Worker
import com.example.berryharvest.data.repository.ConnectionState
import com.example.berryharvest.data.repository.Result
import com.example.berryharvest.data.repository.WorkerRepository
import io.realm.kotlin.ext.query
import io.realm.kotlin.mongodb.subscriptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AddWorkerViewModel(application: Application) : AndroidViewModel(application) {

    private val app: BerryHarvestApplication
        get() = getApplication() as BerryHarvestApplication

    private val repository = app.repositoryProvider.workerRepository

    private val networkStatusManager = app.networkStatusManager

    private val _workers = MutableStateFlow<List<Worker>>(emptyList())
    val workers: StateFlow<List<Worker>> = _workers.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        observeWorkers()
        observeConnectionState()
    }

    private fun observeWorkers() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.getAll().collect { result ->
                _isLoading.value = false

                when (result) {
                    is Result.Success -> {
                        _workers.value = result.data
                        _error.value = null
                    }
                    is Result.Error -> {
                        _error.value = "Error loading workers: ${result.message}"
                        Log.e("AddWorkerViewModel", "Error loading workers", result.exception)
                    }
                    is Result.Loading -> {
                        _isLoading.value = true
                    }
                }
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            // Use the centralized network status manager instead of repository
            networkStatusManager.connectionState.collect { state ->
                _connectionState.value = state

                // If we're back online, try to sync any pending changes
                if (state is ConnectionState.Connected) {
                    Log.d("AddWorkerViewModel", "Network is available, syncing pending changes")
                    syncPendingChanges()
                }
            }
        }
    }

    private fun syncPendingChanges() {
        // Use the global sync manager instead
        viewModelScope.launch {
            try {
                app.syncManager.performSync(silent = true)
            } catch (e: Exception) {
                Log.e("AddWorkerViewModel", "Error syncing changes", e)
            }
        }
    }

    fun addWorker(fullName: String, phoneNumber: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                val result = repository.addWorkerWithDetails(fullName, phoneNumber)

                when (result) {
                    is Result.Success -> {
                        Log.d("AddWorkerViewModel", "Worker added: $fullName with ID: ${result.data}")
                        _error.value = null
                    }
                    is Result.Error -> {
                        _error.value = "Failed to add worker: ${result.message}"
                        Log.e("AddWorkerViewModel", "Error adding worker", result.exception)
                    }
                    is Result.Loading -> { /* Already handled by isLoading state */ }
                }
            } catch (e: Exception) {
                _error.value = "Error adding worker: ${e.message}"
                Log.e("AddWorkerViewModel", "Error adding worker", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateWorker(id: String, fullName: String, phoneNumber: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                val result = repository.updateWorkerWithDetails(id, fullName, phoneNumber)

                when (result) {
                    is Result.Success -> {
                        Log.d("AddWorkerViewModel", "Worker updated: $id")
                        _error.value = null
                    }
                    is Result.Error -> {
                        _error.value = "Failed to update worker: ${result.message}"
                        Log.e("AddWorkerViewModel", "Error updating worker", result.exception)
                    }
                    is Result.Loading -> { /* Already handled by isLoading state */ }
                }
            } catch (e: Exception) {
                _error.value = "Error updating worker: ${e.message}"
                Log.e("AddWorkerViewModel", "Error updating worker", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteWorker(id: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                val result = repository.delete(id)

                when (result) {
                    is Result.Success -> {
                        Log.d("AddWorkerViewModel", "Worker deleted: $id")
                        _error.value = null
                    }
                    is Result.Error -> {
                        _error.value = "Failed to delete worker: ${result.message}"
                        Log.e("AddWorkerViewModel", "Error deleting worker", result.exception)
                    }
                    is Result.Loading -> { /* Already handled by isLoading state */ }
                }
            } catch (e: Exception) {
                _error.value = "Error deleting worker: ${e.message}"
                Log.e("AddWorkerViewModel", "Error deleting worker", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Get comprehensive sync debug report
     */
    fun getFullSyncDebugReport() {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                val report = app.debugRealmSync()

                // Log the full report
                Log.d("AddWorkerViewModel", "FULL SYNC DEBUG REPORT:")
                Log.d("AddWorkerViewModel", report)

                // Show summary in UI
                val lines = report.split("\n")
                val errors = lines.filter { it.contains("❌") }
                val warnings = lines.filter { it.contains("⚠️") }
                val success = lines.filter { it.contains("✅") }

                val summary = buildString {
                    appendLine("SYNC DEBUG SUMMARY:")
                    appendLine("✅ Success: ${success.size}")
                    appendLine("⚠️ Warnings: ${warnings.size}")
                    appendLine("❌ Errors: ${errors.size}")
                    if (errors.isNotEmpty()) {
                        appendLine("\nERRORS:")
                        errors.forEach { appendLine(it) }
                    }
                }

                _error.value = summary

            } catch (e: Exception) {
                _error.value = "Debug failed: ${e.message}"
                Log.e("AddWorkerViewModel", "Error getting debug report", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Get quick sync status
     */
    fun getQuickSyncStatus() {
        viewModelScope.launch {
            try {
                val status = app.getQuickSyncStatus()
                _error.value = "Sync Status: $status"
                Log.d("AddWorkerViewModel", "Quick sync status: $status")
            } catch (e: Exception) {
                _error.value = "Status check failed: ${e.message}"
            }
        }
    }

    /**
     * Attempt to fix common sync issues
     */
    fun attemptSyncFix() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = "Attempting sync fix..."

                // Step 1: Check if user is logged in
                if (app.app?.currentUser == null) {
                    Log.d("AddWorkerViewModel", "No user - attempting anonymous login")
                    try {
                        val user = app.app?.login(io.realm.kotlin.mongodb.Credentials.anonymous())
                        if (user != null) {
                            _error.value = "✅ User login successful"
                            delay(1000)
                        } else {
                            _error.value = "❌ User login failed"
                            return@launch
                        }
                    } catch (e: Exception) {
                        _error.value = "❌ Login error: ${e.message}"
                        return@launch
                    }
                }

                // Step 2: Ensure subscriptions exist
                try {
                    app.ensureSubscriptions()
                    _error.value = "✅ Subscriptions ensured"
                    delay(1000)
                } catch (e: Exception) {
                    _error.value = "❌ Subscription error: ${e.message}"
                    return@launch
                }

                // Step 3: Try manual sync
                try {
                    val result = repository.syncPendingChanges()
                    when (result) {
                        is Result.Success -> _error.value = "✅ Manual sync successful"
                        is Result.Error -> _error.value = "❌ Manual sync failed: ${result.message}"
                        else -> _error.value = "⚠️ Manual sync returned loading state"
                    }
                } catch (e: Exception) {
                    _error.value = "❌ Manual sync error: ${e.message}"
                }

            } catch (e: Exception) {
                _error.value = "❌ Fix attempt failed: ${e.message}"
                Log.e("AddWorkerViewModel", "Error attempting sync fix", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Nuclear option - reset everything
     */
    fun resetEverything() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = "Resetting everything..."

                // Close all realms
                app.realmManager.closeAllInstances()
                delay(1000)

                // Logout user
                app.app?.currentUser?.logOut()
                delay(1000)

                _error.value = "Reset complete. Restart app manually."

            } catch (e: Exception) {
                _error.value = "Reset failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Check actual Atlas connection details
     */
    fun checkAtlasConnection() {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                val message = buildString {
                    appendLine("=== ATLAS CONNECTION CHECK ===")
                    appendLine("Time: ${System.currentTimeMillis()}")
                    appendLine()

                    // App details
                    appendLine("MongoDB App:")
                    appendLine("  App ID: ${app.app?.configuration?.appId}")
                    appendLine("  Base URL: ${app.app?.configuration?.baseUrl}")
                    appendLine("  App exists: ${app.app != null}")
                    appendLine()

                    // User details
                    val user = app.app?.currentUser
                    appendLine("Current User:")
                    appendLine("  User exists: ${user != null}")
                    if (user != null) {
                        appendLine("  User ID: ${user.id}")
                        appendLine("  User state: ${user.state}")
                        appendLine("  Is logged in: no user.isLoggedIn is available")
                        appendLine("  Identity: ${user.identity}")
                    }
                    appendLine()

                    // Realm details
                    try {
                        val realm = app.getRealmInstance("atlas_check")
                        val config = realm.configuration.toString()

                        appendLine("Realm Configuration:")
                        appendLine("  Is Sync Realm: ${config.contains("Sync", ignoreCase = true)}")
                        appendLine("  Config contains 'local': ${config.contains("local", ignoreCase = true)}")
                        appendLine("  Full config: $config")
                        appendLine()

                        if (config.contains("Sync", ignoreCase = true)) {
                            appendLine("Sync Details:")
                            appendLine("  Subscriptions: ${realm.subscriptions.size}")
                            realm.subscriptions.forEach { sub ->
                                appendLine("    - ${sub.name}: ${sub.objectType}")
                            }
                        }

                    } catch (e: Exception) {
                        appendLine("Realm Error: ${e.message}")
                    }

                    appendLine()
                    appendLine("Network: ${app.networkStatusManager.isNetworkAvailable()}")
                    appendLine("=== END CONNECTION CHECK ===")
                }

                _error.value = message
                Log.d("AddWorkerViewModel", message)

            } catch (e: Exception) {
                _error.value = "Atlas check failed: ${e.message}"
                Log.e("AddWorkerViewModel", "Error checking Atlas connection", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Analyze current sync setup to understand what's really happening
     */
    fun analyzeSyncSetup() {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                val realm = app.getRealmInstance("analysis")
                val config = realm.configuration.toString()

                val analysis = buildString {
                    appendLine("=== SYNC ANALYSIS ===")
                    appendLine("Time: ${System.currentTimeMillis()}")
                    appendLine()

                    // Realm type analysis
                    val isSyncRealm = config.contains("Sync", ignoreCase = true)
                    val isLocalRealm = config.contains("local", ignoreCase = true)

                    appendLine("REALM TYPE:")
                    appendLine("  Is Sync Realm: $isSyncRealm")
                    appendLine("  Is Local Realm: $isLocalRealm")
                    appendLine("  Config: $config")
                    appendLine()

                    // MongoDB connection analysis
                    appendLine("MONGODB CONNECTION:")
                    appendLine("  App exists: ${app.app != null}")
                    if (app.app != null) {
                        appendLine("  App ID: ${app.app!!.configuration.appId}")
                        appendLine("  Base URL: ${app.app!!.configuration.baseUrl}")
                        appendLine("  Current user: ${app.app!!.currentUser?.id}")
                        appendLine("  User state: ${app.app!!.currentUser?.state}")
                    }
                    appendLine()

                    // Subscription analysis
                    if (isSyncRealm) {
                        appendLine("SYNC SUBSCRIPTIONS:")
                        appendLine("  Total subscriptions: ${realm.subscriptions.size}")
                        realm.subscriptions.forEach { sub ->
                            appendLine("    - ${sub.name}: ${sub.objectType}")
                        }
                        appendLine()
                    }

                    // Data analysis
                    appendLine("LOCAL DATA:")
                    try {
                        val workerCount = realm.query<Worker>().count().find()
                        val gatherCount = realm.query<com.example.berryharvest.data.model.Gather>().count().find()
                        val assignmentCount = realm.query<com.example.berryharvest.data.model.Assignment>().count().find()

                        appendLine("  Workers: $workerCount")
                        appendLine("  Gathers: $gatherCount")
                        appendLine("  Assignments: $assignmentCount")

                        if (isSyncRealm) {
                            val unsyncedWorkers = realm.query<Worker>("isSynced == false").count().find()
                            val syncedWorkers = realm.query<Worker>("isSynced == true").count().find()
                            appendLine("  Synced Workers: $syncedWorkers")
                            appendLine("  Unsynced Workers: $unsyncedWorkers")
                        }
                    } catch (e: Exception) {
                        appendLine("  Data query error: ${e.message}")
                    }
                    appendLine()

                    // Network analysis
                    appendLine("NETWORK:")
                    appendLine("  Available: ${app.networkStatusManager.isNetworkAvailable()}")
                    appendLine("  Connection state: ${app.networkStatusManager.connectionState.value}")
                    appendLine()

                    // Conclusion
                    appendLine("CONCLUSION:")
                    when {
                        !isSyncRealm -> appendLine("  ❌ Using LOCAL REALM ONLY - No cloud sync")
                        app.app?.currentUser == null -> appendLine("  ❌ NO USER - Sync won't work")
                        realm.subscriptions.size == 0 -> appendLine("  ❌ NO SUBSCRIPTIONS - Sync won't work")
                        !app.networkStatusManager.isNetworkAvailable() -> appendLine("  ⚠️ NO NETWORK - Sync paused")
                        else -> appendLine("  ✅ SYNC SETUP LOOKS GOOD - Should work with Atlas")
                    }

                    appendLine("=== END ANALYSIS ===")
                }

                _error.value = analysis
                Log.d("AddWorkerViewModel", analysis)

            } catch (e: Exception) {
                _error.value = "Analysis failed: ${e.message}"
                Log.e("AddWorkerViewModel", "Error analyzing sync", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Test actual sync by creating a uniquely identifiable worker
     */
    fun testRealSyncFunction() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = "Testing real sync functionality..."

                val uniqueId = "SYNC_TEST_${System.currentTimeMillis()}"
                val testName = "🔥 SYNC TEST WORKER 🔥"

                // Add test worker
                val result = repository.addWorkerWithDetails(testName, "+380999888777")

                when (result) {
                    is Result.Success -> {
                        delay(2000) // Wait for sync

                        val message = buildString {
                            appendLine("=== SYNC TEST RESULT ===")
                            appendLine("✅ Test worker created: $testName")
                            appendLine("Worker ID: ${result.data}")
                            appendLine("Time: ${System.currentTimeMillis()}")
                            appendLine()
                            appendLine("NOW CHECK:")
                            appendLine("1. Other device - does worker appear?")
                            appendLine("2. MongoDB Atlas - any new collections?")
                            appendLine()
                            appendLine("If worker appears on other device = LOCAL SYNC")
                            appendLine("If worker appears in Atlas = CLOUD SYNC")
                            appendLine("If neither = SYNC BROKEN")
                            appendLine("=== END TEST ===")
                        }

                        _error.value = message
                    }
                    else -> {
                        _error.value = "❌ Test failed - couldn't create worker: $result"
                    }
                }

            } catch (e: Exception) {
                _error.value = "❌ Sync test error: ${e.message}"
                Log.e("AddWorkerViewModel", "Error testing sync", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Resolve any sequence number conflicts by ensuring all workers have unique sequence numbers.
     */
    fun resolveSequenceConflicts() {
        viewModelScope.launch {
            try {
                (repository as? WorkerRepository)?.resolveSequenceConflicts()
            } catch (e: Exception) {
                _error.value = "Error resolving sequence conflicts: ${e.message}"
                Log.e("AddWorkerViewModel", "Error resolving sequence conflicts", e)
            }
        }
    }

    /**
     * Clear any error message.
     */
    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        // No need to close Realm here as it's managed by the repository
    }
}


