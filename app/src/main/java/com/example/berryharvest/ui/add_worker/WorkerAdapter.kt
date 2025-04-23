package com.example.berryharvest.ui.add_worker

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.R
import com.example.berryharvest.data.model.Worker

class WorkerAdapter(private val onItemLongClick: (Worker) -> Unit) :
    ListAdapter<Worker, WorkerAdapter.WorkerViewHolder>(WorkerDiffCallback()) {

    class WorkerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val idTextView: TextView = view.findViewById(R.id.textViewId)
        val fullNameTextView: TextView = view.findViewById(R.id.textViewFullName)
        val phoneNumberTextView: TextView = view.findViewById(R.id.textViewPhoneNumber)
        val syncStatusIcon: ImageView = view.findViewById(R.id.syncStatusIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WorkerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_worker, parent, false)
        return WorkerViewHolder(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: WorkerViewHolder, position: Int) {
        val worker = getItem(position)

        // Format the display to include sequence number
        holder.fullNameTextView.text = "[${worker.sequenceNumber}] ${worker.fullName}"

        // Format phone number or show placeholder
        holder.phoneNumberTextView.text = if (worker.phoneNumber.isNotEmpty()) {
            worker.phoneNumber
        } else {
            "Номер не вказано"
        }

        // Remove the separate ID text view
        holder.idTextView.visibility = View.GONE

        // Show sync status indicator for unsynced workers
        if (!worker.isSynced) {
            holder.syncStatusIcon.visibility = View.VISIBLE
            // Use a subtle background color for unsynced workers
            holder.itemView.setBackgroundColor(Color.parseColor("#15FFC107")) // Very light amber
        } else {
            holder.syncStatusIcon.visibility = View.GONE
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }

        // If worker has been deleted but not synced, add strikethrough and indicator
        val paintFlags = if (worker.isDeleted) Paint.STRIKE_THRU_TEXT_FLAG else 0
        holder.fullNameTextView.paintFlags = paintFlags
        holder.phoneNumberTextView.paintFlags = paintFlags

        // Enable long-click behavior for options menu
        holder.itemView.setOnLongClickListener {
            onItemLongClick(worker)
            true
        }
    }

    class WorkerDiffCallback : DiffUtil.ItemCallback<Worker>() {
        override fun areItemsTheSame(oldItem: Worker, newItem: Worker): Boolean {
            return oldItem._id == newItem._id
        }

        @SuppressLint("DiffUtilEquals")
        override fun areContentsTheSame(oldItem: Worker, newItem: Worker): Boolean {
            return oldItem.fullName == newItem.fullName &&
                    oldItem.phoneNumber == newItem.phoneNumber &&
                    oldItem.sequenceNumber == newItem.sequenceNumber &&
                    oldItem.isSynced == newItem.isSynced &&
                    oldItem.isDeleted == newItem.isDeleted
        }
    }
}



