package com.example.berryharvest.data.repository

import android.app.Application
import android.util.Log
import com.example.berryharvest.NetworkConnectivityManager
import com.example.berryharvest.data.model.Settings
import io.realm.kotlin.MutableRealm
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import io.realm.kotlin.query.RealmQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Implementation of SettingsRepository that extends BaseRepositoryImpl.
 * Handles all Settings-specific data operations with real-time price observation.
 */
class SettingsRepositoryImpl(
    application: Application,
    networkManager: NetworkConnectivityManager,
    databaseTransactionManager: DatabaseTransactionManager
) : BaseRepositoryImpl<Settings>(
    application = application,
    networkManager = networkManager,
    databaseTransactionManager = databaseTransactionManager,
    entityType = "settings",
    logTag = "SettingsRepository"
), SettingsRepository {

    // Real-time price observation
    private val _punnetPriceFlow = MutableStateFlow(0.0f)
    override val punnetPriceFlow: StateFlow<Float> = _punnetPriceFlow.asStateFlow()

    private val SINGLE_SETTINGS_ID = "DEFAULT_SETTINGS"

    // Background coroutine scope for observing changes
    private val backgroundScope = CoroutineScope(Dispatchers.IO)

    init {
        // Initialize the price flow
        observePriceChanges()
    }

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
     * Observe price changes from the database and update the flow
     * IMPROVED: Better error handling and reduced frequency
     */
    private fun observePriceChanges() {
        backgroundScope.launch {
            try {
                // Reduced frequency to avoid excessive database access
                while (true) {
                    try {
                        val currentPrice = withDatabaseContext { realm ->
                            val settings = realm.query<Settings>("_id == $0", SINGLE_SETTINGS_ID).first().find()
                            settings?.punnetPrice ?: 0.0f
                        }

                        if (_punnetPriceFlow.value != currentPrice) {
                            _punnetPriceFlow.value = currentPrice
                            Log.d(logTag, "Price updated in flow: $currentPrice")
                        }
                    } catch (e: Exception) {
                        Log.w(logTag, "Error checking price, will retry: ${e.message}")
                        // Don't break the loop for individual errors
                    }

                    delay(5000) // Reduced frequency: check every 5 seconds instead of 1
                }
            } catch (e: Exception) {
                Log.e(logTag, "Price observation stopped due to error", e)

                // Try to restart observation after a delay
                try {
                    delay(10000) // Wait 10 seconds before restarting
                    observePriceChanges() // Restart observation
                } catch (restartError: Exception) {
                    Log.e(logTag, "Failed to restart price observation", restartError)
                }
            }
        }
    }

    /**
     * Get a settings object or create one if it doesn't exist.
     * Always ensures only ONE settings object exists.
     * FIXED: Properly handles frozen objects using findLatest()
     */
    override suspend fun getSettings(): Result<Settings?> = withDatabaseContext { realm ->
        try {
            // First, check for the canonical settings
            var settings = realm.query<Settings>("_id == $0", SINGLE_SETTINGS_ID).first().find()

            if (settings == null) {
                // Check if any settings exist at all
                val allSettings = realm.query<Settings>().find()

                if (allSettings.isNotEmpty()) {
                    Log.w(logTag, "Found ${allSettings.size} settings, consolidating to single settings")

                    // Get the price from the first setting before deletion
                    val preservedPrice = allSettings.firstOrNull()?.punnetPrice ?: 0.0f

                    // FIXED: Safely delete all existing settings using proper Realm transaction handling
                    safeWrite {
                        // Get fresh references to all settings within the transaction
                        val settingsToDelete = query<Settings>().find()

                        // Collect IDs first to avoid iteration issues
                        val settingsIds = settingsToDelete.map { it._id }

                        // Delete each settings record by finding it fresh in the transaction
                        settingsIds.forEach { id ->
                            val settingToDelete = query<Settings>("_id == $0", id).first().find()
                            settingToDelete?.let {
                                delete(it)
                                Log.d(logTag, "Deleted settings record: $id")
                            }
                        }
                    }

                    // Create the canonical settings with the preserved price
                    settings = createCanonicalSettings(preservedPrice)
                } else {
                    // No settings exist, create default
                    settings = createCanonicalSettings(0.0f)
                }
            }

            Result.Success(settings)
        } catch (e: Exception) {
            Log.e(logTag, "Error getting settings", e)

            // Enhanced error recovery: try to create default settings if everything fails
            try {
                Log.w(logTag, "Attempting error recovery by creating default settings")
                val defaultSettings = createCanonicalSettings(0.0f)
                if (defaultSettings != null) {
                    Log.i(logTag, "Successfully recovered with default settings")
                    return@withDatabaseContext Result.Success(defaultSettings)
                }
            } catch (recoveryError: Exception) {
                Log.e(logTag, "Error recovery also failed", recoveryError)
            }

            Result.Error(e)
        }
    }

    /**
     * Create the single canonical settings object
     * FIXED: Proper thread handling for Realm access
     */
    private suspend fun createCanonicalSettings(price: Float): Settings? {
        return try {
            var resultPrice = price

            safeWrite {
                // Check if canonical settings already exist in this transaction
                val existingSettings = query<Settings>("_id == $0", SINGLE_SETTINGS_ID).first().find()

                if (existingSettings != null) {
                    // Update existing canonical settings
                    existingSettings.punnetPrice = price
                    existingSettings.isSynced = false
                    Log.d(logTag, "Updated existing canonical settings with price: $price")
                } else {
                    // Create new canonical settings
                    copyToRealm(Settings().apply {
                        _id = SINGLE_SETTINGS_ID
                        punnetPrice = price
                        isSynced = false
                    })
                    Log.d(logTag, "Created new canonical settings with price: $price")
                }

                // Return the price from the transaction
                resultPrice = price
            }

            // Update the price flow after successful transaction
            _punnetPriceFlow.value = resultPrice

            // Return a simple settings object (not a Realm object to avoid threading issues)
            Settings().apply {
                _id = SINGLE_SETTINGS_ID
                punnetPrice = resultPrice
                isSynced = false
            }

        } catch (e: Exception) {
            Log.e(logTag, "Error creating canonical settings", e)
            null
        }
    }

    /**
     * Get the current punnet price.
     */
    override suspend fun getPunnetPrice(): Float = withDatabaseContext { realm ->
        try {
            val settings = realm.query<Settings>("_id == $0", SINGLE_SETTINGS_ID).first().find()
            val price = settings?.punnetPrice ?: 0f
            Log.d(logTag, "Retrieved punnet price: $price")
            price
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

            // Ensure settings exists first
            getSettings()

            safeWrite {
                val settings = query<Settings>("_id == $0", SINGLE_SETTINGS_ID).first().find()
                if (settings != null) {
                    settings.punnetPrice = price
                    settings.isSynced = false // Mark as unsynced for proper sync handling
                    Log.d(logTag, "Updated existing settings with price: $price")
                } else {
                    // This shouldn't happen after getSettings(), but just in case
                    copyToRealm(Settings().apply {
                        _id = SINGLE_SETTINGS_ID
                        punnetPrice = price
                        isSynced = false
                    })
                    Log.d(logTag, "Created new settings with price: $price")
                }
            }

            // Track pending operation for sync
            addPendingOperation(PendingOperation.Update(SINGLE_SETTINGS_ID, entityType))

            // Update the flow immediately
            _punnetPriceFlow.value = price

            Log.d(logTag, "Punnet price updated successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error updating punnet price", e)
            setError("Failed to update price: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Add a new Settings entity - simplified to always use canonical settings
     */
    override suspend fun add(item: Settings): Result<String> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting add operation for settings")

            safeWrite {
                // Always use the canonical ID
                val settings = query<Settings>("_id == $0", SINGLE_SETTINGS_ID).first().find()
                if (settings != null) {
                    // Update existing
                    settings.punnetPrice = item.punnetPrice
                    settings.isSynced = false
                    Log.d(logTag, "Updated existing canonical settings")
                } else {
                    // Create new
                    copyToRealm(Settings().apply {
                        _id = SINGLE_SETTINGS_ID
                        punnetPrice = item.punnetPrice
                        isSynced = false
                    })
                    Log.d(logTag, "Created new canonical settings")
                }
            }

            // Track pending operation
            addPendingOperation(PendingOperation.Add(SINGLE_SETTINGS_ID, entityType))

            // Update the flow
            _punnetPriceFlow.value = item.punnetPrice

            Log.d(logTag, "Add operation completed successfully")
            Result.Success(SINGLE_SETTINGS_ID)
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
                // Always update the canonical settings regardless of input ID
                val settings = query<Settings>("_id == $0", SINGLE_SETTINGS_ID).first().find()
                settings?.apply {
                    punnetPrice = item.punnetPrice
                    isSynced = false // Mark as unsynced
                }
                Log.d(logTag, "Updated canonical settings")
            }

            // Track pending operation
            addPendingOperation(PendingOperation.Update(SINGLE_SETTINGS_ID, entityType))

            // Update the flow
            _punnetPriceFlow.value = item.punnetPrice

            Log.d(logTag, "Update operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error in update", e)
            setError("Failed to update settings: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Delete a Settings entity by ID - prevent deletion of canonical settings
     */
    override suspend fun delete(id: String): Result<Boolean> = withDatabaseContext { realm ->
        try {
            if (id == SINGLE_SETTINGS_ID) {
                Log.w(logTag, "Attempt to delete canonical settings prevented")
                return@withDatabaseContext Result.Error(Exception("Cannot delete canonical settings"))
            }

            Log.d(logTag, "Starting delete operation for settings: $id")

            safeWrite {
                val settings = query<Settings>("_id == $0", id).first().find()
                settings?.let {
                    delete(it)
                    Log.d(logTag, "Deleted settings: $id")
                }
            }

            // Track pending operation
            addPendingOperation(PendingOperation.Delete(id, entityType))

            Log.d(logTag, "Delete operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error in delete", e)
            setError("Failed to delete settings: ${e.message}")
            Result.Error(e)
        }
    }

    override fun getEntityId(entity: Settings): String {
        return entity._id
    }

    override fun MutableRealm.findEntityById(id: String): Settings? {
        return this.query<Settings>("_id == $0", id).first().find()
    }

    /**
     * Override sync to properly handle settings sync
     */
    override suspend fun syncPendingChanges(): Result<Boolean> {
        Log.d(logTag, "Starting settings sync")

        return try {
            // First ensure we have canonical settings
            getSettings()

            // Then sync normally
            super.syncPendingChanges()
        } catch (e: Exception) {
            Log.e(logTag, "Error during settings sync", e)
            Result.Error(e)
        }
    }

    /**
     * Override close to clean up background scope
     */
    override fun close() {
        backgroundScope.cancel()
        super.close()
    }
}