package com.example.berryharvest.ui.payment

import android.app.Application
import android.icu.text.SimpleDateFormat
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.data.model.PaymentRecord
import com.example.berryharvest.data.model.Worker
import com.example.berryharvest.data.repository.ConnectionState
import com.example.berryharvest.data.repository.Result
import io.realm.kotlin.ext.query
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class PaymentViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "PaymentViewModel"
    private val app: BerryHarvestApplication = getApplication() as BerryHarvestApplication
    private val paymentRepository = app.repositoryProvider.paymentRepository
    private val workerRepository = app.repositoryProvider.workerRepository
    private val settingsRepository = app.repositoryProvider.settingsRepository
    private val networkStatusManager = app.networkStatusManager

    // Mutex for thread-safe operations
    private val operationMutex = Mutex()
    private val dataLoadMutex = Mutex()

    // Atomic flags for state management
    private val isInitialized = AtomicBoolean(false)
    private val isCleanedUp = AtomicBoolean(false)
    private val isLoadingWorkerData = AtomicBoolean(false)

    // Worker data
    private val _selectedWorker = MutableStateFlow<Worker?>(null)
    val selectedWorker: StateFlow<Worker?> = _selectedWorker.asStateFlow()

    private val _allWorkers = MutableStateFlow<List<Worker>>(emptyList())
    val allWorkers: StateFlow<List<Worker>> = _allWorkers.asStateFlow()

    // Payment data
    private val _workerBalance = MutableStateFlow(0.0f)
    val workerBalance: StateFlow<Float> = _workerBalance.asStateFlow()

    private val _todayPunnetCount = MutableStateFlow(0)
    val todayPunnetCount: StateFlow<Int> = _todayPunnetCount.asStateFlow()

    private val _paymentHistory = MutableStateFlow<List<PaymentRecord>>(emptyList())
    val paymentHistory: StateFlow<List<PaymentRecord>> = _paymentHistory.asStateFlow()

    private val _workerEarnings = MutableStateFlow(0.0f)
    val workerEarnings: StateFlow<Float> = _workerEarnings.asStateFlow()

    // UI state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _success = MutableStateFlow<String?>(null)
    val success: StateFlow<String?> = _success.asStateFlow()

    // Connection state
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Current punnet price
    private val _punnetPrice = MutableStateFlow(0.0f)
    val punnetPrice: StateFlow<Float> = _punnetPrice.asStateFlow()

    // Data initialization state
    private val _dataInitialized = MutableStateFlow(false)
    val dataInitialized: StateFlow<Boolean> = _dataInitialized.asStateFlow()

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
                isInitialized.set(false) // Reset on error
                Log.e(TAG, "Error initializing ViewModel", e)
                _error.value = "Помилка ініціалізації: ${e.message}"
            }
        }
    }

    private suspend fun loadInitialDataSynchronously() {
        if (isCleanedUp.get()) return

        try {
            _isLoading.value = true

            // Load workers and settings synchronously
            val workersDeferred = viewModelScope.async { loadWorkersSync() }
            val priceDeferred = viewModelScope.async { loadPunnetPriceSync() }

            // Wait for both to complete
            awaitAll(workersDeferred, priceDeferred)

            _dataInitialized.value = true
            Log.d(TAG, "Initial data loaded successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading initial data", e)
            _error.value = "Помилка завантаження даних: ${e.message}"
        } finally {
            _isLoading.value = false
        }
    }

    private suspend fun loadWorkersSync(): List<Worker> {
        return try {
            val realm = app.getRealmInstance()
            val workers = realm.query<Worker>().find().toList()
            _allWorkers.value = workers
            Log.d(TAG, "Loaded ${workers.size} workers synchronously")
            workers
        } catch (e: Exception) {
            Log.e(TAG, "Error loading workers sync", e)
            emptyList()
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

    private fun setupObservers() {
        if (isCleanedUp.get()) return

        // Observe workers with live updates
        observeWorkers()

        // Observe connection state
        observeConnectionState()
    }

    private fun observeWorkers() {
        viewModelScope.launch {
            try {
                workerRepository.getAll()
                    .distinctUntilChanged()
                    .collect { result ->
                        if (isCleanedUp.get()) return@collect

                        when (result) {
                            is Result.Success -> {
                                if (result.data != _allWorkers.value) {
                                    _allWorkers.value = result.data
                                    Log.d(TAG, "Workers updated: ${result.data.size}")
                                }
                            }
                            is Result.Error -> {
                                Log.e(TAG, "Error observing workers", result.exception)
                                if (!_dataInitialized.value) {
                                    _error.value = "Failed to load workers: ${result.message}"
                                }
                            }
                            is Result.Loading -> {
                                if (!_dataInitialized.value) {
                                    _isLoading.value = true
                                }
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up worker observer", e)
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            try {
                networkStatusManager.connectionState.collect { state ->
                    if (isCleanedUp.get()) return@collect

                    _connectionState.value = state

                    if (state is ConnectionState.Connected && paymentRepository.hasPendingOperations()) {
                        scheduleDelayedSync()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing connection state", e)
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
            // Delay to avoid interfering with UI loading
            kotlinx.coroutines.delay(2000)
            syncPendingChanges()
        }
    }

    private fun syncPendingChanges() {
        viewModelScope.launch {
            try {
                app.syncManager.performSync(silent = true)
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing changes", e)
            }
        }
    }

    fun selectWorker(worker: Worker) {
        viewModelScope.launch {
            dataLoadMutex.withLock {
                if (isLoadingWorkerData.getAndSet(true)) {
                    Log.d(TAG, "Worker data loading already in progress")
                    return@withLock
                }

                try {
                    _selectedWorker.value = worker
                    _isLoading.value = true

                    // Load all worker data concurrently but safely
                    loadWorkerDataConcurrently(worker._id)

                } catch (e: Exception) {
                    Log.e(TAG, "Error selecting worker", e)
                    _error.value = "Помилка завантаження даних працівника: ${e.message}"
                } finally {
                    _isLoading.value = false
                    isLoadingWorkerData.set(false)
                }
            }
        }
    }

    private suspend fun loadWorkerDataConcurrently(workerId: String) {
        try {
            // Launch all operations concurrently but wait for all to complete
            val balanceDeferred = viewModelScope.async { loadWorkerBalanceSafe(workerId) }
            val earningsDeferred = viewModelScope.async { loadWorkerEarningsSafe(workerId) }
            val punnetsDeferred = viewModelScope.async { loadTodayPunnetCountSafe(workerId) }
            val historyDeferred = viewModelScope.async { loadPaymentHistorySafe(workerId) }

            // Wait for all operations to complete
            val results = awaitAll(balanceDeferred, earningsDeferred, punnetsDeferred, historyDeferred)

            // Update UI with results
            _workerBalance.value = results[0] as Float
            _workerEarnings.value = results[1] as Float
            _todayPunnetCount.value = results[2] as Int
            _paymentHistory.value = results[3] as List<PaymentRecord>

            Log.d(TAG, "Worker data loaded successfully")

            // Check for balance discrepancies after all data is loaded
            checkBalanceDiscrepancy()

        } catch (e: Exception) {
            Log.e(TAG, "Error loading worker data concurrently", e)
            _error.value = "Помилка завантаження даних працівника: ${e.message}"
        }
    }

    private suspend fun loadWorkerBalanceSafe(workerId: String): Float {
        return try {
            when (val result = paymentRepository.getWorkerBalance(workerId)) {
                is Result.Success -> result.data
                is Result.Error -> {
                    handleSubscriptionError(result)
                    0.0f
                }
                is Result.Loading -> 0.0f
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading worker balance", e)
            handleSubscriptionError(e)
            0.0f
        }
    }

    private suspend fun loadWorkerEarningsSafe(workerId: String): Float {
        return try {
            when (val result = paymentRepository.getWorkerEarnings(workerId)) {
                is Result.Success -> result.data
                is Result.Error -> {
                    Log.e(TAG, "Error loading worker earnings", result.exception)
                    0.0f
                }
                is Result.Loading -> 0.0f
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading worker earnings", e)
            0.0f
        }
    }

    private suspend fun loadTodayPunnetCountSafe(workerId: String): Int {
        return try {
            when (val result = paymentRepository.getTodayPunnetCount(workerId)) {
                is Result.Success -> result.data
                is Result.Error -> {
                    Log.e(TAG, "Error loading today's punnet count", result.exception)
                    0
                }
                is Result.Loading -> 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading today's punnet count", e)
            0
        }
    }

    private suspend fun loadPaymentHistorySafe(workerId: String): List<PaymentRecord> {
        return try {
            // Use first() to get a single emission instead of collect
            when (val result = paymentRepository.getPaymentRecordsForWorker(workerId).distinctUntilChanged().take(1).first()) {
                is Result.Success -> result.data
                is Result.Error -> {
                    Log.e(TAG, "Error loading payment history", result.exception)
                    emptyList()
                }
                is Result.Loading -> emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading payment history", e)
            emptyList()
        }
    }

    private suspend fun handleSubscriptionError(error: Any) {
        val message = when (error) {
            is Result.Error -> error.message ?: "Unknown error"
            is Exception -> error.message ?: "Unknown exception"
            else -> error.toString()
        }

        if (message.contains("NO_SUBSCRIPTION_FOR_WRITE") || message.contains("subscription")) {
            try {
                app.ensureSubscriptions()
                _error.value = "Виявлено проблему з підпискою. Спробуйте ще раз через мить."
            } catch (e: Exception) {
                Log.e(TAG, "Error ensuring subscriptions", e)
                _error.value = "Помилка з підпискою: ${e.message}"
            }
        }
    }

    /**
     * Check if there's a discrepancy between stored and calculated balance
     */
    fun checkBalanceDiscrepancy() {
        val worker = _selectedWorker.value ?: return

        viewModelScope.launch {
            try {
                val earnings = _workerEarnings.value
                val balance = _workerBalance.value
                val payments = _paymentHistory.value.sumOf { it.amount.toDouble() }.toFloat()

                // Calculate expected balance
                val expectedBalance = earnings - payments

                // If there's a significant difference (more than 0.01)
                if (Math.abs(expectedBalance - balance) > 0.01f) {
                    Log.w(TAG, "Balance discrepancy detected: stored=$balance, calculated=$expectedBalance")
                    _error.value = "Виявлено розбіжність у балансі. Спроба виправлення..."

                    // Try to fix by recalculating
                    val newBalance = loadWorkerBalanceSafe(worker._id)
                    _workerBalance.value = newBalance
                    _success.value = "Баланс оновлено"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking balance discrepancy", e)
            }
        }
    }

    fun makeFullPayment(notes: String) {
        val worker = _selectedWorker.value ?: return
        val balance = _workerBalance.value

        if (balance <= 0) {
            _error.value = "No payment needed - balance is zero or negative"
            return
        }

        makePayment(worker._id, balance, notes)
    }

    fun makePartialPayment(amount: Float, notes: String) {
        val worker = _selectedWorker.value ?: return
        val balance = _workerBalance.value

        if (amount <= 0) {
            _error.value = "Payment amount must be positive"
            return
        }

        if (amount > balance) {
            _error.value = "Payment amount cannot exceed balance"
            return
        }

        makePayment(worker._id, amount, notes)
    }

    private fun makePayment(workerId: String, amount: Float, notes: String) {
        viewModelScope.launch {
            operationMutex.withLock {
                _isLoading.value = true
                try {
                    val result = paymentRepository.recordPayment(workerId, amount, notes)

                    when (result) {
                        is Result.Success -> {
                            _success.value = "Payment recorded successfully"
                            // Refresh worker data
                            refreshWorkerData(workerId)
                        }
                        is Result.Error -> {
                            _error.value = "Failed to record payment: ${result.message}"
                            Log.e(TAG, "Error recording payment", result.exception)
                        }
                        is Result.Loading -> {
                            // Loading handled by progress bar
                        }
                    }
                } catch (e: Exception) {
                    _error.value = "Error recording payment: ${e.message}"
                    Log.e(TAG, "Error recording payment", e)
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    private suspend fun refreshWorkerData(workerId: String) {
        try {
            // Reload only the data that changed
            val newBalance = loadWorkerBalanceSafe(workerId)
            val newHistory = loadPaymentHistorySafe(workerId)

            _workerBalance.value = newBalance
            _paymentHistory.value = newHistory

            Log.d(TAG, "Worker data refreshed after payment")
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing worker data", e)
        }
    }

    fun formatDate(timestamp: Long): String {
        val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        return dateFormat.format(Date(timestamp))
    }

    fun clearError() {
        _error.value = null
    }

    fun clearSuccess() {
        _success.value = null
    }

    fun clearSelection() {
        _selectedWorker.value = null
        _workerBalance.value = 0.0f
        _workerEarnings.value = 0.0f
        _todayPunnetCount.value = 0
        _paymentHistory.value = emptyList()
        isLoadingWorkerData.set(false)
    }

    fun ensureDataLoaded() {
        if (!_dataInitialized.value && !isCleanedUp.get()) {
            viewModelScope.launch {
                loadInitialDataSynchronously()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        isCleanedUp.set(true)
        Log.d(TAG, "ViewModel cleared")
    }
}