package com.example.berryharvest.ui.payment

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.R
import com.example.berryharvest.data.model.Worker
import com.example.berryharvest.ui.common.SearchableSpinnerView
import com.example.berryharvest.ui.common.WorkerSearchableItem
import com.example.berryharvest.ui.common.toSearchableItem
import kotlinx.coroutines.launch
import java.util.Locale

class PaymentFragment : Fragment() {

    private lateinit var viewModel: PaymentViewModel

    // UI components
    private lateinit var workerSearchView: SearchableSpinnerView
    private lateinit var workerInfoCard: View
    private lateinit var workerNameTextView: TextView
    private lateinit var balanceTextView: TextView
    private lateinit var todayPunnetsTextView: TextView
    private lateinit var fullPaymentButton: Button
    private lateinit var partialPaymentButton: Button
    private lateinit var paymentHistoryTitle: TextView
    private lateinit var paymentHistoryRecyclerView: RecyclerView
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var emptyStateTextView: TextView

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
        workerInfoCard = root.findViewById(R.id.workerInfoCard)
        workerNameTextView = root.findViewById(R.id.workerNameTextView)
        balanceTextView = root.findViewById(R.id.balanceTextView)
        todayPunnetsTextView = root.findViewById(R.id.todayPunnetsTextView)
        fullPaymentButton = root.findViewById(R.id.fullPaymentButton)
        partialPaymentButton = root.findViewById(R.id.partialPaymentButton)
        paymentHistoryTitle = root.findViewById(R.id.paymentHistoryTitle)
        paymentHistoryRecyclerView = root.findViewById(R.id.paymentHistoryRecyclerView)
        loadingProgressBar = root.findViewById(R.id.loadingProgressBar)
        emptyStateTextView = root.findViewById(R.id.emptyStateTextView)

        // Set up RecyclerView
        paymentAdapter = PaymentAdapter()
        paymentHistoryRecyclerView.adapter = paymentAdapter
        paymentHistoryRecyclerView.layoutManager = LinearLayoutManager(context)

        // Set up worker search
        setupWorkerSearch()

        // Set up button listeners
        setupButtonListeners()

        // Set up observers
        setupObservers()

        return root
    }

    override fun onPause() {
        super.onPause()
        workerSearchView.hideDropdownForced()
    }

    override fun onResume() {
        super.onResume()
        // Reset the searchable spinner completely
        workerSearchView.clearSelection()
        viewModel.clearSelection()
    }

    private fun setupWorkerSearch() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Observe all workers for search
            viewModel.allWorkers.collect { workers ->
                workerSearchView.setAdapter(workers.map { it.toSearchableItem() })
                workerSearchView.setOnItemSelectedListener { searchableItem ->
                    val workerItem = searchableItem as WorkerSearchableItem
                    viewModel.selectWorker(workerItem.worker)
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
            viewModel.selectedWorker.collect { worker ->
                worker?.let {
                    showWorkerInfo(it)
                } ?: hideWorkerInfo()
            }
        }

        // Observe balance
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.workerBalance.collect { balance ->
                updateBalanceDisplay(balance)
            }
        }

        // Observe today's punnet count
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.todayPunnetCount.collect { count ->
                todayPunnetsTextView.text = count.toString()
            }
        }

        // Observe payment history
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.paymentHistory.collect { payments ->
                paymentAdapter.submitList(payments)

                if (payments.isEmpty()) {
                    emptyStateTextView.visibility = View.VISIBLE
                    paymentHistoryRecyclerView.visibility = View.GONE
                } else {
                    emptyStateTextView.visibility = View.GONE
                    paymentHistoryRecyclerView.visibility = View.VISIBLE
                }
            }
        }

        // Observe loading state
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                loadingProgressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }

        // Observe errors
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.error.collect { errorMessage ->
                errorMessage?.let {
                    Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                    viewModel.clearError()
                }
            }
        }

        // Observe success messages
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.success.collect { successMessage ->
                successMessage?.let {
                    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                    viewModel.clearSuccess()
                }
            }
        }
    }

    private fun showWorkerInfo(worker: Worker) {
        workerNameTextView.text = "${worker.fullName} [${worker.sequenceNumber}]"
        workerInfoCard.visibility = View.VISIBLE
        paymentHistoryTitle.visibility = View.VISIBLE
        paymentHistoryRecyclerView.visibility = View.VISIBLE
    }

    private fun hideWorkerInfo() {
        workerInfoCard.visibility = View.GONE
        paymentHistoryTitle.visibility = View.GONE
        paymentHistoryRecyclerView.visibility = View.GONE
        emptyStateTextView.visibility = View.GONE
    }

    private fun updateBalanceDisplay(balance: Float) {
        balanceTextView.text = String.format(Locale.getDefault(), "%.2f₴", balance)

        // Enable/disable payment buttons based on balance
        val hasPositiveBalance = balance > 0
        fullPaymentButton.isEnabled = hasPositiveBalance
        partialPaymentButton.isEnabled = hasPositiveBalance
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
            .setPositiveButton("Оплатити") { _, _ ->
                val amountText = amountEditText.text.toString()
                val notes = notesEditText.text.toString()

                val amount = amountText.toFloatOrNull()
                if (amount == null) {
                    Toast.makeText(context, "Введіть коректну суму", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (amount <= 0) {
                    Toast.makeText(context, "Сума повинна бути більше нуля", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (amount > balance) {
                    Toast.makeText(context, "Сума не може перевищувати поточний баланс", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                viewModel.makePartialPayment(amount, notes)
            }
            .setNegativeButton("Скасувати", null)
            .create()

        dialog.show()
    }
}