package com.example.berryharvest.data.repository

import com.example.berryharvest.data.model.Gather
import com.example.berryharvest.ui.gather.GatherWithDetails
import com.example.berryharvest.ui.gather.TodayStats

/**
 * Repository interface for Gather entities.
 */
interface GatherRepository : BaseRepository<Gather> {
    /**
     * Get today's gathers with worker details.
     */
    suspend fun getTodayGathers(): Result<List<GatherWithDetails>>

    /**
     * Get gathers for a specific worker.
     */
    suspend fun getGathersByWorkerId(workerId: String): Result<List<Gather>>

    /**
     * Calculate statistics for today.
     */
    suspend fun calculateTodayStats(): Result<TodayStats>

    /**
     * Get count of unsynced gathers.
     */
    suspend fun getUnsyncedGathersCount(): Int

    /**
     * Get total earnings for a worker.
     */
    suspend fun getWorkerEarnings(workerId: String): Result<Float>

    /**
     * Get worker's production for today.
     */
    suspend fun getWorkerTodayProduction(workerId: String): Result<Int>

    /**
     * Record a new gather with full details.
     */
    suspend fun recordGather(workerId: String, rowNumber: Int, numOfPunnets: Int, punnetCost: Float): Result<String>

    /**
     * Update an existing gather record.
     */
    suspend fun updateGatherDetails(gatherId: String, numOfPunnets: Int): Result<Boolean>
}