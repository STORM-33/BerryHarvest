package com.example.berryharvest.data.repository

import com.example.berryharvest.data.model.Worker
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for Worker entities.
 */
interface WorkerRepository : BaseRepository<Worker> {
    /**
     * Find workers by name or partial name.
     */
    fun getWorkersByName(name: String): Flow<Result<List<Worker>>>

    /**
     * Get multiple workers by their IDs.
     */
    suspend fun getWorkersByIds(ids: List<String>): Flow<Result<List<Worker>>>

    /**
     * Resolve sequence conflicts after offline operations.
     */
    suspend fun resolveSequenceConflicts(): Result<Boolean>

    /**
     * Find a worker by QR code.
     */
    suspend fun getWorkerByQrCode(qrCode: String): Result<Worker?>

    /**
     * Generate and assign a QR code to a worker.
     */
    suspend fun generateAndAssignQrCode(workerId: String): Result<String>

    /**
     * Find workers by their names.
     */
    suspend fun getWorkerIdsByNames(names: List<String>): Result<List<String>>

    /**
     * Hard delete a worker (admin only).
     */
    suspend fun hardDelete(id: String): Result<Boolean>
}