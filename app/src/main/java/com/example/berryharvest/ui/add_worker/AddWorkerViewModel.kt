package com.example.berryharvest.ui.add_worker

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.data.model.Worker
import com.example.berryharvest.data.repository.ConnectionState
import com.example.berryharvest.data.repository.Result
import com.example.berryharvest.data.repository.WorkerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AddWorkerViewModel(application: Application) : AndroidViewModel(application) {

    private val app: BerryHarvestApplication
        get() = getApplication() as BerryHarvestApplication

    private val repository = app.repositoryProvider.workerRepository

    private val networkStatusManager = app.networkStatusManager

    private val _workers = MutableStateFlow<List<Worker>>(emptyList())
    val workers: StateFlow<List<Worker>> = _workers.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        observeWorkers()
        observeConnectionState()
    }

    private fun observeWorkers() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.getAll().collect { result ->
                _isLoading.value = false

                when (result) {
                    is Result.Success -> {
                        _workers.value = result.data
                        _error.value = null
                    }
                    is Result.Error -> {
                        _error.value = "Error loading workers: ${result.message}"
                        Log.e("AddWorkerViewModel", "Error loading workers", result.exception)
                    }
                    is Result.Loading -> {
                        _isLoading.value = true
                    }
                }
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            // Use the centralized network status manager instead of repository
            networkStatusManager.connectionState.collect { state ->
                _connectionState.value = state

                // If we're back online, try to sync any pending changes
                if (state is ConnectionState.Connected) {
                    Log.d("AddWorkerViewModel", "Network is available, syncing pending changes")
                    syncPendingChanges()
                }
            }
        }
    }

    private fun syncPendingChanges() {
        // Use the global sync manager instead
        viewModelScope.launch {
            try {
                app.syncManager.performSync(silent = true)
            } catch (e: Exception) {
                Log.e("AddWorkerViewModel", "Error syncing changes", e)
            }
        }
    }

    fun addWorker(fullName: String, phoneNumber: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                val result = repository.addWorkerWithDetails(fullName, phoneNumber)

                when (result) {
                    is Result.Success -> {
                        Log.d("AddWorkerViewModel", "Worker added: $fullName with ID: ${result.data}")
                        _error.value = null
                    }
                    is Result.Error -> {
                        _error.value = "Failed to add worker: ${result.message}"
                        Log.e("AddWorkerViewModel", "Error adding worker", result.exception)
                    }
                    is Result.Loading -> { /* Already handled by isLoading state */ }
                }
            } catch (e: Exception) {
                _error.value = "Error adding worker: ${e.message}"
                Log.e("AddWorkerViewModel", "Error adding worker", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateWorker(id: String, fullName: String, phoneNumber: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                val result = repository.updateWorkerWithDetails(id, fullName, phoneNumber)

                when (result) {
                    is Result.Success -> {
                        Log.d("AddWorkerViewModel", "Worker updated: $id")
                        _error.value = null
                    }
                    is Result.Error -> {
                        _error.value = "Failed to update worker: ${result.message}"
                        Log.e("AddWorkerViewModel", "Error updating worker", result.exception)
                    }
                    is Result.Loading -> { /* Already handled by isLoading state */ }
                }
            } catch (e: Exception) {
                _error.value = "Error updating worker: ${e.message}"
                Log.e("AddWorkerViewModel", "Error updating worker", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteWorker(id: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                val result = repository.delete(id)

                when (result) {
                    is Result.Success -> {
                        Log.d("AddWorkerViewModel", "Worker deleted: $id")
                        _error.value = null
                    }
                    is Result.Error -> {
                        _error.value = "Failed to delete worker: ${result.message}"
                        Log.e("AddWorkerViewModel", "Error deleting worker", result.exception)
                    }
                    is Result.Loading -> { /* Already handled by isLoading state */ }
                }
            } catch (e: Exception) {
                _error.value = "Error deleting worker: ${e.message}"
                Log.e("AddWorkerViewModel", "Error deleting worker", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Resolve any sequence number conflicts by ensuring all workers have unique sequence numbers.
     */
    fun resolveSequenceConflicts() {
        viewModelScope.launch {
            try {
                (repository as? WorkerRepository)?.resolveSequenceConflicts()
            } catch (e: Exception) {
                _error.value = "Error resolving sequence conflicts: ${e.message}"
                Log.e("AddWorkerViewModel", "Error resolving sequence conflicts", e)
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
}


