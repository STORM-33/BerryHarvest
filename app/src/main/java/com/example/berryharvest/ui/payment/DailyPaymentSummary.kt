package com.example.berryharvest.ui.payment

/**
 * Data class representing daily payment summary
 * Used for displaying aggregated payment information by day
 */
data class DailyPaymentSummary(
    val date: String,              // Formatted date (e.g., "15 липня")
    val dateTimestamp: Long,       // Original timestamp for sorting
    val totalPaid: Float,          // Total amount paid that day
    val paidWithBerry: Float,      // Amount paid with berry (comment != null)
    val paidOther: Float           // Amount paid other ways (comment == null/empty)
)