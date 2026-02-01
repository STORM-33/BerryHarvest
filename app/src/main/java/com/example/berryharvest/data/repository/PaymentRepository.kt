package com.example.berryharvest.data.repository

import com.example.berryharvest.data.model.PaymentRecord
import com.example.berryharvest.ui.payment.DailyPaymentSummary
import com.example.berryharvest.ui.payment.DailyGatherSummary
import com.example.berryharvest.ui.payment.PaymentTotals
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for payment operations.
 */
interface PaymentRepository : BaseRepository<PaymentRecord> {
    /**
     * Get payment records for a specific worker.
     */
    suspend fun getPaymentRecordsForWorker(workerId: String): Flow<Result<List<PaymentRecord>>>

    /**
     * Get current balance for a worker.
     */
    suspend fun getWorkerBalance(workerId: String): Result<Float>

    /**
     * Get total earnings for a worker.
     */
    suspend fun getWorkerEarnings(workerId: String): Result<Float>

    /**
     * Record a new payment.
     */
    suspend fun recordPayment(workerId: String, amount: Float, notes: String): Result<String>

    /**
     * Get count of gathered punnets for today.
     */
    suspend fun getTodayPunnetCount(workerId: String): Result<Int>

    /**
     * Update balance for a worker.
     */
    suspend fun updateWorkerBalance(workerId: String, newBalance: Float): Result<Boolean>

    /**
     * Get daily payment summaries (total paid, paid with berry, paid other ways).
     */
    suspend fun getDailyPaymentSummaries(): Result<List<DailyPaymentSummary>>

    /**
     * Get payment totals for all payments.
     */
    suspend fun getGlobalPaymentTotals(): Result<PaymentTotals>

    /**
     * Get payment totals for a specific worker.
     */
    suspend fun getWorkerPaymentTotals(workerId: String): Result<PaymentTotals>

    /**
     * NEW: Get daily gather summaries for a specific worker.
     * Shows punnets collected and earnings per day.
     */
    suspend fun getWorkerDailyGatherSummaries(workerId: String): Result<List<DailyGatherSummary>>
}