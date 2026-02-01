package com.example.berryharvest.ui.payment

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues.TAG
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.BaseFragment
import com.example.berryharvest.R
import com.example.berryharvest.data.model.Worker
import com.example.berryharvest.ui.common.SearchableSpinnerView
import com.example.berryharvest.ui.common.WorkerSearchableItem
import com.example.berryharvest.ui.common.toSearchableItem
import com.google.android.material.button.MaterialButtonToggleGroup
import java.util.Locale

class PaymentFragment : BaseFragment() {

    private lateinit var viewModel: PaymentViewModel

    // UI components
    private lateinit var workerSearchView: SearchableSpinnerView
    private lateinit var workerSummaryCard: View
    private lateinit var workerNameTextView: TextView
    private lateinit var balanceTextView: TextView
    private lateinit var todayPunnetsTextView: TextView
    private lateinit var partialPaymentButton: Button
    private lateinit var paymentHistoryRecyclerView: RecyclerView
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var emptyStateLayout: LinearLayout

    // Payment totals card UI components
    private lateinit var paymentTotalsCard: View
    private lateinit var totalPaidAmountTextView: TextView
    private lateinit var totalPaidWithBerryTextView: TextView
    private lateinit var totalPaidWithMoneyTextView: TextView
    private lateinit var paymentHistorySectionTitle: TextView

    // NEW: View toggle components
    private lateinit var viewToggleGroup: MaterialButtonToggleGroup
    private lateinit var summaryViewButton: Button
    private lateinit var detailedViewButton: Button

    private lateinit var paymentAdapter: PaymentAdapter
    private lateinit var dailySummaryAdapter: DailyPaymentSummaryAdapter

    // NEW: Track current view mode for global view
    private var isGlobalDetailedView = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_payment, container, false)

        // Initialize ViewModel
        viewModel = ViewModelProvider(this).get(PaymentViewModel::class.java)

        // Find views
        workerSearchView = root.findViewById(R.id.workerSearchView)
        workerSummaryCard = root.findViewById(R.id.workerSummaryCard)
        workerNameTextView = root.findViewById(R.id.workerNameTextView)
        balanceTextView = root.findViewById(R.id.balanceTextView)
        todayPunnetsTextView = root.findViewById(R.id.todayPunnetsTextView)
        partialPaymentButton = root.findViewById(R.id.partialPaymentButton)
        paymentHistoryRecyclerView = root.findViewById(R.id.paymentHistoryRecyclerView)
        loadingProgressBar = root.findViewById(R.id.loadingProgressBar)
        emptyStateLayout = root.findViewById(R.id.emptyStateTextView)

        // Find totals card views
        paymentTotalsCard = root.findViewById(R.id.paymentTotalsCard)
        totalPaidAmountTextView = root.findViewById(R.id.totalPaidAmountTextView)
        totalPaidWithBerryTextView = root.findViewById(R.id.totalPaidWithBerryTextView)
        totalPaidWithMoneyTextView = root.findViewById(R.id.totalPaidWithMoneyTextView)
        paymentHistorySectionTitle = root.findViewById(R.id.paymentHistorySectionTitle)

        // NEW: Find toggle views
        viewToggleGroup = root.findViewById(R.id.viewToggleGroup)
        summaryViewButton = root.findViewById(R.id.summaryViewButton)
        detailedViewButton = root.findViewById(R.id.detailedViewButton)

        // Set up RecyclerView with payment adapter (for detailed worker payments)
        paymentAdapter = PaymentAdapter()

        // Set up daily summary adapter (for main view)
        dailySummaryAdapter = DailyPaymentSummaryAdapter()

        // Start with summary adapter (no worker selected)
        paymentHistoryRecyclerView.adapter = dailySummaryAdapter
        paymentHistoryRecyclerView.layoutManager = LinearLayoutManager(context)

        // Set up button listeners
        setupButtonListeners()

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup observers with proper lifecycle awareness
        setupObservers()

        // Setup worker search after observers
        setupWorkerSearch()

        // NEW: Setup view toggle
        setupViewToggle()

        // Ensure data is loaded
        viewModel.ensureDataLoaded()
    }

    override fun onPause() {
        super.onPause()
        workerSearchView.hideDropdownForced()
    }

    override fun onResume() {
        super.onResume()
        workerSearchView.clearSelection()
        viewModel.clearSelection()
    }

    private fun setupWorkerSearch() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allWorkers.collect { workers ->
                    if (workers.isNotEmpty()) {
                        workerSearchView.setAdapter(workers.map { it.toSearchableItem() })
                        workerSearchView.setOnItemSelectedListener { searchableItem ->
                            val workerItem = searchableItem as WorkerSearchableItem
                            viewModel.selectWorker(workerItem.worker)
                        }
                        Log.d(TAG, "Worker search setup with ${workers.size} workers")
                    }
                }
            }
        }
    }

    private fun setupButtonListeners() {
        partialPaymentButton.setOnClickListener {
            showPaymentDialog()
        }
    }

    // NEW: Setup view toggle
    private fun setupViewToggle() {
        // Set initial state
        viewToggleGroup.check(R.id.summaryViewButton)
        isGlobalDetailedView = false

        viewToggleGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.summaryViewButton -> {
                        isGlobalDetailedView = false
                        updateGlobalView()
                    }
                    R.id.detailedViewButton -> {
                        isGlobalDetailedView = true
                        updateGlobalView()
                    }
                }
            }
        }
    }

    private fun setupObservers() {
        // Observe selected worker
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.selectedWorker.collect { worker ->
                    Log.d(TAG, "Selected worker: ${worker?.fullName ?: "none"}")

                    if (worker != null) {
                        // Worker selected - show detailed payment history
                        showWorkerSummary(worker)
                        switchToDetailedView()
                        hideViewToggle() // NEW: Hide toggle when worker is selected
                    } else {
                        // No worker selected - show global view based on toggle
                        hideWorkerSummary()
                        updateGlobalView()
                        showViewToggle() // NEW: Show toggle when no worker is selected
                    }
                }
            }
        }

        // Observe daily payment summaries
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.dailyPaymentSummaries.collect { summaries ->
                    Log.d(TAG, "Daily payment summaries updated: ${summaries.size} summaries")

                    // Only update if no worker is selected and we're in summary view
                    if (viewModel.selectedWorker.value == null && !isGlobalDetailedView) {
                        updateSummaryDisplay(summaries)
                    }
                }
            }
        }

        // Observe global payment totals
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.globalPaymentTotals.collect { totals ->
                    Log.d(TAG, "Global payment totals updated: $totals")

                    // Only show global totals when no worker is selected
                    if (viewModel.selectedWorker.value == null) {
                        updateTotalsDisplay(totals, isWorkerSpecific = false)
                    }
                }
            }
        }

        // Observe worker payment totals
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.workerPaymentTotals.collect { totals ->
                    Log.d(TAG, "Worker payment totals updated: $totals")

                    // Only show worker totals when a worker is selected
                    if (viewModel.selectedWorker.value != null) {
                        updateTotalsDisplay(totals, isWorkerSpecific = true)
                    }
                }
            }
        }

        // Observe balance
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.workerBalance.collect { balance ->
                    Log.d(TAG, "Worker balance updated: $balance")
                    updateBalanceDisplay(balance)
                }
            }
        }

        // Observe today's punnet count
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.todayPunnetCount.collect { count ->
                    Log.d(TAG, "Today's punnet count: $count")
                    todayPunnetsTextView.text = count.toString()
                }
            }
        }

        // Observe payment list items for detailed views
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.paymentListItems.collect { listItems ->
                    Log.d(TAG, "Payment list items updated: ${listItems.size} items")

                    val selectedWorker = viewModel.selectedWorker.value
                    if (selectedWorker != null) {
                        // Worker selected - show detailed view
                        updateDetailedPaymentHistory(listItems)
                    } else if (isGlobalDetailedView) {
                        // No worker selected but detailed view enabled - show global detailed
                        updateGlobalDetailedPaymentHistory(listItems)
                    }
                }
            }
        }

        // Observe loading state
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isLoading.collect { isLoading ->
                    Log.d(TAG, "Loading state: $isLoading")
                    loadingProgressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                }
            }
        }

        // Observe data initialization
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.dataInitialized.collect { initialized ->
                    if (initialized) {
                        loadingProgressBar.visibility = View.GONE
                    }
                }
            }
        }

        // Observe errors
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.error.collect { errorMessage ->
                    errorMessage?.let {
                        Log.e(TAG, "Error: $it")
                        Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                        viewModel.clearError()
                    }
                }
            }
        }

        // Observe success messages
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.success.collect { successMessage ->
                    successMessage?.let {
                        Log.d(TAG, "Success: $it")
                        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                        viewModel.clearSuccess()
                    }
                }
            }
        }
    }

    // NEW: Show/hide view toggle
    private fun showViewToggle() {
        viewToggleGroup.visibility = View.VISIBLE
    }

    private fun hideViewToggle() {
        viewToggleGroup.visibility = View.GONE
    }

    // NEW: Update global view based on toggle state
    private fun updateGlobalView() {
        if (isGlobalDetailedView) {
            switchToGlobalDetailedView()
        } else {
            switchToSummaryView()
        }
    }

    // NEW: Switch to global detailed view (all payments with worker names)
    private fun switchToGlobalDetailedView() {
        Log.d(TAG, "Switching to global detailed view")
        // Create adapter with worker names enabled for global view
        paymentAdapter = PaymentAdapter(showWorkerNames = true, isWorkerView = false)
        paymentHistoryRecyclerView.adapter = paymentAdapter

        // Show global totals
        val globalTotals = viewModel.globalPaymentTotals.value
        updateTotalsDisplay(globalTotals, isWorkerSpecific = false)

        // Update section title
        paymentHistorySectionTitle.text = "Всі платежі"

        // The payment list items will be updated by the observer
        val listItems = viewModel.paymentListItems.value
        updateGlobalDetailedPaymentHistory(listItems)
    }

    // NEW: Update global detailed payment history
    private fun updateGlobalDetailedPaymentHistory(listItems: List<PaymentListItem>) {
        paymentAdapter.submitList(listItems)

        if (listItems.isEmpty()) {
            paymentHistoryRecyclerView.visibility = View.GONE
            showEmptyState(EmptyStateType.NO_PAYMENTS_GLOBAL)
        } else {
            paymentHistoryRecyclerView.visibility = View.VISIBLE
            hideEmptyState()
        }
    }

    private fun updateTotalsDisplay(totals: PaymentTotals, isWorkerSpecific: Boolean) {
        Log.d(TAG, "Updating totals display: $totals, isWorkerSpecific: $isWorkerSpecific")

        // Update section title based on context
        if (!isWorkerSpecific) {
            paymentHistorySectionTitle.text = if (isGlobalDetailedView) {
                "Всі платежі"
            } else {
                "Щоденні підсумки платежів"
            }
        } else {
            paymentHistorySectionTitle.text = "Історія платежів працівника"
        }

        // Determine if we should show the totals card
        val shouldShowTotals = if (isWorkerSpecific) {
            // For worker-specific view: only show if there are payments AND more than 5 payment records
            val workerPaymentCount = viewModel.paymentHistory.value.size
            Log.d(TAG, "Worker has $workerPaymentCount payment records")
            totals.totalPaid > 0 && workerPaymentCount > 5
        } else {
            // For global view: show if there are any payments
            totals.totalPaid > 0
        }

        if (shouldShowTotals) {
            paymentTotalsCard.visibility = View.VISIBLE

            // Update totals values
            totalPaidAmountTextView.text = String.format(Locale.getDefault(), "%.2f₴", totals.totalPaid)
            totalPaidWithBerryTextView.text = String.format(Locale.getDefault(), "%.2f₴", totals.paidWithBerry)
            totalPaidWithMoneyTextView.text = String.format(Locale.getDefault(), "%.2f₴", totals.paidWithMoney)

            Log.d(TAG, "Totals card made visible with total: ${totals.totalPaid}")
        } else {
            paymentTotalsCard.visibility = View.GONE
            if (isWorkerSpecific) {
                val workerPaymentCount = viewModel.paymentHistory.value.size
                Log.d(TAG, "Totals card hidden - worker only has $workerPaymentCount payment records (need >5)")
            } else {
                Log.d(TAG, "Totals card hidden - no payments")
            }
        }
    }

    private fun switchToSummaryView() {
        Log.d(TAG, "Switching to summary view")
        paymentHistoryRecyclerView.adapter = dailySummaryAdapter

        // Update the summaries display
        val summaries = viewModel.dailyPaymentSummaries.value
        updateSummaryDisplay(summaries)

        // Show global totals
        val globalTotals = viewModel.globalPaymentTotals.value
        updateTotalsDisplay(globalTotals, isWorkerSpecific = false)
    }

    private fun switchToDetailedView() {
        Log.d(TAG, "Switching to detailed view")
        // Create adapter with worker view mode enabled (no worker names, worker-specific view)
        paymentAdapter = PaymentAdapter(showWorkerNames = false, isWorkerView = true)
        paymentHistoryRecyclerView.adapter = paymentAdapter

        // Show worker totals
        val workerTotals = viewModel.workerPaymentTotals.value
        updateTotalsDisplay(workerTotals, isWorkerSpecific = true)

        // The payment list items will be updated by the observer
    }

    private fun updateSummaryDisplay(summaries: List<DailyPaymentSummary>) {
        dailySummaryAdapter.submitList(summaries)

        if (summaries.isEmpty()) {
            paymentHistoryRecyclerView.visibility = View.GONE
            showEmptyState(EmptyStateType.NO_PAYMENTS_GLOBAL)
        } else {
            paymentHistoryRecyclerView.visibility = View.VISIBLE
            hideEmptyState()
        }
    }

    private fun updateDetailedPaymentHistory(listItems: List<PaymentListItem>) {
        paymentAdapter.submitList(listItems)

        if (listItems.isEmpty()) {
            paymentHistoryRecyclerView.visibility = View.GONE
            showEmptyState(EmptyStateType.WORKER_NO_HISTORY)
        } else {
            paymentHistoryRecyclerView.visibility = View.VISIBLE
            hideEmptyState()
        }
    }

    private fun updateBalanceDisplay(balance: Float) {
        balanceTextView.text = String.format(Locale.getDefault(), "%.2f₴", balance)

        // Set color based on balance
        val color = when {
            balance > 0 -> requireContext().getColor(android.R.color.holo_green_dark)
            balance < 0 -> requireContext().getColor(android.R.color.holo_red_dark)
            else -> requireContext().getColor(android.R.color.black)
        }
        balanceTextView.setTextColor(color)

        partialPaymentButton.isEnabled = true
    }

    private fun showWorkerSummary(worker: Worker) {
        workerNameTextView.text = "[${worker.sequenceNumber}] ${worker.fullName}"
        workerSummaryCard.visibility = View.VISIBLE
        // Empty state will be handled by the payment history observer
    }

    private fun hideWorkerSummary() {
        workerSummaryCard.visibility = View.GONE
        // Show the "choose worker" empty state when no worker is selected
        // Only if we don't have payment summaries to show
        if (viewModel.dailyPaymentSummaries.value.isEmpty()) {
            showEmptyState(EmptyStateType.CHOOSE_WORKER)
        }
    }

    private fun showEmptyState(type: EmptyStateType) {
        emptyStateLayout.visibility = View.VISIBLE
        paymentTotalsCard.visibility = View.GONE  // Hide totals when showing empty state

        // Find all TextViews in the empty state layout
        val emptyStateChildren = getAllTextViewsInLayout(emptyStateLayout)

        when (type) {
            EmptyStateType.CHOOSE_WORKER -> {
                updateEmptyStateText(emptyStateChildren, "💰", "Виберіть працівника",
                    "Оберіть працівника зі списку вище, щоб переглянути його баланс та здійснити оплату")
            }
            EmptyStateType.WORKER_NO_HISTORY -> {
                updateEmptyStateText(emptyStateChildren, "📋", "Немає історії платежів",
                    "Для цього працівника ще не було здійснено жодного платежу")
            }
            EmptyStateType.NO_PAYMENTS_GLOBAL -> {
                updateEmptyStateText(emptyStateChildren, "💰", "Немає платежів",
                    "Платежі з'являться тут після їх здійснення")
            }
        }
    }

    private fun getAllTextViewsInLayout(layout: ViewGroup): List<TextView> {
        val textViews = mutableListOf<TextView>()

        for (i in 0 until layout.childCount) {
            val child = layout.getChildAt(i)
            when (child) {
                is TextView -> textViews.add(child)
                is ViewGroup -> textViews.addAll(getAllTextViewsInLayout(child))
            }
        }

        return textViews
    }

    private fun updateEmptyStateText(textViews: List<TextView>, icon: String, title: String, description: String) {
        // Try to find TextViews by their content or position
        textViews.forEachIndexed { index, textView ->
            when (index) {
                0 -> textView.text = icon        // First TextView is usually the icon
                1 -> textView.text = title       // Second is the title
                2 -> textView.text = description // Third is the description
            }
        }
    }

    private fun hideEmptyState() {
        emptyStateLayout.visibility = View.GONE
    }

    @SuppressLint("DefaultLocale")
    private fun showPaymentDialog() {
        val balance = viewModel.workerBalance.value

        val dialogView = layoutInflater.inflate(R.layout.dialog_make_payment, null)
        val currentBalanceTextView = dialogView.findViewById<TextView>(R.id.currentBalanceTextView)
        val amountEditText = dialogView.findViewById<EditText>(R.id.amountEditText)
        val notesEditText = dialogView.findViewById<EditText>(R.id.notesEditText)

        // Show current balance
        currentBalanceTextView.text = String.format(Locale.getDefault(), "%.2f₴", balance)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Оплата")
            .setView(dialogView)
            .setPositiveButton("Оплатити", null) // Set to null initially to prevent auto-dismiss
            .setNegativeButton("Скасувати", null)
            .create()

        dialog.show()

        // Override the click listener to prevent dialog from closing on error
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val amountText = amountEditText.text.toString().replace(",", ".")
            val notes = notesEditText.text.toString()

            val amount = amountText.toFloatOrNull()
            if (amount == null) {
                Toast.makeText(context, "Введіть коректну суму", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (amount <= 0) {
                Toast.makeText(context, "Сума повинна бути більше нуля", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (amount > balance) {
                val overpayment = amount - balance
                AlertDialog.Builder(requireContext())
                    .setTitle("Підтвердження")
                    .setMessage(
                        "Сума на ${
                            String.format(
                                "%.2f₴",
                                overpayment
                            )
                        } перевищує баланс. Продовжити?"
                    )
                    .setPositiveButton("Так") { _, _ ->
                        dialog.dismiss()
                        viewModel.makePartialPayment(amount, notes)
                    }
                    .setNegativeButton("Ні", null)
                    .show()
            } else {
                // All validation passed, dismiss dialog and make payment
                dialog.dismiss()
                viewModel.makePartialPayment(amount, notes)
            }
        }
    }

    private enum class EmptyStateType {
        CHOOSE_WORKER,
        WORKER_NO_HISTORY,
        NO_PAYMENTS_GLOBAL
    }
}