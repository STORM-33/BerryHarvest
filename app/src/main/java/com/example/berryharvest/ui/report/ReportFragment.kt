package com.example.berryharvest.ui.report

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.berryharvest.BaseFragment
import com.example.berryharvest.R
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.util.*

class ReportFragment : BaseFragment() {
    private val TAG = "ReportFragment"
    private lateinit var viewModel: ReportViewModel

    // UI components
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var loadingProgressBar: ProgressBar

    // Summary stats views
    private lateinit var totalTraysTextView: TextView
    private lateinit var totalPaidTextView: TextView
    private lateinit var avgWorkersPerDayTextView: TextView
    private lateinit var todayTraysTextView: TextView
    private lateinit var todayToPayTextView: TextView
    private lateinit var activeWorkersTodayTextView: TextView

    // New buttons for detailed views
    private lateinit var workerProductivityButton: MaterialButton
    private lateinit var dailyCollectionsButton: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_report, container, false)
        viewModel = ViewModelProvider(this).get(ReportViewModel::class.java)

        // Initialize existing UI components
        swipeRefreshLayout = root.findViewById(R.id.swipeRefreshLayout)
        loadingProgressBar = root.findViewById(R.id.loadingProgressBar)

        // Initialize summary views
        totalTraysTextView = root.findViewById(R.id.totalTraysTextView)
        totalPaidTextView = root.findViewById(R.id.totalPaidTextView)
        avgWorkersPerDayTextView = root.findViewById(R.id.avgWorkersPerDayTextView)
        todayTraysTextView = root.findViewById(R.id.todayTraysTextView)
        todayToPayTextView = root.findViewById(R.id.todayToPayTextView)
        activeWorkersTodayTextView = root.findViewById(R.id.activeWorkersTodayTextView)

        // Initialize new buttons
        workerProductivityButton = root.findViewById(R.id.workerProductivityButton)
        dailyCollectionsButton = root.findViewById(R.id.dailyCollectionsButton)

        setupUI()
        setupObservers()

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupObservers()
        lifecycleScope.launch {
            viewModel.loadReportData()
        }
    }

    private fun setupUI() {
        // Set up swipe refresh
        swipeRefreshLayout.setOnRefreshListener {
            refreshData()
        }

        // Set up new button click listeners
        workerProductivityButton.setOnClickListener {
            showWorkerProductivityDialog()
        }

        dailyCollectionsButton.setOnClickListener {
            showDailyCollectionsDialog()
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

                // Disable buttons during loading
                workerProductivityButton.isEnabled = !isLoading
                dailyCollectionsButton.isEnabled = !isLoading
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
    }

    private fun updateSummaryStats(stats: SummaryStats?) {
        if (stats == null) {
            // Hide or show placeholder if no data
            return
        }

        // Update all summary text views with the data
        totalTraysTextView.text = "${stats.totalTrays}"
        totalPaidTextView.text = String.format(Locale.getDefault(), "%.2f₴", stats.totalPaid)
        avgWorkersPerDayTextView.text = String.format(Locale.getDefault(), "%.1f", stats.avgWorkersPerDay)
        todayTraysTextView.text = "${stats.todayTrays}"
        todayToPayTextView.text = String.format(Locale.getDefault(), "%.2f₴", stats.todayToPay)
        activeWorkersTodayTextView.text = "${stats.activeWorkersToday}"
    }

    private fun refreshData() {
        viewModel.loadReportData()
    }

    private fun showWorkerProductivityDialog() {
        // Collect current worker stats
        val currentWorkerStats = viewModel.workerStats.value

        if (currentWorkerStats.isEmpty()) {
            Toast.makeText(context, "Немає даних про працівників", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = WorkerProductivityDialog(requireContext(), currentWorkerStats)
        dialog.show()
    }

    private fun showDailyCollectionsDialog() {
        // Create and show daily collections dialog
        lifecycleScope.launch {
            try {
                // Load daily data
                val dailyData = viewModel.loadDailyCollectionsData()

                if (dailyData.isEmpty()) {
                    Toast.makeText(context, "Немає даних про щоденні збори", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val dialog = DailyCollectionsDialog(requireContext(), dailyData)
                dialog.show()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading daily collections data", e)
                Toast.makeText(context, "Помилка завантаження даних: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

// Worker stats adapter remains the same
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
        val rankTextView: TextView = view.findViewById(R.id.rankTextView)
        val nameTextView: TextView = view.findViewById(R.id.workerNameTextView)
        val totalPunnetsTextView: TextView = view.findViewById(R.id.workerPunnetsTextView)
        val avgPunnetsTextView: TextView = view.findViewById(R.id.workerAvgTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_worker_stats, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val rank = position + 1

        // Set proper rank with medal emojis for top performers
        holder.rankTextView.text = when (rank) {
            1 -> "🥇"  // Gold medal for 1st place
            2 -> "🥈"  // Silver medal for 2nd place
            3 -> "🥉"  // Bronze medal for 3rd place
            else -> "$rank"  // Regular number for others
        }

        // Worker name with sequence number
        holder.nameTextView.text = "${item.workerName} [${item.sequenceNumber}]"

        // Total punnets
        holder.totalPunnetsTextView.text = "${item.totalPunnets}"

        // Average punnets per day (with /д suffix)
        holder.avgPunnetsTextView.text = String.format(
            Locale.getDefault(),
            "%.1f/д",
            item.avgPunnetsPerDay
        )
    }
}