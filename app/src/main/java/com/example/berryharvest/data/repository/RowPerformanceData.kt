package com.example.berryharvest.data.repository

/**
 * Data class for row performance reporting in Excel
 */
data class RowPerformanceData(
    val rowNumber: Int,
    val quarter: Int,
    val berryVariety: String,
    val plantCount: Int,
    val totalPunnets: Int,
    val avgYieldPerPlant: Float, // in kg (punnets * 0.64 / plantCount)
    val totalKg: Float // totalPunnets * 0.64
)