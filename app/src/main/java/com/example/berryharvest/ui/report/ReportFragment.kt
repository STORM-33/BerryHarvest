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
import com.example.berryharvest.R
import com.example.berryharvest.data.repository.ConnectionState
import kotlinx.coroutines.launch
import java.util.Locale

class ReportFragment : Fragment() {
    private val TAG = "ReportFragment"
    private lateinit var viewModel: ReportViewModel

    // UI components
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var connectionStatusTextView: TextView

    // Summary stats views
    private lateinit var totalPunnetsTextView: TextView
    private lateinit var totalEarningsTextView: TextView
    private lateinit var workerCountTextView: TextView
    private lateinit var todayPunnetsTextView: TextView
    private lateinit var todayEarningsTextView: TextView
    private lateinit var avgPunnetsPerWorkerTextView: TextView

    // Top workers list
    private lateinit var topWorkersRecyclerView: RecyclerView
    private lateinit var topWorkersAdapter: TopWorkersAdapter

    // Daily production list
    private lateinit var dailyProductionRecyclerView: RecyclerView
    private lateinit var dailyProductionAdapter: DailyProductionAdapter

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

        // Initialize top workers recycler view
        topWorkersRecyclerView = root.findViewById(R.id.topWorkersRecyclerView)
        topWorkersAdapter = TopWorkersAdapter()
        topWorkersRecyclerView.adapter = topWorkersAdapter
        topWorkersRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Initialize daily production recycler view
        dailyProductionRecyclerView = root.findViewById(R.id.dailyProductionRecyclerView)
        dailyProductionAdapter = DailyProductionAdapter()
        dailyProductionRecyclerView.adapter = dailyProductionAdapter
        dailyProductionRecyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)

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
        lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                loadingProgressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                if (!isLoading) {
                    swipeRefreshLayout.isRefreshing = false
                }
            }
        }

        // Observe error messages
        lifecycleScope.launch {
            viewModel.errorMessage.collect { errorMessage ->
                errorMessage?.let {
                    Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                    viewModel.clearError()
                }
            }
        }

        // Observe connection state
        lifecycleScope.launch {
            viewModel.connectionState.collect { state ->
                updateConnectionState(state)
            }
        }

        // Observe summary stats
        lifecycleScope.launch {
            viewModel.summaryStats.collect { stats ->
                updateSummaryStats(stats)
            }
        }

        // Observe top workers
        lifecycleScope.launch {
            viewModel.topWorkers.collect { workers ->
                updateTopWorkers(workers)
            }
        }

        // Observe daily production
        lifecycleScope.launch {
            viewModel.dailyProduction.collect { production ->
                updateDailyProduction(production)
            }
        }
    }

    private fun updateConnectionState(state: ConnectionState) {
        when (state) {
            is ConnectionState.Connected -> {
                connectionStatusTextView.text = "Підключено"
                connectionStatusTextView.setBackgroundResource(R.drawable.rounded_status_background)
                connectionStatusTextView.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_sync_small, 0, 0, 0)
            }
            is ConnectionState.Disconnected -> {
                connectionStatusTextView.text = "Офлайн режим"
                connectionStatusTextView.setBackgroundResource(R.drawable.rounded_status_background)
                connectionStatusTextView.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_offline, 0, 0, 0)
            }
            is ConnectionState.Error -> {
                connectionStatusTextView.text = "Помилка з'єднання"
                connectionStatusTextView.setBackgroundResource(R.drawable.rounded_status_background)
                connectionStatusTextView.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_offline, 0, 0, 0)
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

    private fun updateTopWorkers(workers: List<WorkerStats>) {
        topWorkersAdapter.submitList(workers)
    }

    private fun updateDailyProduction(production: List<DailyProduction>) {
        dailyProductionAdapter.submitList(production)
    }

    private fun refreshData() {
        viewModel.loadReportData()
    }
}

// Adapters for RecyclerViews

class TopWorkersAdapter :
    androidx.recyclerview.widget.ListAdapter<WorkerStats, TopWorkersAdapter.ViewHolder>(
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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_top_worker, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
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