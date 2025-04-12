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
    private val app = getApplication<BerryHarvestApplication>()
    private val settingsRepository = app.repositoryProvider.settingsRepository
    private val assignmentRepository = app.repositoryProvider.assignmentRepository
    private val workerRepository = app.repositoryProvider.workerRepository
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

    fun handleScanResult(scannedCode: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isLoading.value = true

                val realm = app.getRealmInstance()
                // Try to find worker by QR code first
                var worker = realm.query<Worker>("qrCode == $0", scannedCode).first().find()

                // If not found by QR code, try by ID (some QR codes might directly contain the ID)
                if (worker == null) {
                    worker = realm.query<Worker>("_id == $0", scannedCode).first().find()
                }

                if (worker == null) {
                    _errorMessage.postValue("Працівника не знайдено")
                    _isLoading.value = false
                    return@launch
                }

                // Find assignment for this worker
                val assignment = realm.query<Assignment>("workerId == $0 AND isDeleted == false", worker._id)
                    .first().find()

                if (assignment == null) {
                    // Worker is not assigned to a row
                    withContext(Dispatchers.Main) {
                        // Show dialog to assign worker to a row
                        _workerAssignment.postValue(Pair(worker, -1)) // -1 indicates no row assignment
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _workerAssignment.postValue(Pair(worker, assignment.rowNumber))
                    }
                }

                _isLoading.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Error handling scan", e)
                _errorMessage.postValue("Помилка при обробці сканування: ${e.message}")
                _isLoading.value = false
            }
        }
    }

    fun saveGatherData(workerId: String, rowNumber: Int, numOfPunnets: Int) {
        if (numOfPunnets <= 0) {
            _errorMessage.postValue("Кількість пінеток повинна бути більше 0")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isLoading.value = true
                val currentPrice = _punnetPrice.value ?: 0.0f

                // Use safe transaction wrapper
                app.safeWriteTransaction {
                    copyToRealm(Gather().apply {
                        _id = UUID.randomUUID().toString()
                        this.workerId = workerId
                        this.rowNumber = rowNumber
                        this.numOfPunnets = numOfPunnets
                        this.punnetCost = currentPrice
                        dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                        isSynced = networkStatusManager.isNetworkAvailable()
                        isDeleted = false
                    })
                }

                // Refresh data
                loadRecentGathers()
                calculateTodayStats()
                countUnsyncedGathers()

                _successMessage.postValue(true)
                _isLoading.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Error saving gather data", e)
                _errorMessage.postValue("Помилка при збереженні даних: ${e.message}")
                _isLoading.value = false
            }
        }
    }

    fun updateGatherData(gatherId: String, numOfPunnets: Int) {
        if (numOfPunnets <= 0) {
            _errorMessage.postValue("Кількість пінеток повинна бути більше 0")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isLoading.value = true

                app.safeWriteTransaction {
                    val gather = query<Gather>("_id == $0", gatherId).first().find()
                    gather?.apply {
                        this.numOfPunnets = numOfPunnets
                        isSynced = false
                    }
                }

                // Refresh data
                loadRecentGathers()
                calculateTodayStats()
                countUnsyncedGathers()

                _successMessage.postValue(true)
                _isLoading.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Error updating gather data", e)
                _errorMessage.postValue("Помилка при оновленні даних: ${e.message}")
                _isLoading.value = false
            }
        }
    }

    fun deleteGatherData(gatherId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isLoading.value = true

                app.safeWriteTransaction {
                    val gather = query<Gather>("_id == $0", gatherId).first().find()
                    gather?.apply {
                        isDeleted = true
                        isSynced = false
                    }
                }

                // Refresh data
                loadRecentGathers()
                calculateTodayStats()
                countUnsyncedGathers()

                _successMessage.postValue(true)
                _isLoading.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting gather data", e)
                _errorMessage.postValue("Помилка при видаленні даних: ${e.message}")
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

