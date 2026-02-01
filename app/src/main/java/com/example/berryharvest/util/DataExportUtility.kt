package com.example.berryharvest.util

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.data.model.*
import io.realm.kotlin.ext.query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility class for exporting Realm data in debug builds
 * Only available when ENABLE_DATA_EXPORT is true
 */
class DataExportUtility(private val context: Context) {

    companion object {
        private const val TAG = "DataExportUtility"
        private const val EXPORT_FOLDER = "BerryHarvest_DataExport"
    }

    /**
     * Export all data to a single JSON file
     */
    suspend fun exportAllData(): ExportResult {
        return withContext(Dispatchers.IO) {
            try {
                val app = context.applicationContext as BerryHarvestApplication
                val realm = app.getRealmInstance("export")

                // Create export directory
                val exportDir = createExportDirectory()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

                Log.d(TAG, "Starting data export to: ${exportDir.absolutePath}")

                // Create one consolidated JSON object
                val consolidatedData = JSONObject()

                // Export Workers
                val workers = realm.query<Worker>().find()
                consolidatedData.put("workers", exportWorkersToJson(workers))

                // Export Gathers
                val gathers = realm.query<Gather>().find()
                consolidatedData.put("gathers", exportGathersToJson(gathers))

                // Export Assignments
                val assignments = realm.query<Assignment>().find()
                consolidatedData.put("assignments", exportAssignmentsToJson(assignments))

                // Export Settings
                val settings = realm.query<Settings>().find()
                consolidatedData.put("settings", exportSettingsToJson(settings))

                // Export Payment Records
                val paymentRecords = realm.query<PaymentRecord>().find()
                consolidatedData.put("payment_records", exportPaymentRecordsToJson(paymentRecords))

                // Export Payment Balances
                val paymentBalances = realm.query<PaymentBalance>().find()
                consolidatedData.put("payment_balances", exportPaymentBalancesToJson(paymentBalances))

                // Export Rows
                val rows = realm.query<Row>().find()
                consolidatedData.put("rows", exportRowsToJson(rows))

                // Add metadata
                val metadata = JSONObject().apply {
                    put("export_timestamp", timestamp)
                    put("export_date", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                    put("total_workers", workers.size)
                    put("total_gathers", gathers.size)
                    put("total_assignments", assignments.size)
                    put("total_settings", settings.size)
                    put("total_payment_records", paymentRecords.size)
                    put("total_payment_balances", paymentBalances.size)
                    put("total_rows", rows.size)
                    put("total_records", workers.size + gathers.size + assignments.size + settings.size + paymentRecords.size + paymentBalances.size + rows.size)
                }
                consolidatedData.put("metadata", metadata)

                // Save to single file
                val exportFile = File(exportDir, "berry_harvest_backup_$timestamp.json")
                exportFile.writeText(consolidatedData.toString(2))

                val totalRecords = workers.size + gathers.size + assignments.size + settings.size + paymentRecords.size + paymentBalances.size + rows.size

                Log.d(TAG, "Data export completed successfully. Total records: $totalRecords")

                val results = mapOf(
                    "workers" to workers.size,
                    "gathers" to gathers.size,
                    "assignments" to assignments.size,
                    "settings" to settings.size,
                    "payment_records" to paymentRecords.size,
                    "payment_balances" to paymentBalances.size,
                    "rows" to rows.size
                )

                ExportResult.Success(exportFile, results)

            } catch (e: Exception) {
                Log.e(TAG, "Error during data export", e)
                ExportResult.Error("Export failed: ${e.message}")
            }
        }
    }

    // Helper methods to convert to JSON arrays
    private fun exportWorkersToJson(workers: List<Worker>): JSONArray {
        val jsonArray = JSONArray()
        workers.forEach { worker ->
            val jsonObject = JSONObject().apply {
                put("_id", worker._id)
                put("sequenceNumber", worker.sequenceNumber)
                put("fullName", worker.fullName)
                put("phoneNumber", worker.phoneNumber)
                put("qrCode", worker.qrCode)
                put("createdAt", worker.createdAt)
                put("isSynced", worker.isSynced)
                put("isDeleted", worker.isDeleted)
            }
            jsonArray.put(jsonObject)
        }
        return jsonArray
    }

    private fun exportGathersToJson(gathers: List<Gather>): JSONArray {
        val jsonArray = JSONArray()
        gathers.forEach { gather ->
            val jsonObject = JSONObject().apply {
                put("_id", gather._id)
                put("workerId", gather.workerId)
                put("rowNumber", gather.rowNumber)
                put("numOfPunnets", gather.numOfPunnets)
                put("dateTime", gather.dateTime)
                put("punnetCost", gather.punnetCost)
                put("isSynced", gather.isSynced)
                put("isDeleted", gather.isDeleted)
            }
            jsonArray.put(jsonObject)
        }
        return jsonArray
    }

    private fun exportAssignmentsToJson(assignments: List<Assignment>): JSONArray {
        val jsonArray = JSONArray()
        assignments.forEach { assignment ->
            val jsonObject = JSONObject().apply {
                put("_id", assignment._id)
                put("rowNumber", assignment.rowNumber)
                put("workerId", assignment.workerId)
                put("isSynced", assignment.isSynced)
                put("isDeleted", assignment.isDeleted)
            }
            jsonArray.put(jsonObject)
        }
        return jsonArray
    }

    private fun exportSettingsToJson(settings: List<Settings>): JSONArray {
        val jsonArray = JSONArray()
        settings.forEach { setting ->
            val jsonObject = JSONObject().apply {
                put("_id", setting._id)
                put("punnetPrice", setting.punnetPrice)
                put("isSynced", setting.isSynced)
            }
            jsonArray.put(jsonObject)
        }
        return jsonArray
    }

    private fun exportPaymentRecordsToJson(paymentRecords: List<PaymentRecord>): JSONArray {
        val jsonArray = JSONArray()
        paymentRecords.forEach { paymentRecord ->
            val jsonObject = JSONObject().apply {
                put("_id", paymentRecord._id)
                put("workerId", paymentRecord.workerId)
                put("amount", paymentRecord.amount)
                put("date", paymentRecord.date)
                put("notes", paymentRecord.notes)
                put("isSynced", paymentRecord.isSynced)
                put("isDeleted", paymentRecord.isDeleted)
            }
            jsonArray.put(jsonObject)
        }
        return jsonArray
    }

    private fun exportPaymentBalancesToJson(paymentBalances: List<PaymentBalance>): JSONArray {
        val jsonArray = JSONArray()
        paymentBalances.forEach { paymentBalance ->
            val jsonObject = JSONObject().apply {
                put("_id", paymentBalance._id)
                put("workerId", paymentBalance.workerId)
                put("currentBalance", paymentBalance.currentBalance)
                put("lastUpdated", paymentBalance.lastUpdated)
                put("isSynced", paymentBalance.isSynced)
            }
            jsonArray.put(jsonObject)
        }
        return jsonArray
    }

    private fun exportRowsToJson(rows: List<Row>): JSONArray {
        val jsonArray = JSONArray()
        rows.forEach { row ->
            val jsonObject = JSONObject().apply {
                put("_id", row._id)
                put("rowNumber", row.rowNumber)
                put("quarter", row.quarter)
                put("berryVariety", row.berryVariety)
                put("plantCount", row.plantCount)
                put("isCollected", row.isCollected)
                put("createdAt", row.createdAt)
                put("collectedAt", row.collectedAt)
                put("lastModifiedAt", row.lastModifiedAt)
                put("isSynced", row.isSynced)
                put("isDeleted", row.isDeleted)
            }
            jsonArray.put(jsonObject)
        }
        return jsonArray
    }

    private fun createExportDirectory(): File {
        val exportDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            EXPORT_FOLDER
        )

        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }

        return exportDir
    }

    /**
     * Get the count of records for each table without exporting
     */
    suspend fun getDataCounts(): Map<String, Int> {
        if (false) {
            return emptyMap()
        }

        return withContext(Dispatchers.IO) {
            try {
                val app = context.applicationContext as BerryHarvestApplication
                val realm = app.getRealmInstance("count")

                mapOf(
                    "workers" to realm.query<Worker>().count().find().toInt(),
                    "gathers" to realm.query<Gather>().count().find().toInt(),
                    "assignments" to realm.query<Assignment>().count().find().toInt(),
                    "settings" to realm.query<Settings>().count().find().toInt(),
                    "payment_records" to realm.query<PaymentRecord>().count().find().toInt(),
                    "payment_balances" to realm.query<PaymentBalance>().count().find().toInt(),
                    "rows" to realm.query<Row>().count().find().toInt()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error getting data counts", e)
                emptyMap()
            }
        }
    }
}

sealed class ExportResult {
    data class Success(val exportFile: File, val results: Map<String, Int>) : ExportResult()
    data class Error(val message: String) : ExportResult()
}