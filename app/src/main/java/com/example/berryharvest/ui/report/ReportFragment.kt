package com.example.berryharvest.ui.report

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.berryharvest.BaseFragment
import com.example.berryharvest.R
import com.example.berryharvest.data.repository.ConnectionState
import kotlinx.coroutines.launch
import java.util.Locale

class ReportFragment : BaseFragment() {
    private val TAG = "ReportFragment"
    private lateinit var viewModel: ReportViewModel

    // UI components
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var loadingProgressBar: ProgressBar

    // Summary stats views
    private lateinit var totalPunnetsTextView: TextView
    private lateinit var totalEarningsTextView: TextView
    private lateinit var workerCountTextView: TextView
    private lateinit var todayPunnetsTextView: TextView
    private lateinit var todayEarningsTextView: TextView
    private lateinit var avgPunnetsPerWorkerTextView: TextView

    // Daily production list
    private lateinit var dailyProductionRecyclerView: RecyclerView
    private lateinit var dailyProductionAdapter: DailyProductionAdapter

    // Worker stats
    private lateinit var workerStatsRecyclerView: RecyclerView
    private lateinit var workerStatsAdapter: WorkerStatsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_report, container, false)
        viewModel = ViewModelProvider(this).get(ReportViewModel::class.java)

        // Initialize UI components
        swipeRefreshLayout = root.findViewById(R.id.swipeRefreshLayout)
        loadingProgressBar = root.findViewById(R.id.loadingProgressBar)

        // Initialize summary views
        totalPunnetsTextView = root.findViewById(R.id.totalPunnetsTextView)
        totalEarningsTextView = root.findViewById(R.id.totalEarningsTextView)
        workerCountTextView = root.findViewById(R.id.workerCountTextView)
        todayPunnetsTextView = root.findViewById(R.id.todayPunnetsTextView)
        todayEarningsTextView = root.findViewById(R.id.todayEarningsTextView)
        avgPunnetsPerWorkerTextView = root.findViewById(R.id.avgPunnetsPerWorkerTextView)

        // Initialize daily production recycler view
        dailyProductionRecyclerView = root.findViewById(R.id.dailyProductionRecyclerView)
        dailyProductionAdapter = DailyProductionAdapter()
        dailyProductionRecyclerView.adapter = dailyProductionAdapter
        dailyProductionRecyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)

        // Initialize worker stats recycler view
        workerStatsRecyclerView = root.findViewById(R.id.workerStatsRecyclerView)
        workerStatsAdapter = WorkerStatsAdapter()
        workerStatsRecyclerView.adapter = workerStatsAdapter
        workerStatsRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        setupUI()
        setupObservers()

        return root
    }

    private fun setupUI() {
        // Set up swipe refresh
        swipeRefreshLayout.setOnRefreshListener {
            refreshData()
        }
    }

    private fun setupObservers() {
        // Observe loading state
        launchWhenStarted("loading-state") {
            viewModel.isLoading.collect { isLoading ->
                loadingProgressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                if (!isLoading) {
                    swipeRefreshLayout.isRefreshing = false
                }
            }
        }

        // Observe error messages
        launchWhenStarted("error-flow") {
            viewModel.errorMessage.collect { errorMessage ->
                errorMessage?.let {
                    Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                    viewModel.clearError()
                }
            }
        }

        // Observe summary stats
        launchWhenStarted("summary-stats") {
            viewModel.summaryStats.collect { stats ->
                updateSummaryStats(stats)
            }
        }

        // Observe worker stats
        launchWhenStarted("worker-stats") {
            viewModel.topWorkers.collect { workers ->
                updateWorkerStats(workers)
            }
        }

        // Observe daily production
        launchWhenStarted("daily-production") {
            viewModel.dailyProduction.collect { production ->
                updateDailyProduction(production)
            }
        }
    }

    private fun updateSummaryStats(stats: SummaryStats?) {
        if (stats == null) {
            // Hide or show placeholder if no data
            return
        }

        // Update all summary text views with the data
        totalPunnetsTextView.text = "${stats.totalPunnets}"
        totalEarningsTextView.text = String.format(Locale.getDefault(), "%.2f₴", stats.totalEarnings)
        workerCountTextView.text = "${stats.workerCount}"
        todayPunnetsTextView.text = "${stats.todayPunnets}"
        todayEarningsTextView.text = String.format(Locale.getDefault(), "%.2f₴", stats.todayEarnings)
        avgPunnetsPerWorkerTextView.text = String.format(Locale.getDefault(), "%.1f", stats.avgPunnetsPerWorker)
    }

    private fun updateWorkerStats(workers: List<WorkerStats>) {
        workerStatsAdapter.submitList(workers)
    }

    private fun updateDailyProduction(production: List<DailyProduction>) {
        dailyProductionAdapter.submitList(production)
    }

    private fun refreshData() {
        viewModel.loadReportData()
    }
}

// Worker stats adapter - simplified version without "top workers" concept
class WorkerStatsAdapter :
    androidx.recyclerview.widget.ListAdapter<WorkerStats, WorkerStatsAdapter.ViewHolder>(
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
        val nameTextView: TextView = view.findViewById(R.id.workerNameTextView)
        val punnetsTextView: TextView = view.findViewById(R.id.workerPunnetsTextView)
        val earningsTextView: TextView = view.findViewById(R.id.workerEarningsTextView)
        val rankTextView: TextView = view.findViewById(R.id.rankTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_worker_stats, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.rankTextView.text = "#${position + 1}"
        holder.nameTextView.text = "${item.workerName} [${item.sequenceNumber}]"
        holder.punnetsTextView.text = "${item.totalPunnets}"
        holder.earningsTextView.text = String.format(Locale.getDefault(), "%.2f₴", item.totalEarnings)
    }
}

class DailyProductionAdapter :
    androidx.recyclerview.widget.ListAdapter<DailyProduction, DailyProductionAdapter.ViewHolder>(
        object : androidx.recyclerview.widget.DiffUtil.ItemCallback<DailyProduction>() {
            override fun areItemsTheSame(oldItem: DailyProduction, newItem: DailyProduction): Boolean {
                return oldItem.fullDate == newItem.fullDate
            }

            override fun areContentsTheSame(oldItem: DailyProduction, newItem: DailyProduction): Boolean {
                return oldItem == newItem
            }
        }
    ) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dateTextView: TextView = view.findViewById(R.id.dateTextView)
        val punnetsTextView: TextView = view.findViewById(R.id.punnetsTextView)
        val earningsTextView: TextView = view.findViewById(R.id.earningsTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_daily_production, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.dateTextView.text = item.date
        holder.punnetsTextView.text = "${item.totalPunnets}"
        holder.earningsTextView.text = String.format(Locale.getDefault(), "%.2f₴", item.totalEarnings)
    }
}