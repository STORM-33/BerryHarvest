package com.example.berryharvest.data.repository

import android.app.Application
import android.util.Log
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.data.model.Gather
import com.example.berryharvest.data.model.PaymentBalance
import com.example.berryharvest.data.model.PaymentRecord
import com.example.berryharvest.data.network.EnhancedNetworkManager
import io.realm.kotlin.MutableRealm
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import io.realm.kotlin.query.Sort
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.*
import kotlin.time.Duration.Companion.seconds

private const val TAG = "PaymentRepository"
private const val ENTITY_TYPE = "payment"

class PaymentRepositoryImpl(
    private val application: Application,
    private val networkManager: EnhancedNetworkManager
) : PaymentRepository {

    private val app: BerryHarvestApplication = application as BerryHarvestApplication
    private var _realm: Realm? = null
    private val _pendingOperations = MutableStateFlow<List<PendingOperation>>(emptyList())
    val pendingOperations: StateFlow<List<PendingOperation>> = _pendingOperations.asStateFlow()

    // Add error state for better error propagation
    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    private suspend fun getRealm(): Realm {
        if (_realm != null && !_realm!!.isClosed()) {
            return _realm!!
        }

        // Get realm outside of synchronized block to avoid suspension in critical section
        return try {
            Log.d(TAG, "Getting Realm instance")
            val realm = app.getRealmInstance()
            synchronized(this) {
                _realm = realm
            }
            Log.d(TAG, "Successfully obtained Realm instance")
            realm
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Realm instance", e)
            _errorState.value = "Failed to initialize database: ${e.message}"
            throw e
        }
    }

    override suspend fun getAll(): Flow<Result<List<PaymentRecord>>> = flow {
        emit(Result.Loading)
        try {
            Log.d(TAG, "Getting all payment records")
            val realm = getRealm()
            realm.query<PaymentRecord>().sort("date", Sort.DESCENDING).asFlow()
                .map { result ->
                    Log.d(TAG, "Received ${result.list.size} payment records in flow")
                    Result.Success(result.list.toList())
                }
                .catch { e ->
                    Log.e(TAG, "Error in getAll flow", e)
                    emit(Result.Error(Exception(e)))
                }
                .collect { emit(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getAll", e)
            _errorState.value = "Failed to load payment records: ${e.message}"
            emit(Result.Error(e))
        }
    }

    override suspend fun getPaymentRecordsForWorker(workerId: String): Flow<Result<List<PaymentRecord>>> = flow {
        emit(Result.Loading)
        try {
            Log.d(TAG, "Getting payment records for worker: $workerId")
            val realm = getRealm()
            realm.query<PaymentRecord>("workerId == $0 AND isDeleted == false", workerId)
                .sort("date", Sort.DESCENDING)
                .asFlow()
                .map { result ->
                    Log.d(TAG, "Received ${result.list.size} payment records for worker in flow")
                    Result.Success(result.list.toList())
                }
                .catch { e ->
                    Log.e(TAG, "Error in getPaymentRecordsForWorker flow", e)
                    emit(Result.Error(Exception(e)))
                }
                .collect { emit(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getPaymentRecordsForWorker", e)
            _errorState.value = "Failed to load worker payment records: ${e.message}"
            emit(Result.Error(e))
        }
    }

    override suspend fun getWorkerBalance(workerId: String): Result<Float> {
        return try {
            Log.d(TAG, "Getting balance for worker: $workerId")
            val realm = getRealm()

            // First get all the payment records
            val payments = realm.query<PaymentRecord>("workerId == $0 AND isDeleted == false", workerId).find()
            val totalPayments = payments.sumOf { it.amount.toDouble() }.toFloat()
            Log.d(TAG, "Total payments: $totalPayments")

            // Next, calculate earnings from gather records - ONLY count non-deleted records
            val gathers = realm.query<Gather>("workerId == $0", workerId).find()
                .filter { it.isDeleted != true } // Explicitly check for not true to handle null
            var totalEarnings = 0.0f

            for (gather in gathers) {
                val punnets = gather.numOfPunnets ?: 0
                val punnetCost = gather.punnetCost ?: 0.0f
                val earnings = punnets * punnetCost
                totalEarnings += earnings
                Log.d(TAG, "Gather: $punnets punnets at $punnetCost each = $earnings")
            }

            Log.d(TAG, "Total earnings from gathers: $totalEarnings")

            // The actual balance is earnings minus payments
            // (since positive payments mean money paid OUT to worker)
            val actualBalance = totalEarnings - totalPayments
            Log.d(TAG, "Actual balance (earnings - payments): $actualBalance")

            // Try to find existing balance record
            val balance = realm.query<PaymentBalance>("workerId == $0", workerId).first().find()

            if (balance != null) {
                // If the calculated amount differs from stored balance, update it
                if (balance.currentBalance != actualBalance) {
                    try {
                        app.safeWriteTransaction {
                            val liveBalance = query<PaymentBalance>("_id == $0", balance._id).first().find()
                            liveBalance?.apply {
                                currentBalance = actualBalance
                                lastUpdated = System.currentTimeMillis()
                                isSynced = networkManager.isNetworkAvailable()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating existing balance", e)
                    }
                }

                Log.d(TAG, "Using updated balance record: $actualBalance")
                return Result.Success(actualBalance)
            }

            // If no balance exists, use the calculated amount
            Log.d(TAG, "Calculated balance (no record exists): $actualBalance")

            // Attempt to create a balance record
            try {
                app.safeWriteTransaction {
                    copyToRealm(PaymentBalance().apply {
                        _id = UUID.randomUUID().toString()
                        this.workerId = workerId
                        currentBalance = actualBalance
                        lastUpdated = System.currentTimeMillis()
                        isSynced = networkManager.isNetworkAvailable()
                    })
                }
                Log.d(TAG, "Created new balance record")
            } catch (e: Exception) {
                // Just log the error but don't fail
                Log.e(TAG, "Failed to create balance record", e)
            }

            Result.Success(actualBalance)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting worker balance", e)
            _errorState.value = "Failed to get worker balance: ${e.message}"
            Result.Error(e)
        }
    }

    override suspend fun getWorkerEarnings(workerId: String): Result<Float> {
        return try {
            Log.d(TAG, "Getting total earnings for worker: $workerId")
            val realm = getRealm()

            // Only count non-deleted gathers
            val gathers = realm.query<Gather>("workerId == $0", workerId).find()
                .filter { it.isDeleted != true } // Explicitly check for not true to handle null

            var totalEarnings = 0.0f
            for (gather in gathers) {
                val punnets = gather.numOfPunnets ?: 0
                val punnetCost = gather.punnetCost ?: 0.0f
                val earnings = punnets * punnetCost
                totalEarnings += earnings
                Log.d(TAG, "Gather added to earnings: $punnets punnets at $punnetCost each = $earnings")
            }

            Log.d(TAG, "Total earnings from gathers: $totalEarnings")
            Result.Success(totalEarnings)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting worker earnings", e)
            _errorState.value = "Failed to get worker earnings: ${e.message}"
            Result.Error(e)
        }
    }

    override suspend fun getTodayPunnetCount(workerId: String): Result<Int> {
        return try {
            Log.d(TAG, "Getting today's punnet count for worker: $workerId")
            val realm = getRealm()

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

            Log.d(TAG, "Found ${allGathers.size} total gathers for worker (non-deleted)")

            // Filter gathers from today with safer date parsing
            val todayGathers = allGathers.filter { gather ->
                try {
                    val dateStr = gather.dateTime
                    if (dateStr != null) {
                        val gatherDate = try {
                            dateFormat.parse(dateStr)?.time ?: 0
                        } catch (e: Exception) {
                            // If date parsing fails, log but don't crash
                            Log.e(TAG, "Error parsing date: $dateStr", e)
                            0L
                        }
                        gatherDate >= startOfDay
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error filtering gather by date", e)
                    false
                }
            }

            Log.d(TAG, "Filtered to ${todayGathers.size} gathers from today")

            // Sum up punnets - safely handle null values
            val todayCount = todayGathers.sumOf { it.numOfPunnets?.toInt() ?: 0 }
            Log.d(TAG, "Today's punnet count: $todayCount")

            Result.Success(todayCount)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting today's punnet count", e)
            _errorState.value = "Failed to get today's punnet count: ${e.message}"
            Result.Error(e)
        }
    }

    override suspend fun recordPayment(workerId: String, amount: Float, notes: String): Result<String> {
        Log.d(TAG, "Recording payment for worker: $workerId, amount: $amount")
        return try {
            val realm = getRealm()
            val paymentId = UUID.randomUUID().toString()

            // Add payment record
            app.safeWriteTransaction {
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
                app.safeWriteTransaction {
                    if (existingBalance != null) {
                        // Update existing balance
                        val liveBalance = query<PaymentBalance>("_id == $0", existingBalance._id).first().find()
                        liveBalance?.apply {
                            currentBalance = totalAmount
                            lastUpdated = System.currentTimeMillis()
                            isSynced = networkManager.isNetworkAvailable()
                        }
                        Log.d(TAG, "Updated existing balance record: ${existingBalance._id}")
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
                            Log.d(TAG, "Created new balance record")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to create balance record, but payment was recorded", e)
                            // We don't fail the whole operation - the payment is recorded
                        }
                    }
                }
            } catch (e: Exception) {
                // Just log this error - the payment was recorded successfully
                Log.e(TAG, "Error updating balance, but payment was recorded", e)
            }

            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Add(paymentId, ENTITY_TYPE)
                )
            }

            Log.d(TAG, "Payment recorded successfully: $paymentId")
            Result.Success(paymentId)
        } catch (e: Exception) {
            Log.e(TAG, "Error recording payment", e)
            _errorState.value = "Failed to record payment: ${e.message}"
            Result.Error(e)
        }
    }

    override suspend fun updateWorkerBalance(workerId: String, newBalance: Float): Result<Boolean> {
        Log.d(TAG, "Updating worker balance for worker: $workerId, new balance: $newBalance")
        return try {
            val realm = getRealm()

            // Check if balance already exists
            val existingBalance = realm.query<PaymentBalance>("workerId == $0", workerId).first().find()

            app.safeWriteTransaction {
                if (existingBalance != null) {
                    // Update existing balance
                    val liveBalance = query<PaymentBalance>("_id == $0", existingBalance._id).first().find()
                    liveBalance?.apply {
                        currentBalance = newBalance
                        lastUpdated = System.currentTimeMillis()
                        isSynced = networkManager.isNetworkAvailable()
                    }
                    Log.d(TAG, "Updated existing balance record: ${existingBalance._id}")
                } else {
                    // Create new balance record
                    copyToRealm(PaymentBalance().apply {
                        _id = UUID.randomUUID().toString()
                        this.workerId = workerId
                        currentBalance = newBalance
                        lastUpdated = System.currentTimeMillis()
                        isSynced = networkManager.isNetworkAvailable()
                    })
                    Log.d(TAG, "Created new balance record")
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

            Log.d(TAG, "Worker balance updated successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating worker balance", e)
            _errorState.value = "Failed to update worker balance: ${e.message}"
            Result.Error(e)
        }
    }

    // Calculate and update the worker's balance based on their payment records
    private suspend fun updateWorkerBalance(workerId: String): Result<Boolean> {
        try {
            val realm = getRealm()

            // Get all payment records for this worker
            val payments = realm.query<PaymentRecord>("workerId == $0 AND isDeleted == false", workerId).find()
            val totalAmount = payments.sumOf { it.amount.toDouble() }.toFloat()

            // Update the balance
            return updateWorkerBalance(workerId, totalAmount)
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating worker balance", e)
            return Result.Error(e)
        }
    }

    override suspend fun getById(id: String): Result<PaymentRecord?> {
        return try {
            Log.d(TAG, "Getting payment record by ID: $id")
            val realm = getRealm()
            val payment = realm.query<PaymentRecord>("_id == $0", id).first().find()
            Log.d(TAG, "Payment record found: ${payment != null}")
            Result.Success(payment)
        } catch (e: Exception) {
            Log.e(TAG, "Error in getById", e)
            _errorState.value = "Failed to get payment record: ${e.message}"
            Result.Error(e)
        }
    }

    override suspend fun add(item: PaymentRecord): Result<String> {
        Log.d(TAG, "Starting add operation for payment record")
        return try {
            val realm = getRealm()
            var paymentId = ""

            app.safeWriteTransaction {
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
                Log.d(TAG, "Added payment record: $paymentId")
            }

            // Update worker balance
            updateWorkerBalance(item.workerId)

            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Add(paymentId, ENTITY_TYPE)
                )
            }

            Log.d(TAG, "Add operation completed successfully")
            Result.Success(paymentId)
        } catch (e: Exception) {
            Log.e(TAG, "Error in add", e)
            _errorState.value = "Failed to add payment record: ${e.message}"
            Result.Error(e)
        }
    }

    override suspend fun update(item: PaymentRecord): Result<Boolean> {
        Log.d(TAG, "Starting update operation for payment record: ${item._id}")
        return try {
            app.safeWriteTransaction {
                // Find the live object INSIDE transaction
                val payment = query<PaymentRecord>("_id == $0", item._id).first().find()
                payment?.apply {
                    amount = item.amount
                    notes = item.notes
                    isSynced = networkManager.isNetworkAvailable()
                }
                Log.d(TAG, "Updated payment record: ${item._id}")
            }

            // Update worker balance
            updateWorkerBalance(item.workerId)

            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Update(item._id, ENTITY_TYPE)
                )
            }

            Log.d(TAG, "Update operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in update", e)
            _errorState.value = "Failed to update payment record: ${e.message}"
            Result.Error(e)
        }
    }

    override suspend fun delete(id: String): Result<Boolean> {
        Log.d(TAG, "Starting delete operation for payment record: $id")
        return try {
            val realm = getRealm()
            var workerId = ""

            // Get worker ID before deleting
            val payment = realm.query<PaymentRecord>("_id == $0", id).first().find()
            if (payment != null) {
                workerId = payment.workerId
            }

            app.safeWriteTransaction {
                // Find the live object INSIDE transaction
                val livePayment = query<PaymentRecord>("_id == $0", id).first().find()
                livePayment?.apply {
                    isDeleted = true
                    isSynced = networkManager.isNetworkAvailable()
                }
                Log.d(TAG, "Marked payment record as deleted: $id")
            }

            // Update worker balance if we have the worker ID
            if (workerId.isNotEmpty()) {
                updateWorkerBalance(workerId)
            }

            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Delete(id, ENTITY_TYPE)
                )
            }

            Log.d(TAG, "Delete operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in delete", e)
            _errorState.value = "Failed to delete payment record: ${e.message}"
            Result.Error(e)
        }
    }

    override suspend fun syncPendingChanges(): Result<Boolean> {
        Log.d(TAG, "Starting syncPendingChanges operation")
        return try {
            if (!networkManager.isNetworkAvailable()) {
                Log.d(TAG, "Network not available, skipping sync")
                return Result.Error(Exception("Network not available"))
            }

            val operations = _pendingOperations.value
            Log.d(TAG, "Syncing ${operations.size} pending operations")

            val realm = getRealm()

            // Check for unsynced payment records
            val unsyncedPayments = realm.query<PaymentRecord>("isSynced == false").count().find()

            // Check for unsynced payment balances
            val unsyncedBalances = realm.query<PaymentBalance>("isSynced == false").count().find()

            if (unsyncedPayments > 0 || unsyncedBalances > 0) {
                Log.d(TAG, "Found $unsyncedPayments unsynced payments and $unsyncedBalances unsynced balances")

                // Try with a timeout to avoid indefinite waiting
                withTimeout(10000) {
                    app.safeWriteTransaction {
                        // Mark payment records as synced
                        query<PaymentRecord>("isSynced == false").find().forEach { payment ->
                            payment.isSynced = true
                            Log.d(TAG, "Marked payment ${payment._id} as synced")
                        }

                        // Mark payment balances as synced
                        query<PaymentBalance>("isSynced == false").find().forEach { balance ->
                            balance.isSynced = true
                            Log.d(TAG, "Marked balance ${balance._id} as synced")
                        }
                    }
                }
            } else {
                Log.d(TAG, "No unsynced payment data found")
            }

            // Clear pending operations after successful sync
            _pendingOperations.value = emptyList()
            Log.d(TAG, "Cleared pending operations")

            Log.d(TAG, "SyncPendingChanges operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in syncPendingChanges", e)
            _errorState.value = "Failed to sync changes: ${e.message}"
            Result.Error(e)
        }
    }

    override fun getConnectionState(): Flow<ConnectionState> {
        return networkManager.connectionState
    }

    private fun addPendingOperation(operation: PendingOperation) {
        val currentOperations = _pendingOperations.value.toMutableList()
        currentOperations.add(operation)
        _pendingOperations.value = currentOperations
        Log.d(TAG, "Added pending operation: $operation")
    }

    override fun hasPendingOperations(): Boolean {
        return _pendingOperations.value.isNotEmpty()
    }

    override fun getPendingOperationsCount(): Int {
        return _pendingOperations.value.size
    }

    override suspend fun <R> safeWriteWithTimeout(block: MutableRealm.() -> R): R {
        val app = application as BerryHarvestApplication
        return withTimeout(5.seconds) {
            app.safeWriteTransaction(block)
        }
    }

    override fun close() {
        _realm?.close()
        _realm = null
        Log.d(TAG, "Repository closed")
    }
}