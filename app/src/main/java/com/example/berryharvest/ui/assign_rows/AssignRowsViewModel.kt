package com.example.berryharvest.ui.assign_rows

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.data.model.Assignment
import com.example.berryharvest.data.repository.ConnectionState
import com.example.berryharvest.data.repository.Result
import com.example.berryharvest.data.model.Worker
import io.realm.kotlin.ext.query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

class AssignRowsViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "AssignRowsViewModel"
    private val app: BerryHarvestApplication = getApplication() as BerryHarvestApplication
    private val assignmentRepository = app.repositoryProvider.assignmentRepository
    private val workerRepository = app.repositoryProvider.workerRepository
    private val networkStatusManager = app.networkStatusManager

    // Mutex for thread-safe operations
    private val operationMutex = Mutex()

    // Atomic flags for state management
    private val isInitialized = AtomicBoolean(false)
    private val isCleanedUp = AtomicBoolean(false)

    private val _assignments = MutableStateFlow<List<AssignmentGroup>>(emptyList())
    val assignments: StateFlow<List<AssignmentGroup>> = _assignments.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _dataInitialized = MutableStateFlow(false)
    val dataInitialized: StateFlow<Boolean> = _dataInitialized.asStateFlow()

    // New Flow for worker details, exposed to the UI
    private val _workerDetails = MutableStateFlow<Map<String, Worker>>(mapOf())
    val workerDetails: StateFlow<Map<String, Worker>> = _workerDetails.asStateFlow()

    // New Flow for all workers, for search and selection
    private val _allWorkers = MutableStateFlow<List<Worker>>(listOf())
    val allWorkers: StateFlow<List<Worker>> = _allWorkers.asStateFlow()

    // This flag is used to signal when initialization is complete
    private val _realmInitialized = MutableStateFlow(false)
    val realmInitialized: StateFlow<Boolean> = _realmInitialized.asStateFlow()

    init {
        observeAssignments()
        observeWorkers()
        observeConnectionState()
    }

    private fun observeAssignments() {
        if (isCleanedUp.get()) return

        viewModelScope.launch {
            try {
                _isLoading.value = true

                assignmentRepository.getAllGroupedByRow().collect { result ->
                    if (isCleanedUp.get()) return@collect

                    _isLoading.value = false

                    when (result) {
                        is Result.Success -> {
                            Log.d(TAG, "Successfully loaded ${result.data.size} assignment groups")
                            _assignments.value = result.data
                            _error.value = null
                            _realmInitialized.value = true

                            // When assignments change, update worker details
                            updateWorkerDetails(result.data)
                        }
                        is Result.Error -> {
                            Log.e(TAG, "Error loading assignments", result.exception)
                            _error.value = "Помилка завантаження даних: ${result.message}"

                            // Add direct query fallback for offline mode
                            handleAssignmentLoadError()
                        }
                        is Result.Loading -> {
                            _isLoading.value = true
                        }
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Assignment observation cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error observing assignments", e)
                _isLoading.value = false
                _error.value = "Помилка: ${e.message}"
            }
        }
    }

    private suspend fun handleAssignmentLoadError() {
        try {
            val realm = app.getRealmInstance()
            val localAssignments = realm.query<Assignment>().find()

            // Group them manually
            val groupedAssignments = localAssignments
                .groupBy { it.rowNumber }
                .map { (rowNumber, assignments) ->
                    AssignmentGroup(rowNumber, assignments.toList())
                }
                .sortedBy { it.rowNumber }

            if (groupedAssignments.isNotEmpty()) {
                _assignments.value = groupedAssignments
                updateWorkerDetails(groupedAssignments)
                _error.value = null // Clear error if fallback succeeded
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading fallback local data", e)
        }
    }

    private fun observeWorkers() {
        if (isCleanedUp.get()) return

        viewModelScope.launch {
            try {
                workerRepository.getAll().collect { result ->
                    if (isCleanedUp.get()) return@collect

                    when (result) {
                        is Result.Success -> {
                            val workers = result.data
                            // Update all workers for search
                            _allWorkers.value = workers

                            // Create a map for quick lookup
                            val workerMap = workers.associateBy { it._id }
                            _workerDetails.value = workerMap
                        }
                        is Result.Error -> {
                            _error.value = "Помилка завантаження працівників: ${result.message}"
                            Log.e(TAG, "Error loading workers", result.exception)
                        }
                        is Result.Loading -> {
                            // We're already tracking loading state elsewhere
                        }
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Worker observation cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error observing workers", e)
                _error.value = "Помилка завантаження працівників: ${e.message}"
            }
        }
    }

    private fun observeConnectionState() {
        if (isCleanedUp.get()) return

        viewModelScope.launch {
            try {
                // Use the centralized network status manager instead of repository
                networkStatusManager.connectionState.collect { state ->
                    if (isCleanedUp.get()) return@collect

                    _connectionState.value = state

                    // If we're back online, try to sync any pending changes
                    if (state is ConnectionState.Connected) {
                        Log.d(TAG, "Network is available, syncing pending changes")
                        syncPendingChanges()
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Connection state observation cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error observing connection state", e)
            }
        }
    }

    fun loadInitialData() {
        // Only load if not already initialized
        if (_dataInitialized.value || isCleanedUp.get()) return

        viewModelScope.launch {
            operationMutex.withLock {
                if (_dataInitialized.value || isCleanedUp.get()) return@withLock

                try {
                    _isLoading.value = true

                    // Get direct reference to Realm
                    val realm = app.getRealmInstance()

                    // First load workers to ensure they're available for display
                    val workers = realm.query<Worker>().find().toList()
                    _allWorkers.value = workers
                    Log.d(TAG, "Pre-loaded ${workers.size} workers for UI")

                    // Load assignments directly
                    val assignments = realm.query<Assignment>().find()

                    // Group them manually
                    val groupedAssignments = assignments
                        .groupBy { it.rowNumber }
                        .map { (rowNumber, assignments) ->
                            AssignmentGroup(rowNumber, assignments.toList())
                        }
                        .sortedBy { it.rowNumber }

                    if (groupedAssignments.isNotEmpty()) {
                        Log.d(TAG, "Directly loaded ${groupedAssignments.size} assignment groups")
                        _assignments.value = groupedAssignments

                        // Also update worker details
                        updateWorkerDetails(groupedAssignments)
                    }

                    _dataInitialized.value = true

                } catch (e: Exception) {
                    Log.e(TAG, "Error in loadInitialData", e)
                    _error.value = "Помилка завантаження даних: ${e.message}"
                } finally {
                    // Always ensure loading state is reset
                    _isLoading.value = false
                }
            }
        }
    }

    fun ensureLoadingStateReset() {
        viewModelScope.launch {
            if (_isLoading.value && !isCleanedUp.get()) {
                // Give a short delay for any ongoing operation to complete
                delay(300)
                _isLoading.value = false
                Log.d(TAG, "Force reset loading state to avoid UI being stuck")
            }
        }
    }

    private fun updateWorkerDetails(assignmentGroups: List<AssignmentGroup>) {
        if (isCleanedUp.get()) return

        viewModelScope.launch {
            try {
                // Get all worker IDs from assignments
                val workerIds = assignmentGroups
                    .flatMap { it.assignments }
                    .map { it.workerId }
                    .distinct()

                if (workerIds.isEmpty()) return@launch

                // Load worker details by IDs if needed
                if (workerIds.any { !_workerDetails.value.containsKey(it) }) {
                    workerRepository.getWorkersByIds(workerIds).collect { result ->
                        if (isCleanedUp.get()) return@collect

                        when (result) {
                            is Result.Success -> {
                                val newWorkerMap = result.data.associateBy { it._id }
                                _workerDetails.value = newWorkerMap
                            }
                            is Result.Error -> {
                                Log.e(TAG, "Error loading worker details", result.exception)
                            }
                            is Result.Loading -> {
                                // We're already tracking loading state elsewhere
                            }
                        }
                        // Only collect first emission to avoid continuous updates
                        return@collect
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating worker details", e)
            }
        }
    }

    suspend fun deleteAllRows(): Result<Boolean> {
        if (isCleanedUp.get()) return Result.Error(Exception("ViewModel is cleaned up"))

        return operationMutex.withLock {
            _isLoading.value = true

            try {
                val realm = app.getRealmInstance()

                // Find all assignments
                val assignments = realm.query<Assignment>().find()

                if (assignments.isEmpty()) {
                    _isLoading.value = false
                    return@withLock Result.Success(true)
                }

                // Delete all assignments
                app.safeWriteTransaction {
                    val liveAssignments = query<Assignment>().find()
                    delete(liveAssignments)
                    Log.d(TAG, "Deleted all assignments (${liveAssignments.size})")
                }

                Result.Success(true)
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting all rows", e)
                _error.value = "Помилка видалення всіх рядів: ${e.message}"
                Result.Error(e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun syncPendingChanges() {
        if (isCleanedUp.get()) return

        viewModelScope.launch {
            try {
                // Use the global sync manager instead of repository-specific calls
                Log.d(TAG, "Using global sync manager to sync pending changes")
                app.syncManager.performSync(silent = true)

                // After sync is complete, explicitly refresh the assignments data
                refreshAssignmentsData()
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing changes", e)
                _error.value = "Failed to sync changes: ${e.message}"
            }
        }
    }

    fun forceRefreshUI() {
        if (isCleanedUp.get()) return

        viewModelScope.launch {
            val currentList = _assignments.value
            // First set to empty list to force change
            _assignments.value = emptyList()
            // Small delay to ensure UI processes the change
            delay(50)
            // Then set back to original list
            _assignments.value = currentList
            Log.d(TAG, "Forced UI refresh with ${currentList.size} assignment groups")
        }
    }

    private fun refreshAssignmentsData() {
        if (isCleanedUp.get()) return

        viewModelScope.launch {
            Log.d(TAG, "Refreshing assignments data after sync")
            _isLoading.value = true

            try {
                // Force a UI refresh by clearing and then reloading
                val currentList = _assignments.value.toList()
                _assignments.value = emptyList() // Clear first
                delay(50) // Short delay

                // Then reload with fresh data
                assignmentRepository.getAllGroupedByRow().collect { result ->
                    if (isCleanedUp.get()) return@collect

                    if (result is Result.Success) {
                        Log.d(TAG, "Successfully refreshed ${result.data.size} assignment groups")
                        _assignments.value = result.data

                        // Also check and update UI even if no data actually changed
                        if (result.data.isEmpty() && currentList.isNotEmpty()) {
                            _assignments.value = currentList // Restore if no new data
                        }
                    }
                    // We only need one emission, so break the collection
                    return@collect
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing assignments data", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Assign a worker to a row.
     */
    suspend fun assignWorkerToRow(workerId: String, rowNumber: Int): Result<Boolean> {
        if (isCleanedUp.get()) return Result.Error(Exception("ViewModel is cleaned up"))

        return operationMutex.withLock {
            _isLoading.value = true

            try {
                // Use repository method instead of direct Realm access
                val result = assignmentRepository.assignWorkerToRow(workerId, rowNumber)

                when (result) {
                    is Result.Success -> {
                        Log.d(TAG, "Worker assigned successfully to row: $rowNumber, ID=${result.data}")
                        Result.Success(true)
                    }
                    is Result.Error -> {
                        Log.e(TAG, "Error assigning worker", result.exception)
                        _error.value = "Failed to assign worker: ${result.message}"
                        Result.Error(result.exception)
                    }
                    is Result.Loading -> Result.Loading
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error assigning worker", e)
                _error.value = "Error assigning worker: ${e.message}"
                Result.Error(e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Move a worker to a different row.
     */
    suspend fun moveWorkerToRow(assignmentId: String, newRowNumber: Int): Result<Boolean> {
        if (isCleanedUp.get()) return Result.Error(Exception("ViewModel is cleaned up"))

        return operationMutex.withLock {
            _isLoading.value = true

            try {
                // Use repository method instead of direct Realm access
                val result = assignmentRepository.moveWorkerToRow(assignmentId, newRowNumber)

                when (result) {
                    is Result.Success -> {
                        Log.d(TAG, "Worker moved to row: $newRowNumber")
                        Result.Success(true)
                    }
                    is Result.Error -> {
                        Log.e(TAG, "Error moving worker", result.exception)
                        _error.value = "Failed to move worker: ${result.message}"
                        Result.Error(result.exception)
                    }
                    is Result.Loading -> Result.Loading
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error moving worker", e)
                _error.value = "Error moving worker: ${e.message}"
                Result.Error(e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Delete a worker assignment.
     */
    suspend fun deleteWorkerAssignment(assignmentId: String): Result<Boolean> {
        if (isCleanedUp.get()) return Result.Error(Exception("ViewModel is cleaned up"))

        return operationMutex.withLock {
            _isLoading.value = true

            try {
                val result = assignmentRepository.delete(assignmentId)

                when (result) {
                    is Result.Success -> {
                        Log.d(TAG, "Worker assignment deleted")
                        Result.Success(true)
                    }
                    is Result.Error -> {
                        _error.value = "Помилка видалення: ${result.message}"
                        Log.e(TAG, "Error deleting assignment", result.exception)
                        Result.Error(result.exception, result.message)
                    }
                    is Result.Loading -> Result.Loading
                }
            } catch (e: Exception) {
                _error.value = "Помилка видалення: ${e.message}"
                Log.e(TAG, "Error deleting assignment", e)
                Result.Error(e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Delete an entire row.
     */
    suspend fun deleteEntireRow(rowNumber: Int): Result<Boolean> {
        if (isCleanedUp.get()) return Result.Error(Exception("ViewModel is cleaned up"))

        return operationMutex.withLock {
            _isLoading.value = true

            try {
                // Use repository method instead of direct Realm access
                val result = assignmentRepository.completelyDeleteRow(rowNumber)

                when (result) {
                    is Result.Success -> {
                        Log.d(TAG, "Row deleted: $rowNumber")
                        Result.Success(true)
                    }
                    is Result.Error -> {
                        Log.e(TAG, "Error deleting row", result.exception)
                        _error.value = "Failed to delete row: ${result.message}"
                        Result.Error(result.exception)
                    }
                    is Result.Loading -> Result.Loading
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting row", e)
                _error.value = "Error deleting row: ${e.message}"
                Result.Error(e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Check if a worker is already assigned to a row.
     */
    suspend fun checkWorkerAssignment(workerId: String): Result<Int> {
        if (isCleanedUp.get()) return Result.Error(Exception("ViewModel is cleaned up"))

        return try {
            // Use repository method instead of direct Realm access
            val result = assignmentRepository.getWorkerAssignment(workerId)

            when (result) {
                is Result.Success -> {
                    val assignment = result.data
                    Result.Success(assignment?.rowNumber ?: -1)
                }
                is Result.Error -> {
                    Log.e(TAG, "Error checking worker assignment", result.exception)
                    Result.Error(result.exception)
                }
                is Result.Loading -> Result.Loading
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking worker assignment", e)
            Result.Error(e)
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
        isCleanedUp.set(true)
        Log.d(TAG, "ViewModel cleared")
        // No need to close Realm here as it's managed by the repository
    }
}
