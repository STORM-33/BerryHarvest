package com.example.berryharvest.ui.payment

/**
 * Data class representing daily gather summary for a worker
 * Used for displaying worker's daily punnet collection and earnings
 */
data class DailyGatherSummary(
    val date: String,              // Formatted date (e.g., "15 липня")
    val dateTimestamp: Long,       // Original timestamp for sorting
    val totalPunnets: Int,         // Total punnets gathered that day
    val punnetPrice: Float,        // Price per punnet used that day
    val totalEarnings: Float       // Total earnings for that day (punnets * price)
)