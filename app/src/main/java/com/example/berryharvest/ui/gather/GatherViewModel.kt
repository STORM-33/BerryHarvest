package com.example.berryharvest.ui.gather

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.data.model.Assignment
import com.example.berryharvest.data.model.Gather
import com.example.berryharvest.data.model.Worker
import com.example.berryharvest.data.repository.ConnectionState
import com.example.berryharvest.data.repository.Result
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class GatherViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "GatherViewModel"
    private val app: BerryHarvestApplication = getApplication() as BerryHarvestApplication

    private val workerRepository = app.repositoryProvider.workerRepository
    private val assignmentRepository = app.repositoryProvider.assignmentRepository
    private val gatherRepository = app.repositoryProvider.gatherRepository
    private val settingsRepository = app.repositoryProvider.settingsRepository
    private val networkStatusManager = app.networkStatusManager

    // Mutex for thread-safe operations
    private val operationMutex = Mutex()
    private val dataLoadMutex = Mutex()

    // Atomic flags for state management
    private val isInitialized = AtomicBoolean(false)
    private val isCleanedUp = AtomicBoolean(false)

    // StateFlow for consistent reactive pattern
    private val _punnetPrice = MutableStateFlow(0.0f)
    val punnetPrice: StateFlow<Float> = _punnetPrice.asStateFlow()

    private val _recentGathers = MutableStateFlow<List<GatherWithDetails>>(emptyList())
    val recentGathers: StateFlow<List<GatherWithDetails>> = _recentGathers.asStateFlow()

    private val _todayStats = MutableStateFlow(TodayStats(0, 0f))
    val todayStats: StateFlow<TodayStats> = _todayStats.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _unsyncedCount = MutableStateFlow(0)
    val unsyncedCount: StateFlow<Int> = _unsyncedCount.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _dataInitialized = MutableStateFlow(false)
    val dataInitialized: StateFlow<Boolean> = _dataInitialized.asStateFlow()

    // LiveData for compatibility with existing UI (will migrate these gradually)
    private val _workerAssignment = MutableLiveData<Pair<Worker, Int>?>()
    val workerAssignment: LiveData<Pair<Worker, Int>?> = _workerAssignment

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _successMessage = MutableLiveData<Boolean>()
    val successMessage: LiveData<Boolean> = _successMessage

    // Cache for efficient data access
    private var workersCache = mapOf<String, Worker>()
    private var lastDataRefresh = 0L

    init {
        initializeViewModel()
    }

    private fun initializeViewModel() {
        viewModelScope.launch {
            if (isInitialized.getAndSet(true)) {
                Log.d(TAG, "ViewModel already initialized, skipping")
                return@launch
            }

            try {
                // Load initial data synchronously
                loadInitialDataSynchronously()

                // Set up observers
                setupObservers()

                // Handle sync after initialization
                handleInitialSync()

            } catch (e: Exception) {
                isInitialized.set(false)
                Log.e(TAG, "Error initializing ViewModel", e)
                _errorMessage.postValue("Помилка ініціалізації: ${e.message}")
            }
        }
    }

    private suspend fun loadInitialDataSynchronously() {
        if (isCleanedUp.get()) return

        try {
            _isLoading.value = true

            // Load all initial data concurrently
            val priceDeferred = viewModelScope.async { loadPunnetPriceSync() }
            val workersDeferred = viewModelScope.async { loadWorkersSync() }
            val gathersDeferred = viewModelScope.async { loadGathersSync() }
            val statsDeferred = viewModelScope.async { calculateStatsSync() }
            val unsyncedDeferred = viewModelScope.async { countUnsyncedSync() }

            // Wait for all to complete
            awaitAll(priceDeferred, workersDeferred, gathersDeferred, statsDeferred, unsyncedDeferred)

            _dataInitialized.value = true
            Log.d(TAG, "Initial data loaded successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading initial data", e)
            _errorMessage.postValue("Помилка завантаження даних: ${e.message}")
        } finally {
            _isLoading.value = false
        }
    }

    private suspend fun loadPunnetPriceSync(): Float {
        return try {
            val price = settingsRepository.getPunnetPrice()
            _punnetPrice.value = price
            Log.d(TAG, "Loaded punnet price: $price")
            price
        } catch (e: Exception) {
            Log.e(TAG, "Error loading punnet price sync", e)
            0.0f
        }
    }

    private suspend fun loadWorkersSync(): Map<String, Worker> {
        return try {
            // Get workers directly for caching
            when (val result = workerRepository.getAll().first()) {
                is Result.Success -> {
                    val workers = result.data.associateBy { it._id }
                    workersCache = workers
                    Log.d(TAG, "Loaded ${workers.size} workers for cache")
                    workers
                }
                else -> {
                    Log.w(TAG, "Failed to load workers for cache")
                    emptyMap()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading workers sync", e)
            emptyMap()
        }
    }

    private suspend fun loadGathersSync(): List<GatherWithDetails> {
        return try {
            // Get today's gathers using repository
            when (val result = gatherRepository.getTodayGathers()) {
                is Result.Success -> {
                    // result.data is already List<GatherWithDetails>
                    val gathersWithDetails = result.data

                    // Sort by dateTime in descending order (newest first)
                    val sortedGathers = gathersWithDetails.sortedByDescending { gatherWithDetails ->
                        try {
                            // Parse the dateTime string to get a comparable date
                            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            gatherWithDetails.gather.dateTime?.let { dateTimeStr ->
                                dateFormat.parse(dateTimeStr)?.time ?: 0L
                            } ?: 0L
                        } catch (e: Exception) {
                            Log.w(TAG, "Error parsing dateTime: ${gatherWithDetails.gather.dateTime}", e)
                            0L // If parsing fails, put it at the end
                        }
                    }

                    // Update the recent gathers with sorted data
                    _recentGathers.value = sortedGathers
                    Log.d(TAG, "Loaded and sorted ${sortedGathers.size} today's gathers by date")
                    sortedGathers
                }
                else -> {
                    Log.w(TAG, "Failed to load today's gathers")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading gathers sync", e)
            emptyList()
        }
    }

    private suspend fun calculateStatsSync(): TodayStats {
        return try {
            val gathers = _recentGathers.value
            val totalPunnets = gathers.sumOf { it.gather.numOfPunnets ?: 0 }
            val totalAmount = gathers.sumOf {
                val punnets = it.gather.numOfPunnets ?: 0
                val cost = it.gather.punnetCost ?: 0.0f
                (punnets * cost).toDouble()
            }.toFloat()

            val stats = TodayStats(totalPunnets, totalAmount)
            _todayStats.value = stats
            Log.d(TAG, "Calculated today stats: $stats")
            stats
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating stats sync", e)
            TodayStats(0, 0f)
        }
    }

    private suspend fun countUnsyncedSync(): Int {
        return try {
            // Count unsynced gathers from current data
            val count = _recentGathers.value.count { gatherWithDetails ->
                // Since isSynced is Boolean?, we need to handle null
                gatherWithDetails.gather.isSynced != true
            }
            _unsyncedCount.value = count
            Log.d(TAG, "Counted $count unsynced gathers")
            count
        } catch (e: Exception) {
            Log.e(TAG, "Error counting unsynced sync", e)
            0
        }
    }

    private fun setupObservers() {
        if (isCleanedUp.get()) return

        // Observe price changes
        observePunnetPrice()

        // Observe connection state
        observeConnectionState()

        // Observe gather changes
        observeGatherChanges()
    }

    private fun observePunnetPrice() {
        viewModelScope.launch {
            try {
                settingsRepository.punnetPriceFlow
                    .collect { newPrice ->
                        if (isCleanedUp.get()) return@collect

                        if (newPrice != _punnetPrice.value) {
                            _punnetPrice.value = newPrice
                            Log.d(TAG, "Price updated from repository: $newPrice")
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing punnet price", e)
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            try {
                networkStatusManager.connectionState.collect { state ->
                    if (isCleanedUp.get()) return@collect

                    _connectionState.value = state

                    if (state is ConnectionState.Connected) {
                        scheduleDelayedSync()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing connection state", e)
            }
        }
    }

    private fun observeGatherChanges() {
        viewModelScope.launch {
            try {
                // Observe gather repository changes if available
                // For now, we'll rely on manual refresh after operations
                Log.d(TAG, "Gather change observation setup complete")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up gather observation", e)
            }
        }
    }

    private fun handleInitialSync() {
        viewModelScope.launch {
            if (networkStatusManager.connectionState.value is ConnectionState.Connected) {
                scheduleDelayedSync()
            }
        }
    }

    private fun scheduleDelayedSync() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            syncPendingChanges()
        }
    }

    fun syncPendingChanges() {
        viewModelScope.launch {
            try {
                when (val result = app.syncManager.performSync(silent = true)) {
                    is Result.Success -> {
                        refreshData()
                        _successMessage.postValue(true)
                    }
                    is Result.Error -> {
                        _errorMessage.postValue("Синхронізація не вдалася: ${result.message}")
                    }
                    is Result.Loading -> {
                        // Handle loading if needed
                    }
                }
            } catch (e: Exception) {
                _errorMessage.postValue("Помилка синхронізації: ${e.message}")
            }
        }
    }

    fun handleScanResult(qrContent: String) {
        viewModelScope.launch {
            dataLoadMutex.withLock {
                _isLoading.value = true

                try {
                    // Get worker by QR code
                    val workerResult = workerRepository.getWorkerByQrCode(qrContent)

                    when (workerResult) {
                        is Result.Success -> {
                            val worker = workerResult.data
                            if (worker != null) {
                                // Check assignment
                                val assignmentResult = assignmentRepository.getWorkerAssignment(worker._id)

                                when (assignmentResult) {
                                    is Result.Success -> {
                                        val assignment = assignmentResult.data
                                        val rowNumber = assignment?.rowNumber ?: -1
                                        _workerAssignment.postValue(Pair(worker, rowNumber))
                                    }
                                    is Result.Error -> {
                                        _errorMessage.postValue("Помилка перевірки призначення: ${assignmentResult.message}")
                                    }
                                    is Result.Loading -> {
                                        // Loading handled by progress bar
                                    }
                                }
                            } else {
                                _errorMessage.postValue("Працівника не знайдено за QR кодом")
                            }
                        }
                        is Result.Error -> {
                            _errorMessage.postValue("Помилка сканування: ${workerResult.message}")
                        }
                        is Result.Loading -> {
                            // Loading handled by progress bar
                        }
                    }
                } catch (e: Exception) {
                    _errorMessage.postValue("Помилка: ${e.message}")
                    Log.e(TAG, "Error handling scan", e)
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    fun saveGatherData(workerId: String, rowNumber: Int, numOfPunnets: Int) {
        viewModelScope.launch {
            operationMutex.withLock {
                _isLoading.value = true

                try {
                    val currentPrice = _punnetPrice.value
                    val result = gatherRepository.recordGather(workerId, rowNumber, numOfPunnets, currentPrice)

                    when (result) {
                        is Result.Success -> {
                            _successMessage.postValue(true)
                            refreshDataAfterOperation()
                        }
                        is Result.Error -> {
                            _errorMessage.postValue("Помилка збереження даних: ${result.message}")
                        }
                        is Result.Loading -> {
                            // Loading handled by progress bar
                        }
                    }
                } catch (e: Exception) {
                    _errorMessage.postValue("Помилка: ${e.message}")
                    Log.e(TAG, "Error saving gather", e)
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    fun updateGatherDetails(gatherId: String, numOfPunnets: Int) {
        viewModelScope.launch {
            operationMutex.withLock {
                _isLoading.value = true

                try {
                    val result = gatherRepository.updateGatherDetails(gatherId, numOfPunnets)

                    when (result) {
                        is Result.Success -> {
                            _successMessage.postValue(true)
                            refreshDataAfterOperation()
                        }
                        is Result.Error -> {
                            _errorMessage.postValue("Помилка оновлення даних: ${result.message}")
                        }
                        is Result.Loading -> {
                            // Loading handled by progress bar
                        }
                    }
                } catch (e: Exception) {
                    _errorMessage.postValue("Помилка: ${e.message}")
                    Log.e(TAG, "Error updating gather", e)
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    fun deleteGather(gatherId: String) {
        viewModelScope.launch {
            operationMutex.withLock {
                _isLoading.value = true

                try {
                    val result = gatherRepository.delete(gatherId)

                    when (result) {
                        is Result.Success -> {
                            _successMessage.postValue(true)
                            refreshDataAfterOperation()
                        }
                        is Result.Error -> {
                            _errorMessage.postValue("Помилка видалення даних: ${result.message}")
                        }
                        is Result.Loading -> {
                            // Loading handled by progress bar
                        }
                    }
                } catch (e: Exception) {
                    _errorMessage.postValue("Помилка: ${e.message}")
                    Log.e(TAG, "Error deleting gather", e)
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    fun updatePunnetPrice(newPrice: Float) {
        if (newPrice < 0) {
            _errorMessage.postValue("Ціна не може бути від'ємною")
            return
        }

        viewModelScope.launch {
            try {
                when (val result = settingsRepository.updatePunnetPrice(newPrice)) {
                    is Result.Success -> {
                        Log.d(TAG, "Price update requested successfully")
                    }
                    is Result.Error -> {
                        _errorMessage.postValue("Помилка при оновленні ціни: ${result.message}")
                    }
                    is Result.Loading -> {
                        // Handle loading if needed
                    }
                }
            } catch (e: Exception) {
                _errorMessage.postValue("Помилка при оновленні ціни: ${e.message}")
            }
        }
    }

    fun assignWorkerToRow(workerId: String, rowNumber: Int): LiveData<Boolean> {
        val result = MutableLiveData<Boolean>()

        viewModelScope.launch {
            operationMutex.withLock {
                _isLoading.value = true

                try {
                    val assignment = Assignment().apply {
                        _id = UUID.randomUUID().toString()
                        this.workerId = workerId
                        this.rowNumber = rowNumber
                        isSynced = networkStatusManager.isNetworkAvailable()
                    }

                    when (val assignResult = assignmentRepository.add(assignment)) {
                        is Result.Success -> {
                            result.postValue(true)
                        }
                        is Result.Error -> {
                            _errorMessage.postValue("Помилка при призначенні працівника: ${assignResult.message}")
                            result.postValue(false)
                        }
                        is Result.Loading -> {
                            // Loading handled by progress bar
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error assigning worker to row", e)
                    _errorMessage.postValue("Помилка при призначенні працівника: ${e.message}")
                    result.postValue(false)
                } finally {
                    _isLoading.value = false
                }
            }
        }

        return result
    }

    fun getGatherById(gatherId: String): LiveData<Gather?> {
        val result = MutableLiveData<Gather?>()

        viewModelScope.launch {
            try {
                when (val gatherResult = gatherRepository.getById(gatherId)) {
                    is Result.Success -> {
                        result.postValue(gatherResult.data)
                    }
                    is Result.Error -> {
                        Log.e(TAG, "Error getting gather by ID", gatherResult.exception)
                        result.postValue(null)
                    }
                    is Result.Loading -> {
                        // Loading handled elsewhere
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting gather by ID", e)
                result.postValue(null)
            }
        }

        return result
    }

    fun refreshData() {
        viewModelScope.launch {
            dataLoadMutex.withLock {
                try {
                    // Avoid too frequent refreshes
                    val now = System.currentTimeMillis()
                    if (now - lastDataRefresh < 1000) { // 1 second minimum between refreshes
                        Log.d(TAG, "Skipping refresh, too soon since last refresh")
                        return@withLock
                    }
                    lastDataRefresh = now

                    loadInitialDataSynchronously()
                } catch (e: Exception) {
                    Log.e(TAG, "Error refreshing data", e)
                }
            }
        }
    }

    private suspend fun refreshDataAfterOperation() {
        try {
            // Quick refresh of essential data after operations
            val gathersDeferred = viewModelScope.async { loadGathersSync() }
            val statsDeferred = viewModelScope.async { calculateStatsSync() }
            val unsyncedDeferred = viewModelScope.async { countUnsyncedSync() }

            awaitAll(gathersDeferred, statsDeferred, unsyncedDeferred)
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing data after operation", e)
        }
    }

    fun ensureDataLoaded() {
        if (!_dataInitialized.value && !isCleanedUp.get()) {
            viewModelScope.launch {
                loadInitialDataSynchronously()
            }
        }
    }

    // Clear methods for UI
    fun clearErrorMessage() = _errorMessage.postValue(null)
    fun clearWorkerAssignment() = _workerAssignment.postValue(null)
    fun clearSuccessMessage() = _successMessage.postValue(false)

    override fun onCleared() {
        super.onCleared()
        isCleanedUp.set(true)
        Log.d(TAG, "ViewModel cleared")
    }
}

data class TodayStats(
    val totalPunnets: Int,
    val totalAmount: Float
)

data class GatherWithDetails(
    val gather: Gather,
    val workerName: String,
    val dateTime: String? = null
)