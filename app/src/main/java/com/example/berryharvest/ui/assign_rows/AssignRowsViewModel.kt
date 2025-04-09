package com.example.berryharvest.ui.assign_rows

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.berryharvest.MyApplication
import com.example.berryharvest.data.repository.ConnectionState
import com.example.berryharvest.data.repository.Result
import com.example.berryharvest.ui.add_worker.Worker
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "AssignRowsViewModel"

class AssignRowsViewModel(application: Application) : AndroidViewModel(application) {
    private val app: MyApplication = getApplication() as MyApplication
    private val assignmentRepository = app.repositoryProvider.assignmentRepository
    private val workerRepository = app.repositoryProvider.workerRepository

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

            assignmentRepository.getAllGroupedByRow().collect { result ->
                _isLoading.value = false

                when (result) {
                    is Result.Success -> {
                        _assignments.value = result.data
                        _error.value = null
                        _realmInitialized.value = true

                        // When assignments change, update worker details
                        updateWorkerDetails(result.data)
                    }
                    is Result.Error -> {
                        _error.value = "Помилка завантаження даних: ${result.message}"
                        Log.e(TAG, "Error loading assignments", result.exception)
                    }
                    is Result.Loading -> {
                        _isLoading.value = true
                    }
                }
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

    private fun observeConnectionState() {
        viewModelScope.launch {
            assignmentRepository.getConnectionState().collect { state ->
                _connectionState.value = state

                // If we're back online, try to sync any pending changes
                if (state is ConnectionState.Connected) {
                    syncPendingChanges()
                }
            }
        }
    }

    private fun syncPendingChanges() {
        viewModelScope.launch {
            val syncResult = assignmentRepository.syncPendingChanges()
            if (syncResult is Result.Error) {
                _error.value = "Помилка синхронізації: ${syncResult.message}"
            }
        }
    }

    /**
     * Assign a worker to a row.
     */
    suspend fun assignWorkerToRow(workerId: String, rowNumber: Int): Result<Boolean> {
        _isLoading.value = true
        Log.d(TAG, "Starting assignWorkerToRow process with workerId=$workerId, rowNumber=$rowNumber")

        try {
            // First log the worker details for diagnostics
            val workerDetails = workerRepository.getById(workerId)
            Log.d(TAG, "Worker details: ${workerDetails.getOrNull()?.fullName ?: "Not found"}")

            // Create and configure the assignment object
            val assignment = Assignment().apply {
                this.workerId = workerId
                this.rowNumber = rowNumber
            }
            Log.d(TAG, "Created assignment object: ${assignment._id}")

            // Try to add the assignment
            Log.d(TAG, "Calling assignmentRepository.add...")
            val result = assignmentRepository.add(assignment)
            Log.d(TAG, "assignmentRepository.add result: $result")

            return when (result) {
                is Result.Success -> {
                    Log.d(TAG, "Worker assigned successfully to row: $rowNumber, ID=${result.data}")
                    Result.Success(true)
                }
                is Result.Error -> {
                    Log.e(TAG, "Error assigning worker", result.exception)
                    _error.value = "Failed to assign worker: ${result.message}"
                    Result.Error(result.exception, result.message)
                }
                is Result.Loading -> Result.Loading
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during assignWorkerToRow", e)
            _error.value = "Error assigning worker: ${e.message}"
            return Result.Error(e)
        } finally {
            _isLoading.value = false
            Log.d(TAG, "assignWorkerToRow process completed")
        }
    }

    /**
     * Move a worker to a different row.
     */
    suspend fun moveWorkerToRow(assignmentId: String, newRowNumber: Int): Result<Boolean> {
        _isLoading.value = true

        try {
            val getResult = assignmentRepository.getById(assignmentId)

            return when (getResult) {
                is Result.Success -> {
                    val assignment = getResult.data
                    if (assignment != null) {
                        assignment.rowNumber = newRowNumber
                        val updateResult = assignmentRepository.update(assignment)

                        when (updateResult) {
                            is Result.Success -> {
                                Log.d(TAG, "Worker moved to row: $newRowNumber")
                                Result.Success(true)
                            }
                            is Result.Error -> {
                                _error.value = "Помилка переміщення: ${updateResult.message}"
                                Result.Error(updateResult.exception, updateResult.message)
                            }
                            is Result.Loading -> Result.Loading
                        }
                    } else {
                        _error.value = "Призначення не знайдено"
                        Result.Success(false)
                    }
                }
                is Result.Error -> {
                    _error.value = "Помилка пошуку призначення: ${getResult.message}"
                    Result.Error(getResult.exception, getResult.message)
                }
                is Result.Loading -> Result.Loading
            }
        } catch (e: Exception) {
            _error.value = "Помилка переміщення: ${e.message}"
            Log.e(TAG, "Error moving worker", e)
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
     * Delete all assignments for a row.
     */
    suspend fun deleteRow(rowNumber: Int): Result<Boolean> {
        _isLoading.value = true

        try {
            val result = assignmentRepository.deleteByRow(rowNumber)

            return when (result) {
                is Result.Success -> {
                    Log.d(TAG, "Row deleted: $rowNumber")
                    Result.Success(true)
                }
                is Result.Error -> {
                    _error.value = "Помилка видалення ряду: ${result.message}"
                    Log.e(TAG, "Error deleting row", result.exception)
                    Result.Error(result.exception, result.message)
                }
                is Result.Loading -> Result.Loading
            }
        } catch (e: Exception) {
            _error.value = "Помилка видалення ряду: ${e.message}"
            Log.e(TAG, "Error deleting row", e)
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
}
