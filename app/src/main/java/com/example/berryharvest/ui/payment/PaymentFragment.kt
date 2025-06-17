package com.example.berryharvest.ui.payment

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
import java.util.Locale

class PaymentFragment : BaseFragment() {

    private lateinit var viewModel: PaymentViewModel

    // UI components
    private lateinit var workerSearchView: SearchableSpinnerView
    private lateinit var workerSummaryCard: View
    private lateinit var workerNameTextView: TextView
    private lateinit var totalEarningsTextView: TextView
    private lateinit var totalPaymentsTextView: TextView
    private lateinit var balanceTextView: TextView
    private lateinit var todayPunnetsTextView: TextView
    private lateinit var fullPaymentButton: Button
    private lateinit var partialPaymentButton: Button
    private lateinit var paymentHistoryRecyclerView: RecyclerView
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var emptyStateLayout: LinearLayout

    private lateinit var paymentAdapter: PaymentAdapter

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
        totalEarningsTextView = root.findViewById(R.id.totalEarningsTextView)
        totalPaymentsTextView = root.findViewById(R.id.totalPaymentsTextView)
        balanceTextView = root.findViewById(R.id.balanceTextView)
        todayPunnetsTextView = root.findViewById(R.id.todayPunnetsTextView)
        fullPaymentButton = root.findViewById(R.id.fullPaymentButton)
        partialPaymentButton = root.findViewById(R.id.partialPaymentButton)
        paymentHistoryRecyclerView = root.findViewById(R.id.paymentHistoryRecyclerView)
        loadingProgressBar = root.findViewById(R.id.loadingProgressBar)
        emptyStateLayout = root.findViewById(R.id.emptyStateTextView)

        // Set up RecyclerView
        paymentAdapter = PaymentAdapter()
        paymentHistoryRecyclerView.adapter = paymentAdapter
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
        fullPaymentButton.setOnClickListener {
            showFullPaymentDialog()
        }

        partialPaymentButton.setOnClickListener {
            showPartialPaymentDialog()
        }
    }

    private fun setupObservers() {
        // Observe selected worker
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.selectedWorker.collect { worker ->
                    Log.d(TAG, "Selected worker: ${worker?.fullName ?: "none"}")
                    worker?.let {
                        showWorkerSummary(it)
                    } ?: hideWorkerSummary()
                }
            }
        }

        // Observe earnings
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.workerEarnings.collect { earnings ->
                    Log.d(TAG, "Worker earnings updated: $earnings")
                    updateEarningsDisplay(earnings)
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

        // Observe payment history
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.paymentHistory.collect { payments ->
                    Log.d(TAG, "Payment history updated: ${payments.size} payments")
                    paymentAdapter.submitList(payments)
                    updatePaymentHistoryDisplay(payments)
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

    private fun updateEarningsDisplay(earnings: Float) {
        totalEarningsTextView.text = String.format(Locale.getDefault(), "%.2f₴", earnings)
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

        // Enable/disable payment buttons based on balance
        val hasPositiveBalance = balance > 0
        fullPaymentButton.isEnabled = hasPositiveBalance
        partialPaymentButton.isEnabled = hasPositiveBalance
    }

    private fun updatePaymentHistoryDisplay(payments: List<com.example.berryharvest.data.model.PaymentRecord>) {
        // Calculate total payments for display
        val totalPayments = payments.sumOf { it.amount.toDouble() }.toFloat()
        totalPaymentsTextView.text = String.format(Locale.getDefault(), "%.2f₴", totalPayments)
    }

    private fun showWorkerSummary(worker: Worker) {
        workerNameTextView.text = "[${worker.sequenceNumber}] ${worker.fullName}"
        workerSummaryCard.visibility = View.VISIBLE
        paymentHistoryRecyclerView.visibility = View.VISIBLE
        emptyStateLayout.visibility = View.GONE
    }

    private fun hideWorkerSummary() {
        workerSummaryCard.visibility = View.GONE
        paymentHistoryRecyclerView.visibility = View.GONE
        emptyStateLayout.visibility = View.VISIBLE
    }

    private fun showFullPaymentDialog() {
        val balance = viewModel.workerBalance.value

        val dialogView = layoutInflater.inflate(R.layout.dialog_make_payment, null)
        val currentBalanceTextView = dialogView.findViewById<TextView>(R.id.currentBalanceTextView)
        val amountEditText = dialogView.findViewById<EditText>(R.id.amountEditText)
        val notesEditText = dialogView.findViewById<EditText>(R.id.notesEditText)

        // Pre-fill with full balance
        currentBalanceTextView.text = String.format(Locale.getDefault(), "%.2f₴", balance)
        amountEditText.setText(String.format(Locale.getDefault(), "%.2f", balance))
        amountEditText.isEnabled = false  // Don't allow editing for full payment

        AlertDialog.Builder(requireContext())
            .setTitle("Повна оплата")
            .setView(dialogView)
            .setPositiveButton("Оплатити") { _, _ ->
                val notes = notesEditText.text.toString()
                viewModel.makeFullPayment(notes)
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun showPartialPaymentDialog() {
        val balance = viewModel.workerBalance.value

        val dialogView = layoutInflater.inflate(R.layout.dialog_make_payment, null)
        val currentBalanceTextView = dialogView.findViewById<TextView>(R.id.currentBalanceTextView)
        val amountEditText = dialogView.findViewById<EditText>(R.id.amountEditText)
        val notesEditText = dialogView.findViewById<EditText>(R.id.notesEditText)

        // Show current balance
        currentBalanceTextView.text = String.format(Locale.getDefault(), "%.2f₴", balance)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Часткова оплата")
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
                Toast.makeText(context, "Сума не може перевищувати поточний баланс", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // All validation passed, dismiss dialog and make payment
            dialog.dismiss()
            viewModel.makePartialPayment(amount, notes)
        }
    }
}