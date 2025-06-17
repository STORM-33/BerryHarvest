package com.example.berryharvest.ui.assign_rows

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.R
import com.example.berryharvest.data.model.Assignment
import com.example.berryharvest.data.model.Worker

class WorkerInRowAdapter(
    private val assignments: List<Assignment>,
    private val workerDetailsMap: Map<String, Worker>,
    private val onMoveWorkerClick: (Assignment) -> Unit
) : RecyclerView.Adapter<WorkerInRowAdapter.WorkerViewHolder>() {

    inner class WorkerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val workerTextView: TextView = view.findViewById(R.id.workerTextView)
        val syncStatusIcon: ImageView = view.findViewById(R.id.syncStatusIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WorkerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_worker_in_row, parent, false)
        return WorkerViewHolder(view)
    }

    override fun onBindViewHolder(holder: WorkerViewHolder, position: Int) {
        val assignment = assignments[position]

        // Get worker details from the map
        val worker = workerDetailsMap[assignment.workerId]

        val workerInfo = if (worker != null) {
            "[${worker.sequenceNumber}] ${worker.fullName}"
        } else {
            "Невідомий працівник"
        }

        holder.workerTextView.text = workerInfo

        // Handle sync status
        if (!assignment.isSynced) {
            // Show sync icon
            holder.syncStatusIcon.visibility = View.VISIBLE

            // Add subtle background color to indicate unsynced state
            holder.itemView.setBackgroundColor(Color.parseColor("#15FFC107")) // Very light amber
        } else {
            // Hide sync icon
            holder.syncStatusIcon.visibility = View.GONE

            // Clear background color
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }

        // Set long-click listener for move/delete options
        holder.itemView.setOnLongClickListener {
            onMoveWorkerClick(assignment)
            true
        }

        // Optional: Add click listener for quick info or actions
        holder.itemView.setOnClickListener {
            // You can add quick actions here, like showing worker details
            // For now, just provide visual feedback
        }
    }

    override fun getItemCount(): Int {
        return assignments.size
    }
}