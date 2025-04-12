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

            // First try to get from PaymentRecord calculations to avoid creating PaymentBalance if not needed
            val payments = realm.query<PaymentRecord>("workerId == $0 AND isDeleted == false", workerId).find()
            val totalAmount = payments.sumOf { it.amount.toDouble() }.toFloat()

            // Try to find existing balance
            val balance = realm.query<PaymentBalance>("workerId == $0", workerId).first().find()

            if (balance != null) {
                // If the calculated amount differs from stored balance, update it
                if (balance.currentBalance != totalAmount) {
                    try {
                        app.safeWriteTransaction {
                            val liveBalance = query<PaymentBalance>("_id == $0", balance._id).first().find()
                            liveBalance?.apply {
                                currentBalance = totalAmount
                                lastUpdated = System.currentTimeMillis()
                                isSynced = networkManager.isNetworkAvailable()
                            }
                        }
                    } catch (e: Exception) {
                        // Just log the error but don't fail - we can still return the calculated amount
                        Log.e(TAG, "Error updating existing balance", e)
                    }
                }

                Log.d(TAG, "Using existing balance record: ${balance.currentBalance}")
                return Result.Success(balance.currentBalance)
            }

            // If no balance exists, use the calculated amount but don't try to create a balance record yet
            // Wait until next payment operation to create it

            Log.d(TAG, "Calculated balance (no record exists): $totalAmount")
            Result.Success(totalAmount)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting worker balance", e)
            _errorState.value = "Failed to get worker balance: ${e.message}"
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

            // Query gathers from today for this worker
            val gathers = realm.query<Gather>("workerId == $0", workerId).find()
                .filter { it.dateTime?.let { dateStr ->
                    try {
                        (SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .parse(dateStr)?.time ?: 0) >= startOfDay
                    } catch (e: Exception) {
                        false
                    }
                } ?: false }

            // Sum up punnets
            val todayCount = gathers.sumOf { it.numOfPunnets?.toInt() ?: 0 }
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