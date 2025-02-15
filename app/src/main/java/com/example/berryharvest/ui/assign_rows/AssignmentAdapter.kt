package com.example.berryharvest.ui.assign_rows

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.R
import io.realm.kotlin.Realm

class AssignmentAdapter(
    private val realm: Realm,
    private val onMoveWorkerClick: (Assignment) -> Unit,
    private val onRemoveRowClick: (Int) -> Unit
) : ListAdapter<AssignmentGroup, AssignmentAdapter.AssignmentViewHolder>(AssignmentDiffCallback()) {

    inner class AssignmentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rowNumberTextView: TextView = view.findViewById(R.id.rowNumberTextView)
        val workerRecyclerView: RecyclerView = view.findViewById(R.id.workerRecyclerView)
        val removeRowButton: Button = view.findViewById(R.id.removeRowButton)
        val container: View = view.findViewById(R.id.assignmentContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssignmentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_assignment, parent, false)
        return AssignmentViewHolder(view)
    }

    override fun onBindViewHolder(holder: AssignmentViewHolder, position: Int) {
        val assignmentGroup = getItem(position)
        val rowNumber = assignmentGroup.rowNumber
        val assignments = assignmentGroup.assignments

        holder.rowNumberTextView.text = "Ряд $rowNumber"

        // Set up inner RecyclerView
        val workerAdapter = WorkerInRowAdapter(
            assignments,
            onMoveWorkerClick, // Передаем функцию для обработки перемещения/удаления
            realm
        )
        holder.workerRecyclerView.layoutManager = LinearLayoutManager(holder.itemView.context)
        holder.workerRecyclerView.adapter = workerAdapter

        // Highlight if any assignment is not synced
        val isSynced = assignments.all { it.isSynced }
        if (!isSynced) {
            holder.container.setBackgroundColor(Color.RED)
        } else {
            holder.container.setBackgroundColor(Color.TRANSPARENT)
        }

        holder.removeRowButton.setOnClickListener {
            onRemoveRowClick(rowNumber)
        }
    }

    class AssignmentDiffCallback : DiffUtil.ItemCallback<AssignmentGroup>() {
        override fun areItemsTheSame(oldItem: AssignmentGroup, newItem: AssignmentGroup): Boolean {
            return oldItem.rowNumber == newItem.rowNumber
        }

        override fun areContentsTheSame(oldItem: AssignmentGroup, newItem: AssignmentGroup): Boolean {
            return oldItem == newItem
        }
    }
}


