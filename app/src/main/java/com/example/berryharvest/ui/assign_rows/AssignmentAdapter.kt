package com.example.berryharvest.ui.assign_rows

import android.annotation.SuppressLint
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
import io.realm.kotlin.Realm

class AssignmentAdapter(
    private val onMoveWorkerClick: (Assignment) -> Unit,
    private val onRemoveRowClick: (Int) -> Unit,
    private val realmProvider: () -> Realm?
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

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: AssignmentViewHolder, position: Int) {
        val assignmentGroup = getItem(position)
        val rowNumber = assignmentGroup.rowNumber
        val assignments = assignmentGroup.assignments

        holder.rowNumberTextView.text = "Ряд $rowNumber"

        // Настраиваем внутренний RecyclerView
        val workerAdapter = WorkerInRowAdapter(
            assignments,
            onMoveWorkerClick,
            realmProvider
        )
        holder.workerRecyclerView.layoutManager = LinearLayoutManager(holder.itemView.context)
        holder.workerRecyclerView.adapter = workerAdapter

        // Проверяем статус синхронизации - есть ли неотправленные назначения
        val hasUnsyncedAssignments = assignments.any { !it.isSynced }

        // Устанавливаем цвет фона в зависимости от статуса синхронизации
        if (hasUnsyncedAssignments) {
            holder.container.setBackgroundColor(Color.parseColor("#FFCDD2")) // Светло-красный
            Log.d("AssignmentAdapter", "Row $rowNumber is UNSYNCED")
        } else {
            holder.container.setBackgroundColor(Color.TRANSPARENT)
            Log.d("AssignmentAdapter", "Row $rowNumber is SYNCED")
        }

        holder.removeRowButton.setOnClickListener {
            onRemoveRowClick(rowNumber)
        }
    }

    class AssignmentDiffCallback : DiffUtil.ItemCallback<AssignmentGroup>() {
        override fun areItemsTheSame(oldItem: AssignmentGroup, newItem: AssignmentGroup): Boolean {
            return oldItem.rowNumber == newItem.rowNumber
        }

        @SuppressLint("DiffUtilEquals")
        override fun areContentsTheSame(oldItem: AssignmentGroup, newItem: AssignmentGroup): Boolean {
            // Проверяем размер списка назначений
            if (oldItem.assignments.size != newItem.assignments.size) {
                return false
            }

            // Проверяем статус синхронизации
            val oldSyncStatus = oldItem.assignments.all { it.isSynced }
            val newSyncStatus = newItem.assignments.all { it.isSynced }

            // Если статус синхронизации изменился, содержимое отличается
            if (oldSyncStatus != newSyncStatus) {
                return false
            }

            // Проверяем ID назначений (для случаев, когда назначения изменились)
            val oldIds = oldItem.assignments.map { it._id }.toSet()
            val newIds = newItem.assignments.map { it._id }.toSet()

            return oldIds == newIds
        }
    }
}


