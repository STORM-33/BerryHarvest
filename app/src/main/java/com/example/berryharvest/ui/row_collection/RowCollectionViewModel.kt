package com.example.berryharvest.ui.row_collection

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.data.model.Row
import com.example.berryharvest.data.repository.ConnectionState
import com.example.berryharvest.data.repository.Result
import com.example.berryharvest.data.repository.RowRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class RowCollectionViewModel(application: Application) : AndroidViewModel(application) {

    private val app: BerryHarvestApplication
        get() = getApplication() as BerryHarvestApplication

    private val repository: RowRepository = app.repositoryProvider.rowRepository
    private val networkStatusManager = app.networkStatusManager

    // Filter states
    private val _selectedQuarters = MutableStateFlow(setOf(1, 2, 3))
    val selectedQuarters: StateFlow<Set<Int>> = _selectedQuarters.asStateFlow()

    private val _filterMode = MutableStateFlow(FilterMode.ALL)
    val filterMode: StateFlow<FilterMode> = _filterMode.asStateFlow()

    // Data states
    private val _rows = MutableStateFlow<List<Row>>(emptyList())
    val rows: StateFlow<List<Row>> = _rows.asStateFlow()

    private val _groupedRows = MutableStateFlow<Map<Int, List<Row>>>(emptyMap())
    val groupedRows: StateFlow<Map<Int, List<Row>>> = _groupedRows.asStateFlow()

    private val _allRows = MutableStateFlow<List<Row>>(emptyList()) // Keep track of all rows for correct counts
    val allRows: StateFlow<List<Row>> = _allRows.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        initializeData()
        observeConnectionState()
        observeFilters()
        // Check for expired rows on initialization
        checkForExpiredRows()
    }

    private fun initializeData() {
        viewModelScope.launch {
            try {
                // Initialize default rows if needed
                repository.initializeDefaultRows()

                // Load initial data
                loadRows()
            } catch (e: Exception) {
                Log.e("RowCollectionViewModel", "Error initializing data", e)
                _error.value = "Error initializing data: ${e.message}"
            }
        }
    }

    private fun checkForExpiredRows() {
        viewModelScope.launch {
            try {
                val result = repository.expireOldCollectedRows()
                when (result) {
                    is Result.Success -> {
                        val expiredCount = result.data
                        if (expiredCount > 0) {
                            Log.d("RowCollectionViewModel", "Expired $expiredCount old collected rows")
                            // Reload data to reflect changes
                            loadRows()
                        }
                    }
                    is Result.Error -> {
                        Log.e("RowCollectionViewModel", "Error checking for expired rows", result.exception)
                    }
                    is Result.Loading -> { /* Not applicable for this operation */ }
                }
            } catch (e: Exception) {
                Log.e("RowCollectionViewModel", "Error checking for expired rows", e)
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            networkStatusManager.connectionState.collect { state ->
                _connectionState.value = state

                // If we're back online, try to sync any pending changes
                if (state is ConnectionState.Connected) {
                    Log.d("RowCollectionViewModel", "Network is available, syncing pending changes")
                    syncPendingChanges()
                }
            }
        }
    }

    private fun observeFilters() {
        viewModelScope.launch {
            // Combine filter states and reload data when any changes
            combine(
                _selectedQuarters,
                _filterMode
            ) { quarters, mode ->
                FilterConfig(quarters, mode)
            }.collect { config ->
                loadRows()
            }
        }
    }

    private fun loadRows() {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                when (_filterMode.value) {
                    FilterMode.ALL -> loadAllRows()
                    FilterMode.COLLECTED -> loadCollectedRows()
                    FilterMode.UNCOLLECTED -> loadUncollectedRows()
                }
            } catch (e: Exception) {
                Log.e("RowCollectionViewModel", "Error loading rows", e)
                _error.value = "Error loading rows: ${e.message}"
                _isLoading.value = false
            }
        }
    }

    private suspend fun loadAllRows() {
        val quarters = _selectedQuarters.value.toList()
        repository.getRowsByQuarters(quarters).collect { result ->
            when (result) {
                is Result.Success -> {
                    _rows.value = result.data
                    _allRows.value = result.data // Keep all rows for count calculation
                    _groupedRows.value = result.data.groupBy { it.quarter }
                    _error.value = null
                    _isLoading.value = false
                }
                is Result.Error -> {
                    _error.value = "Error loading rows: ${result.message}"
                    Log.e("RowCollectionViewModel", "Error loading rows", result.exception)
                    _isLoading.value = false
                }
                is Result.Loading -> {
                    _isLoading.value = true
                }
            }
        }
    }

    private suspend fun loadCollectedRows() {
        // First, load all rows to get correct counts
        val quarters = _selectedQuarters.value.toList()
        repository.getRowsByQuarters(quarters).collect { allRowsResult ->
            when (allRowsResult) {
                is Result.Success -> {
                    _allRows.value = allRowsResult.data // Keep all rows for count calculation

                    // Then get only collected rows for display
                    repository.getCollectedRows().collect { result ->
                        when (result) {
                            is Result.Success -> {
                                val filteredRows = result.data.filter { row ->
                                    _selectedQuarters.value.contains(row.quarter)
                                }
                                _rows.value = filteredRows
                                _groupedRows.value = filteredRows.groupBy { it.quarter }
                                _error.value = null
                                _isLoading.value = false
                            }
                            is Result.Error -> {
                                _error.value = "Error loading collected rows: ${result.message}"
                                Log.e("RowCollectionViewModel", "Error loading collected rows", result.exception)
                                _isLoading.value = false
                            }
                            is Result.Loading -> {
                                _isLoading.value = true
                            }
                        }
                    }
                }
                is Result.Error -> {
                    _error.value = "Error loading rows: ${allRowsResult.message}"
                    _isLoading.value = false
                }
                is Result.Loading -> {
                    _isLoading.value = true
                }
            }
        }
    }

    fun getTotalRowCounts(): Map<Int, Int> {
        return _allRows.value.groupBy { it.quarter }
            .mapValues { it.value.size }
    }


    private suspend fun loadUncollectedRows() {
        // First, load all rows to get correct counts
        val quarters = _selectedQuarters.value.toList()
        repository.getRowsByQuarters(quarters).collect { allRowsResult ->
            when (allRowsResult) {
                is Result.Success -> {
                    _allRows.value = allRowsResult.data // Keep all rows for count calculation

                    // Then get only uncollected rows for display
                    repository.getUncollectedRows().collect { result ->
                        when (result) {
                            is Result.Success -> {
                                val filteredRows = result.data.filter { row ->
                                    _selectedQuarters.value.contains(row.quarter)
                                }
                                _rows.value = filteredRows
                                _groupedRows.value = filteredRows.groupBy { it.quarter }
                                _error.value = null
                                _isLoading.value = false
                            }
                            is Result.Error -> {
                                _error.value = "Error loading uncollected rows: ${result.message}"
                                Log.e("RowCollectionViewModel", "Error loading uncollected rows", result.exception)
                                _isLoading.value = false
                            }
                            is Result.Loading -> {
                                _isLoading.value = true
                            }
                        }
                    }
                }
                is Result.Error -> {
                    _error.value = "Error loading rows: ${allRowsResult.message}"
                    _isLoading.value = false
                }
                is Result.Loading -> {
                    _isLoading.value = true
                }
            }
        }
    }

    /**
     * Get the actual collected count per quarter from all rows (not filtered)
     */
    fun getActualCollectedCounts(): Map<Int, Int> {
        return _allRows.value.groupBy { it.quarter }
            .mapValues { (_, rows) -> rows.count { it.isCollected } }
    }

    // Filter management
    fun setSelectedQuarters(quarters: Set<Int>) {
        _selectedQuarters.value = quarters
    }

    fun setFilterMode(mode: FilterMode) {
        _filterMode.value = mode
    }

    // Row operations
    fun toggleRowCollection(rowId: String) {
        viewModelScope.launch {
            try {
                val row = _rows.value.find { it._id == rowId }
                if (row != null) {
                    val result = repository.updateRowCollectionStatus(rowId, !row.isCollected)
                    when (result) {
                        is Result.Success -> {
                            Log.d("RowCollectionViewModel", "Row collection status updated: $rowId")
                            _error.value = null
                        }
                        is Result.Error -> {
                            _error.value = "Failed to update row: ${result.message}"
                            Log.e("RowCollectionViewModel", "Error updating row collection status", result.exception)
                        }
                        is Result.Loading -> { /* Already handled by isLoading state */ }
                    }
                }
            } catch (e: Exception) {
                _error.value = "Error updating row: ${e.message}"
                Log.e("RowCollectionViewModel", "Error updating row collection status", e)
            }
        }
    }

    private fun syncPendingChanges() {
        viewModelScope.launch {
            try {
                app.syncManager.performSync(silent = true)
            } catch (e: Exception) {
                Log.e("RowCollectionViewModel", "Error syncing changes", e)
            }
        }
    }

    /**
     * Manually check for and expire old collected rows.
     * Can be called periodically or when the app comes to foreground.
     */
    fun checkForExpiredRowsManually() {
        checkForExpiredRows()
    }

    /**
     * Get rows that will expire soon (within next 24 hours).
     */
    fun getRowsExpiringSoon(callback: (List<Row>) -> Unit) {
        viewModelScope.launch {
            try {
                val result = repository.getRowsExpiringSoon()
                when (result) {
                    is Result.Success -> {
                        callback(result.data)
                    }
                    is Result.Error -> {
                        Log.e("RowCollectionViewModel", "Error getting rows expiring soon", result.exception)
                        callback(emptyList())
                    }
                    is Result.Loading -> { /* Not applicable */ }
                }
            } catch (e: Exception) {
                Log.e("RowCollectionViewModel", "Error getting rows expiring soon", e)
                callback(emptyList())
            }
        }
    }

    /**
     * Get worker history for a specific row number.
     */
    fun getRowWorkerHistory(rowNumber: Int, callback: (List<com.example.berryharvest.ui.gather.GatherWithDetails>) -> Unit) {
        viewModelScope.launch {
            try {
                val result = app.repositoryProvider.gatherRepository.getGatherHistoryByRowNumber(rowNumber)
                when (result) {
                    is Result.Success -> {
                        callback(result.data)
                    }
                    is Result.Error -> {
                        Log.e("RowCollectionViewModel", "Error getting worker history for row $rowNumber", result.exception)
                        _error.value = "Помилка завантаження історії: ${result.message}"
                        callback(emptyList())
                    }
                    is Result.Loading -> {
                        // Loading state handled by caller
                    }
                }
            } catch (e: Exception) {
                Log.e("RowCollectionViewModel", "Error getting worker history for row $rowNumber", e)
                _error.value = "Помилка завантаження історії: ${e.message}"
                callback(emptyList())
            }
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

    // Data classes for filter management
    data class FilterConfig(
        val quarters: Set<Int>,
        val mode: FilterMode
    )

    enum class FilterMode {
        ALL,
        COLLECTED,
        UNCOLLECTED
    }
}