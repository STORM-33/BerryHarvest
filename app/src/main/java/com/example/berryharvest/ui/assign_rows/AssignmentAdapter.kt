package com.example.berryharvest.ui.assign_rows

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
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
        val rowNumberSection: LinearLayout = view.findViewById(R.id.rowNumberSection)
        val syncStatusIcon: ImageView = view.findViewById(R.id.syncStatusIcon)
        val workerRecyclerView: RecyclerView = view.findViewById(R.id.workerRecyclerView)
        val emptyWorkersTextView: TextView = view.findViewById(R.id.emptyWorkersTextView)
        val removeRowButton: Button = view.findViewById(R.id.removeRowButton)
        val container: View = view.findViewById(R.id.assignmentContainer)

        // Removed duplicate dialog - let fragment handle confirmation
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

        Log.d("AssignmentAdapter", "Binding row $rowNumber with ${assignments.size} assignments")

        // Set row number text
        holder.rowNumberTextView.text = rowNumber.toString()

        // Handle empty state
        if (assignments.isEmpty()) {
            holder.workerRecyclerView.visibility = View.GONE
            holder.emptyWorkersTextView.visibility = View.VISIBLE
        } else {
            holder.workerRecyclerView.visibility = View.VISIBLE
            holder.emptyWorkersTextView.visibility = View.GONE

            // Set up inner RecyclerView with the worker adapter
            val workerAdapter = WorkerInRowAdapter(
                assignments,
                workerDetailsMap,
                onMoveWorkerClick
            )
            holder.workerRecyclerView.layoutManager = LinearLayoutManager(holder.itemView.context)
            holder.workerRecyclerView.adapter = workerAdapter
        }

        // Handle sync status
        val hasUnsyncedAssignments = assignments.any { !it.isSynced }
        if (hasUnsyncedAssignments) {
            // Show sync status icon
            holder.syncStatusIcon.visibility = View.VISIBLE

            // Add subtle background color to indicate unsynced state
            holder.container.setBackgroundColor(Color.parseColor("#15FFC107")) // Very light amber

            Log.d("AssignmentAdapter", "Row $rowNumber is UNSYNCED")
        } else {
            // Hide sync status icon
            holder.syncStatusIcon.visibility = View.GONE

            // Clear background color
            holder.container.setBackgroundColor(Color.TRANSPARENT)

            Log.d("AssignmentAdapter", "Row $rowNumber is SYNCED")
        }

        // Set up row number section click handlers
        holder.rowNumberSection.setOnLongClickListener {
            onRemoveRowClick(rowNumber)
            true
        }

        // Add visual feedback for row number section
        holder.rowNumberSection.setOnClickListener {
            // Optional: You can add row-specific actions here
            // For now, just provide haptic feedback or show a toast with row info
        }

        // Hide the remove row button (now handled by long-click on row number)
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

    override fun onCurrentListChanged(previousList: List<AssignmentGroup>, currentList: List<AssignmentGroup>) {
        super.onCurrentListChanged(previousList, currentList)
        Log.d("AssignmentAdapter", "List changed from ${previousList.size} to ${currentList.size} items")
    }

    override fun getItemCount(): Int {
        val count = super.getItemCount()
        Log.d("AssignmentAdapter", "getItemCount() returning $count")
        return count
    }
}