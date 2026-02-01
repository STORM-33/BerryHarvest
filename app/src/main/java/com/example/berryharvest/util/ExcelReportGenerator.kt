package com.example.berryharvest.util

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.data.model.*
import com.example.berryharvest.data.model.Row
import com.example.berryharvest.data.repository.RowPerformanceData
import io.realm.kotlin.ext.query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Comprehensive Excel report generator for berry harvest data
 * Creates detailed reports with multiple sheets covering all aspects of the operation
 */
class ExcelReportGenerator(private val context: Context) {

    companion object {
        private const val TAG = "ExcelReportGenerator"
        private const val REPORT_FOLDER = "BerryHarvest_Reports"
        private const val PUNNET_TO_KG = 0.64f
    }

    private val app: BerryHarvestApplication
        get() = context.applicationContext as BerryHarvestApplication

    /**
     * Generate a comprehensive Excel report with all data
     */
    suspend fun generateComprehensiveReport(
        startDate: Long? = null,
        endDate: Long? = null
    ): ExcelGenerationResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting comprehensive Excel report generation")

                val realm = app.getRealmInstance("excel_export")
                val workbook = XSSFWorkbook()

                // Create styles
                val styles = createStyles(workbook)

                // Get all data
                val workers = realm.query<Worker>("isDeleted == false").find()
                val rows = realm.query<Row>("isDeleted == false").find()
                val gathers = if (startDate != null && endDate != null) {
                    getGathersInDateRange(realm, startDate, endDate)
                } else {
                    realm.query<Gather>("isDeleted == false").find()
                }
                val assignments = realm.query<Assignment>("isDeleted == false").find()
                val paymentRecords = if (startDate != null && endDate != null) {
                    getPaymentRecordsInDateRange(realm, startDate, endDate)
                } else {
                    realm.query<PaymentRecord>("isDeleted == false").find()
                }
                val settings = realm.query<Settings>().find().firstOrNull()

                // Generate each sheet
                createRowStatisticsSheet(workbook, styles, rows, gathers)
                createWorkerPerformanceSheet(workbook, styles, workers, gathers, paymentRecords)
                createCollectionsByRowsSheet(workbook, styles, rows, gathers, workers)
                createDailyProductionSheet(workbook, styles, gathers, workers)
                createSummarySheet(workbook, styles, workers, rows, gathers, paymentRecords, settings)

                // Save file
                val reportFile = createReportFile()
                workbook.use { wb ->
                    FileOutputStream(reportFile).use { outputStream ->
                        wb.write(outputStream)
                    }
                }

                Log.d(TAG, "Excel report generated successfully: ${reportFile.absolutePath}")

                ExcelGenerationResult.Success(
                    file = reportFile,
                    sheetsGenerated = 5,
                    totalRecords = workers.size + rows.size + gathers.size
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error generating Excel report", e)
                ExcelGenerationResult.Error("Failed to generate report: ${e.message}")
            }
        }
    }

    /**
     * Create comprehensive styles for the Excel workbook
     */
    private fun createStyles(workbook: Workbook): ExcelStyles {
        val headerStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.DARK_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            setBorderBottom(BorderStyle.THIN)
            setBorderTop(BorderStyle.THIN)
            setBorderRight(BorderStyle.THIN)
            setBorderLeft(BorderStyle.THIN)
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
        }

        val headerFont = workbook.createFont().apply {
            color = IndexedColors.WHITE.index
            bold = true
            fontHeightInPoints = 12
            fontName = "Arial"
        }
        headerStyle.setFont(headerFont)

        val dataStyle = workbook.createCellStyle().apply {
            setBorderBottom(BorderStyle.THIN)
            setBorderTop(BorderStyle.THIN)
            setBorderRight(BorderStyle.THIN)
            setBorderLeft(BorderStyle.THIN)
            alignment = HorizontalAlignment.LEFT
            verticalAlignment = VerticalAlignment.CENTER
        }

        val numberStyle = workbook.createCellStyle().apply {
            setBorderBottom(BorderStyle.THIN)
            setBorderTop(BorderStyle.THIN)
            setBorderRight(BorderStyle.THIN)
            setBorderLeft(BorderStyle.THIN)
            alignment = HorizontalAlignment.RIGHT
            verticalAlignment = VerticalAlignment.CENTER
            dataFormat = workbook.createDataFormat().getFormat("#,##0.00")
        }

        val currencyStyle = workbook.createCellStyle().apply {
            setBorderBottom(BorderStyle.THIN)
            setBorderTop(BorderStyle.THIN)
            setBorderRight(BorderStyle.THIN)
            setBorderLeft(BorderStyle.THIN)
            alignment = HorizontalAlignment.RIGHT
            verticalAlignment = VerticalAlignment.CENTER
            dataFormat = workbook.createDataFormat().getFormat("#,##0.00₴")
        }

        val titleStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
        }

        val titleFont = workbook.createFont().apply {
            bold = true
            fontHeightInPoints = 14
            fontName = "Arial"
        }
        titleStyle.setFont(titleFont)

        return ExcelStyles(
            headerStyle = headerStyle,
            dataStyle = dataStyle,
            numberStyle = numberStyle,
            currencyStyle = currencyStyle,
            titleStyle = titleStyle
        )
    }

    /**
     * Sheet 1: Row Statistics (Статистика рядів)
     */
    private fun createRowStatisticsSheet(
        workbook: Workbook,
        styles: ExcelStyles,
        rows: List<Row>,
        gathers: List<Gather>
    ) {
        val sheet = workbook.createSheet("Статистика рядів")
        var currentRow = 0

        // Title
        val titleRow = sheet.createRow(currentRow++)
        val titleCell = titleRow.createCell(0)
        titleCell.setCellValue("СТАТИСТИКА РЯДІВ")
        titleCell.cellStyle = styles.titleStyle
        sheet.addMergedRegion(CellRangeAddress(0, 0, 0, 6))

        currentRow++ // Empty row

        // Headers
        val headerRow = sheet.createRow(currentRow++)
        val headers = listOf(
            "Номер ряду",
            "Квартал",
            "Сорт ягоди",
            "Кількість рослин",
            "Кількість пінеток",
            "Загальний збір (кг)",
            "Середнє плодоношення на рослину (кг)"
        )

        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = styles.headerStyle
        }

        // Group gathers by row number
        val gathersByRow = gathers.groupBy { it.rowNumber }

        // Data rows
        rows.sortedBy { it.rowNumber }.forEach { row ->
            val dataRow = sheet.createRow(currentRow++)
            val rowGathers = gathersByRow[row.rowNumber] ?: emptyList()
            val totalPunnets = rowGathers.sumOf { it.numOfPunnets ?: 0 }
            val totalKg = totalPunnets * PUNNET_TO_KG
            val avgYieldPerPlant = if (row.plantCount > 0) totalKg / row.plantCount else 0f

            // Row number
            val cell0 = dataRow.createCell(0)
            cell0.setCellValue(row.rowNumber.toDouble())
            cell0.cellStyle = styles.dataStyle

            // Quarter
            val cell1 = dataRow.createCell(1)
            cell1.setCellValue(row.quarter.toDouble())
            cell1.cellStyle = styles.dataStyle

            // Berry variety
            val cell2 = dataRow.createCell(2)
            cell2.setCellValue(row.berryVariety.ifEmpty { "Не вказано" })
            cell2.cellStyle = styles.dataStyle

            // Plant count
            val cell3 = dataRow.createCell(3)
            cell3.setCellValue(row.plantCount.toDouble())
            cell3.cellStyle = styles.numberStyle

            // Total punnets
            val cell4 = dataRow.createCell(4)
            cell4.setCellValue(totalPunnets.toDouble())
            cell4.cellStyle = styles.numberStyle

            // Total kg
            val cell5 = dataRow.createCell(5)
            cell5.setCellValue(totalKg.toDouble())
            cell5.cellStyle = styles.numberStyle

            // Average yield per plant
            val cell6 = dataRow.createCell(6)
            cell6.setCellValue(avgYieldPerPlant.toDouble())
            cell6.cellStyle = styles.numberStyle
        }

        // Set fixed column widths instead of auto-sizing (Android compatibility)
        sheet.setColumnWidth(0, 3000)  // Row number
        sheet.setColumnWidth(1, 2500)  // Quarter
        sheet.setColumnWidth(2, 4000)  // Berry variety
        sheet.setColumnWidth(3, 3500)  // Plant count
        sheet.setColumnWidth(4, 3500)  // Punnets
        sheet.setColumnWidth(5, 3500)  // Total kg
        sheet.setColumnWidth(6, 5000)  // Average yield per plant
    }

    /**
     * Sheet 2: Worker Performance (Працівники)
     */
    private fun createWorkerPerformanceSheet(
        workbook: Workbook,
        styles: ExcelStyles,
        workers: List<Worker>,
        gathers: List<Gather>,
        paymentRecords: List<PaymentRecord>
    ) {
        val sheet = workbook.createSheet("Працівники")
        var currentRow = 0

        // Title
        val titleRow = sheet.createRow(currentRow++)
        val titleCell = titleRow.createCell(0)
        titleCell.setCellValue("СТАТИСТИКА ПРАЦІВНИКІВ")
        titleCell.cellStyle = styles.titleStyle
        sheet.addMergedRegion(CellRangeAddress(0, 0, 0, 7))

        currentRow++ // Empty row

        // Headers
        val headerRow = sheet.createRow(currentRow++)
        val headers = listOf(
            "№",
            "Ім'я працівника",
            "Телефон",
            "Всього зібрано (пінеток)",
            "Всього зібрано (кг)",
            "Заробітної плати",
            "Всього виплачено",
            "Поточний баланс"
        )

        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = styles.headerStyle
        }

        // Group data by worker
        val gathersByWorker = gathers.groupBy { it.workerId }
        val paymentsByWorker = paymentRecords.groupBy { it.workerId }

        // Data rows
        workers.sortedBy { it.sequenceNumber }.forEach { worker ->
            val dataRow = sheet.createRow(currentRow++)
            val workerGathers = gathersByWorker[worker._id] ?: emptyList()
            val workerPayments = paymentsByWorker[worker._id] ?: emptyList()

            val totalPunnets = workerGathers.sumOf { it.numOfPunnets ?: 0 }
            val totalKg = totalPunnets * PUNNET_TO_KG
            val totalEarnings = workerGathers.sumOf { gather ->
                ((gather.numOfPunnets ?: 0) * (gather.punnetCost ?: 0f)).toDouble()
            }
            val totalPaid = workerPayments.sumOf { it.amount.toDouble() }
            val currentBalance = totalEarnings - totalPaid

            // Sequence number
            val cell0 = dataRow.createCell(0)
            cell0.setCellValue(worker.sequenceNumber.toDouble())
            cell0.cellStyle = styles.dataStyle

            // Name
            val cell1 = dataRow.createCell(1)
            cell1.setCellValue(worker.fullName)
            cell1.cellStyle = styles.dataStyle

            // Phone
            val cell2 = dataRow.createCell(2)
            cell2.setCellValue(worker.phoneNumber.ifEmpty { "Не вказано" })
            cell2.cellStyle = styles.dataStyle

            // Total punnets
            val cell3 = dataRow.createCell(3)
            cell3.setCellValue(totalPunnets.toDouble())
            cell3.cellStyle = styles.numberStyle

            // Total kg
            val cell4 = dataRow.createCell(4)
            cell4.setCellValue(totalKg.toDouble())
            cell4.cellStyle = styles.numberStyle

            // Total earnings
            val cell5 = dataRow.createCell(5)
            cell5.setCellValue(totalEarnings.toDouble())
            cell5.cellStyle = styles.currencyStyle

            // Total paid
            val cell6 = dataRow.createCell(6)
            cell6.setCellValue(totalPaid.toDouble())
            cell6.cellStyle = styles.currencyStyle

            // Current balance
            val cell7 = dataRow.createCell(7)
            cell7.setCellValue(currentBalance.toDouble())
            cell7.cellStyle = styles.currencyStyle
        }

        // Set fixed column widths instead of auto-sizing (Android compatibility)
        sheet.setColumnWidth(0, 2000)  // Sequence number
        sheet.setColumnWidth(1, 6000)  // Name
        sheet.setColumnWidth(2, 4000)  // Phone
        sheet.setColumnWidth(3, 3500)  // Total punnets
        sheet.setColumnWidth(4, 3500)  // Total kg
        sheet.setColumnWidth(5, 4000)  // Earnings
        sheet.setColumnWidth(6, 4000)  // Total paid
        sheet.setColumnWidth(7, 4000)  // Current balance
    }

    /**
     * Sheet 3: Collections by Rows (Збори по рядках)
     */
    private fun createCollectionsByRowsSheet(
        workbook: Workbook,
        styles: ExcelStyles,
        rows: List<Row>,
        gathers: List<Gather>,
        workers: List<Worker>
    ) {
        val sheet = workbook.createSheet("Збори по рядках")
        var currentRow = 0

        // Title
        val titleRow = sheet.createRow(currentRow++)
        val titleCell = titleRow.createCell(0)
        titleCell.setCellValue("ЗБОРИ ПО РЯДКАХ")
        titleCell.cellStyle = styles.titleStyle
        sheet.addMergedRegion(CellRangeAddress(0, 0, 0, 6))

        currentRow++ // Empty row

        // Headers
        val headerRow = sheet.createRow(currentRow++)
        val headers = listOf(
            "Ряд",
            "Дата збору",
            "Працівник",
            "Кількість пінеток",
            "Кількість ягоди (кг)",
            "Ціна за пінетку",
            "Сума"
        )

        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = styles.headerStyle
        }

        // Create worker lookup map
        val workerMap = workers.associateBy { it._id }
        val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

        // Sort gathers by row number and date
        val sortedGathers = gathers.sortedWith(compareBy({ it.rowNumber }, { it.dateTime }))

        // Data rows
        sortedGathers.forEach { gather ->
            val dataRow = sheet.createRow(currentRow++)
            val worker = workerMap[gather.workerId]
            val punnets = gather.numOfPunnets ?: 0
            val kg = punnets * PUNNET_TO_KG
            val price = gather.punnetCost ?: 0f
            val sum = punnets * price

            // Row number
            val cell0 = dataRow.createCell(0)
            cell0.setCellValue((gather.rowNumber ?: 0).toDouble())
            cell0.cellStyle = styles.dataStyle

            // Date
            val cell1 = dataRow.createCell(1)
            val dateStr = gather.dateTime ?: ""
            try {
                val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(dateStr)
                cell1.setCellValue(if (date != null) dateFormat.format(date) else dateStr)
            } catch (e: Exception) {
                cell1.setCellValue(dateStr)
            }
            cell1.cellStyle = styles.dataStyle

            // Worker
            val cell2 = dataRow.createCell(2)
            val workerName = worker?.let { "${it.fullName} [${it.sequenceNumber}]" } ?: "Невідомий"
            cell2.setCellValue(workerName)
            cell2.cellStyle = styles.dataStyle

            // Punnets
            val cell3 = dataRow.createCell(3)
            cell3.setCellValue(punnets.toDouble())
            cell3.cellStyle = styles.numberStyle

            // Kg
            val cell4 = dataRow.createCell(4)
            cell4.setCellValue(kg.toDouble())
            cell4.cellStyle = styles.numberStyle

            // Price per punnet
            val cell5 = dataRow.createCell(5)
            cell5.setCellValue(price.toDouble())
            cell5.cellStyle = styles.currencyStyle

            // Sum
            val cell6 = dataRow.createCell(6)
            cell6.setCellValue(sum.toDouble())
            cell6.cellStyle = styles.currencyStyle
        }

        // Set fixed column widths instead of auto-sizing (Android compatibility)
        sheet.setColumnWidth(0, 2500)  // Row
        sheet.setColumnWidth(1, 4000)  // Date
        sheet.setColumnWidth(2, 6000)  // Worker
        sheet.setColumnWidth(3, 3500)  // Punnets
        sheet.setColumnWidth(4, 3500)  // Kg
        sheet.setColumnWidth(5, 3500)  // Price per punnet
        sheet.setColumnWidth(6, 3500)  // Sum
    }

    /**
     * Sheet 4: Daily Production (Щоденна продукція)
     */
    private fun createDailyProductionSheet(
        workbook: Workbook,
        styles: ExcelStyles,
        gathers: List<Gather>,
        workers: List<Worker>
    ) {
        val sheet = workbook.createSheet("Щоденна продукція")
        var currentRow = 0

        // Title
        val titleRow = sheet.createRow(currentRow++)
        val titleCell = titleRow.createCell(0)
        titleCell.setCellValue("ЩОДЕННА ПРОДУКЦІЯ")
        titleCell.cellStyle = styles.titleStyle
        sheet.addMergedRegion(CellRangeAddress(0, 0, 0, 5))

        currentRow++ // Empty row

        // Headers
        val headerRow = sheet.createRow(currentRow++)
        val headers = listOf(
            "Дата",
            "Кількість працівників",
            "Всього пінеток",
            "Всього кг",
            "Середнє на працівника",
            "Загальна сума"
        )

        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = styles.headerStyle
        }

        // Group gathers by date
        val gathersByDate = gathers.groupBy { gather ->
            try {
                val dateStr = gather.dateTime ?: ""
                dateStr.substring(0, 10) // Extract YYYY-MM-DD part
            } catch (e: Exception) {
                "Unknown"
            }
        }.filterKeys { it != "Unknown" }.toSortedMap()

        // Data rows
        gathersByDate.forEach { (date, dayGathers) ->
            val dataRow = sheet.createRow(currentRow++)
            val uniqueWorkers = dayGathers.mapNotNull { it.workerId }.distinct()
            val totalPunnets = dayGathers.sumOf { it.numOfPunnets ?: 0 }
            val totalKg = totalPunnets * PUNNET_TO_KG
            val avgPerWorker = if (uniqueWorkers.isNotEmpty()) totalPunnets.toFloat() / uniqueWorkers.size else 0f
            val totalSum = dayGathers.sumOf { gather ->
                ((gather.numOfPunnets ?: 0) * (gather.punnetCost ?: 0f)).toDouble()
            }

            // Date
            val cell0 = dataRow.createCell(0)
            try {
                val formattedDate = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                    .format(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(date) ?: Date())
                cell0.setCellValue(formattedDate)
            } catch (e: Exception) {
                cell0.setCellValue(date)
            }
            cell0.cellStyle = styles.dataStyle

            // Worker count
            val cell1 = dataRow.createCell(1)
            cell1.setCellValue(uniqueWorkers.size.toDouble())
            cell1.cellStyle = styles.numberStyle

            // Total punnets
            val cell2 = dataRow.createCell(2)
            cell2.setCellValue(totalPunnets.toDouble())
            cell2.cellStyle = styles.numberStyle

            // Total kg
            val cell3 = dataRow.createCell(3)
            cell3.setCellValue(totalKg.toDouble())
            cell3.cellStyle = styles.numberStyle

            // Average per worker
            val cell4 = dataRow.createCell(4)
            cell4.setCellValue(avgPerWorker.toDouble())
            cell4.cellStyle = styles.numberStyle

            // Total sum
            val cell5 = dataRow.createCell(5)
            cell5.setCellValue(totalSum.toDouble())
            cell5.cellStyle = styles.currencyStyle
        }

        // Set fixed column widths instead of auto-sizing (Android compatibility)
        sheet.setColumnWidth(0, 3000)  // Date
        sheet.setColumnWidth(1, 3500)  // Worker count
        sheet.setColumnWidth(2, 3500)  // Total punnets
        sheet.setColumnWidth(3, 3500)  // Total kg
        sheet.setColumnWidth(4, 4000)  // Average per worker
        sheet.setColumnWidth(5, 4000)  // Total sum
    }

    /**
     * Sheet 5: Summary (Підсумок)
     */
    private fun createSummarySheet(
        workbook: Workbook,
        styles: ExcelStyles,
        workers: List<Worker>,
        rows: List<Row>,
        gathers: List<Gather>,
        paymentRecords: List<PaymentRecord>,
        settings: Settings?
    ) {
        val sheet = workbook.createSheet("Підсумок")
        var currentRow = 0

        // Title
        val titleRow = sheet.createRow(currentRow++)
        val titleCell = titleRow.createCell(0)
        titleCell.setCellValue("ЗАГАЛЬНИЙ ПІДСУМОК")
        titleCell.cellStyle = styles.titleStyle
        sheet.addMergedRegion(CellRangeAddress(0, 0, 0, 1))

        currentRow++ // Empty row

        // Generation info
        val generationRow = sheet.createRow(currentRow++)
        generationRow.createCell(0).apply {
            setCellValue("Дата створення звіту:")
            cellStyle = styles.dataStyle
        }
        generationRow.createCell(1).apply {
            val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            setCellValue(dateFormat.format(Date()))
            cellStyle = styles.dataStyle
        }

        currentRow++ // Empty row

        // Summary statistics
        val totalWorkers = workers.size
        val totalRows = rows.size
        val totalPunnets = gathers.sumOf { it.numOfPunnets ?: 0 }
        val totalKg = totalPunnets * PUNNET_TO_KG
        val totalEarnings = gathers.sumOf { gather ->
            ((gather.numOfPunnets ?: 0) * (gather.punnetCost ?: 0f)).toDouble()
        }
        val totalPaid = paymentRecords.sumOf { it.amount.toDouble() }
        val totalBalance = totalEarnings - totalPaid
        val currentPrice = settings?.punnetPrice ?: 0f

        val summaryData = listOf(
            "Загальна кількість працівників" to totalWorkers.toString(),
            "Загальна кількість рядів" to totalRows.toString(),
            "Всього зібрано пінеток" to totalPunnets.toString(),
            "Всього зібрано кг" to String.format("%.2f", totalKg),
            "Загальна заробітна плата" to String.format("%.2f₴", totalEarnings),
            "Всього виплачено" to String.format("%.2f₴", totalPaid),
            "Загальний баланс до виплати" to String.format("%.2f₴", totalBalance),
            "Поточна ціна за пінетку" to String.format("%.2f₴", currentPrice)
        )

        summaryData.forEach { (label, value) ->
            val dataRow = sheet.createRow(currentRow++)
            dataRow.createCell(0).apply {
                setCellValue(label)
                cellStyle = styles.dataStyle
            }
            dataRow.createCell(1).apply {
                setCellValue(value)
                cellStyle = styles.numberStyle
            }
        }

        // Set fixed column widths instead of auto-sizing (Android compatibility)
        sheet.setColumnWidth(0, 6000)  // Label column
        sheet.setColumnWidth(1, 4000)  // Value column
    }

    /**
     * Helper functions
     */
    private fun getGathersInDateRange(realm: io.realm.kotlin.Realm, startDate: Long, endDate: Long): List<Gather> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val startDateStr = dateFormat.format(Date(startDate))
        val endDateStr = dateFormat.format(Date(endDate))

        return realm.query<Gather>("dateTime >= $0 AND dateTime <= $1 AND isDeleted == false", startDateStr, endDateStr).find()
    }

    private fun getPaymentRecordsInDateRange(realm: io.realm.kotlin.Realm, startDate: Long, endDate: Long): List<PaymentRecord> {
        return realm.query<PaymentRecord>("date >= $0 AND date <= $1 AND isDeleted == false", startDate, endDate).find()
    }

    private fun createReportFile(): File {
        val reportsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            REPORT_FOLDER
        )

        if (!reportsDir.exists()) {
            reportsDir.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(reportsDir, "berry_harvest_report_$timestamp.xlsx")
    }

    /**
     * Generate a quick summary report with just key metrics
     */
    suspend fun generateQuickSummaryReport(): ExcelGenerationResult {
        return withContext(Dispatchers.IO) {
            try {
                val realm = app.getRealmInstance("quick_export")
                val workbook = XSSFWorkbook()
                val styles = createStyles(workbook)

                val gathers = realm.query<Gather>("isDeleted == false").find()
                val workers = realm.query<Worker>("isDeleted == false").find()
                val paymentRecords = realm.query<PaymentRecord>("isDeleted == false").find()

                // Create single summary sheet
                createSummarySheet(workbook, styles, workers, emptyList(), gathers, paymentRecords, null)

                val reportFile = createReportFile()
                workbook.use { wb ->
                    FileOutputStream(reportFile).use { outputStream ->
                        wb.write(outputStream)
                    }
                }

                ExcelGenerationResult.Success(
                    file = reportFile,
                    sheetsGenerated = 1,
                    totalRecords = workers.size + gathers.size
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error generating quick summary report", e)
                ExcelGenerationResult.Error("Failed to generate summary: ${e.message}")
            }
        }
    }
}

/**
 * Data classes for Excel generation
 */
data class ExcelStyles(
    val headerStyle: CellStyle,
    val dataStyle: CellStyle,
    val numberStyle: CellStyle,
    val currencyStyle: CellStyle,
    val titleStyle: CellStyle
)

sealed class ExcelGenerationResult {
    data class Success(
        val file: File,
        val sheetsGenerated: Int,
        val totalRecords: Int
    ) : ExcelGenerationResult()

    data class Error(val message: String) : ExcelGenerationResult()
}