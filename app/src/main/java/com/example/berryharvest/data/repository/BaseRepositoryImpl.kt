package com.example.berryharvest.data.repository

import android.app.Application
import android.util.Log
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.data.network.EnhancedNetworkManager
import io.realm.kotlin.MutableRealm
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import io.realm.kotlin.notifications.InitialResults
import io.realm.kotlin.notifications.UpdatedResults
import io.realm.kotlin.query.RealmQuery
import io.realm.kotlin.types.RealmObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Base implementation of [BaseRepository] that handles Realm operations safely.
 *
 * @param T The entity type managed by this repository. Must be a RealmObject.
 * @param entityType String identifier for the entity type (used in logging and pending operations).
 * @param logTag The tag to use for logging.
 */
abstract class BaseRepositoryImpl<T : RealmObject>(
    protected val application: Application,
    protected val networkManager: EnhancedNetworkManager,
    protected val databaseTransactionManager: DatabaseTransactionManager,
    protected val entityType: String,
    protected val logTag: String
) : BaseRepository<T> {

    protected val app: BerryHarvestApplication by lazy { application as BerryHarvestApplication }

    private var _realm: Realm? = null
    private val realmMutex = Mutex()
    private val isInitialized = AtomicBoolean(false)

    // Pending operations tracking
    private val _pendingOperations = MutableStateFlow<List<PendingOperation>>(emptyList())
    val pendingOperations: StateFlow<List<PendingOperation>> = _pendingOperations.asStateFlow()

    // Error state for better error propagation
    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    // Custom CoroutineScope for repository operations
    protected val repositoryScope = CoroutineScope(Dispatchers.IO)

    // Retry counter for operations
    private val operationRetryCounter = AtomicInteger(0)
    private val MAX_RETRY_ATTEMPTS = 3

    /**
     * Safely gets a Realm instance, initializing it if necessary.
     * Uses mutex to ensure thread safety when acquiring the instance.
     *
     * @return A valid Realm instance
     * @throws IllegalStateException if Realm initialization fails
     */
    protected suspend fun getRealm(): Realm {
        if (_realm != null && !_realm!!.isClosed()) {
            return _realm!!
        }

        return realmMutex.withLock {
            try {
                // Double-check after acquiring the lock
                if (_realm != null && !_realm!!.isClosed()) {
                    return@withLock _realm!!
                }

                Log.d(logTag, "Getting new Realm instance")
                val newRealm = app.getRealmInstance()
                _realm = newRealm

                if (!isInitialized.getAndSet(true)) {
                    // First-time initialization
                    onRepositoryInitialized(newRealm)
                }

                newRealm
            } catch (e: Exception) {
                Log.e(logTag, "Error getting Realm instance", e)
                _errorState.value = "Failed to initialize database: ${e.message}"
                throw e
            }
        }
    }

    /**
     * Called when the repository is first initialized with a Realm instance.
     * Override this to perform any one-time setup operations.
     *
     * @param realm The initialized Realm instance
     */
    protected open suspend fun onRepositoryInitialized(realm: Realm) {
        // Default implementation does nothing
        // Subclasses can override to add custom initialization logic
    }

    /**
     * Creates a query for this repository's entity type.
     * Override this method to provide the specific query for the entity.
     *
     * @param realm The Realm instance to query
     * @return A RealmQuery for the entity type
     */
    protected abstract fun createQuery(realm: Realm): RealmQuery<T>

    /**
     * Builds a query for a specific ID.
     * Override this method to provide the specific ID query for the entity.
     *
     * @param realm The Realm instance to query
     * @param id The ID to query for
     * @return A RealmQuery for the specific entity by ID
     */
    protected abstract fun createQueryById(realm: Realm, id: String): RealmQuery<T>

    /**
     * Safely executes a database operation with proper error handling.
     */
    protected suspend fun <R> withDatabaseContext(block: suspend (Realm) -> R): R {
        return databaseTransactionManager.executeQuery(block)
    }

    /**
     * Safely performs a write transaction with proper error handling and automatic retry on failures.
     */
    protected suspend fun <R> safeWrite(
        maxRetries: Int = MAX_RETRY_ATTEMPTS,
        block: MutableRealm.() -> R
    ): R {
        var attempts = 0
        var lastException: Exception? = null

        while (attempts < maxRetries) {
            try {
                return databaseTransactionManager.executeTransaction(block)
            } catch (e: Exception) {
                if (e is CancellationException) throw e // Don't catch cancellation

                lastException = e
                Log.w(logTag, "Transaction failed (attempt ${attempts + 1}/$maxRetries): ${e.message}")
                attempts++

                if (attempts < maxRetries) {
                    // Exponential backoff
                    val delayMs = (100L * (1 shl attempts))
                    withContext(Dispatchers.IO) {
                        kotlinx.coroutines.delay(delayMs)
                    }
                }
            }
        }

        throw lastException ?: IllegalStateException("Transaction failed after $maxRetries attempts")
    }

    /**
     * Performs a write transaction with timeout to prevent indefinite blocking.
     */
    override suspend fun <R> safeWriteWithTimeout(block: MutableRealm.() -> R): R {
        return withTimeout(5.seconds) {
            safeWrite(block = block)
        }
    }

    /**
     * Get all entities as a Flow.
     *
     * @return Flow of Result containing a list of entities.
     */
    override suspend fun getAll(): Flow<Result<List<T>>> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Getting all $entityType entities")

            createQuery(realm).asFlow()
                .onEach { result ->
                    when (result) {
                        is InitialResults -> {
                            Log.d(logTag, "Initial load of ${result.list.size} $entityType entities")
                        }
                        is UpdatedResults -> {
                            Log.d(logTag, "Updated ${result.list.size} $entityType entities")
                        }
                    }
                }
                .map { result ->
                    Result.Success(result.list.toList()) as Result<List<T>>
                }
                .catch { e ->
                    Log.e(logTag, "Error in getAll flow", e)
                    _errorState.value = "Failed to load $entityType: ${e.message}"
                    emit(Result.Error(Exception(e)) as Result<List<T>>)
                }
        } catch (e: Exception) {
            Log.e(logTag, "Error setting up getAll flow", e)
            _errorState.value = "Failed to load $entityType: ${e.message}"
            throw e
        }
    }

    /**
     * Get an entity by its ID.
     *
     * @param id The ID of the entity to retrieve.
     * @return Result containing the entity or null if not found.
     */
    override suspend fun getById(id: String): Result<T?> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Getting $entityType by ID: $id")

            val item = createQueryById(realm, id).first().find()
            Log.d(logTag, "$entityType found: ${item != null}")

            Result.Success(item)
        } catch (e: Exception) {
            Log.e(logTag, "Error in getById", e)
            _errorState.value = "Failed to get $entityType: ${e.message}"
            Result.Error(e)
        }
    }

    /**
     * Synchronize pending changes to the remote server.
     * This method will mark local unsynchronized records as synchronized.
     *
     * @return Result indicating success or failure.
     */
    override suspend fun syncPendingChanges(): Result<Boolean> = withDatabaseContext { realm ->
        try {
            if (!networkManager.isNetworkAvailable()) {
                Log.d(logTag, "Network not available, skipping sync")
                return@withDatabaseContext Result.Error(Exception("Network not available"))
            }

            val operations = _pendingOperations.value
            Log.d(logTag, "Syncing ${operations.size} pending operations for $entityType")

            // Check for unsynced entities
            val unsyncedCount = getUnsyncedCount(realm)

            if (unsyncedCount > 0) {
                Log.d(logTag, "Found $unsyncedCount unsynced $entityType entities")

                // Get IDs of unsynced entities before entering the write transaction
                val unsyncedEntities = getUnsyncedQuery(realm).find()
                val unsyncedIds = unsyncedEntities.map { getEntityId(it) }

                if (unsyncedIds.isNotEmpty()) {
                    // Try with a timeout to avoid indefinite waiting
                    withTimeout(10.seconds) {
                        app.safeWriteTransaction {
                            for (id in unsyncedIds) {
                                // Use the abstract method to find entity by ID
                                val entity = findEntityById(id)
                                if (entity != null) {
                                    markAsSynced(entity)
                                    Log.d(logTag, "Marked $entityType entity as synced: $id")
                                }
                            }
                        }
                    }
                }
            } else {
                Log.d(logTag, "No unsynced $entityType entities found")
            }

            // Clear pending operations after successful sync
            _pendingOperations.value = emptyList()
            Log.d(logTag, "Cleared pending operations")

            Log.d(logTag, "SyncPendingChanges operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error in syncPendingChanges", e)
            _errorState.value = "Failed to sync changes: ${e.message}"
            Result.Error(e)
        }
    }

    protected abstract fun getEntityId(entity: T): String

    protected abstract fun MutableRealm.findEntityById(id: String): T?

    /**
     * Get the query to find unsynced entities.
     * Override this to provide entity-specific unsynced query.
     *
     * @param realm The Realm instance
     * @return RealmQuery finding unsynced entities
     */
    protected abstract fun getUnsyncedQuery(realm: Realm): RealmQuery<T>

    /**
     * Get the count of unsynced entities.
     *
     * @param realm The Realm instance
     * @return Count of unsynced entities
     */
    protected open suspend fun getUnsyncedCount(realm: Realm): Long {
        return getUnsyncedQuery(realm).count().find()
    }

    /**
     * Mark an entity as synced.
     * Override this to provide entity-specific sync marking.
     *
     * @param entity The entity to mark as synced
     */
    protected abstract fun MutableRealm.markAsSynced(entity: T)

    /**
     * Add a pending operation to the list.
     *
     * @param operation The operation to add
     */
    protected fun addPendingOperation(operation: PendingOperation) {
        val currentOperations = _pendingOperations.value.toMutableList()
        currentOperations.add(operation)
        _pendingOperations.value = currentOperations
        Log.d(logTag, "Added pending operation: $operation")
    }

    /**
     * Get the current connection state.
     *
     * @return Flow of ConnectionState.
     */
    override fun getConnectionState(): Flow<ConnectionState> {
        return networkManager.connectionState
    }

    /**
     * Start monitoring for network connectivity and sync when back online.
     *
     * @param coroutineScope The CoroutineScope to launch the monitoring in
     */
    fun startSyncWhenOnline(coroutineScope: CoroutineScope) {
        coroutineScope.launch {
            networkManager.connectionState
                .filter { it is ConnectionState.Connected }
                .collect {
                    Log.d(logTag, "Network connection restored, syncing pending changes")
                    syncPendingChanges()
                }
        }
    }

    /**
     * Checks if there are pending operations that need to be synced.
     *
     * @return True if there are pending operations.
     */
    override fun hasPendingOperations(): Boolean {
        return _pendingOperations.value.isNotEmpty()
    }

    /**
     * Get the count of pending operations.
     *
     * @return The number of pending operations.
     */
    override fun getPendingOperationsCount(): Int {
        return _pendingOperations.value.size
    }

    /**
     * Set an error message in the error state flow.
     *
     * @param message The error message
     */
    protected fun setError(message: String) {
        _errorState.value = message
    }

    /**
     * Clear the current error message.
     */
    fun clearError() {
        _errorState.value = null
    }

    /**
     * Clean up resources when the repository is no longer needed.
     * Closes the Realm instance if open.
     */
    override fun close() {
        _realm?.let { realm ->
            if (!realm.isClosed()) {
                realm.close()
                Log.d(logTag, "Closed Realm instance")
            }
        }
        _realm = null
        Log.d(logTag, "Repository closed")
    }
}