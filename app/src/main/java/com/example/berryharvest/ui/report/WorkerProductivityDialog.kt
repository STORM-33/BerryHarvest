package com.example.berryharvest.ui.report

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.R
import com.google.android.material.button.MaterialButton
import java.util.*

/**
 * Full-screen dialog showing detailed worker productivity statistics
 */
class WorkerProductivityDialog(
    context: Context,
    private val workerStats: List<WorkerStats>
) : AppCompatDialog(context, R.style.Theme_BerryHarvest) {

    private lateinit var titleTextView: TextView
    private lateinit var totalWorkersTextView: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var closeButton: MaterialButton
    private lateinit var adapter: DetailedWorkerStatsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make dialog full screen
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        setContentView(R.layout.dialog_worker_productivity)

        initializeViews()
        setupRecyclerView()
        populateData()
    }

    private fun initializeViews() {
        titleTextView = findViewById(R.id.titleTextView)!!
        totalWorkersTextView = findViewById(R.id.totalWorkersTextView)!!
        recyclerView = findViewById(R.id.workerProductivityRecyclerView)!!
        closeButton = findViewById(R.id.closeButton)!!

        closeButton.setOnClickListener {
            dismiss()
        }
    }

    private fun setupRecyclerView() {
        adapter = DetailedWorkerStatsAdapter()
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(context)
    }

    private fun populateData() {
        // Set title and total count
        titleTextView.text = "👥 Продуктивність працівників"
        totalWorkersTextView.text = "Всього працівників: ${workerStats.size}"

        // Set data to adapter
        adapter.submitList(workerStats)
    }
}

/**
 * Adapter for detailed worker productivity display
 */
class DetailedWorkerStatsAdapter :
    androidx.recyclerview.widget.ListAdapter<WorkerStats, DetailedWorkerStatsAdapter.ViewHolder>(
        object : androidx.recyclerview.widget.DiffUtil.ItemCallback<WorkerStats>() {
            override fun areItemsTheSame(oldItem: WorkerStats, newItem: WorkerStats): Boolean {
                return oldItem.workerId == newItem.workerId
            }

            override fun areContentsTheSame(oldItem: WorkerStats, newItem: WorkerStats): Boolean {
                return oldItem == newItem
            }
        }
    ) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rankTextView: TextView = view.findViewById(R.id.rankTextView)
        val nameTextView: TextView = view.findViewById(R.id.workerNameTextView)
        val sequenceTextView: TextView = view.findViewById(R.id.sequenceTextView)
        val totalPunnetsTextView: TextView = view.findViewById(R.id.totalPunnetsTextView)
        val avgPunnetsTextView: TextView = view.findViewById(R.id.avgPunnetsTextView)
        val totalEarningsTextView: TextView = view.findViewById(R.id.totalEarningsTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_detailed_worker_stats, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val rank = position + 1

        // Set rank with medals for top 3
        holder.rankTextView.text = when (rank) {
            1 -> "🥇"
            2 -> "🥈"
            3 -> "🥉"
            else -> "$rank"
        }

        // Worker details
        holder.nameTextView.text = item.workerName
        holder.sequenceTextView.text = "№${item.sequenceNumber}"

        // Stats
        holder.totalPunnetsTextView.text = "${item.totalPunnets}"
        holder.avgPunnetsTextView.text = String.format(
            Locale.getDefault(),
            "%.1f/д",
            item.avgPunnetsPerDay
        )
        holder.totalEarningsTextView.text = String.format(
            Locale.getDefault(),
            "%.2f₴",
            item.totalEarnings
        )
    }
}