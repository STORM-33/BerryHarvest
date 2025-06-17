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
    suspend fun getAllGroupedByRow(): Flow<Result<List<AssignmentGroup>>>

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

    /**
     * Assign a worker to a row.
     */
    suspend fun assignWorkerToRow(workerId: String, rowNumber: Int): Result<String>

    /**
     * Move a worker to a different row.
     */
    suspend fun moveWorkerToRow(assignmentId: String, newRowNumber: Int): Result<Boolean>

    /**
     * Check if a worker is already assigned to a row.
     */
    suspend fun getWorkerAssignment(workerId: String): Result<Assignment?>

    /**
     * Delete all assignments in a row.
     */
    suspend fun completelyDeleteRow(rowNumber: Int): Result<Boolean>
}