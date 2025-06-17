package com.example.berryharvest.data.repository

import com.example.berryharvest.data.model.Row
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for Row entities.
 */
interface RowRepository : BaseRepository<Row> {
    /**
     * Get rows by quarter.
     */
    suspend fun getRowsByQuarter(quarter: Int): Flow<Result<List<Row>>>

    /**
     * Get rows by multiple quarters.
     */
    suspend fun getRowsByQuarters(quarters: List<Int>): Flow<Result<List<Row>>>

    /**
     * Get collected rows only.
     */
    suspend fun getCollectedRows(): Flow<Result<List<Row>>>

    /**
     * Get uncollected rows only.
     */
    suspend fun getUncollectedRows(): Flow<Result<List<Row>>>

    /**
     * Get rows in a specific range.
     */
    suspend fun getRowsInRange(startRow: Int, endRow: Int): Flow<Result<List<Row>>>

    /**
     * Mark a row as collected or uncollected.
     */
    suspend fun updateRowCollectionStatus(rowId: String, isCollected: Boolean): Result<Boolean>

    /**
     * Mark multiple rows as collected or uncollected.
     */
    suspend fun updateMultipleRowsCollectionStatus(rowIds: List<String>, isCollected: Boolean): Result<Boolean>

    /**
     * Initialize default rows for all quarters.
     */
    suspend fun initializeDefaultRows(): Result<Boolean>

    /**
     * Get row count by collection status.
     */
    suspend fun getRowCountByStatus(quarter: Int? = null): Result<Map<String, Int>>

    /**
     * Update berry variety for a row.
     */
    suspend fun updateRowBerryVariety(rowId: String, berryVariety: String): Result<Boolean>

    /**
     * Get rows by berry variety.
     */
    suspend fun getRowsByBerryVariety(berryVariety: String): Flow<Result<List<Row>>>
}