package com.example.berryharvest.ui.payment

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.R
import java.util.Locale

/**
 * Adapter for displaying daily payment summaries
 */
class DailyPaymentSummaryAdapter :
    ListAdapter<DailyPaymentSummary, DailyPaymentSummaryAdapter.SummaryViewHolder>(DailyPaymentSummaryDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SummaryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_daily_payment_summary, parent, false)
        return SummaryViewHolder(view)
    }

    override fun onBindViewHolder(holder: SummaryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class SummaryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dateTextView: TextView = itemView.findViewById(R.id.dateTextView)
        private val totalPaidTextView: TextView = itemView.findViewById(R.id.totalPaidTextView)
        private val paidWithBerryTextView: TextView = itemView.findViewById(R.id.paidWithBerryTextView)
        private val paidOtherTextView: TextView = itemView.findViewById(R.id.paidOtherTextView)

        fun bind(summary: DailyPaymentSummary) {
            // Set date
            dateTextView.text = summary.date

            // Format and set amounts
            totalPaidTextView.text = String.format(Locale.getDefault(), "%.2f₴", summary.totalPaid)
            paidWithBerryTextView.text = String.format(Locale.getDefault(), "%.2f₴", summary.paidWithBerry)
            paidOtherTextView.text = String.format(Locale.getDefault(), "%.2f₴", summary.paidOther)

            // Set visibility for zero amounts (optional - you can choose to always show or hide zeros)
            // Uncomment these lines if you want to hide rows with zero amounts
            /*
            paidWithBerryTextView.visibility = if (summary.paidWithBerry > 0) View.VISIBLE else View.GONE
            paidOtherTextView.visibility = if (summary.paidOther > 0) View.VISIBLE else View.GONE
            */
        }
    }

    class DailyPaymentSummaryDiffCallback : DiffUtil.ItemCallback<DailyPaymentSummary>() {
        override fun areItemsTheSame(oldItem: DailyPaymentSummary, newItem: DailyPaymentSummary): Boolean {
            return oldItem.dateTimestamp == newItem.dateTimestamp
        }

        override fun areContentsTheSame(oldItem: DailyPaymentSummary, newItem: DailyPaymentSummary): Boolean {
            return oldItem == newItem
        }
    }
}