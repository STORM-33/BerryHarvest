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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PaymentAdapter :
    ListAdapter<PaymentRecord, PaymentAdapter.PaymentViewHolder>(PaymentDiffCallback()) {

    class PaymentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dateTextView: TextView = view.findViewById(R.id.dateTextView)
        val amountTextView: TextView = view.findViewById(R.id.amountTextView)
        val notesTextView: TextView = view.findViewById(R.id.notesTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PaymentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_payment, parent, false)
        return PaymentViewHolder(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: PaymentViewHolder, position: Int) {
        val payment = getItem(position)

        // Format date
        val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val formattedDate = dateFormat.format(Date(payment.date))
        holder.dateTextView.text = formattedDate

        // Format amount with sign and currency
        val formattedAmount = String.format(Locale.getDefault(), "%.2f₴", payment.amount)
        holder.amountTextView.text = formattedAmount

        // Set color based on amount (positive = green, negative = red)
        if (payment.amount > 0) {
            holder.amountTextView.setTextColor(Color.parseColor("#4CAF50")) // Green
        } else {
            holder.amountTextView.setTextColor(Color.parseColor("#F44336")) // Red
        }

        // Show notes if available
        if (payment.notes.isNotEmpty()) {
            holder.notesTextView.visibility = View.VISIBLE
            holder.notesTextView.text = payment.notes
        } else {
            holder.notesTextView.visibility = View.GONE
        }

        // More subtle background for unsynced payments
        if (!payment.isSynced) {
            // Use a subtle background color
            holder.itemView.setBackgroundColor(Color.parseColor("#15FFC107")) // Very light amber with 10% opacity
            // Add a small sync indicator
            holder.dateTextView.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_sync_small, 0)
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.dateTextView.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        }
    }

    class PaymentDiffCallback : DiffUtil.ItemCallback<PaymentRecord>() {
        override fun areItemsTheSame(oldItem: PaymentRecord, newItem: PaymentRecord): Boolean {
            return oldItem._id == newItem._id
        }

        @SuppressLint("DiffUtilEquals")
        override fun areContentsTheSame(oldItem: PaymentRecord, newItem: PaymentRecord): Boolean {
            return oldItem.amount == newItem.amount &&
                    oldItem.date == newItem.date &&
                    oldItem.notes == newItem.notes &&
                    oldItem.isSynced == newItem.isSynced
        }
    }
}