package com.example.berryharvest.data.repository

import com.example.berryharvest.data.model.Assignment
import com.example.berryharvest.ui.assign_rows.AssignmentGroup
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for Assignment entities.
 */
interface AssignmentRepository : BaseRepository<Assignment> {
    /**
     * Get all assignments grouped by row.
     */
    fun getAllGroupedByRow(): Flow<Result<List<AssignmentGroup>>>

    /**
     * Delete all assignments for a specific row.
     */
    suspend fun deleteByRow(rowNumber: Int): Result<Boolean>

    /**
     * Get all row numbers that have assignments.
     */
    suspend fun getAllRowNumbers(): List<Int>

    /**
     * Find the row assigned to a worker.
     */
    suspend fun findWorkerRow(workerId: String): Int

    /**
     * Get all worker IDs assigned to a specific row.
     */
    suspend fun getWorkersInRow(rowNumber: Int): Result<List<String>>

    /**
     * Move all workers from one row to another.
     */
    suspend fun moveRow(fromRow: Int, toRow: Int): Result<Boolean>
}