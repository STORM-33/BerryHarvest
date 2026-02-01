package com.example.berryharvest.ui.payment

/**
 * Data class representing payment totals
 * Used for displaying aggregate payment information
 */
data class PaymentTotals(
    val totalPaid: Float,           // Total amount paid
    val paidWithBerry: Float,       // Amount paid with berry (comment != null)
    val paidWithMoney: Float        // Amount paid with money (comment == null/empty)
) {
    companion object {
        val EMPTY = PaymentTotals(0f, 0f, 0f)
    }
}