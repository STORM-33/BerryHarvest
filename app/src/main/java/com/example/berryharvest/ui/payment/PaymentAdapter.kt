package com.example.berryharvest.ui.payment

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.R
import com.example.berryharvest.data.model.PaymentRecord
import com.example.berryharvest.data.model.Worker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Sealed class for different item types
sealed class PaymentListItem {
    data class DateHeader(val date: String, val totalAmount: Float, val isWorkerView: Boolean = false) : PaymentListItem()
    data class PaymentItem(val payment: PaymentRecord, val workerName: String? = null) : PaymentListItem()
    data class GatherItem(val gatherSummary: DailyGatherSummary) : PaymentListItem()
    data class SummaryItem(val summary: DailyPaymentSummary) : PaymentListItem() // NEW: Add summary item type
}

class PaymentAdapter(
    private val showWorkerNames: Boolean = false,
    private val isWorkerView: Boolean = false,
    private val showSummaries: Boolean = false // NEW: Flag to show summaries instead of individual payments
) : ListAdapter<PaymentListItem, RecyclerView.ViewHolder>(PaymentListItemDiffCallback()) {

    companion object {
        private const val TYPE_DATE_HEADER = 0
        private const val TYPE_PAYMENT_ITEM = 1
        private const val TYPE_GATHER_ITEM = 2
        private const val TYPE_SUMMARY_ITEM = 3 // NEW: Summary item type
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is PaymentListItem.DateHeader -> TYPE_DATE_HEADER
            is PaymentListItem.PaymentItem -> TYPE_PAYMENT_ITEM
            is PaymentListItem.GatherItem -> TYPE_GATHER_ITEM
            is PaymentListItem.SummaryItem -> TYPE_SUMMARY_ITEM // NEW
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_DATE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_history_date_header, parent, false)
                DateHeaderViewHolder(view)
            }
            TYPE_PAYMENT_ITEM -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_payment, parent, false)
                PaymentViewHolder(view)
            }
            TYPE_GATHER_ITEM -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_gather_summary, parent, false)
                GatherViewHolder(view)
            }
            TYPE_SUMMARY_ITEM -> { // NEW
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_daily_payment_summary, parent, false)
                SummaryViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is PaymentListItem.DateHeader -> {
                (holder as DateHeaderViewHolder).bind(item)
            }
            is PaymentListItem.PaymentItem -> {
                (holder as PaymentViewHolder).bind(item, showWorkerNames)
            }
            is PaymentListItem.GatherItem -> {
                (holder as GatherViewHolder).bind(item.gatherSummary)
            }
            is PaymentListItem.SummaryItem -> { // NEW
                (holder as SummaryViewHolder).bind(item.summary)
            }
        }
    }

    class DateHeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val dateHeaderTextView: TextView = view.findViewById(R.id.dateHeaderTextView)
        private val paymentAmountTextView: TextView = view.findViewById(R.id.paymentAmountTextView)

        fun bind(dateHeader: PaymentListItem.DateHeader) {
            // Always show the date
            dateHeaderTextView.text = dateHeader.date

            if (dateHeader.isWorkerView) {
                // Worker view: Show payment amount separately if payments > 0
                if (dateHeader.totalAmount > 0) {
                    val formattedAmount = String.format(Locale.getDefault(), "%.2f₴", dateHeader.totalAmount)
                    paymentAmountTextView.text = "виплачено $formattedAmount"
                    paymentAmountTextView.visibility = View.VISIBLE
                } else {
                    paymentAmountTextView.visibility = View.GONE
                }
            } else {
                // Global view: Show net amount on the right side
                val formattedAmount = String.format(Locale.getDefault(), "%.2f₴", dateHeader.totalAmount)
                paymentAmountTextView.text = formattedAmount
                paymentAmountTextView.visibility = View.VISIBLE
            }
        }
    }

    class PaymentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dateTextView: TextView = view.findViewById(R.id.dateTextView)
        val amountTextView: TextView = view.findViewById(R.id.amountTextView)
        val notesTextView: TextView = view.findViewById(R.id.notesTextView)
        val workerTextView: TextView? = try {
            view.findViewById(R.id.workerNameTextView)
        } catch (e: Exception) {
            null
        }

        @SuppressLint("SetTextI18n")
        fun bind(paymentItem: PaymentListItem.PaymentItem, showWorkerNames: Boolean) {
            val payment = paymentItem.payment

            // Format time only (since date is in header)
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val formattedTime = timeFormat.format(Date(payment.date))
            dateTextView.text = formattedTime

            // Format amount with sign and currency
            val formattedAmount = String.format(Locale.getDefault(), "%.2f₴", payment.amount)
            amountTextView.text = formattedAmount

            // Set color based on amount (positive = green, negative = red)
            if (payment.amount > 0) {
                amountTextView.setTextColor(Color.parseColor("#4CAF50")) // Green
            } else {
                amountTextView.setTextColor(Color.parseColor("#F44336")) // Red
            }

            // Handle worker name and notes display
            if (showWorkerNames && paymentItem.workerName != null) {
                // Show worker name in a dedicated TextView if available
                workerTextView?.let {
                    it.visibility = View.VISIBLE
                    it.text = paymentItem.workerName
                }

                // Show notes separately if they exist
                if (payment.notes.isNotEmpty()) {
                    notesTextView.visibility = View.VISIBLE
                    notesTextView.text = payment.notes
                } else {
                    notesTextView.visibility = View.GONE
                }
            } else {
                // Hide worker name TextView
                workerTextView?.visibility = View.GONE

                // Show notes if available (worker-specific view)
                if (payment.notes.isNotEmpty()) {
                    notesTextView.visibility = View.VISIBLE
                    notesTextView.text = payment.notes
                } else {
                    notesTextView.visibility = View.GONE
                }
            }

            // More subtle background for unsynced payments
            if (!payment.isSynced) {
                itemView.setBackgroundColor(Color.parseColor("#15FFC107"))
                dateTextView.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_sync_small, 0)
            } else {
                itemView.setBackgroundColor(Color.TRANSPARENT)
                dateTextView.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            }
        }
    }

    class GatherViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val timeTextView: TextView = view.findViewById(R.id.timeTextView)
        private val punnetsTextView: TextView = view.findViewById(R.id.punnetsTextView)
        private val priceTextView: TextView = view.findViewById(R.id.priceTextView)
        private val earningsTextView: TextView = view.findViewById(R.id.earningsTextView)

        fun bind(gatherSummary: DailyGatherSummary) {
            // For gather items, we don't show specific time, just "Збір"
            timeTextView.text = "Збір"

            // Show punnet details
            punnetsTextView.text = "${gatherSummary.totalPunnets} шт."
            priceTextView.text = String.format(Locale.getDefault(), "%.2f₴/шт", gatherSummary.punnetPrice)
            earningsTextView.text = String.format(Locale.getDefault(), "+%.2f₴", gatherSummary.totalEarnings)

            // Set earnings color to indicate positive amount (green)
            earningsTextView.setTextColor(Color.parseColor("#4CAF50"))
        }
    }

    // NEW: Summary ViewHolder
    class SummaryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val dateTextView: TextView = view.findViewById(R.id.dateTextView)
        private val totalPaidTextView: TextView = view.findViewById(R.id.totalPaidTextView)
        private val paidWithBerryTextView: TextView = view.findViewById(R.id.paidWithBerryTextView)
        private val paidOtherTextView: TextView = view.findViewById(R.id.paidOtherTextView)

        fun bind(summary: DailyPaymentSummary) {
            // Set date
            dateTextView.text = summary.date

            // Format and set amounts
            totalPaidTextView.text = String.format(Locale.getDefault(), "%.2f₴", summary.totalPaid)
            paidWithBerryTextView.text = String.format(Locale.getDefault(), "%.2f₴", summary.paidWithBerry)
            paidOtherTextView.text = String.format(Locale.getDefault(), "%.2f₴", summary.paidOther)
        }
    }

    class PaymentListItemDiffCallback : DiffUtil.ItemCallback<PaymentListItem>() {
        override fun areItemsTheSame(oldItem: PaymentListItem, newItem: PaymentListItem): Boolean {
            return when {
                oldItem is PaymentListItem.DateHeader && newItem is PaymentListItem.DateHeader ->
                    oldItem.date == newItem.date
                oldItem is PaymentListItem.PaymentItem && newItem is PaymentListItem.PaymentItem ->
                    oldItem.payment._id == newItem.payment._id
                oldItem is PaymentListItem.GatherItem && newItem is PaymentListItem.GatherItem ->
                    oldItem.gatherSummary.dateTimestamp == newItem.gatherSummary.dateTimestamp
                oldItem is PaymentListItem.SummaryItem && newItem is PaymentListItem.SummaryItem -> // NEW
                    oldItem.summary.dateTimestamp == newItem.summary.dateTimestamp
                else -> false
            }
        }

        @SuppressLint("DiffUtilEquals")
        override fun areContentsTheSame(oldItem: PaymentListItem, newItem: PaymentListItem): Boolean {
            return oldItem == newItem
        }
    }
}

// Helper function to group payments and gathers by date - UPDATED for worker view
fun groupPaymentsByDateWithGathers(
    payments: List<PaymentRecord>,
    gatherSummaries: List<DailyGatherSummary>,
    workers: List<Worker> = emptyList(),
    showWorkerNames: Boolean = false,
    isWorkerView: Boolean = false
): List<PaymentListItem> {
    val result = mutableListOf<PaymentListItem>()
    val workerMap = workers.associateBy { it._id }

    // For worker view, we only show gathers and payment amounts in headers
    if (isWorkerView) {
        // Group payments by date for header calculation only
        val groupedPayments = payments
            .sortedByDescending { it.date }
            .groupBy { payment ->
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = payment.date
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                calendar.timeInMillis
            }

        // Group gathers by date
        val groupedGathers = gatherSummaries.associateBy { it.dateTimestamp }

        val dateFormat = SimpleDateFormat("dd MMMM", Locale("uk", "UA"))

        // Get all unique dates from both payments and gathers, sorted descending
        val allDates = (groupedPayments.keys + groupedGathers.keys).distinct().sortedDescending()

        allDates.forEach { dateTimestamp ->
            val paymentsForDate = groupedPayments[dateTimestamp] ?: emptyList()
            val gatherForDate = groupedGathers[dateTimestamp]

            // Calculate payment total for the header (only positive amounts paid out)
            val paymentTotal = paymentsForDate.sumOf { it.amount.toDouble() }.toFloat()

            // Only show header if there are gathers or payments for this date
            if (gatherForDate != null || paymentTotal > 0) {
                val dateString = dateFormat.format(Date(dateTimestamp))
                result.add(PaymentListItem.DateHeader(dateString, paymentTotal, isWorkerView = true))

                // Add gather summary if it exists (only gather items, no payment items)
                gatherForDate?.let { gather ->
                    result.add(PaymentListItem.GatherItem(gather))
                }
                // Note: Payment items are NOT added in worker view
            }
        }
    } else {
        // Original logic for global view (unchanged)
        val groupedPayments = payments
            .sortedByDescending { it.date }
            .groupBy { payment ->
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = payment.date
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                calendar.timeInMillis
            }

        val groupedGathers = gatherSummaries.associateBy { it.dateTimestamp }
        val dateFormat = SimpleDateFormat("dd MMMM", Locale("uk", "UA"))
        val allDates = (groupedPayments.keys + groupedGathers.keys).distinct().sortedDescending()

        allDates.forEach { dateTimestamp ->
            val paymentsForDate = groupedPayments[dateTimestamp] ?: emptyList()
            val gatherForDate = groupedGathers[dateTimestamp]

            // Calculate total for the date header
            val paymentTotal = paymentsForDate.sumOf { it.amount.toDouble() }.toFloat()
            val gatherTotal = gatherForDate?.totalEarnings ?: 0f

            // For detailed view, show just the payment total (money that was paid out)
            val displayTotal = paymentTotal

            // Add date header
            val dateString = dateFormat.format(Date(dateTimestamp))
            result.add(PaymentListItem.DateHeader(dateString, displayTotal, isWorkerView = false))

            // Add gather summary first if it exists
            gatherForDate?.let { gather ->
                result.add(PaymentListItem.GatherItem(gather))
            }

            // Add payments for this date (sorted by time, most recent first)
            paymentsForDate
                .sortedByDescending { it.date }
                .forEach { payment ->
                    val workerName = if (showWorkerNames) {
                        workerMap[payment.workerId]?.fullName
                    } else null
                    result.add(PaymentListItem.PaymentItem(payment, workerName))
                }
        }
    }

    return result
}

// NEW: Helper function to convert summaries to list items
fun groupSummariesAsListItems(summaries: List<DailyPaymentSummary>): List<PaymentListItem> {
    return summaries.map { PaymentListItem.SummaryItem(it) }
}

// Keep the original function for backward compatibility - UPDATED to pass isWorkerView
fun groupPaymentsByDate(
    payments: List<PaymentRecord>,
    workers: List<Worker> = emptyList(),
    showWorkerNames: Boolean = false
): List<PaymentListItem> {
    return groupPaymentsByDateWithGathers(payments, emptyList(), workers, showWorkerNames, isWorkerView = false)
}