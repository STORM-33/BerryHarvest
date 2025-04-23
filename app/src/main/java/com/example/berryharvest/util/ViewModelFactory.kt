package com.example.berryharvest.util

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.ui.add_worker.AddWorkerViewModel
import com.example.berryharvest.ui.assign_rows.AssignRowsViewModel
import com.example.berryharvest.ui.gather.GatherViewModel
import com.example.berryharvest.ui.payment.PaymentViewModel
import com.example.berryharvest.ui.report.ReportViewModel

/**
 * Factory for creating ViewModels with proper dependency injection
 */
class ViewModelFactory(private val application: BerryHarvestApplication) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            // Add case for each ViewModel class
            modelClass.isAssignableFrom(AddWorkerViewModel::class.java) -> {
                AddWorkerViewModel(application) as T
            }
            modelClass.isAssignableFrom(AssignRowsViewModel::class.java) -> {
                AssignRowsViewModel(application) as T
            }
            modelClass.isAssignableFrom(GatherViewModel::class.java) -> {
                GatherViewModel(application) as T
            }
            modelClass.isAssignableFrom(PaymentViewModel::class.java) -> {
                PaymentViewModel(application) as T
            }
            modelClass.isAssignableFrom(ReportViewModel::class.java) -> {
                ReportViewModel(application) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}