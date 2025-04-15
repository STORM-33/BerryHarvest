package com.example.berryharvest.ui.assign_rows

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
            "${worker.fullName} [${worker.sequenceNumber}]"
        } else {
            "Невідомий працівник"
        }

        holder.workerTextView.text = workerInfo

        // Set visual indication for sync status
        if (!assignment.isSynced) {
            // Use a subtle background color
            holder.itemView.setBackgroundColor(Color.parseColor("#15FFC107")) // Very light amber
            // Show sync icon
            holder.workerTextView.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_sync_small, 0)
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.workerTextView.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        }

        // Set long-click listener for move/delete options
        holder.workerTextView.setOnLongClickListener {
            onMoveWorkerClick(assignment)
            true
        }
    }

    override fun getItemCount(): Int {
        return assignments.size
    }
}


