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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AssignRowsViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "AssignRowsViewModel"
    private val app: BerryHarvestApplication = getApplication() as BerryHarvestApplication
    private val assignmentRepository = app.repositoryProvider.assignmentRepository
    private val workerRepository = app.repositoryProvider.workerRepository
    private val networkStatusManager = app.networkStatusManager

    private val _assignments = MutableStateFlow<List<AssignmentGroup>>(emptyList())
    val assignments: StateFlow<List<AssignmentGroup>> = _assignments.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

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
        viewModelScope.launch {
            _isLoading.value = true

            // Make sure to launch a new coroutine that won't be cancelled if there's an error
            try {
                assignmentRepository.getAllGroupedByRow().collect { result ->
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

                            // Even if there's an error, try to load any local data we might have
                            // This ensures we show something in offline mode
                            try {
                                val realm = (getApplication() as BerryHarvestApplication).getRealmInstance()
                                val localAssignments = realm.query<Assignment>().find()

                                // Group them manually
                                val groupedAssignments = localAssignments
                                    .groupBy { it.rowNumber }
                                    .map { (rowNumber, assignments) ->
                                        AssignmentGroup(rowNumber, assignments.toList())
                                    }
                                    .sortedBy { it.rowNumber }

                                if (groupedAssignments.isNotEmpty()) {
                                    Log.d(TAG, "Loaded ${groupedAssignments.size} assignment groups from local database")
                                    _assignments.value = groupedAssignments
                                    updateWorkerDetails(groupedAssignments)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error loading fallback local data", e)
                            }
                        }
                        is Result.Loading -> {
                            _isLoading.value = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error observing assignments", e)
                _isLoading.value = false
                _error.value = "Помилка: ${e.message}"
            }
        }
    }

    private fun observeWorkers() {
        viewModelScope.launch {
            workerRepository.getAll().collect { result ->
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
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            // Use the centralized network status manager instead of repository
            networkStatusManager.connectionState.collect { state ->
                _connectionState.value = state

                // If we're back online, try to sync any pending changes
                if (state is ConnectionState.Connected) {
                    Log.d(TAG, "Network is available, syncing pending changes")
                    syncPendingChanges()
                }
            }
        }
    }

    fun loadInitialData() {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                // Get direct reference to Realm
                val app = getApplication() as BerryHarvestApplication
                val realm = app.getRealmInstance()

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

                _isLoading.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Error in loadInitialData", e)
                _isLoading.value = false
                _error.value = "Помилка завантаження даних: ${e.message}"
            }
        }
    }

    private fun updateWorkerDetails(assignmentGroups: List<AssignmentGroup>) {
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
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating worker details", e)
            }
        }
    }

    suspend fun deleteAllRows(): Result<Boolean> {
        _isLoading.value = true

        return try {
            val app = getApplication() as BerryHarvestApplication
            val realm = app.getRealmInstance()

            // Find all assignments
            val assignments = realm.query<Assignment>().find()

            if (assignments.isEmpty()) {
                _isLoading.value = false
                return Result.Success(true)
            }

            // Delete all assignments
            app.safeWriteTransaction {
                val liveAssignments = query<Assignment>().find()
                delete(liveAssignments)
                Log.d(TAG, "Deleted all assignments (${liveAssignments.size})")
            }

            _isLoading.value = false
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting all rows", e)
            _isLoading.value = false
            _error.value = "Помилка видалення всіх рядів: ${e.message}"
            Result.Error(e)
        }
    }

    private fun syncPendingChanges() {
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

    private fun refreshAssignmentsData() {
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
        _isLoading.value = true

        try {
            // Use repository method instead of direct Realm access
            val result = assignmentRepository.assignWorkerToRow(workerId, rowNumber)

            return when (result) {
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
            return Result.Error(e)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Move a worker to a different row.
     */
    suspend fun moveWorkerToRow(assignmentId: String, newRowNumber: Int): Result<Boolean> {
        _isLoading.value = true

        try {
            // Use repository method instead of direct Realm access
            val result = assignmentRepository.moveWorkerToRow(assignmentId, newRowNumber)

            return when (result) {
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
            return Result.Error(e)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Delete a worker assignment.
     */
    suspend fun deleteWorkerAssignment(assignmentId: String): Result<Boolean> {
        _isLoading.value = true

        try {
            val result = assignmentRepository.delete(assignmentId)

            return when (result) {
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
            return Result.Error(e)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Delete an entire row.
     */
    suspend fun deleteEntireRow(rowNumber: Int): Result<Boolean> {
        _isLoading.value = true

        try {
            // Use repository method instead of direct Realm access
            val result = assignmentRepository.completelyDeleteRow(rowNumber)

            return when (result) {
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
            return Result.Error(e)
        } finally {
            _isLoading.value = false
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

    /**
     * Check if a worker is already assigned to a row.
     */
    suspend fun checkWorkerAssignment(workerId: String): Result<Int> {
        _isLoading.value = true

        try {
            // Use repository method instead of direct Realm access
            val result = assignmentRepository.getWorkerAssignment(workerId)

            return when (result) {
                is Result.Success -> {
                    val assignment = result.data
                    Result.Success(assignment?.rowNumber ?: -1)
                }
                is Result.Error -> {
                    Log.e(TAG, "Error checking worker assignment", result.exception)
                    _error.value = "Failed to check worker assignment: ${result.message}"
                    Result.Error(result.exception)
                }
                is Result.Loading -> Result.Loading
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking worker assignment", e)
            _error.value = "Error checking worker assignment: ${e.message}"
            return Result.Error(e)
        } finally {
            _isLoading.value = false
        }
    }
}
