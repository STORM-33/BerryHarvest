package com.example.berryharvest.ui.gather

import android.app.Application
import android.icu.text.SimpleDateFormat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.berryharvest.MyApplication
import com.example.berryharvest.ui.add_worker.Worker
import com.example.berryharvest.ui.assign_rows.Assignment
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import java.util.UUID

class GatherViewModel(application: Application) : AndroidViewModel(application) {

    private var realm: Realm? = null

    private val _punnetPrice = MutableLiveData<Float>()
    val punnetPrice: LiveData<Float> = _punnetPrice

    private val _workerAssignment = MutableLiveData<Pair<Worker, Int>?>()
    val workerAssignment: LiveData<Pair<Worker, Int>?> = _workerAssignment

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _successMessage = MutableLiveData<Boolean>()
    val successMessage: LiveData<Boolean> = _successMessage

    init {
        initializeRealm()
    }

    private fun initializeRealm() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                realm = (getApplication() as MyApplication).getRealmInstance()
                loadPunnetPrice()
            } catch (e: Exception) {
                _errorMessage.postValue("Помилка при ініціалізації Realm: ${e.message}")
            }
        }
    }

    fun handleScanResult(workerId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val worker = realm?.query<Worker>("_id == $0", workerId)?.first()?.find()
                if (worker == null) {
                    _errorMessage.postValue("Працівника не знайдено")
                    return@launch
                }

                val assignment = realm?.query<Assignment>("workerId == $0 AND isDeleted == false", workerId)
                    ?.first()?.find()

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
                // Используем неблокирующую операцию записи
                realm?.write {
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

        viewModelScope.launch(Dispatchers.IO) {
            try {
                realm?.let { r ->
                    r.write {
                        // Получаем настройки
                        val settings = query<Settings>().first().find()
                            ?: copyToRealm(Settings().apply {
                                _id = UUID.randomUUID().toString()
                            })

                        // Обновляем цену
                        findLatest(settings)?.apply {
                            punnetPrice = newPrice
                            isSynced = false
                        }
                    }
                    _punnetPrice.postValue(newPrice)
                } ?: run {
                    _errorMessage.postValue("Помилка: Realm не ініціалізовано")
                }
            } catch (e: Exception) {
                _errorMessage.postValue("Помилка при оновленні ціни: ${e.message}")
            }
        }
    }

    private fun loadPunnetPrice() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                realm?.let { r ->
                    val settings = r.query<Settings>().first().find()
                    if (settings != null) {
                        _punnetPrice.postValue(settings.punnetPrice)
                    } else {
                        r.write {
                            val newSettings = copyToRealm(Settings().apply {
                                _id = UUID.randomUUID().toString()
                                punnetPrice = 0.0f
                            })
                            _punnetPrice.postValue(newSettings.punnetPrice)
                        }
                    }
                } ?: run {
                    _punnetPrice.postValue(0.0f)
                    _errorMessage.postValue("Помилка: Realm не ініціалізовано")
                }
            } catch (e: Exception) {
                _punnetPrice.postValue(0.0f)
                _errorMessage.postValue("Помилка при завантаженні ціни: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        realm?.close() // Закрываем Realm при очистке ViewModel
    }

    fun clearErrorMessage() = _errorMessage.postValue(null)
    fun clearWorkerAssignment() = _workerAssignment.postValue(null)
    fun clearSuccessMessage() = _successMessage.postValue(false)
}

