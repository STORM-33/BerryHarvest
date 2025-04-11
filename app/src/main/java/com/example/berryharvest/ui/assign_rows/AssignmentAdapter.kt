package com.example.berryharvest.ui.assign_rows

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.util.Log
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.R
import com.example.berryharvest.data.model.Assignment
import com.example.berryharvest.data.model.Worker

class AssignmentAdapter(
    private val onMoveWorkerClick: (Assignment) -> Unit,
    private val onRemoveRowClick: (Int) -> Unit
) : ListAdapter<AssignmentGroup, AssignmentAdapter.AssignmentViewHolder>(AssignmentDiffCallback()) {

    // Map to store worker details keyed by worker ID
    private var workerDetailsMap: Map<String, Worker> = mapOf()

    inner class AssignmentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rowNumberTextView: TextView = view.findViewById(R.id.rowNumberTextView)
        val workerRecyclerView: RecyclerView = view.findViewById(R.id.workerRecyclerView)
        val removeRowButton: Button = view.findViewById(R.id.removeRowButton)
        val container: View = view.findViewById(R.id.assignmentContainer)

        fun showDeleteRowDialog(rowNumber: Int) {
            AlertDialog.Builder(itemView.context)
                .setTitle("Видалити ряд")
                .setMessage("Ви впевнені, що бажаєте видалити ряд $rowNumber?")
                .setPositiveButton("Так") { _, _ -> onRemoveRowClick(rowNumber) }
                .setNegativeButton("Ні", null)
                .show()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssignmentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_assignment, parent, false)
        return AssignmentViewHolder(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: AssignmentViewHolder, position: Int) {
        val assignmentGroup = getItem(position)
        val rowNumber = assignmentGroup.rowNumber
        val assignments = assignmentGroup.assignments

        // Remove sync indicator from text and use more subtle approach
        holder.rowNumberTextView.text = "Ряд $rowNumber"

        // Set up inner RecyclerView with the worker adapter
        val workerAdapter = RefactoredWorkerInRowAdapter(
            assignments,
            workerDetailsMap,
            onMoveWorkerClick
        )
        holder.workerRecyclerView.layoutManager = LinearLayoutManager(holder.itemView.context)
        holder.workerRecyclerView.adapter = workerAdapter

        // Set background color based on sync status - make it more subtle
        val hasUnsyncedAssignments = assignments.any { !it.isSynced }
        if (hasUnsyncedAssignments) {
            holder.container.setBackgroundColor(Color.parseColor("#15FFC107")) // Very light amber with 10% opacity
            // Add a small sync icon
            holder.rowNumberTextView.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_sync_small, 0)
            Log.d("AssignmentAdapter", "Row $rowNumber is UNSYNCED")
        } else {
            holder.container.setBackgroundColor(Color.TRANSPARENT)
            holder.rowNumberTextView.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            Log.d("AssignmentAdapter", "Row $rowNumber is SYNCED")
        }

        // Long click to delete row
        holder.rowNumberTextView.setOnLongClickListener {
            holder.showDeleteRowDialog(rowNumber)
            true
        }

        // Hide the remove row button
        holder.removeRowButton.visibility = View.GONE
    }

    /**
     * Update the worker details map and refresh the adapter
     */
    @SuppressLint("NotifyDataSetChanged")
    fun updateWorkerDetails(workerMap: Map<String, Worker>) {
        this.workerDetailsMap = workerMap
        notifyDataSetChanged()
    }

    class AssignmentDiffCallback : DiffUtil.ItemCallback<AssignmentGroup>() {
        override fun areItemsTheSame(oldItem: AssignmentGroup, newItem: AssignmentGroup): Boolean {
            return oldItem.rowNumber == newItem.rowNumber
        }

        @SuppressLint("DiffUtilEquals")
        override fun areContentsTheSame(oldItem: AssignmentGroup, newItem: AssignmentGroup): Boolean {
            // Check if list size is different
            if (oldItem.assignments.size != newItem.assignments.size) {
                return false
            }

            // Check sync status
            val oldSyncStatus = oldItem.assignments.all { it.isSynced }
            val newSyncStatus = newItem.assignments.all { it.isSynced }

            // If sync status changed, content is different
            if (oldSyncStatus != newSyncStatus) {
                return false
            }

            // Check assignment IDs (for cases when assignments changed)
            val oldIds = oldItem.assignments.map { it._id }.toSet()
            val newIds = newItem.assignments.map { it._id }.toSet()

            return oldIds == newIds
        }
    }
}

/**
 * Refactored worker adapter that doesn't need direct Realm access
 */
class RefactoredWorkerInRowAdapter(
    private val assignments: List<Assignment>,
    private val workerDetailsMap: Map<String, Worker>,
    private val onMoveWorkerClick: (Assignment) -> Unit
) : RecyclerView.Adapter<RefactoredWorkerInRowAdapter.WorkerViewHolder>() {

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

        // Get worker details from the map rather than from Realm directly
        val worker = workerDetailsMap[assignment.workerId]

        val workerInfo = if (worker != null) {
            "${worker.fullName} (${worker.sequenceNumber})"
        } else {
            "Невідомий працівник"
        }

        holder.workerTextView.text = workerInfo

        holder.workerTextView.setOnLongClickListener {
            onMoveWorkerClick(assignment)
            true
        }
    }

    override fun getItemCount(): Int {
        return assignments.size
    }
}


