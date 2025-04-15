package com.example.berryharvest.ui.report

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.data.model.Gather
import com.example.berryharvest.data.model.Worker
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

    // Top workers
    private val _topWorkers = MutableStateFlow<List<WorkerStats>>(emptyList())
    val topWorkers: StateFlow<List<WorkerStats>> = _topWorkers.asStateFlow()

    // Daily production
    private val _dailyProduction = MutableStateFlow<List<DailyProduction>>(emptyList())
    val dailyProduction: StateFlow<List<DailyProduction>> = _dailyProduction.asStateFlow()

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
                    launch { loadTopWorkers() }
                    launch { loadDailyProduction() }
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

            // Calculate total punnets and earnings
            var totalPunnets = 0
            var totalEarnings = 0.0f
            var workerCount = 0
            var todayPunnets = 0
            var todayEarnings = 0.0f

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
                val cost = gather.punnetCost ?: 0.0f
                val earnings = punnets * cost

                totalPunnets += punnets
                totalEarnings += earnings

                // Check if this gather is from today
                val gatherDateStr = gather.dateTime
                if (gatherDateStr != null) {
                    try {
                        val gatherDate = dateFormat.parse(gatherDateStr)?.time ?: 0
                        if (gatherDate >= startOfToday) {
                            todayPunnets += punnets
                            todayEarnings += earnings
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing date: $gatherDateStr", e)
                    }
                }
            }

            // Count active workers
            workerCount = realm.query<Worker>("isDeleted == false").count().find().toInt()

            // Create the stats object
            val stats = SummaryStats(
                totalPunnets = totalPunnets,
                totalEarnings = totalEarnings,
                workerCount = workerCount,
                todayPunnets = todayPunnets,
                todayEarnings = todayEarnings,
                avgPunnetsPerWorker = if (workerCount > 0) totalPunnets.toFloat() / workerCount else 0f
            )

            _summaryStats.value = stats

        } catch (e: Exception) {
            Log.e(TAG, "Error loading summary stats", e)
            _errorMessage.value = "Помилка при завантаженні статистики: ${e.message}"
        }
    }

    // Load top performing workers
    private suspend fun loadTopWorkers() {
        try {
            val realm = app.getRealmInstance()

            // Get all workers
            val workers = realm.query<Worker>("isDeleted == false").find()

            // Get all non-deleted gathers
            val gathers = realm.query<Gather>("isDeleted == false").find()

            // Group gathers by worker
            val workerStats = mutableListOf<WorkerStats>()

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

                for (gather in workerGathers) {
                    val punnets = gather.numOfPunnets ?: 0
                    val cost = gather.punnetCost ?: 0.0f

                    totalPunnets += punnets
                    totalEarnings += punnets * cost
                }

                workerStats.add(
                    WorkerStats(
                        workerId = workerId,
                        workerName = worker.fullName,
                        sequenceNumber = worker.sequenceNumber,
                        totalPunnets = totalPunnets,
                        totalEarnings = totalEarnings
                    )
                )
            }

            // Sort by punnets or earnings (here by earnings)
            val sortedStats = workerStats.sortedByDescending { it.totalEarnings }

            // Take top 10 for display
            _topWorkers.value = sortedStats.take(10)

        } catch (e: Exception) {
            Log.e(TAG, "Error loading top workers", e)
            _errorMessage.value = "Помилка при завантаженні даних працівників: ${e.message}"
        }
    }

    // Load daily production statistics for the last 7 days
    private suspend fun loadDailyProduction() {
        try {
            val realm = app.getRealmInstance()

            // Get all non-deleted gathers
            val gathers = realm.query<Gather>("isDeleted == false").find()

            // Calculate the start of 7 days ago
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, -6) // -6 to include today (7 days total)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startDate = calendar.timeInMillis

            // Format for both parsing and display
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val displayFormat = SimpleDateFormat("dd.MM", Locale.getDefault())

            // Create a map for each day's production
            val dailyMap = mutableMapOf<String, DailyProduction>()

            // Initialize the map with the last 7 days
            for (i in 0..6) {
                val date = Calendar.getInstance()
                date.timeInMillis = startDate
                date.add(Calendar.DAY_OF_YEAR, i)

                val displayDate = displayFormat.format(date.time)
                val fullDateStr = dateFormat.format(date.time).substring(0, 10) // YYYY-MM-DD

                dailyMap[fullDateStr] = DailyProduction(
                    date = displayDate,
                    fullDate = fullDateStr,
                    totalPunnets = 0,
                    totalEarnings = 0.0f
                )
            }

            // Process gathers and group by day
            for (gather in gathers) {
                val dateTimeStr = gather.dateTime ?: continue

                try {
                    // Extract just the date part (YYYY-MM-DD)
                    val datePart = dateTimeStr.substring(0, 10)

                    // Only process if it's within our date range
                    if (dailyMap.containsKey(datePart)) {
                        val punnets = gather.numOfPunnets ?: 0
                        val cost = gather.punnetCost ?: 0.0f
                        val earnings = punnets * cost

                        val current = dailyMap[datePart]!!
                        dailyMap[datePart] = current.copy(
                            totalPunnets = current.totalPunnets + punnets,
                            totalEarnings = current.totalEarnings + earnings
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing date: $dateTimeStr", e)
                }
            }

            // Convert to ordered list for display
            val dailyList = dailyMap.values.sortedBy { it.fullDate }

            _dailyProduction.value = dailyList

        } catch (e: Exception) {
            Log.e(TAG, "Error loading daily production", e)
            _errorMessage.value = "Помилка при завантаженні щоденних даних: ${e.message}"
        }
    }
}

// Data classes for UI state

data class SummaryStats(
    val totalPunnets: Int,
    val totalEarnings: Float,
    val workerCount: Int,
    val todayPunnets: Int,
    val todayEarnings: Float,
    val avgPunnetsPerWorker: Float
)

data class WorkerStats(
    val workerId: String,
    val workerName: String,
    val sequenceNumber: Int,
    val totalPunnets: Int,
    val totalEarnings: Float
)

data class DailyProduction(
    val date: String,  // Formatted date for display (e.g., "23.04")
    val fullDate: String, // Full date for sorting (e.g., "2023-04-23")
    val totalPunnets: Int,
    val totalEarnings: Float
)