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
import io.realm.kotlin.ext.query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

class GatherViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "GatherViewModel"
    private val app: BerryHarvestApplication = getApplication() as BerryHarvestApplication

    private val workerRepository = app.repositoryProvider.workerRepository
    private val assignmentRepository = app.repositoryProvider.assignmentRepository
    private val gatherRepository = app.repositoryProvider.gatherRepository
    private val settingsRepository = app.repositoryProvider.settingsRepository
    private val networkStatusManager = app.networkStatusManager

    // Punnet price
    private val _punnetPrice = MutableLiveData<Float>()
    val punnetPrice: LiveData<Float> = _punnetPrice

    // Worker assignment info
    private val _workerAssignment = MutableLiveData<Pair<Worker, Int>?>()
    val workerAssignment: LiveData<Pair<Worker, Int>?> = _workerAssignment

    // Recently scanned gathers
    private val _recentGathers = MutableStateFlow<List<GatherWithDetails>>(emptyList())
    val recentGathers: StateFlow<List<GatherWithDetails>> = _recentGathers.asStateFlow()

    // Today's statistics
    private val _todayStats = MutableStateFlow(TodayStats(0, 0f))
    val todayStats: StateFlow<TodayStats> = _todayStats.asStateFlow()

    // Error messages
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    // Success messages
    private val _successMessage = MutableLiveData<Boolean>()
    val successMessage: LiveData<Boolean> = _successMessage

    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Connection state
    private val _connectionState = MutableLiveData<ConnectionState>()
    val connectionState: LiveData<ConnectionState> = _connectionState

    // Unsynchronized gathers count
    private val _unsyncedCount = MutableStateFlow(0)
    val unsyncedCount: StateFlow<Int> = _unsyncedCount.asStateFlow()

    init {
        loadPunnetPrice()
        loadRecentGathers()
        calculateTodayStats()
        observeConnectionState()
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            networkStatusManager.connectionStateLiveData.observeForever { state ->
                _connectionState.postValue(state)

                // If we're back online, try to sync
                if (state is ConnectionState.Connected) {
                    syncPendingChanges()
                }
            }
        }
    }

    fun syncPendingChanges() {
        viewModelScope.launch {
            try {
                val result = app.syncManager.performSync()
                if (result is Result.Success) {
                    loadRecentGathers() // Refresh data after sync
                    countUnsyncedGathers() // Update unsynced count
                    _successMessage.postValue(true)
                } else if (result is Result.Error) {
                    _errorMessage.postValue("Синхронізація не вдалася: ${result.message}")
                }
            } catch (e: Exception) {
                _errorMessage.postValue("Помилка синхронізації: ${e.message}")
            }
        }
    }

    private fun loadPunnetPrice() {
        viewModelScope.launch {
            try {
                val price = settingsRepository.getPunnetPrice()
                _punnetPrice.postValue(price)
            } catch (e: Exception) {
                _errorMessage.postValue("Помилка при завантаженні ціни: ${e.message}")
                _punnetPrice.postValue(0.0f)
            }
        }
    }

    /**
     * Handle QR scan result.
     */
    fun handleScanResult(qrContent: String) {
        viewModelScope.launch {
            _isLoading.value = true

            try {
                // Use worker repository to find worker by QR code
                val result = workerRepository.getWorkerByQrCode(qrContent)

                when (result) {
                    is Result.Success -> {
                        val worker = result.data
                        if (worker != null) {
                            // Check if worker is assigned to a row
                            val assignmentResult = assignmentRepository.getWorkerAssignment(worker._id)

                            when (assignmentResult) {
                                is Result.Success -> {
                                    val assignment = assignmentResult.data
                                    val rowNumber = assignment?.rowNumber ?: -1

                                    // Set the worker assignment (to be observed by UI)
                                    _workerAssignment.value = Pair(worker, rowNumber)
                                }
                                is Result.Error -> {
                                    _errorMessage.value = "Помилка перевірки призначення: ${assignmentResult.message}"
                                    Log.e(TAG, "Error checking assignment", assignmentResult.exception)
                                }
                                is Result.Loading -> {
                                    // Loading is already handled
                                }
                            }
                        } else {
                            _errorMessage.value = "Працівника не знайдено за QR кодом"
                        }
                    }
                    is Result.Error -> {
                        _errorMessage.value = "Помилка сканування: ${result.message}"
                        Log.e(TAG, "Error scanning QR", result.exception)
                    }
                    is Result.Loading -> {
                        // Loading is already handled
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Помилка: ${e.message}"
                Log.e(TAG, "Error handling scan", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Save gather data.
     */
    fun saveGatherData(workerId: String, rowNumber: Int, numOfPunnets: Int) {
        viewModelScope.launch {
            _isLoading.value = true

            try {
                // Get current punnet price
                val priceResult = settingsRepository.getPunnetPrice()
                val punnetPrice = _punnetPrice.value ?: priceResult

                // Use gather repository to record gather
                val result = gatherRepository.recordGather(workerId, rowNumber, numOfPunnets, punnetPrice)

                when (result) {
                    is Result.Success -> {
                        _successMessage.value = true
                        // Update stats and gathers after successful save
                        loadRecentGathers()
                        calculateTodayStats()
                    }
                    is Result.Error -> {
                        _errorMessage.value = "Помилка збереження даних: ${result.message}"
                        Log.e(TAG, "Error saving gather", result.exception)
                    }
                    is Result.Loading -> {
                        // Loading is already handled
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Помилка: ${e.message}"
                Log.e(TAG, "Error saving gather", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Update gather details.
     */
    fun updateGatherDetails(gatherId: String, numOfPunnets: Int) {
        viewModelScope.launch {
            _isLoading.value = true

            try {
                // Use gather repository to update gather
                val result = gatherRepository.updateGatherDetails(gatherId, numOfPunnets)

                when (result) {
                    is Result.Success -> {
                        _successMessage.value = true
                        // Update stats and gathers after successful update
                        loadRecentGathers()
                        calculateTodayStats()
                    }
                    is Result.Error -> {
                        _errorMessage.value = "Помилка оновлення даних: ${result.message}"
                        Log.e(TAG, "Error updating gather", result.exception)
                    }
                    is Result.Loading -> {
                        // Loading is already handled
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Помилка: ${e.message}"
                Log.e(TAG, "Error updating gather", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Delete gather record.
     */
    fun deleteGather(gatherId: String) {
        viewModelScope.launch {
            _isLoading.value = true

            try {
                // Use gather repository to delete gather
                val result = gatherRepository.delete(gatherId)

                when (result) {
                    is Result.Success -> {
                        _successMessage.value = true
                        // Update stats and gathers after successful delete
                        loadRecentGathers()
                        calculateTodayStats()
                    }
                    is Result.Error -> {
                        _errorMessage.value = "Помилка видалення даних: ${result.message}"
                        Log.e(TAG, "Error deleting gather", result.exception)
                    }
                    is Result.Loading -> {
                        // Loading is already handled
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Помилка: ${e.message}"
                Log.e(TAG, "Error deleting gather", e)
            } finally {
                _isLoading.value = false
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
                // Use the settings repository
                val result = settingsRepository.updatePunnetPrice(newPrice)

                when (result) {
                    is Result.Success -> {
                        _punnetPrice.postValue(newPrice)
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

    private fun loadRecentGathers() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isLoading.value = true

                val realm = app.getRealmInstance()

                // Calculate start of day timestamp
                val calendar = Calendar.getInstance()
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val startOfDayStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(calendar.time)

                // Query gathers from today
                val gathers = realm.query<Gather>("dateTime >= $0 AND isDeleted == false", startOfDayStr)
                    .find()
                    .sortedByDescending { it.dateTime }

                // Create list of GatherWithDetails
                val gathersWithDetails = mutableListOf<GatherWithDetails>()

                for (gather in gathers) {
                    val worker = realm.query<Worker>("_id == $0", gather.workerId).first().find()
                    val workerName = if (worker != null) {
                        "${worker.fullName} [${worker.sequenceNumber}]"
                    } else {
                        "Невідомий працівник"
                    }

                    gathersWithDetails.add(
                        GatherWithDetails(
                            gather = gather,
                            workerName = workerName,
                            dateTime = gather.dateTime
                        )
                    )
                }

                _recentGathers.value = gathersWithDetails
                _isLoading.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Error loading recent gathers", e)
                _errorMessage.postValue("Помилка при завантаженні даних: ${e.message}")
                _isLoading.value = false
            }
        }
    }

    private fun calculateTodayStats() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val realm = app.getRealmInstance()

                // Calculate start of day timestamp
                val calendar = Calendar.getInstance()
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val startOfDayStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(calendar.time)

                // Query gathers from today
                val gathers = realm.query<Gather>("dateTime >= $0 AND isDeleted == false", startOfDayStr)
                    .find()

                // Calculate total punnets and amount
                var totalPunnets = 0
                var totalAmount = 0.0f

                for (gather in gathers) {
                    val punnets = gather.numOfPunnets ?: 0
                    val cost = gather.punnetCost ?: 0.0f

                    totalPunnets += punnets
                    totalAmount += punnets * cost
                }

                _todayStats.value = TodayStats(totalPunnets, totalAmount)
            } catch (e: Exception) {
                Log.e(TAG, "Error calculating today stats", e)
            }
        }
    }

    private fun countUnsyncedGathers() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val realm = app.getRealmInstance()
                val count = realm.query<Gather>("isSynced == false AND isDeleted == false").count().find()
                _unsyncedCount.value = count.toInt()
            } catch (e: Exception) {
                Log.e(TAG, "Error counting unsynced gathers", e)
            }
        }
    }

    fun assignWorkerToRow(workerId: String, rowNumber: Int): LiveData<Boolean> {
        val result = MutableLiveData<Boolean>()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isLoading.value = true

                // Create a new assignment
                val assignment = Assignment().apply {
                    _id = UUID.randomUUID().toString()
                    this.workerId = workerId
                    this.rowNumber = rowNumber
                    isSynced = networkStatusManager.isNetworkAvailable()
                }

                // Add assignment using repository
                val assignResult = assignmentRepository.add(assignment)

                when (assignResult) {
                    is Result.Success -> {
                        result.postValue(true)
                    }
                    is Result.Error -> {
                        _errorMessage.postValue("Помилка при призначенні працівника: ${assignResult.message}")
                        result.postValue(false)
                    }
                    is Result.Loading -> {
                        // Handle loading if needed
                    }
                }

                _isLoading.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Error assigning worker to row", e)
                _errorMessage.postValue("Помилка при призначенні працівника: ${e.message}")
                result.postValue(false)
                _isLoading.value = false
            }
        }

        return result
    }

    fun clearErrorMessage() = _errorMessage.postValue(null)
    fun clearWorkerAssignment() = _workerAssignment.postValue(null)
    fun clearSuccessMessage() = _successMessage.postValue(false)

    fun getGatherById(gatherId: String): LiveData<Gather?> {
        val result = MutableLiveData<Gather?>()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val realm = app.getRealmInstance()
                val gather = realm.query<Gather>("_id == $0", gatherId).first().find()
                result.postValue(gather)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting gather by ID", e)
                result.postValue(null)
            }
        }

        return result
    }

    fun refreshData() {
        loadRecentGathers()
        calculateTodayStats()
        countUnsyncedGathers()
    }
}

data class TodayStats(
    val totalPunnets: Int,
    val totalAmount: Float
)

