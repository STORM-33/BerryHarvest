package com.example.berryharvest.data.repository

import android.app.Application
import android.util.Log
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.data.network.EnhancedNetworkManager
import com.example.berryharvest.data.model.Settings
import io.realm.kotlin.MutableRealm
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import io.realm.kotlin.mongodb.subscriptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private const val TAG = "SettingsRepository"
private const val ENTITY_TYPE = "settings"

class SettingsRepository(
    private val application: Application,
    private val networkManager: EnhancedNetworkManager
) : BaseRepository<Settings> {

    private val app: BerryHarvestApplication = application as BerryHarvestApplication
    private var _realm: Realm? = null
    private val _pendingOperations = MutableStateFlow<List<PendingOperation>>(emptyList())
    val pendingOperations: StateFlow<List<PendingOperation>> = _pendingOperations.asStateFlow()

    // Add error state for better error propagation
    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    private suspend fun getRealm(): Realm {
        if (_realm != null && !_realm!!.isClosed()) {
            return _realm!!
        }

        // Get realm outside of synchronized block to avoid suspension in critical section
        return try {
            Log.d(TAG, "Getting Realm instance")
            val realm = app.getRealmInstance()
            synchronized(this) {
                _realm = realm
            }
            Log.d(TAG, "Successfully obtained Realm instance")
            realm
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Realm instance", e)
            _errorState.value = "Failed to initialize database: ${e.message}"
            throw e
        }
    }

    override suspend fun getAll(): Flow<Result<List<Settings>>> = flow {
        emit(Result.Loading)
        try {
            Log.d(TAG, "Getting all settings")
            val realm = getRealm()
            realm.query<Settings>().asFlow()
                .map { result ->
                    Log.d(TAG, "Received ${result.list.size} settings in flow")
                    Result.Success(result.list.toList())
                }
                .catch { e ->
                    Log.e(TAG, "Error in getAll flow", e)
                    emit(Result.Error(Exception(e)))
                }
                .collect { emit(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getAll", e)
            _errorState.value = "Failed to load settings: ${e.message}"
            emit(Result.Error(e))
        }
    }

    override suspend fun getById(id: String): Result<Settings?> {
        return try {
            Log.d(TAG, "Getting settings by ID: $id")
            val realm = getRealm()
            val settings = realm.query<Settings>("_id == $0", id).first().find()
            Log.d(TAG, "Settings found: ${settings != null}")
            Result.Success(settings)
        } catch (e: Exception) {
            Log.e(TAG, "Error in getById", e)
            _errorState.value = "Failed to get settings: ${e.message}"
            Result.Error(e)
        }
    }

    suspend fun getSettings(): Result<Settings?> {
        return try {
            Log.d(TAG, "Getting settings")
            val realm = getRealm()
            val settings = realm.query<Settings>().first().find()
            Log.d(TAG, "Settings found: ${settings != null}")
            Result.Success(settings)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting settings", e)
            _errorState.value = "Failed to get settings: ${e.message}"
            Result.Error(e)
        }
    }

    suspend fun getPunnetPrice(): Float {
        try {
            val realm = getRealm()

            // Find all Settings objects
            val allSettings = realm.query<Settings>().find()

            if (allSettings.isEmpty()) {
                Log.d(TAG, "No settings found, returning default price 0")
                return 0f
            } else if (allSettings.size > 1) {
                // Multiple settings found - log warning and use the one with highest price
                Log.w(TAG, "Multiple settings found (${allSettings.size}), consolidating...")

                // Get the most recently modified setting (assuming higher ID is more recent)
                // or the one with the highest price if you prefer
                val mostRecentSetting = allSettings.maxByOrNull { it.punnetPrice }

                // Schedule a cleanup for the duplicates (in a separate coroutine)
                cleanupDuplicateSettings(allSettings.map { it._id }, mostRecentSetting?._id)

                return mostRecentSetting?.punnetPrice ?: 0f
            } else {
                // Normal case - just one settings object
                return allSettings.first().punnetPrice
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting punnet price", e)
            return 0f
        }
    }

    // Add this method to clean up duplicate settings
    private fun cleanupDuplicateSettings(allIds: List<String>, idToKeep: String?) {
        if (idToKeep == null || allIds.size <= 1) return

        // Launch in a background scope to not block the UI
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Starting cleanup of duplicate settings...")
                val realm = getRealm()

                realm.write {
                    // Delete all settings except the one to keep
                    val settingsToDelete = query<Settings>("_id != $0", idToKeep).find()
                    Log.d(TAG, "Found ${settingsToDelete.size} settings to delete")

                    settingsToDelete.forEach { setting ->
                        try {
                            delete(setting)
                            Log.d(TAG, "Deleted duplicate setting: ${setting._id}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to delete setting: ${setting._id}", e)
                        }
                    }
                }

                Log.d(TAG, "Duplicate settings cleanup completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up duplicate settings", e)
            }
        }
    }

    suspend fun updatePunnetPrice(price: Float): Result<Boolean> {
        return try {
            Log.d(TAG, "Updating punnet price to $price")
            val realm = getRealm()

            // Find all existing settings
            val allSettings = realm.query<Settings>().find()

            app.safeWriteTransaction {
                if (allSettings.isEmpty()) {
                    // No settings exist, create a new one
                    Log.d(TAG, "No settings found, creating new")
                    copyToRealm(Settings().apply {
                        _id = UUID.randomUUID().toString()
                        punnetPrice = price
                        isSynced = networkManager.isNetworkAvailable()
                    })
                } else if (allSettings.size > 1) {
                    // Multiple settings exist, update the first one and schedule cleanup
                    Log.w(TAG, "Multiple settings found (${allSettings.size}), consolidating")
                    val firstSetting = query<Settings>("_id == $0", allSettings.first()._id).first().find()
                    firstSetting?.punnetPrice = price
                    firstSetting?.isSynced = networkManager.isNetworkAvailable()

                    // Schedule cleanup outside the transaction
                    val idToKeep = allSettings.first()._id
                    val allIds = allSettings.map { it._id }

                    // Launch cleanup after this transaction completes
                    CoroutineScope(Dispatchers.IO).launch {
                        cleanupDuplicateSettings(allIds, idToKeep)
                    }
                } else {
                    // Normal case - just one settings object
                    Log.d(TAG, "Updating existing settings")
                    val setting = query<Settings>().first().find()
                    setting?.punnetPrice = price
                    setting?.isSynced = networkManager.isNetworkAvailable()
                }
            }

            Log.d(TAG, "Punnet price updated successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating punnet price", e)
            _errorState.value = "Failed to update price: ${e.message}"
            Result.Error(e)
        }
    }

    override suspend fun add(item: Settings): Result<String> {
        Log.d(TAG, "Starting add operation for settings")
        return try {
            val realm = getRealm()
            var settingsId = ""

            // First check if settings already exist
            val existingSettings = realm.query<Settings>().first().find()
            Log.d(TAG, "Existing settings check: ${existingSettings?._id ?: "none"}")

            app.safeWriteTransaction {
                if (existingSettings != null) {
                    // Update existing settings
                    val liveSettings = query<Settings>("_id == $0", existingSettings._id).first().find()
                    liveSettings?.apply {
                        punnetPrice = item.punnetPrice
                        isSynced = networkManager.isNetworkAvailable()
                    }
                    settingsId = existingSettings._id
                    Log.d(TAG, "Updated existing settings: $settingsId")
                } else {
                    // Create new settings
                    val newSettings = copyToRealm(Settings().apply {
                        _id = item._id.ifEmpty { java.util.UUID.randomUUID().toString() }
                        punnetPrice = item.punnetPrice
                        isSynced = networkManager.isNetworkAvailable()
                    })
                    settingsId = newSettings._id
                    Log.d(TAG, "Created new settings: $settingsId")
                }
            }

            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Add(settingsId, ENTITY_TYPE)
                )
            }

            Log.d(TAG, "Add operation completed successfully")
            Result.Success(settingsId)
        } catch (e: Exception) {
            Log.e(TAG, "Error in add", e)
            _errorState.value = "Failed to add settings: ${e.message}"
            Result.Error(e)
        }
    }

    override suspend fun update(item: Settings): Result<Boolean> {
        Log.d(TAG, "Starting update operation for settings: ${item._id}")
        return try {
            app.safeWriteTransaction {
                // Find the live object INSIDE transaction
                val settings = query<Settings>("_id == $0", item._id).first().find()
                settings?.apply {
                    punnetPrice = item.punnetPrice
                    isSynced = networkManager.isNetworkAvailable()
                }
                Log.d(TAG, "Updated settings: ${item._id}")
            }

            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Update(item._id, ENTITY_TYPE)
                )
            }

            Log.d(TAG, "Update operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in update", e)
            _errorState.value = "Failed to update settings: ${e.message}"
            Result.Error(e)
        }
    }

    override suspend fun delete(id: String): Result<Boolean> {
        Log.d(TAG, "Starting delete operation for settings: $id")
        return try {
            app.safeWriteTransaction {
                // Find the live object INSIDE transaction
                val settings = query<Settings>("_id == $0", id).first().find()
                settings?.let {
                    delete(it)
                    Log.d(TAG, "Deleted settings: $id")
                }
            }

            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Delete(id, ENTITY_TYPE)
                )
            }

            Log.d(TAG, "Delete operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in delete", e)
            _errorState.value = "Failed to delete settings: ${e.message}"
            Result.Error(e)
        }
    }

    override suspend fun syncPendingChanges(): Result<Boolean> {
        Log.d(TAG, "Starting syncPendingChanges operation")
        return try {
            if (!networkManager.isNetworkAvailable()) {
                Log.d(TAG, "Network not available, skipping sync")
                return Result.Error(Exception("Network not available"))
            }

            val operations = _pendingOperations.value
            Log.d(TAG, "Syncing ${operations.size} pending operations")

            val realm = getRealm()

            // Only start transaction if there are unsynced settings
            val unsyncedCount = realm.query<Settings>("isSynced == false").count().find()

            if (unsyncedCount > 0) {
                Log.d(TAG, "Found $unsyncedCount unsynced settings")

                // Try with a timeout to avoid indefinite waiting
                withTimeout(10000) {
                    app.safeWriteTransaction {
                        val unsyncedSettings = query<Settings>("isSynced == false").find()
                        Log.d(TAG, "Processing ${unsyncedSettings.size} unsynced settings")

                        unsyncedSettings.forEach { settings ->
                            settings.isSynced = true
                            Log.d(TAG, "Marked settings ${settings._id} as synced")
                        }
                    }
                }
            } else {
                Log.d(TAG, "No unsynced settings found")
            }

            // Clear pending operations after successful sync
            _pendingOperations.value = emptyList()
            Log.d(TAG, "Cleared pending operations")

            Log.d(TAG, "SyncPendingChanges operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in syncPendingChanges", e)
            _errorState.value = "Failed to sync changes: ${e.message}"
            Result.Error(e)
        }
    }

    override fun getConnectionState(): Flow<ConnectionState> {
        return networkManager.connectionState
    }

    private fun addPendingOperation(operation: PendingOperation) {
        val currentOperations = _pendingOperations.value.toMutableList()
        currentOperations.add(operation)
        _pendingOperations.value = currentOperations
        Log.d(TAG, "Added pending operation: $operation")
    }

    override fun hasPendingOperations(): Boolean {
        return _pendingOperations.value.isNotEmpty()
    }

    override fun getPendingOperationsCount(): Int {
        return _pendingOperations.value.size
    }

    override suspend fun <R> safeWriteWithTimeout(block: MutableRealm.() -> R): R {
        val app = application as BerryHarvestApplication
        return withTimeout(5.seconds) {
            app.safeWriteTransaction(block)
        }
    }

    override fun close() {
        _realm?.close()
        _realm = null
        Log.d(TAG, "Repository closed")
    }
}