package com.example.berryharvest.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * Base repository interface that defines standard operations for data access.
 * @param T The entity type managed by this repository.
 */
interface BaseRepository<T> {
    /**
     * Get all entities as a Flow.
     * @return Flow of Result containing a list of entities.
     */
    suspend fun getAll(): Flow<Result<List<T>>>

    /**
     * Get an entity by its ID.
     * @param id The ID of the entity to retrieve.
     * @return Result containing the entity or null if not found.
     */
    suspend fun getById(id: String): Result<T?>

    /**
     * Add a new entity.
     * @param item The entity to add.
     * @return Result containing the ID of the added entity.
     */
    suspend fun add(item: T): Result<String>

    /**
     * Update an existing entity.
     * @param item The entity to update with new values.
     * @return Result indicating success or failure.
     */
    suspend fun update(item: T): Result<Boolean>

    /**
     * Delete an entity by its ID.
     * @param id The ID of the entity to delete.
     * @return Result indicating success or failure.
     */
    suspend fun delete(id: String): Result<Boolean>

    /**
     * Get the current connection state.
     * @return Flow of ConnectionState.
     */
    fun getConnectionState(): Flow<ConnectionState>

    /**
     * Synchronize pending changes to the remote server.
     * @return Result indicating success or failure.
     */
    suspend fun syncPendingChanges(): Result<Boolean>

    /**
     * Close repository resources.
     */
    fun close()
}