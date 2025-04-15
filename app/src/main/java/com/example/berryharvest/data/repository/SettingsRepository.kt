package com.example.berryharvest.data.repository

import com.example.berryharvest.data.model.Settings

/**
 * Repository interface for Settings entities.
 */
interface SettingsRepository : BaseRepository<Settings> {
    /**
     * Get the current Settings object or create default if none exists.
     */
    suspend fun getSettings(): Result<Settings?>

    /**
     * Get the current punnet price.
     */
    suspend fun getPunnetPrice(): Float

    /**
     * Update the punnet price.
     */
    suspend fun updatePunnetPrice(price: Float): Result<Boolean>
}