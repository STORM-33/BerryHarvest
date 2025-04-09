package com.example.berryharvest.ui.assign_rows

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.berryharvest.MyApplication
import com.example.berryharvest.NetworkConnectivityManager
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import io.realm.kotlin.mongodb.subscriptions
import io.realm.kotlin.mongodb.sync.ConnectionState
import io.realm.kotlin.mongodb.syncSession
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

class AssignRowsViewModel(application: Application) : AndroidViewModel(application) {
    private val app: MyApplication = getApplication() as MyApplication
    private val networkManager = NetworkConnectivityManager(application)

    private var _realm: Realm? = null
    private val realm: Realm?
        get() = _realm

    private val _realmInitialized = MutableStateFlow(false)
    val realmInitialized: StateFlow<Boolean> = _realmInitialized.asStateFlow()

    private val _assignments = MutableStateFlow<List<AssignmentGroup>>(emptyList())
    val assignments: StateFlow<List<AssignmentGroup>> = _assignments.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        initRealm()
        observeNetworkConnectivity()
    }

    private fun initRealm() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Используем супервизорскоп для обработки ошибок без отмены родительской корутины
                supervisorScope {
                    try {
                        withTimeout(15000) { // 15 секунд таймаут
                            _realm = app.getRealmInstance()
                            _realm?.let { realm ->
                                observeAssignments(realm)
                                _realmInitialized.value = true
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        _error.value = "Превышено время ожидания для подключения к базе данных"
                        Log.e("Realm", "Realm initialization timeout: ${e.message}")
                    } catch (e: Exception) {
                        _error.value = "Ошибка инициализации базы данных: ${e.message}"
                        Log.e("Realm", "Error initializing Realm: ${e.message}")
                    }
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun observeNetworkConnectivity() {
        networkManager.registerNetworkCallback { isConnected ->
            if (isConnected) {
                viewModelScope.launch {
                    Log.d("Network", "Connection restored, syncing data")
                    syncUnsyncedAssignments()
                }
            }
        }
    }

    private fun observeAssignments(realm: Realm) {
        viewModelScope.launch {
            realm.query<Assignment>().asFlow()
                .catch { throwable ->
                    Log.e("Realm", "Error in assignment flow: $throwable")
                    _error.value = "Ошибка при получении данных: ${throwable.message}"
                }
                .collect { changes ->
                    // Принудительно создаем новый список для гарантированного обновления UI
                    val assignmentsByRow = changes.list.toList().groupBy { it.rowNumber }
                    val groups = assignmentsByRow.map { (rowNumber, assignments) ->
                        AssignmentGroup(rowNumber, assignments.toList())
                    }.sortedBy { it.rowNumber }

                    _assignments.value = groups

                    // Проверяем статус синхронизации
                    if (changes.list.any { !it.isSynced } && networkManager.isNetworkAvailable()) {
                        syncUnsyncedAssignments()
                    }
                }
        }
    }

    private fun syncUnsyncedAssignments() {
        viewModelScope.launch {
            if (!networkManager.isNetworkAvailable()) {
                Log.d("Sync", "Network unavailable, skipping sync")
                return@launch
            }

            realm?.write {
                val unsyncedAssignments = query<Assignment>("isSynced == false").find()
                Log.d("Sync", "Found ${unsyncedAssignments.size} unsynced assignments")
                unsyncedAssignments.forEach { assignment ->
                    if (assignment.isDeleted) {
                        delete(assignment)
                        Log.d("Sync", "Deleted assignment: ${assignment._id}")
                    } else {
                        assignment.isSynced = true
                        Log.d("Sync", "Synced assignment: ${assignment._id}")
                    }
                }
            }
        }
    }

    // Предоставляем безопасный доступ к Realm
    fun obtainRealm(): Realm? = _realm

    // Безопасно выполняем операции с Realm
    fun executeRealmOperation(operation: suspend (Realm) -> Unit) {
        viewModelScope.launch {
            realm?.let {
                try {
                    operation(it)
                } catch (e: Exception) {
                    Log.e("Realm", "Error executing Realm operation: ${e.message}")
                    _error.value = "Ошибка операции с базой данных: ${e.message}"
                }
            } ?: run {
                Log.e("Realm", "Realm not initialized")
                _error.value = "База данных не инициализирована"

                // Пробуем заново инициализировать Realm
                if (!_realmInitialized.value) {
                    initRealm()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        _realm?.close()
        _realm = null
    }
}
