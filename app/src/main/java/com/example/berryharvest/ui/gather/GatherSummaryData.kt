package com.example.berryharvest.ui.gather

/**
 * Data class for the simple gather summary popup
 * Shows worker name and total punnets collected today
 */
data class GatherSummaryData(
    val workerName: String,
    val totalPunnetsToday: Int
)