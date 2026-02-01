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
 * Full-screen dialog showing daily collections data
 */
class DailyCollectionsDialog(
    context: Context,
    private val dailyData: List<DailyCollectionData>
) : AppCompatDialog(context, R.style.Theme_BerryHarvest) {

    private lateinit var titleTextView: TextView
    private lateinit var totalDaysTextView: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var closeButton: MaterialButton
    private lateinit var adapter: DailyCollectionsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make dialog full screen
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        setContentView(R.layout.dialog_daily_collections)

        initializeViews()
        setupRecyclerView()
        populateData()
    }

    private fun initializeViews() {
        titleTextView = findViewById(R.id.titleTextView)!!
        totalDaysTextView = findViewById(R.id.totalDaysTextView)!!
        recyclerView = findViewById(R.id.dailyCollectionsRecyclerView)!!
        closeButton = findViewById(R.id.closeButton)!!

        closeButton.setOnClickListener {
            dismiss()
        }
    }

    private fun setupRecyclerView() {
        adapter = DailyCollectionsAdapter()
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(context)
    }

    private fun populateData() {
        // Set title and total count
        titleTextView.text = "📅 Щоденні збори"
        totalDaysTextView.text = "Всього днів: ${dailyData.size}"

        // Set data to adapter
        adapter.submitList(dailyData)
    }
}

/**
 * Adapter for daily collections display
 */
class DailyCollectionsAdapter :
    androidx.recyclerview.widget.ListAdapter<DailyCollectionData, DailyCollectionsAdapter.ViewHolder>(
        object : androidx.recyclerview.widget.DiffUtil.ItemCallback<DailyCollectionData>() {
            override fun areItemsTheSame(oldItem: DailyCollectionData, newItem: DailyCollectionData): Boolean {
                return oldItem.dateKey == newItem.dateKey
            }

            override fun areContentsTheSame(oldItem: DailyCollectionData, newItem: DailyCollectionData): Boolean {
                return oldItem == newItem
            }
        }
    ) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dateTextView: TextView = view.findViewById(R.id.dateTextView)
        val workerCountTextView: TextView = view.findViewById(R.id.workerCountTextView)
        val totalPunnetsTextView: TextView = view.findViewById(R.id.totalPunnetsTextView)
        val totalTraysTextView: TextView = view.findViewById(R.id.totalTraysTextView)
        val avgPunnetsTextView: TextView = view.findViewById(R.id.avgPunnetsTextView)
        val totalSumTextView: TextView = view.findViewById(R.id.totalSumTextView)
        val workerDetailsRecyclerView: RecyclerView = view.findViewById(R.id.workerDetailsRecyclerView)

        init {
            // Setup nested RecyclerView for worker details
            workerDetailsRecyclerView.layoutManager = LinearLayoutManager(view.context)
            workerDetailsRecyclerView.isNestedScrollingEnabled = false
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_daily_collection, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)

        // Basic day info
        holder.dateTextView.text = item.date
        holder.workerCountTextView.text = "${item.workerCount}"
        holder.totalPunnetsTextView.text = "${item.totalPunnets}"
        holder.totalTraysTextView.text = "${item.totalTrays}"
        holder.avgPunnetsTextView.text = String.format(
            Locale.getDefault(),
            "%.1f",
            item.avgPunnetsPerWorker
        )
        holder.totalSumTextView.text = String.format(
            Locale.getDefault(),
            "%.2f₴",
            item.totalSum
        )

        // Setup worker details adapter
        val workerAdapter = DailyWorkerDetailsAdapter()
        holder.workerDetailsRecyclerView.adapter = workerAdapter
        workerAdapter.submitList(item.workerDetails)
    }
}

/**
 * Nested adapter for worker details within each day
 */
class DailyWorkerDetailsAdapter :
    androidx.recyclerview.widget.ListAdapter<DailyWorkerData, DailyWorkerDetailsAdapter.ViewHolder>(
        object : androidx.recyclerview.widget.DiffUtil.ItemCallback<DailyWorkerData>() {
            override fun areItemsTheSame(oldItem: DailyWorkerData, newItem: DailyWorkerData): Boolean {
                return oldItem.workerId == newItem.workerId
            }

            override fun areContentsTheSame(oldItem: DailyWorkerData, newItem: DailyWorkerData): Boolean {
                return oldItem == newItem
            }
        }
    ) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val workerNameTextView: TextView = view.findViewById(R.id.workerNameTextView)
        val workerPunnetsTextView: TextView = view.findViewById(R.id.workerPunnetsTextView)
        val workerEarningsTextView: TextView = view.findViewById(R.id.workerEarningsTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_daily_worker_detail, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)

        holder.workerNameTextView.text = item.workerName
        holder.workerPunnetsTextView.text = "${item.punnets}"
        holder.workerEarningsTextView.text = String.format(
            Locale.getDefault(),
            "%.2f₴",
            item.earnings
        )
    }
}