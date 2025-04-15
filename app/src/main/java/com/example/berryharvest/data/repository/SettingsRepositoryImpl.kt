package com.example.berryharvest.data.repository

import android.app.Application
import android.util.Log
import com.example.berryharvest.data.model.Settings
import com.example.berryharvest.data.network.EnhancedNetworkManager
import io.realm.kotlin.MutableRealm
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import io.realm.kotlin.query.RealmQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Implementation of SettingsRepository that extends BaseRepositoryImpl.
 * Handles all Settings-specific data operations.
 */
class SettingsRepositoryImpl(
    application: Application,
    networkManager: EnhancedNetworkManager,
    databaseTransactionManager: DatabaseTransactionManager
) : BaseRepositoryImpl<Settings>(
    application = application,
    networkManager = networkManager,
    databaseTransactionManager = databaseTransactionManager,
    entityType = "settings",
    logTag = "SettingsRepository"
), SettingsRepository {

    /**
     * Create the base query for Settings entities.
     */
    override fun createQuery(realm: Realm): RealmQuery<Settings> {
        return realm.query<Settings>()
    }

    /**
     * Create a query to find a Settings by ID.
     */
    override fun createQueryById(realm: Realm, id: String): RealmQuery<Settings> {
        return realm.query<Settings>("_id == $0", id)
    }

    /**
     * Get the query to find unsynced Settings entities.
     */
    override fun getUnsyncedQuery(realm: Realm): RealmQuery<Settings> {
        return realm.query<Settings>("isSynced == false")
    }

    /**
     * Mark a Settings entity as synced.
     */
    override fun MutableRealm.markAsSynced(entity: Settings) {
        entity.isSynced = true
    }

    /**
     * Get a settings object or create one if it doesn't exist.
     */
    override suspend fun getSettings(): Result<Settings?> = withDatabaseContext { realm ->
        try {
            val settings = realm.query<Settings>().first().find()

            if (settings != null) {
                Result.Success(settings)
            } else {
                // Create default settings if none exists
                val defaultSettings = Settings().apply {
                    _id = UUID.randomUUID().toString()
                    punnetPrice = 0.0f
                    isSynced = networkManager.isNetworkAvailable()
                }

                // Save default settings
                val result = add(defaultSettings)

                if (result is Result.Success) {
                    val newSettings = realm.query<Settings>("_id == $0", result.data).first().find()
                    Result.Success(newSettings)
                } else {
                    Result.Error(Exception("Failed to create default settings"))
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error getting settings", e)
            Result.Error(e)
        }
    }

    /**
     * Get the current punnet price.
     */
    override suspend fun getPunnetPrice(): Float = withDatabaseContext { realm ->
        try {
            // Find all Settings objects
            val allSettings = realm.query<Settings>().find()

            if (allSettings.isEmpty()) {
                Log.d(logTag, "No settings found, returning default price 0")
                0f
            } else if (allSettings.size > 1) {
                // Multiple settings found - log warning and use the one with highest price
                Log.w(logTag, "Multiple settings found (${allSettings.size}), consolidating...")

                // Get the most recently modified setting or the one with the highest price
                val mostRecentSetting = allSettings.maxByOrNull { it.punnetPrice }

                // Schedule a cleanup for the duplicates (in a separate coroutine)
                cleanupDuplicateSettings(allSettings.map { it._id }, mostRecentSetting?._id)

                mostRecentSetting?.punnetPrice ?: 0f
            } else {
                // Normal case - just one settings object
                allSettings.first().punnetPrice
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error getting punnet price", e)
            0f
        }
    }

    /**
     * Update the punnet price.
     */
    override suspend fun updatePunnetPrice(price: Float): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Updating punnet price to $price")

            // Find all existing settings
            val allSettings = realm.query<Settings>().find()

            safeWrite {
                if (allSettings.isEmpty()) {
                    // No settings exist, create a new one
                    Log.d(logTag, "No settings found, creating new")
                    copyToRealm(Settings().apply {
                        _id = UUID.randomUUID().toString()
                        punnetPrice = price
                        isSynced = networkManager.isNetworkAvailable()
                    })
                } else if (allSettings.size > 1) {
                    // Multiple settings exist, update the first one and schedule cleanup
                    Log.w(logTag, "Multiple settings found (${allSettings.size}), consolidating")
                    val firstSetting = query<Settings>("_id == $0", allSettings.first()._id).first().find()
                    firstSetting?.apply {
                        punnetPrice = price
                        isSynced = networkManager.isNetworkAvailable()
                    }

                    // Schedule cleanup outside the transaction
                    val idToKeep = allSettings.first()._id
                    val allIds = allSettings.map { it._id }

                    // Launch cleanup after this transaction completes
                    CoroutineScope(Dispatchers.IO).launch {
                        cleanupDuplicateSettings(allIds, idToKeep)
                    }
                } else {
                    // Normal case - just one settings object
                    Log.d(logTag, "Updating existing settings")
                    val setting = query<Settings>().first().find()
                    setting?.punnetPrice = price
                    setting?.isSynced = networkManager.isNetworkAvailable()
                }
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                // Since we might not know the exact ID, we'll track all settings
                allSettings.forEach { setting ->
                    addPendingOperation(
                        PendingOperation.Update(setting._id, entityType)
                    )
                }
            }

            Log.d(logTag, "Punnet price updated successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error updating punnet price", e)
            setError("Failed to update price: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Add a new Settings entity.
     */
    override suspend fun add(item: Settings): Result<String> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting add operation for settings")
            var settingsId = ""

            // First check if settings already exist
            val existingSettings = realm.query<Settings>().first().find()
            Log.d(logTag, "Existing settings check: ${existingSettings?._id ?: "none"}")

            safeWrite {
                if (existingSettings != null) {
                    // Update existing settings
                    val liveSettings = query<Settings>("_id == $0", existingSettings._id).first().find()
                    liveSettings?.apply {
                        punnetPrice = item.punnetPrice
                        isSynced = networkManager.isNetworkAvailable()
                    }
                    settingsId = existingSettings._id
                    Log.d(logTag, "Updated existing settings: $settingsId")
                } else {
                    // Create new settings
                    val newSettings = copyToRealm(Settings().apply {
                        _id = item._id.ifEmpty { UUID.randomUUID().toString() }
                        punnetPrice = item.punnetPrice
                        isSynced = networkManager.isNetworkAvailable()
                    })
                    settingsId = newSettings._id
                    Log.d(logTag, "Created new settings: $settingsId")
                }
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Add(settingsId, entityType)
                )
            }

            Log.d(logTag, "Add operation completed successfully")
            Result.Success(settingsId)
        } catch (e: Exception) {
            Log.e(logTag, "Error in add", e)
            setError("Failed to add settings: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Update an existing Settings entity.
     */
    override suspend fun update(item: Settings): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting update operation for settings: ${item._id}")

            safeWrite {
                // Find the live object INSIDE transaction
                val settings = query<Settings>("_id == $0", item._id).first().find()
                settings?.apply {
                    punnetPrice = item.punnetPrice
                    isSynced = networkManager.isNetworkAvailable()
                }
                Log.d(logTag, "Updated settings: ${item._id}")
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Update(item._id, entityType)
                )
            }

            Log.d(logTag, "Update operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error in update", e)
            setError("Failed to update settings: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Delete a Settings entity by ID.
     */
    override suspend fun delete(id: String): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting delete operation for settings: $id")

            safeWrite {
                // Find the live object INSIDE transaction
                val settings = query<Settings>("_id == $0", id).first().find()
                settings?.let {
                    delete(it)
                    Log.d(logTag, "Deleted settings: $id")
                }
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Delete(id, entityType)
                )
            }

            Log.d(logTag, "Delete operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error in delete", e)
            setError("Failed to delete settings: ${e.message}")
            Result.Error(e)
        }
    }

    // Add this method to clean up duplicate settings
    private suspend fun cleanupDuplicateSettings(allIds: List<String>, idToKeep: String?) {
        if (idToKeep == null || allIds.size <= 1) return

        try {
            Log.d(logTag, "Starting cleanup of duplicate settings...")
            val realm = getRealm()

            realm.write {
                // Delete all settings except the one to keep
                allIds.filter { it != idToKeep }.forEach { id ->
                    val settingToDelete = query<Settings>("_id == $0", id).first().find()
                    settingToDelete?.let {
                        delete(it)
                        Log.d(logTag, "Deleted duplicate setting: $id")
                    }
                }
            }

            Log.d(logTag, "Duplicate settings cleanup completed")
        } catch (e: Exception) {
            Log.e(logTag, "Error cleaning up duplicate settings", e)
        }
    }

    override fun getEntityId(entity: Settings): String {
        return entity._id
    }

    override fun MutableRealm.findEntityById(id: String): Settings? {
        return this.query<Settings>("_id == $0", id).first().find()
    }
}