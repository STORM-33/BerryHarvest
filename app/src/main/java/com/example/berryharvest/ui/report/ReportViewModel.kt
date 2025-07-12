package com.example.berryharvest.ui.report

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.data.model.Gather
import com.example.berryharvest.data.model.Worker
import com.example.berryharvest.data.model.PaymentRecord
import com.example.berryharvest.data.repository.ConnectionState
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
import java.util.concurrent.TimeUnit

private const val TAG = "ReportViewModel"

class ReportViewModel(application: Application) : AndroidViewModel(application) {
    private val app = getApplication<BerryHarvestApplication>()
    private val networkStatusManager = app.networkStatusManager

    // State holders for UI
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Summary statistics
    private val _summaryStats = MutableStateFlow<SummaryStats?>(null)
    val summaryStats: StateFlow<SummaryStats?> = _summaryStats.asStateFlow()

    // Worker stats
    private val _workerStats = MutableStateFlow<List<WorkerStats>>(emptyList())
    val workerStats: StateFlow<List<WorkerStats>> = _workerStats.asStateFlow()

    init {
        observeConnectionState()
        loadReportData()
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            networkStatusManager.connectionState.collect { state ->
                _connectionState.value = state

                // Optionally refresh data when connection is restored
                if (state is ConnectionState.Connected) {
                    Log.d(TAG, "Network connection restored, refreshing report data")
                    loadReportData()
                }
            }
        }
    }

    fun loadReportData() {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                withContext(Dispatchers.IO) {
                    // Load all data in parallel for efficiency
                    launch { loadSummaryStats() }
                    launch { loadWorkerStats() }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading report data", e)
                _errorMessage.value = "Помилка при завантаженні даних: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    // Load summary statistics about the overall operation
    private suspend fun loadSummaryStats() {
        try {
            val realm = app.getRealmInstance()

            // Get all non-deleted gathers
            val gathers = realm.query<Gather>("isDeleted == false").find()

            // Get all payment records
            val payments = realm.query<PaymentRecord>("isDeleted == false").find()

            // Calculate total trays and total paid
            var totalPunnets = 0
            var totalPaid = 0.0f
            var todayPunnets = 0
            var todayToPay = 0.0f

            // Calculate the start of today
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startOfToday = calendar.timeInMillis
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            // Process each gather
            for (gather in gathers) {
                val punnets = gather.numOfPunnets ?: 0
                totalPunnets += punnets

                // Check if this gather is from today
                val gatherDateStr = gather.dateTime
                if (gatherDateStr != null) {
                    try {
                        val gatherDate = dateFormat.parse(gatherDateStr)?.time ?: 0
                        if (gatherDate >= startOfToday) {
                            todayPunnets += punnets
                            val cost = gather.punnetCost ?: 0.0f
                            todayToPay += punnets * cost
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing date: $gatherDateStr", e)
                    }
                }
            }

            // Calculate total paid from payment records
            for (payment in payments) {
                if (payment.amount > 0) { // Only count positive payments (money given to workers)
                    totalPaid += payment.amount
                }
            }

            // Calculate average workers per day
            val avgWorkersPerDay = calculateAverageWorkersPerDay(gathers, dateFormat)

            // Count active workers today
            val activeWorkersToday = countActiveWorkersToday(gathers, dateFormat, startOfToday)

            // Create the stats object
            val stats = SummaryStats(
                totalTrays = totalPunnets / 10, // Convert punnets to trays
                totalPaid = totalPaid,
                avgWorkersPerDay = avgWorkersPerDay,
                todayTrays = todayPunnets / 10, // Convert punnets to trays
                todayToPay = todayToPay,
                activeWorkersToday = activeWorkersToday
            )

            _summaryStats.value = stats

        } catch (e: Exception) {
            Log.e(TAG, "Error loading summary stats", e)
            _errorMessage.value = "Помилка при завантаженні статистики: ${e.message}"
        }
    }

    private fun calculateAverageWorkersPerDay(
        gathers: List<Gather>,
        dateFormat: SimpleDateFormat
    ): Float {
        // Group gathers by date and count unique workers per day
        val workersByDay = mutableMapOf<String, MutableSet<String>>()

        for (gather in gathers) {
            val dateTimeStr = gather.dateTime ?: continue
            val workerId = gather.workerId ?: continue

            try {
                // Extract just the date part (YYYY-MM-DD)
                val datePart = dateTimeStr.substring(0, 10)

                if (!workersByDay.containsKey(datePart)) {
                    workersByDay[datePart] = mutableSetOf()
                }
                workersByDay[datePart]?.add(workerId)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing date for worker average: $dateTimeStr", e)
            }
        }

        // Calculate average
        return if (workersByDay.isNotEmpty()) {
            val totalWorkerDays = workersByDay.values.sumOf { it.size }
            totalWorkerDays.toFloat() / workersByDay.size
        } else {
            0f
        }
    }

    private fun countActiveWorkersToday(
        gathers: List<Gather>,
        dateFormat: SimpleDateFormat,
        startOfToday: Long
    ): Int {
        val activeWorkersToday = mutableSetOf<String>()

        for (gather in gathers) {
            val dateTimeStr = gather.dateTime ?: continue
            val workerId = gather.workerId ?: continue

            try {
                val gatherDate = dateFormat.parse(dateTimeStr)?.time ?: 0
                if (gatherDate >= startOfToday) {
                    activeWorkersToday.add(workerId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing date for active workers: $dateTimeStr", e)
            }
        }

        return activeWorkersToday.size
    }

    // Load worker performance statistics
    private suspend fun loadWorkerStats() {
        try {
            val realm = app.getRealmInstance()

            // Get all workers
            val workers = realm.query<Worker>("isDeleted == false").find()

            // Get all non-deleted gathers
            val gathers = realm.query<Gather>("isDeleted == false").find()

            // Group gathers by worker
            val workerStatsList = mutableListOf<WorkerStats>()

            // Create a map of worker ID to Worker object for quick lookup
            val workerMap = workers.associateBy { it._id }

            // Calculate stats per worker
            val workerGathers = gathers.groupBy { it.workerId }

            for ((workerId, workerGathers) in workerGathers) {
                // Skip entries with null worker ID
                if (workerId.isNullOrEmpty()) continue

                val worker = workerMap[workerId] ?: continue

                var totalPunnets = 0
                var totalEarnings = 0.0f
                val workerDays = mutableSetOf<String>()

                for (gather in workerGathers) {
                    val punnets = gather.numOfPunnets ?: 0
                    val cost = gather.punnetCost ?: 0.0f

                    totalPunnets += punnets
                    totalEarnings += (punnets * cost) // Calculate earnings for this gather

                    // Count unique days this worker worked
                    val dateTimeStr = gather.dateTime
                    if (dateTimeStr != null) {
                        try {
                            val datePart = dateTimeStr.substring(0, 10) // YYYY-MM-DD
                            workerDays.add(datePart)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing date for worker stats: $dateTimeStr", e)
                        }
                    }
                }

                val avgPunnetsPerDay = if (workerDays.isNotEmpty()) {
                    totalPunnets.toFloat() / workerDays.size
                } else {
                    0f
                }

                workerStatsList.add(
                    WorkerStats(
                        workerId = workerId,
                        workerName = worker.fullName,
                        sequenceNumber = worker.sequenceNumber,
                        totalPunnets = totalPunnets,
                        totalEarnings = totalEarnings, // Include total earnings
                        avgPunnetsPerDay = avgPunnetsPerDay
                    )
                )
            }

            // Sort by total earnings (descending) - this makes more sense for leaderboard
            val sortedStats = workerStatsList.sortedByDescending { it.totalEarnings }

            _workerStats.value = sortedStats

        } catch (e: Exception) {
            Log.e(TAG, "Error loading worker stats", e)
            _errorMessage.value = "Помилка при завантаженні даних працівників: ${e.message}"
        }
    }
}

// Data classes for UI state

data class SummaryStats(
    val totalTrays: Int,
    val totalPaid: Float,
    val avgWorkersPerDay: Float,
    val todayTrays: Int,
    val todayToPay: Float,
    val activeWorkersToday: Int
)

data class WorkerStats(
    val workerId: String,
    val workerName: String,
    val sequenceNumber: Int,
    val totalPunnets: Int,
    val totalEarnings: Float,
    val avgPunnetsPerDay: Float
)