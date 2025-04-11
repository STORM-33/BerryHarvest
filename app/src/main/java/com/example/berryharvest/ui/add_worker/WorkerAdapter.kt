package com.example.berryharvest.ui.add_worker

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WorkerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_worker, parent, false)
        return WorkerViewHolder(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: WorkerViewHolder, position: Int) {
        val worker = getItem(position)

        // Format the display to include sequence number in same row
        holder.fullNameTextView.text = "${worker.fullName} [${worker.sequenceNumber}]"
        holder.phoneNumberTextView.text = worker.phoneNumber

        // Remove the separate ID text view since we're including the sequence number in the name
        holder.idTextView.visibility = View.GONE

        // More subtle background for unsynced workers
        when {
            worker.isDeleted -> holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            !worker.isSynced -> {
                // Use a more subtle background color
                holder.itemView.setBackgroundColor(Color.parseColor("#15FFC107")) // Very light amber with 10% opacity

                // Add a small sync indicator
                holder.fullNameTextView.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_sync_small, 0)
            }
            else -> {
                holder.itemView.setBackgroundColor(Color.TRANSPARENT)
                holder.fullNameTextView.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            }
        }

        // If worker has been deleted but not synced, add strikethrough and indicator
        val paintFlags = if (worker.isDeleted) Paint.STRIKE_THRU_TEXT_FLAG else 0
        holder.fullNameTextView.paintFlags = paintFlags
        holder.phoneNumberTextView.paintFlags = paintFlags

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
            return oldItem == newItem && oldItem.isSynced == newItem.isSynced && oldItem.isDeleted == newItem.isDeleted
        }
    }
}



