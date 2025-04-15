package com.example.berryharvest.data.repository

import android.app.Application
import android.util.Log
import com.example.berryharvest.data.model.Gather
import com.example.berryharvest.data.model.PaymentBalance
import com.example.berryharvest.data.model.PaymentRecord
import com.example.berryharvest.data.network.EnhancedNetworkManager
import io.realm.kotlin.MutableRealm
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import io.realm.kotlin.query.RealmQuery
import io.realm.kotlin.query.Sort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * Implementation of PaymentRepository that extends BaseRepositoryImpl.
 * Handles all Payment-specific data operations.
 */
class PaymentRepositoryImpl(
    application: Application,
    networkManager: EnhancedNetworkManager,
    databaseTransactionManager: DatabaseTransactionManager
) : BaseRepositoryImpl<PaymentRecord>(
    application = application,
    networkManager = networkManager,
    databaseTransactionManager = databaseTransactionManager,
    entityType = "payment",
    logTag = "PaymentRepository"
), PaymentRepository {

    /**
     * Create the base query for PaymentRecord entities.
     */
    override fun createQuery(realm: Realm): RealmQuery<PaymentRecord> {
        return realm.query<PaymentRecord>("isDeleted == false").sort("date", Sort.DESCENDING)
    }

    /**
     * Create a query to find a PaymentRecord by ID.
     */
    override fun createQueryById(realm: Realm, id: String): RealmQuery<PaymentRecord> {
        return realm.query<PaymentRecord>("_id == $0", id)
    }

    /**
     * Get the query to find unsynced PaymentRecord entities.
     */
    override fun getUnsyncedQuery(realm: Realm): RealmQuery<PaymentRecord> {
        return realm.query<PaymentRecord>("isSynced == false")
    }

    /**
     * Mark a PaymentRecord entity as synced.
     */
    override fun MutableRealm.markAsSynced(entity: PaymentRecord) {
        entity.isSynced = true
    }

    /**
     * Get payment records for a specific worker.
     */
    override suspend fun getPaymentRecordsForWorker(workerId: String): Flow<Result<List<PaymentRecord>>> =
        withDatabaseContext { realm ->
            realm.query<PaymentRecord>("workerId == $0 AND isDeleted == false", workerId)
                .sort("date", Sort.DESCENDING)
                .asFlow()
                .map { result ->
                    Result.Success(result.list.toList())
                }
        }

    /**
     * Get current balance for a worker.
     */
    override suspend fun getWorkerBalance(workerId: String): Result<Float> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Getting balance for worker: $workerId")

            // First get all the payment records
            val payments = realm.query<PaymentRecord>("workerId == $0 AND isDeleted == false", workerId).find()
            val totalPayments = payments.sumOf { it.amount.toDouble() }.toFloat()
            Log.d(logTag, "Total payments: $totalPayments")

            // Next, calculate earnings from gather records - ONLY count non-deleted records
            val gathers = realm.query<Gather>("workerId == $0", workerId).find()
                .filter { it.isDeleted != true } // Explicitly check for not true to handle null
            var totalEarnings = 0.0f

            for (gather in gathers) {
                val punnets = gather.numOfPunnets ?: 0
                val punnetCost = gather.punnetCost ?: 0.0f
                val earnings = punnets * punnetCost
                totalEarnings += earnings
                Log.d(logTag, "Gather: $punnets punnets at $punnetCost each = $earnings")
            }

            Log.d(logTag, "Total earnings from gathers: $totalEarnings")

            // The actual balance is earnings minus payments
            // (since positive payments mean money paid OUT to worker)
            val actualBalance = totalEarnings - totalPayments
            Log.d(logTag, "Actual balance (earnings - payments): $actualBalance")

            // Try to find existing balance record
            val balance = realm.query<PaymentBalance>("workerId == $0", workerId).first().find()

            if (balance != null) {
                // If the calculated amount differs from stored balance, update it
                if (balance.currentBalance != actualBalance) {
                    try {
                        safeWrite {
                            val liveBalance = query<PaymentBalance>("_id == $0", balance._id).first().find()
                            liveBalance?.apply {
                                currentBalance = actualBalance
                                lastUpdated = System.currentTimeMillis()
                                isSynced = networkManager.isNetworkAvailable()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(logTag, "Error updating existing balance", e)
                    }
                }

                Log.d(logTag, "Using updated balance record: $actualBalance")
                return@withDatabaseContext Result.Success(actualBalance)
            }

            // If no balance exists, use the calculated amount
            Log.d(logTag, "Calculated balance (no record exists): $actualBalance")

            // Attempt to create a balance record
            try {
                safeWrite {
                    copyToRealm(PaymentBalance().apply {
                        _id = UUID.randomUUID().toString()
                        this.workerId = workerId
                        currentBalance = actualBalance
                        lastUpdated = System.currentTimeMillis()
                        isSynced = networkManager.isNetworkAvailable()
                    })
                }
                Log.d(logTag, "Created new balance record")
            } catch (e: Exception) {
                // Just log the error but don't fail
                Log.e(logTag, "Failed to create balance record, but calculation was successful", e)
            }

            Result.Success(actualBalance)
        } catch (e: Exception) {
            Log.e(logTag, "Error getting worker balance", e)
            setError("Failed to get worker balance: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Get total earnings for a worker.
     */
    override suspend fun getWorkerEarnings(workerId: String): Result<Float> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Getting total earnings for worker: $workerId")

            // Only count non-deleted gathers
            val gathers = realm.query<Gather>("workerId == $0", workerId).find()
                .filter { it.isDeleted != true } // Explicitly check for not true to handle null

            var totalEarnings = 0.0f
            for (gather in gathers) {
                val punnets = gather.numOfPunnets ?: 0
                val punnetCost = gather.punnetCost ?: 0.0f
                val earnings = punnets * punnetCost
                totalEarnings += earnings
                Log.d(logTag, "Gather added to earnings: $punnets punnets at $punnetCost each = $earnings")
            }

            Log.d(logTag, "Total earnings from gathers: $totalEarnings")
            Result.Success(totalEarnings)
        } catch (e: Exception) {
            Log.e(logTag, "Error getting worker earnings", e)
            setError("Failed to get worker earnings: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Record a new payment.
     */
    override suspend fun recordPayment(workerId: String, amount: Float, notes: String): Result<String> =
        withDatabaseContext { realm ->
            try {
                Log.d(logTag, "Recording payment for worker: $workerId, amount: $amount")
                val paymentId = UUID.randomUUID().toString()

                // Add payment record
                safeWrite {
                    copyToRealm(PaymentRecord().apply {
                        _id = paymentId
                        this.workerId = workerId
                        this.amount = amount
                        this.date = System.currentTimeMillis()
                        this.notes = notes
                        this.isSynced = networkManager.isNetworkAvailable()
                        this.isDeleted = false
                    })
                }

                // Update worker balance safely
                try {
                    // Calculate current balance from records
                    val payments = realm.query<PaymentRecord>("workerId == $0 AND isDeleted == false", workerId).find()
                    val totalAmount = payments.sumOf { it.amount.toDouble() }.toFloat()

                    // Find existing balance
                    val existingBalance = realm.query<PaymentBalance>("workerId == $0", workerId).first().find()

                    // Update or create balance record
                    safeWrite {
                        if (existingBalance != null) {
                            // Update existing balance
                            val liveBalance = query<PaymentBalance>("_id == $0", existingBalance._id).first().find()
                            liveBalance?.apply {
                                currentBalance = totalAmount
                                lastUpdated = System.currentTimeMillis()
                                isSynced = networkManager.isNetworkAvailable()
                            }
                            Log.d(logTag, "Updated existing balance record: ${existingBalance._id}")
                        } else {
                            // Create new balance record
                            try {
                                copyToRealm(PaymentBalance().apply {
                                    _id = UUID.randomUUID().toString()
                                    this.workerId = workerId
                                    currentBalance = totalAmount
                                    lastUpdated = System.currentTimeMillis()
                                    isSynced = networkManager.isNetworkAvailable()
                                })
                                Log.d(logTag, "Created new balance record")
                            } catch (e: Exception) {
                                Log.e(logTag, "Failed to create balance record, but payment was recorded", e)
                                // We don't fail the whole operation - the payment is recorded
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Just log this error - the payment was recorded successfully
                    Log.e(logTag, "Error updating balance, but payment was recorded", e)
                }

                if (!networkManager.isNetworkAvailable()) {
                    addPendingOperation(
                        PendingOperation.Add(paymentId, entityType)
                    )
                }

                Log.d(logTag, "Payment recorded successfully: $paymentId")
                Result.Success(paymentId)
            } catch (e: Exception) {
                Log.e(logTag, "Error recording payment", e)
                setError("Failed to record payment: ${e.message}")
                Result.Error(e)
            }
        }

    /**
     * Get count of gathered punnets for today.
     */
    override suspend fun getTodayPunnetCount(workerId: String): Result<Int> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Getting today's punnet count for worker: $workerId")

            // Calculate start of day timestamp
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startOfDay = calendar.timeInMillis

            // Used for date parsing
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            // Query gathers from today for this worker - only non-deleted records
            val allGathers = realm.query<Gather>("workerId == $0", workerId).find()
                .filter { it.isDeleted != true } // Skip deleted gathers

            Log.d(logTag, "Found ${allGathers.size} total gathers for worker (non-deleted)")

            // Filter gathers from today with safer date parsing
            val todayGathers = allGathers.filter { gather ->
                try {
                    val dateStr = gather.dateTime
                    if (dateStr != null) {
                        val gatherDate = try {
                            dateFormat.parse(dateStr)?.time ?: 0
                        } catch (e: Exception) {
                            // If date parsing fails, log but don't crash
                            Log.e(logTag, "Error parsing date: $dateStr", e)
                            0L
                        }
                        gatherDate >= startOfDay
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    Log.e(logTag, "Error filtering gather by date", e)
                    false
                }
            }

            Log.d(logTag, "Filtered to ${todayGathers.size} gathers from today")

            // Sum up punnets - safely handle null values
            val todayCount = todayGathers.sumOf { it.numOfPunnets?.toInt() ?: 0 }
            Log.d(logTag, "Today's punnet count: $todayCount")

            Result.Success(todayCount)
        } catch (e: Exception) {
            Log.e(logTag, "Error getting today's punnet count", e)
            setError("Failed to get today's punnet count: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Update balance for a worker.
     */
    override suspend fun updateWorkerBalance(workerId: String, newBalance: Float): Result<Boolean> =
        withDatabaseContext { realm ->
            try {
                Log.d(logTag, "Updating worker balance for worker: $workerId, new balance: $newBalance")

                // Check if balance already exists
                val existingBalance = realm.query<PaymentBalance>("workerId == $0", workerId).first().find()

                safeWrite {
                    if (existingBalance != null) {
                        // Update existing balance
                        val liveBalance = query<PaymentBalance>("_id == $0", existingBalance._id).first().find()
                        liveBalance?.apply {
                            currentBalance = newBalance
                            lastUpdated = System.currentTimeMillis()
                            isSynced = networkManager.isNetworkAvailable()
                        }
                        Log.d(logTag, "Updated existing balance record: ${existingBalance._id}")
                    } else {
                        // Create new balance record
                        copyToRealm(PaymentBalance().apply {
                            _id = UUID.randomUUID().toString()
                            this.workerId = workerId
                            currentBalance = newBalance
                            lastUpdated = System.currentTimeMillis()
                            isSynced = networkManager.isNetworkAvailable()
                        })
                        Log.d(logTag, "Created new balance record")
                    }
                }

                if (!networkManager.isNetworkAvailable()) {
                    // Add pending operation for the balance update
                    if (existingBalance != null) {
                        addPendingOperation(
                            PendingOperation.Update(existingBalance._id, "payment_balance")
                        )
                    }
                }

                Log.d(logTag, "Worker balance updated successfully")
                Result.Success(true)
            } catch (e: Exception) {
                Log.e(logTag, "Error updating worker balance", e)
                setError("Failed to update worker balance: ${e.message}")
                Result.Error(e)
            }
        }

    /**
     * Add a new PaymentRecord entity.
     */
    override suspend fun add(item: PaymentRecord): Result<String> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting add operation for payment record")
            var paymentId = ""

            safeWrite {
                val newPayment = copyToRealm(PaymentRecord().apply {
                    _id = item._id.ifEmpty { UUID.randomUUID().toString() }
                    workerId = item.workerId
                    amount = item.amount
                    date = item.date
                    notes = item.notes
                    isSynced = networkManager.isNetworkAvailable()
                    isDeleted = false
                })
                paymentId = newPayment._id
                Log.d(logTag, "Added payment record: $paymentId")
            }

            // Update worker balance
            updateWorkerBalance(item.workerId)

            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Add(paymentId, entityType)
                )
            }

            Log.d(logTag, "Add operation completed successfully")
            Result.Success(paymentId)
        } catch (e: Exception) {
            Log.e(logTag, "Error in add", e)
            setError("Failed to add payment record: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Update an existing PaymentRecord entity.
     */
    override suspend fun update(item: PaymentRecord): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting update operation for payment record: ${item._id}")

            safeWrite {
                // Find the live object INSIDE transaction
                val payment = query<PaymentRecord>("_id == $0", item._id).first().find()
                payment?.apply {
                    amount = item.amount
                    notes = item.notes
                    isSynced = networkManager.isNetworkAvailable()
                }
                Log.d(logTag, "Updated payment record: ${item._id}")
            }

            // Update worker balance
            updateWorkerBalance(item.workerId)

            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Update(item._id, entityType)
                )
            }

            Log.d(logTag, "Update operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error in update", e)
            setError("Failed to update payment record: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Delete a PaymentRecord entity by ID.
     */
    override suspend fun delete(id: String): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting delete operation for payment record: $id")
            var workerId = ""

            // Get worker ID before deleting
            val payment = realm.query<PaymentRecord>("_id == $0", id).first().find()
            if (payment != null) {
                workerId = payment.workerId
            }

            safeWrite {
                // Find the live object INSIDE transaction
                val livePayment = query<PaymentRecord>("_id == $0", id).first().find()
                livePayment?.apply {
                    isDeleted = true
                    isSynced = networkManager.isNetworkAvailable()
                }
                Log.d(logTag, "Marked payment record as deleted: $id")
            }

            // Update worker balance if we have the worker ID
            if (workerId.isNotEmpty()) {
                updateWorkerBalance(workerId)
            }

            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Delete(id, entityType)
                )
            }

            Log.d(logTag, "Delete operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error in delete", e)
            setError("Failed to delete payment record: ${e.message}")
            Result.Error(e)
        }
    }

    // Helper method to update a worker's balance
    private suspend fun updateWorkerBalance(workerId: String): Result<Boolean> {
        try {
            val realm = getRealm()

            // Get all payment records for this worker
            val payments = realm.query<PaymentRecord>("workerId == $0 AND isDeleted == false", workerId).find()
            val totalAmount = payments.sumOf { it.amount.toDouble() }.toFloat()

            // Update the balance
            return updateWorkerBalance(workerId, totalAmount)
        } catch (e: Exception) {
            Log.e(logTag, "Error calculating worker balance", e)
            return Result.Error(e)
        }
    }

    override fun getEntityId(entity: PaymentRecord): String {
        return entity._id
    }

    override fun MutableRealm.findEntityById(id: String): PaymentRecord? {
        return this.query<PaymentRecord>("_id == $0", id).first().find()
    }
}