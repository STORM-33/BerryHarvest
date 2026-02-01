package com.example.berryharvest.data.repository

import android.app.Application
import android.util.Log
import com.example.berryharvest.NetworkConnectivityManager
import com.example.berryharvest.data.model.Gather
import com.example.berryharvest.data.model.Row
import io.realm.kotlin.MutableRealm
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import io.realm.kotlin.query.RealmQuery
import io.realm.kotlin.query.Sort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Implementation of RowRepository that extends BaseRepositoryImpl.
 * Handles all Row-specific data operations.
 */
class RowRepositoryImpl(
    application: Application,
    networkManager: NetworkConnectivityManager,
    databaseTransactionManager: DatabaseTransactionManager
) : BaseRepositoryImpl<Row>(
    application = application,
    networkManager = networkManager,
    databaseTransactionManager = databaseTransactionManager,
    entityType = "row",
    logTag = "RowRepository"
), RowRepository {

    /**
     * Create the base query for Row entities.
     */
    override fun createQuery(realm: Realm): RealmQuery<Row> {
        return realm.query<Row>("isDeleted == false").sort("quarter" to Sort.ASCENDING, "rowNumber" to Sort.ASCENDING)
    }

    /**
     * Create a query to find a Row by ID.
     */
    override fun createQueryById(realm: Realm, id: String): RealmQuery<Row> {
        return realm.query<Row>("_id == $0", id)
    }

    /**
     * Get the query to find unsynced Row entities.
     */
    override fun getUnsyncedQuery(realm: Realm): RealmQuery<Row> {
        return realm.query<Row>("isSynced == false")
    }

    /**
     * Mark a Row entity as synced.
     */
    override fun MutableRealm.markAsSynced(entity: Row) {
        entity.isSynced = true
    }

    /**
     * Add a new Row entity.
     */
    override suspend fun add(item: Row): Result<String> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting add operation for row: ${item.rowNumber}")
            var rowId = ""

            // Use safe write with retry
            safeWrite {
                val newRow = copyToRealm(Row().apply {
                    _id = item._id.ifEmpty { UUID.randomUUID().toString() }
                    rowNumber = item.rowNumber
                    quarter = item.quarter
                    berryVariety = item.berryVariety
                    isCollected = item.isCollected
                    collectedAt = item.collectedAt
                    createdAt = System.currentTimeMillis()
                    isSynced = networkManager.isNetworkAvailable()
                    isDeleted = false
                })
                rowId = newRow._id
                Log.d(logTag, "Added row: $rowId (Row ${item.rowNumber}, Quarter ${item.quarter})")
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Add(rowId, entityType)
                )
            }

            Log.d(logTag, "Add operation completed successfully")
            Result.Success(rowId)
        } catch (e: Exception) {
            Log.e(logTag, "Error in add", e)
            setError("Failed to add row: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Update an existing Row entity.
     */
    override suspend fun update(item: Row): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting update operation for row: ${item._id}")

            // Use safe write with retry
            safeWrite {
                // Find the live object INSIDE transaction
                val row = query<Row>("_id == $0", item._id).first().find()
                row?.apply {
                    rowNumber = item.rowNumber
                    quarter = item.quarter
                    berryVariety = item.berryVariety
                    isCollected = item.isCollected
                    collectedAt = item.collectedAt
                    isSynced = networkManager.isNetworkAvailable()
                }
                Log.d(logTag, "Updated row: ${item._id}")
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Update(item._id, entityType)
                )
            }

            Log.d(logTag, "Update operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error in update", e)
            setError("Failed to update row: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Delete a Row entity by ID.
     * Uses soft delete by marking isDeleted flag.
     */
    override suspend fun delete(id: String): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Starting delete operation for row: $id")

            // Use safe write with retry
            safeWrite {
                // Find the live object INSIDE transaction
                val row = query<Row>("_id == $0", id).first().find()
                row?.apply {
                    // Soft delete
                    isDeleted = true
                    isSynced = networkManager.isNetworkAvailable()
                    Log.d(logTag, "Soft deleted row: $id")
                }
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Delete(id, entityType)
                )
            }

            Log.d(logTag, "Delete operation completed successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error in delete", e)
            setError("Failed to delete row: ${e.message}")
            Result.Error(e)
        }
    }

    /**
     * Get rows by quarter.
     */
    override suspend fun getRowsByQuarter(quarter: Int): Flow<Result<List<Row>>> = flow {
        emit(Result.Loading)
        try {
            val realm = getRealm()
            realm.query<Row>("quarter == $0 AND isDeleted == false", quarter)
                .sort("rowNumber" to Sort.ASCENDING)
                .asFlow()
                .map { Result.Success(it.list.toList()) }
                .collect { emit(it) }
        } catch (e: Exception) {
            Log.e(logTag, "Error in getRowsByQuarter", e)
            emit(Result.Error(e))
        }
    }

    /**
     * Get rows by multiple quarters.
     */
    override suspend fun getRowsByQuarters(quarters: List<Int>): Flow<Result<List<Row>>> = flow {
        emit(Result.Loading)
        try {
            if (quarters.isEmpty()) {
                emit(Result.Success(emptyList()))
                return@flow
            }

            val realm = getRealm()
            val queryString = quarters.mapIndexed { index, _ -> "quarter == \$$index" }.joinToString(" OR ")

            realm.query<Row>("($queryString) AND isDeleted == false", *quarters.toTypedArray())
                .sort("quarter" to Sort.ASCENDING, "rowNumber" to Sort.ASCENDING)
                .asFlow()
                .map { Result.Success(it.list.toList()) }
                .collect { emit(it) }
        } catch (e: Exception) {
            Log.e(logTag, "Error in getRowsByQuarters", e)
            emit(Result.Error(e))
        }
    }

    /**
     * Get collected rows only.
     */
    override suspend fun getCollectedRows(): Flow<Result<List<Row>>> = flow {
        emit(Result.Loading)
        try {
            val realm = getRealm()
            realm.query<Row>("isCollected == true AND isDeleted == false")
                .sort("quarter" to Sort.ASCENDING, "rowNumber" to Sort.ASCENDING)
                .asFlow()
                .map { Result.Success(it.list.toList()) }
                .collect { emit(it) }
        } catch (e: Exception) {
            Log.e(logTag, "Error in getCollectedRows", e)
            emit(Result.Error(e))
        }
    }

    /**
     * Get uncollected rows only.
     */
    override suspend fun getUncollectedRows(): Flow<Result<List<Row>>> = flow {
        emit(Result.Loading)
        try {
            val realm = getRealm()
            realm.query<Row>("isCollected == false AND isDeleted == false")
                .sort("quarter" to Sort.ASCENDING, "rowNumber" to Sort.ASCENDING)
                .asFlow()
                .map { Result.Success(it.list.toList()) }
                .collect { emit(it) }
        } catch (e: Exception) {
            Log.e(logTag, "Error in getUncollectedRows", e)
            emit(Result.Error(e))
        }
    }

    /**
     * Get rows in a specific range.
     */
    override suspend fun getRowsInRange(startRow: Int, endRow: Int): Flow<Result<List<Row>>> = flow {
        emit(Result.Loading)
        try {
            val realm = getRealm()
            realm.query<Row>("rowNumber >= $0 AND rowNumber <= $1 AND isDeleted == false", startRow, endRow)
                .sort("quarter" to Sort.ASCENDING, "rowNumber" to Sort.ASCENDING)
                .asFlow()
                .map { Result.Success(it.list.toList()) }
                .collect { emit(it) }
        } catch (e: Exception) {
            Log.e(logTag, "Error in getRowsInRange", e)
            emit(Result.Error(e))
        }
    }

    /**
     * Mark a row as collected or uncollected.
     */
    override suspend fun updateRowCollectionStatus(rowId: String, isCollected: Boolean): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Updating collection status for row: $rowId to $isCollected")

            val currentTime = System.currentTimeMillis()

            safeWrite {
                val row = query<Row>("_id == $0", rowId).first().find()
                row?.apply {
                    this.isCollected = isCollected
                    this.lastModifiedAt = currentTime
                    if (isCollected) {
                        this.collectedAt = currentTime
                    } else {
                        this.collectedAt = null
                    }
                    this.isSynced = networkManager.isNetworkAvailable()
                }
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Update(rowId, entityType)
                )
            }

            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error updating collection status", e)
            Result.Error(e)
        }
    }

    /**
     * Mark multiple rows as collected or uncollected.
     */
    override suspend fun updateMultipleRowsCollectionStatus(rowIds: List<String>, isCollected: Boolean): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Updating collection status for ${rowIds.size} rows to $isCollected")

            val currentTime = System.currentTimeMillis()

            safeWrite {
                rowIds.forEach { rowId ->
                    val row = query<Row>("_id == $0", rowId).first().find()
                    row?.apply {
                        this.isCollected = isCollected
                        this.lastModifiedAt = currentTime
                        if (isCollected) {
                            this.collectedAt = currentTime
                        } else {
                            this.collectedAt = null
                        }
                        this.isSynced = networkManager.isNetworkAvailable()
                    }
                }
            }

            // Track pending operations if offline
            if (!networkManager.isNetworkAvailable()) {
                rowIds.forEach { rowId ->
                    addPendingOperation(
                        PendingOperation.Update(rowId, entityType)
                    )
                }
            }

            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error updating multiple collection statuses", e)
            Result.Error(e)
        }
    }

    /**
     * Initialize default rows for all quarters.
     */
    override suspend fun initializeDefaultRows(): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Initializing default rows")

            // Check if rows already exist
            val existingCount = realm.query<Row>("isDeleted == false").count().find()
            if (existingCount > 0) {
                Log.d(logTag, "Rows already exist ($existingCount), skipping initialization")
                return@withDatabaseContext Result.Success(true)
            }

            safeWrite {
                // Create 100 rows for each of the 4 quarters
                for (quarter in 1..4) {
                    for (rowNumber in 1..100) {
                        copyToRealm(Row().apply {
                            _id = UUID.randomUUID().toString()
                            this.rowNumber = rowNumber + ((quarter - 1) * 100) // Global row numbering
                            this.quarter = quarter
                            this.berryVariety = "Mixed" // Default variety
                            this.isCollected = false
                            this.createdAt = System.currentTimeMillis()
                            this.isSynced = networkManager.isNetworkAvailable()
                            this.isDeleted = false
                        })
                    }
                }
                Log.d(logTag, "Created 400 default rows (100 per quarter)")
            }

            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error initializing default rows", e)
            Result.Error(e)
        }
    }

    /**
     * Get row count by collection status.
     */
    override suspend fun getRowCountByStatus(quarter: Int?): Result<Map<String, Int>> = withDatabaseContext { realm ->
        try {
            val baseQuery = if (quarter != null) {
                "quarter == $quarter AND isDeleted == false"
            } else {
                "isDeleted == false"
            }

            val totalCount = if (quarter != null) {
                realm.query<Row>(baseQuery, quarter).count().find().toInt()
            } else {
                realm.query<Row>(baseQuery).count().find().toInt()
            }

            val collectedCount = if (quarter != null) {
                realm.query<Row>("$baseQuery AND isCollected == true", quarter).count().find().toInt()
            } else {
                realm.query<Row>("$baseQuery AND isCollected == true").count().find().toInt()
            }

            val uncollectedCount = totalCount - collectedCount

            val result = mapOf(
                "total" to totalCount,
                "collected" to collectedCount,
                "uncollected" to uncollectedCount
            )

            Log.d(logTag, "Row count stats: $result")
            Result.Success(result)
        } catch (e: Exception) {
            Log.e(logTag, "Error getting row count by status", e)
            Result.Error(e)
        }
    }

    /**
     * Update berry variety for a row.
     */
    override suspend fun updateRowBerryVariety(rowId: String, berryVariety: String): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Updating berry variety for row: $rowId to $berryVariety")

            safeWrite {
                val row = query<Row>("_id == $0", rowId).first().find()
                row?.apply {
                    this.berryVariety = berryVariety
                    this.isSynced = networkManager.isNetworkAvailable()
                }
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Update(rowId, entityType)
                )
            }

            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error updating berry variety", e)
            Result.Error(e)
        }
    }

    /**
     * Get rows by berry variety.
     */
    override suspend fun getRowsByBerryVariety(berryVariety: String): Flow<Result<List<Row>>> = flow {
        emit(Result.Loading)
        try {
            val realm = getRealm()
            realm.query<Row>("berryVariety CONTAINS[c] $0 AND isDeleted == false", berryVariety)
                .sort("quarter" to Sort.ASCENDING, "rowNumber" to Sort.ASCENDING)
                .asFlow()
                .map { Result.Success(it.list.toList()) }
                .collect { emit(it) }
        } catch (e: Exception) {
            Log.e(logTag, "Error in getRowsByBerryVariety", e)
            emit(Result.Error(e))
        }
    }

    override fun getEntityId(entity: Row): String {
        return entity._id
    }

    override fun MutableRealm.findEntityById(id: String): Row? {
        return this.query<Row>("_id == $0", id).first().find()
    }

    /**
     * Check for and automatically expire collected rows that are older than 3 days.
     * Returns the number of rows that were expired.
     */
    override suspend fun expireOldCollectedRows(): Result<Int> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Checking for expired collected rows")

            val threeDaysAgo = System.currentTimeMillis() - (3 * 24 * 60 * 60 * 1000L) // 3 days in milliseconds
            var expiredCount = 0

            // Find collected rows that are older than 3 days
            val expiredRows = realm.query<Row>(
                "isCollected == true AND collectedAt != null AND collectedAt < $0 AND isDeleted == false",
                threeDaysAgo
            ).find()

            if (expiredRows.isNotEmpty()) {
                Log.d(logTag, "Found ${expiredRows.size} expired rows")

                safeWrite {
                    expiredRows.forEach { row ->
                        val liveRow = query<Row>("_id == $0", row._id).first().find()
                        liveRow?.apply {
                            isCollected = false
                            collectedAt = null
                            lastModifiedAt = System.currentTimeMillis()
                            isSynced = networkManager.isNetworkAvailable()
                            expiredCount++
                        }
                    }
                }

                // Track pending operations if offline
                if (!networkManager.isNetworkAvailable()) {
                    expiredRows.forEach { row ->
                        addPendingOperation(
                            PendingOperation.Update(row._id, entityType)
                        )
                    }
                }

                Log.d(logTag, "Expired $expiredCount collected rows")
            } else {
                Log.d(logTag, "No expired rows found")
            }

            Result.Success(expiredCount)
        } catch (e: Exception) {
            Log.e(logTag, "Error expiring old collected rows", e)
            Result.Error(e)
        }
    }

    /**
     * Get rows that will expire soon (within next 24 hours).
     */
    override suspend fun getRowsExpiringSoon(): Result<List<Row>> = withDatabaseContext { realm ->
        try {
            val now = System.currentTimeMillis()
            val threeDaysAgo = now - (3 * 24 * 60 * 60 * 1000L)
            val twoDaysAgo = now - (2 * 24 * 60 * 60 * 1000L)

            // Rows collected between 2-3 days ago will expire within 24 hours
            val expiringSoonRows = realm.query<Row>(
                "isCollected == true AND collectedAt != null AND collectedAt >= $0 AND collectedAt < $1 AND isDeleted == false",
                threeDaysAgo, twoDaysAgo
            ).find().toList()

            Log.d(logTag, "Found ${expiringSoonRows.size} rows expiring soon")
            Result.Success(expiringSoonRows)
        } catch (e: Exception) {
            Log.e(logTag, "Error getting rows expiring soon", e)
            Result.Error(e)
        }
    }

    /**
     * Get rows collected within a specific time range.
     */
    override suspend fun getRowsCollectedInRange(startTime: Long, endTime: Long): Flow<Result<List<Row>>> = flow {
        emit(Result.Loading)
        try {
            val realm = getRealm()
            realm.query<Row>(
                "isCollected == true AND collectedAt != null AND collectedAt >= $0 AND collectedAt <= $1 AND isDeleted == false",
                startTime, endTime
            )
                .sort("quarter" to Sort.ASCENDING, "rowNumber" to Sort.ASCENDING)
                .asFlow()
                .map { Result.Success(it.list.toList()) }
                .collect { emit(it) }
        } catch (e: Exception) {
            Log.e(logTag, "Error in getRowsCollectedInRange", e)
            emit(Result.Error(e))
        }
    }

    /**
     * Update plant count for a row.
     */
    override suspend fun updateRowPlantCount(rowId: String, plantCount: Int): Result<Boolean> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Updating plant count for row: $rowId to $plantCount")

            if (plantCount <= 0) {
                return@withDatabaseContext Result.Error(Exception("Plant count must be greater than 0"))
            }

            safeWrite {
                val row = query<Row>("_id == $0", rowId).first().find()
                row?.apply {
                    this.plantCount = plantCount
                    this.isSynced = networkManager.isNetworkAvailable()
                }
            }

            // Track pending operation if offline
            if (!networkManager.isNetworkAvailable()) {
                addPendingOperation(
                    PendingOperation.Update(rowId, entityType)
                )
            }

            Result.Success(true)
        } catch (e: Exception) {
            Log.e(logTag, "Error updating plant count", e)
            Result.Error(e)
        }
    }

    /**
     * Get all rows with their performance data for Excel reporting.
     */
    override suspend fun getRowsPerformanceData(): Result<List<RowPerformanceData>> = withDatabaseContext { realm ->
        try {
            Log.d(logTag, "Getting rows performance data for Excel reporting")

            // Get all non-deleted rows
            val rows = realm.query<Row>("isDeleted == false")
                .sort("quarter" to Sort.ASCENDING, "rowNumber" to Sort.ASCENDING)
                .find()

            // Get all non-deleted gathers
            val gathers = realm.query<Gather>("isDeleted == false").find()

            // Group gathers by row number
            val gathersByRow = gathers.groupBy { it.rowNumber }

            // Calculate performance data for each row
            val performanceData = rows.map { row ->
                val rowGathers = gathersByRow[row.rowNumber] ?: emptyList()
                val totalPunnets = rowGathers.sumOf { it.numOfPunnets ?: 0 }
                val totalKg = totalPunnets * 0.64f
                val avgYieldPerPlant = if (row.plantCount > 0) totalKg / row.plantCount else 0f

                RowPerformanceData(
                    rowNumber = row.rowNumber,
                    quarter = row.quarter,
                    berryVariety = row.berryVariety.ifEmpty { "Not specified" },
                    plantCount = row.plantCount,
                    totalPunnets = totalPunnets,
                    avgYieldPerPlant = avgYieldPerPlant,
                    totalKg = totalKg
                )
            }

            Log.d(logTag, "Generated performance data for ${performanceData.size} rows")
            Result.Success(performanceData)
        } catch (e: Exception) {
            Log.e(logTag, "Error getting rows performance data", e)
            Result.Error(e)
        }
    }
}