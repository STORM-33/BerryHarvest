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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
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
    private val initializationMutex = Mutex()

    // Atomic flags for state management
    private val isInitialized = AtomicBoolean(false)
    private val isCleanedUp = AtomicBoolean(false)
    private val isSyncInProgress = AtomicBoolean(false)

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

    // Worker details and all workers flows
    private val _workerDetails = MutableStateFlow<Map<String, Worker>>(mapOf())
    val workerDetails: StateFlow<Map<String, Worker>> = _workerDetails.asStateFlow()

    private val _allWorkers = MutableStateFlow<List<Worker>>(listOf())
    val allWorkers: StateFlow<List<Worker>> = _allWorkers.asStateFlow()

    private val _realmInitialized = MutableStateFlow(false)
    val realmInitialized: StateFlow<Boolean> = _realmInitialized.asStateFlow()

    // Cache for stable data during sync
    private var dataCache: List<AssignmentGroup> = emptyList()

    init {
        initializeViewModel()
    }

    private fun initializeViewModel() {
        viewModelScope.launch {
            initializationMutex.withLock {
                if (isInitialized.getAndSet(true)) return@withLock

                try {
                    // Load initial data immediately and synchronously
                    loadInitialDataSynchronously()

                    // Then set up observers for live updates
                    setupObservers()

                    // Finally, handle sync with proper isolation
                    handleInitialSync()

                } catch (e: Exception) {
                    Log.e(TAG, "Error initializing ViewModel", e)
                    _error.value = "Помилка ініціалізації: ${e.message}"
                }
            }
        }
    }

    private suspend fun loadInitialDataSynchronously() {
        if (isCleanedUp.get()) return

        try {
            _isLoading.value = true
            val realm = app.getRealmInstance()

            // Load workers first (they're needed for assignment display)
            val workers = realm.query<Worker>().find().toList()
            _allWorkers.value = workers
            val workerMap = workers.associateBy { it._id }
            _workerDetails.value = workerMap
            Log.d(TAG, "Pre-loaded ${workers.size} workers for UI")

            // Load assignments
            val assignments = realm.query<Assignment>("isDeleted == false").find()
            val groupedAssignments = assignments
                .groupBy { it.rowNumber }
                .map { (rowNumber, assignmentList) ->
                    AssignmentGroup(rowNumber, assignmentList.toList())
                }
                .sortedBy { it.rowNumber }

            // Update state atomically
            dataCache = groupedAssignments
            _assignments.value = groupedAssignments
            _dataInitialized.value = true
            _realmInitialized.value = true

            Log.d(TAG, "Initial data loaded: ${groupedAssignments.size} assignment groups")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading initial data", e)
            _error.value = "Помилка завантаження даних: ${e.message}"
        } finally {
            _isLoading.value = false
        }
    }

    private fun setupObservers() {
        if (isCleanedUp.get()) return

        // Observe assignments with sync protection
        observeAssignmentsWithSyncProtection()

        // Observe workers
        observeWorkers()

        // Observe connection state
        observeConnectionState()
    }

    private fun observeAssignmentsWithSyncProtection() {
        viewModelScope.launch {
            try {
                assignmentRepository.getAllGroupedByRow()
                    .distinctUntilChanged()
                    .collect { result ->
                        if (isCleanedUp.get()) return@collect

                        when (result) {
                            is Result.Success -> {
                                val newData = result.data

                                // Only update if we're not syncing and data actually changed
                                if (!isSyncInProgress.get()) {
                                    if (newData != _assignments.value) {
                                        dataCache = newData
                                        _assignments.value = newData
                                        updateWorkerDetails(newData)
                                        Log.d(TAG, "Updated assignments: ${newData.size} groups")
                                    }
                                } else {
                                    // During sync, update cache but don't clear UI
                                    if (newData.isNotEmpty()) {
                                        dataCache = newData
                                    }
                                    Log.d(TAG, "Sync in progress, cached ${newData.size} groups")
                                }

                                _error.value = null
                            }

                            is Result.Error -> {
                                if (!isSyncInProgress.get()) {
                                    Log.e(TAG, "Error loading assignments", result.exception)
                                    _error.value = "Помилка завантаження даних: ${result.message}"
                                }
                            }

                            is Result.Loading -> {
                                if (!_dataInitialized.value) {
                                    _isLoading.value = true
                                }
                            }
                        }
                    }
            } catch (e: CancellationException) {
                Log.d(TAG, "Assignment observation cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error observing assignments", e)
            }
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
                            _allWorkers.value = workers
                            val workerMap = workers.associateBy { it._id }
                            _workerDetails.value = workerMap
                        }
                        is Result.Error -> {
                            Log.e(TAG, "Error loading workers", result.exception)
                            _error.value = "Помилка завантаження працівників: ${result.message}"
                        }
                        is Result.Loading -> {
                            // Loading handled elsewhere
                        }
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Worker observation cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error observing workers", e)
            }
        }
    }

    private fun observeConnectionState() {
        if (isCleanedUp.get()) return

        viewModelScope.launch {
            try {
                networkStatusManager.connectionState.collect { state ->
                    if (isCleanedUp.get()) return@collect

                    _connectionState.value = state

                    // Only sync after initial data is loaded
                    if (state is ConnectionState.Connected && _dataInitialized.value) {
                        Log.d(TAG, "Network available, scheduling sync")
                        scheduleDelayedSync()
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Connection state observation cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error observing connection state", e)
            }
        }
    }

    private fun handleInitialSync() {
        if (isCleanedUp.get()) return

        viewModelScope.launch {
            // Only sync if we have network and haven't synced recently
            if (networkStatusManager.connectionState.value is ConnectionState.Connected) {
                // Delay initial sync to avoid interfering with UI loading
                delay(2000)
                performProtectedSync()
            }
        }
    }

    private fun scheduleDelayedSync() {
        viewModelScope.launch {
            // Small delay to avoid immediate sync interference
            delay(1000)
            performProtectedSync()
        }
    }

    private suspend fun performProtectedSync() {
        if (isCleanedUp.get() || isSyncInProgress.getAndSet(true)) return

        try {
            Log.d(TAG, "Starting protected sync")

            // Use global sync manager
            app.syncManager.performSync(silent = true)

            // After sync, restore UI if needed
            restoreUIAfterSync()

        } catch (e: Exception) {
            Log.e(TAG, "Error during sync", e)
        } finally {
            isSyncInProgress.set(false)
        }
    }

    private suspend fun restoreUIAfterSync() {
        // If UI got cleared during sync, restore from cache
        if (_assignments.value.isEmpty() && dataCache.isNotEmpty()) {
            _assignments.value = dataCache
            Log.d(TAG, "Restored UI from cache: ${dataCache.size} groups")
        }
    }

    private fun updateWorkerDetails(assignmentGroups: List<AssignmentGroup>) {
        if (isCleanedUp.get()) return

        viewModelScope.launch {
            try {
                val workerIds = assignmentGroups
                    .flatMap { it.assignments }
                    .map { it.workerId }
                    .distinct()

                if (workerIds.isEmpty()) return@launch

                // Only load if we don't have all workers
                val currentWorkers = _workerDetails.value
                val missingWorkerIds = workerIds.filter { !currentWorkers.containsKey(it) }

                if (missingWorkerIds.isNotEmpty()) {
                    workerRepository.getWorkersByIds(missingWorkerIds).collect { result ->
                        if (isCleanedUp.get()) return@collect

                        when (result) {
                            is Result.Success -> {
                                val newWorkers = result.data.associateBy { it._id }
                                _workerDetails.value = currentWorkers + newWorkers
                            }
                            is Result.Error -> {
                                Log.e(TAG, "Error loading worker details", result.exception)
                            }
                            is Result.Loading -> {
                                // Loading handled elsewhere
                            }
                        }
                        return@collect
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating worker details", e)
            }
        }
    }

    // Remove the old loadInitialData() method and replace with this
    fun ensureDataLoaded() {
        if (!_dataInitialized.value && !isCleanedUp.get()) {
            viewModelScope.launch {
                loadInitialDataSynchronously()
            }
        }
    }

    fun ensureLoadingStateReset() {
        viewModelScope.launch {
            if (_isLoading.value && !isCleanedUp.get()) {
                delay(300)
                _isLoading.value = false
                Log.d(TAG, "Force reset loading state")
            }
        }
    }

    // Keep all your existing CRUD operations unchanged
    suspend fun assignWorkerToRow(workerId: String, rowNumber: Int): Result<String> {
        if (isCleanedUp.get()) return Result.Error(Exception("ViewModel is cleaned up"))

        return operationMutex.withLock {
            _isLoading.value = true

            try {
                val result = assignmentRepository.assignWorkerToRow(workerId, rowNumber)
                when (result) {
                    is Result.Success -> {
                        Log.d(TAG, "Worker assigned successfully")
                        Result.Success(result.data)
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

    suspend fun moveWorkerToRow(assignmentId: String, newRowNumber: Int): Result<Boolean> {
        if (isCleanedUp.get()) return Result.Error(Exception("ViewModel is cleaned up"))

        return operationMutex.withLock {
            _isLoading.value = true

            try {
                val result = assignmentRepository.moveWorkerToRow(assignmentId, newRowNumber)
                when (result) {
                    is Result.Success -> {
                        Log.d(TAG, "Worker moved successfully")
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

    suspend fun deleteWorkerAssignment(assignmentId: String): Result<Boolean> {
        if (isCleanedUp.get()) return Result.Error(Exception("ViewModel is cleaned up"))

        return operationMutex.withLock {
            _isLoading.value = true

            try {
                val result = assignmentRepository.delete(assignmentId)
                when (result) {
                    is Result.Success -> {
                        Log.d(TAG, "Assignment deleted successfully")
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

    suspend fun deleteEntireRow(rowNumber: Int): Result<Boolean> {
        if (isCleanedUp.get()) return Result.Error(Exception("ViewModel is cleaned up"))

        return operationMutex.withLock {
            _isLoading.value = true

            try {
                val result = assignmentRepository.completelyDeleteRow(rowNumber)
                when (result) {
                    is Result.Success -> {
                        Log.d(TAG, "Row deleted successfully")
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

    suspend fun checkWorkerAssignment(workerId: String): Result<Int> {
        if (isCleanedUp.get()) return Result.Error(Exception("ViewModel is cleaned up"))

        return try {
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

    suspend fun deleteAllRows(): Result<Boolean> {
        if (isCleanedUp.get()) return Result.Error(Exception("ViewModel is cleaned up"))

        return operationMutex.withLock {
            _isLoading.value = true

            try {
                val realm = app.getRealmInstance()
                val assignments = realm.query<Assignment>().find()

                if (assignments.isEmpty()) {
                    _isLoading.value = false
                    return@withLock Result.Success(true)
                }

                app.safeWriteTransaction {
                    val liveAssignments = query<Assignment>().find()
                    delete(liveAssignments)
                    Log.d(TAG, "Deleted all assignments")
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

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        isCleanedUp.set(true)
        Log.d(TAG, "ViewModel cleared")
    }
}