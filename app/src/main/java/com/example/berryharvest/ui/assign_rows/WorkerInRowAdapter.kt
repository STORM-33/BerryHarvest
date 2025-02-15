package com.example.berryharvest.ui.assign_rows

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.R
import com.example.berryharvest.ui.add_worker.Worker
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query

class WorkerInRowAdapter(
    private val assignments: List<Assignment>,
    private val onMoveWorkerClick: (Assignment) -> Unit,
    private val realm: Realm
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

        val worker = realm.query<Worker>("_id == $0", assignment.workerId).first().find()
        val workerInfo = if (worker != null) "${worker.fullName} (${worker.sequenceNumber})" else "Невідомий працівник"

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


