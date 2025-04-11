package com.example.berryharvest.ui.gather

import android.app.Application
import android.icu.text.SimpleDateFormat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.data.repository.Result
import com.example.berryharvest.data.model.Worker
import com.example.berryharvest.data.model.Assignment
import com.example.berryharvest.data.model.Gather
import com.example.berryharvest.data.repository.ConnectionState
import io.realm.kotlin.ext.query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import java.util.UUID

class GatherViewModel(application: Application) : AndroidViewModel(application) {
    private val app = getApplication<BerryHarvestApplication>()
    private val settingsRepository = app.repositoryProvider.settingsRepository
    private val networkStatusManager = app.networkStatusManager

    private val _punnetPrice = MutableLiveData<Float>()
    val punnetPrice: LiveData<Float> = _punnetPrice

    private val _workerAssignment = MutableLiveData<Pair<Worker, Int>?>()
    val workerAssignment: LiveData<Pair<Worker, Int>?> = _workerAssignment

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _successMessage = MutableLiveData<Boolean>()
    val successMessage: LiveData<Boolean> = _successMessage

    // Add connection state LiveData
    private val _connectionState = MutableLiveData<ConnectionState>()
    val connectionState: LiveData<ConnectionState> = _connectionState

    init {
        loadPunnetPrice()

        // Observe connection state from the centralized manager
        viewModelScope.launch {
            networkStatusManager.connectionStateLiveData.observeForever { state ->
                _connectionState.postValue(state)
            }
        }
    }

    fun handleScanResult(workerId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val realm = app.getRealmInstance()
                val worker = realm.query<Worker>("_id == $0", workerId).first().find()
                if (worker == null) {
                    _errorMessage.postValue("Працівника не знайдено")
                    return@launch
                }

                val assignment = realm.query<Assignment>("workerId == $0 AND isDeleted == false", workerId)
                    .first().find()

                if (assignment == null) {
                    _errorMessage.postValue("Працівнику не призначено ряд")
                } else {
                    _workerAssignment.postValue(Pair(worker, assignment.rowNumber))
                }
            } catch (e: Exception) {
                _errorMessage.postValue("Помилка при обробці сканування: ${e.message}")
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
                        isSynced = false
                        isDeleted = false
                    })
                }

                _successMessage.postValue(true)
            } catch (e: Exception) {
                _errorMessage.postValue("Помилка при збереженні даних: ${e.message}")
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

    fun syncData() {
        viewModelScope.launch {
            if (networkStatusManager.isNetworkAvailable()) {
                try {
                    val result = app.syncManager.performSync()
                    if (result is Result.Success) {
                        _successMessage.postValue(true)
                    } else if (result is Result.Error) {
                        _errorMessage.postValue("Синхронізація не вдалася: ${result.message}")
                    }
                } catch (e: Exception) {
                    _errorMessage.postValue("Помилка синхронізації: ${e.message}")
                }
            } else {
                _errorMessage.postValue("Немає з'єднання з мережею")
            }
        }
    }

    fun clearErrorMessage() = _errorMessage.postValue(null)
    fun clearWorkerAssignment() = _workerAssignment.postValue(null)
    fun clearSuccessMessage() = _successMessage.postValue(false)
}

