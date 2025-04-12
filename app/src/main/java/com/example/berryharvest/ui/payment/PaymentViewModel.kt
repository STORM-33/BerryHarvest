// app/src/main/java/com/example/berryharvest/ui/payment/PaymentViewModel.kt
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

class PaymentViewModel(application: Application) : AndroidViewModel(application) {
    private val app: BerryHarvestApplication = getApplication() as BerryHarvestApplication
    private val paymentRepository = app.repositoryProvider.paymentRepository
    private val workerRepository = app.repositoryProvider.workerRepository
    private val settingsRepository = app.repositoryProvider.settingsRepository
    private val networkStatusManager = app.networkStatusManager

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

    init {
        loadAllWorkers()
        observeConnectionState()
        loadPunnetPrice()
    }

    private fun loadPunnetPrice() {
        viewModelScope.launch {
            try {
                val price = settingsRepository.getPunnetPrice()
                _punnetPrice.value = price
            } catch (e: Exception) {
                Log.e("PaymentViewModel", "Error loading punnet price", e)
                _error.value = "Failed to load punnet price: ${e.message}"
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            networkStatusManager.connectionState.collect { state ->
                _connectionState.value = state

                // If back online, try to sync
                if (state is ConnectionState.Connected && paymentRepository.hasPendingOperations()) {
                    syncPendingChanges()
                }
            }
        }
    }

    private fun syncPendingChanges() {
        viewModelScope.launch {
            try {
                // Use the global sync manager
                app.syncManager.performSync(silent = true)
            } catch (e: Exception) {
                Log.e("PaymentViewModel", "Error syncing changes", e)
            }
        }
    }

    private fun loadAllWorkers() {
        viewModelScope.launch {
            _isLoading.value = true
            workerRepository.getAll().collect { result ->
                _isLoading.value = false
                when (result) {
                    is Result.Success -> {
                        _allWorkers.value = result.data
                    }
                    is Result.Error -> {
                        _error.value = "Failed to load workers: ${result.message}"
                        Log.e("PaymentViewModel", "Error loading workers", result.exception)
                    }
                    is Result.Loading -> {
                        _isLoading.value = true
                    }
                }
            }
        }
    }

    fun selectWorker(worker: Worker) {
        _selectedWorker.value = worker

        // Load data for this worker
        loadWorkerBalance(worker._id)
        loadWorkerEarnings(worker._id)
        loadTodayPunnetCount(worker._id)
        loadPaymentHistory(worker._id)
    }

    private fun loadWorkerBalance(workerId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = paymentRepository.getWorkerBalance(workerId)
                _isLoading.value = false

                when (result) {
                    is Result.Success -> {
                        _workerBalance.value = result.data
                    }
                    is Result.Error -> {
                        // Check if it's a subscription error
                        if (result.message.contains("NO_SUBSCRIPTION_FOR_WRITE") ||
                            result.message.contains("subscription")) {
                            // Try to ensure subscriptions
                            app.ensureSubscriptions()

                            // Still show the error but with a more helpful message
                            _error.value = "Subscription issue detected. Please try again in a moment."
                        } else {
                            _error.value = "Failed to load worker balance: ${result.message}"
                        }

                        Log.e("PaymentViewModel", "Error loading worker balance", result.exception)
                    }
                    is Result.Loading -> {
                        // Already handling loading state
                    }
                }
            } catch (e: Exception) {
                _isLoading.value = false

                // Check if it's a subscription error
                if (e.message?.contains("NO_SUBSCRIPTION_FOR_WRITE") == true ||
                    e.message?.contains("subscription") == true) {
                    // Try to ensure subscriptions
                    app.ensureSubscriptions()

                    // Show a more helpful error message
                    _error.value = "Subscription issue detected. Please try again in a moment."
                } else {
                    _error.value = "Error loading worker balance: ${e.message}"
                }

                Log.e("PaymentViewModel", "Error loading worker balance", e)
            }
        }
    }

    private fun loadWorkerEarnings(workerId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = paymentRepository.getWorkerEarnings(workerId)
                _isLoading.value = false

                when (result) {
                    is Result.Success -> {
                        _workerEarnings.value = result.data
                    }
                    is Result.Error -> {
                        _error.value = "Failed to load worker earnings: ${result.message}"
                        Log.e("PaymentViewModel", "Error loading worker earnings", result.exception)
                    }
                    is Result.Loading -> {
                        // Already handling loading state
                    }
                }
            } catch (e: Exception) {
                _isLoading.value = false
                _error.value = "Error loading worker earnings: ${e.message}"
                Log.e("PaymentViewModel", "Error loading worker earnings", e)
            }
        }
    }

    private fun loadTodayPunnetCount(workerId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = paymentRepository.getTodayPunnetCount(workerId)
                _isLoading.value = false

                when (result) {
                    is Result.Success -> {
                        _todayPunnetCount.value = result.data
                    }
                    is Result.Error -> {
                        _error.value = "Failed to load today's punnet count: ${result.message}"
                        Log.e("PaymentViewModel", "Error loading today's punnet count", result.exception)
                    }
                    is Result.Loading -> {
                        // Already handling loading state
                    }
                }
            } catch (e: Exception) {
                _isLoading.value = false
                _error.value = "Error loading today's punnet count: ${e.message}"
                Log.e("PaymentViewModel", "Error loading today's punnet count", e)
            }
        }
    }

    private fun loadPaymentHistory(workerId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            paymentRepository.getPaymentRecordsForWorker(workerId).collect { result ->
                _isLoading.value = false

                when (result) {
                    is Result.Success -> {
                        _paymentHistory.value = result.data
                    }
                    is Result.Error -> {
                        _error.value = "Failed to load payment history: ${result.message}"
                        Log.e("PaymentViewModel", "Error loading payment history", result.exception)
                    }
                    is Result.Loading -> {
                        _isLoading.value = true
                    }
                }
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
                    // Log the discrepancy
                    Log.w("PaymentViewModel", "Balance discrepancy detected: stored=$balance, calculated=$expectedBalance")

                    // Show a message to the user
                    _error.value = "Виявлено розбіжність у балансі. Спроба виправлення..."

                    // Try to fix by recalculating from scratch
                    val result = paymentRepository.getWorkerBalance(worker._id)
                    if (result is Result.Success) {
                        _workerBalance.value = result.data
                        _success.value = "Баланс оновлено"
                    }
                }
            } catch (e: Exception) {
                Log.e("PaymentViewModel", "Error checking balance discrepancy", e)
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
            _isLoading.value = true
            try {
                val result = paymentRepository.recordPayment(workerId, amount, notes)
                _isLoading.value = false

                when (result) {
                    is Result.Success -> {
                        _success.value = "Payment recorded successfully"

                        // Refresh data
                        loadWorkerBalance(workerId)
                        loadPaymentHistory(workerId)
                    }
                    is Result.Error -> {
                        _error.value = "Failed to record payment: ${result.message}"
                        Log.e("PaymentViewModel", "Error recording payment", result.exception)
                    }
                    is Result.Loading -> {
                        // Already handling loading state
                    }
                }
            } catch (e: Exception) {
                _isLoading.value = false
                _error.value = "Error recording payment: ${e.message}"
                Log.e("PaymentViewModel", "Error recording payment", e)
            }
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
    }
}