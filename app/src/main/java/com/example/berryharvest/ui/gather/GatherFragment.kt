package com.example.berryharvest.ui.gather

import android.annotation.SuppressLint
import android.content.ContentValues.TAG
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.berryharvest.BaseFragment
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.R
import com.example.berryharvest.data.model.Gather
import com.example.berryharvest.data.model.Worker
import com.example.berryharvest.data.repository.ConnectionState
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.zxing.integration.android.IntentIntegrator
import io.realm.kotlin.ext.query
import kotlinx.coroutines.launch
import java.util.Locale
import com.example.berryharvest.ui.common.SearchableSpinnerView
import com.example.berryharvest.ui.common.toSearchableItem
import com.example.berryharvest.ui.common.toSearchableItem
import kotlinx.coroutines.flow.first

class GatherFragment : BaseFragment() {
    private lateinit var viewModel: GatherViewModel

    // UI components
    private lateinit var punnetPriceTextView: TextView
    private lateinit var scanButton: Button
    private lateinit var recentScansRecyclerView: RecyclerView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var emptyStateTextView: TextView
    private lateinit var syncStatusTextView: TextView
    private lateinit var totalPunnetsTextView: TextView
    private lateinit var totalAmountTextView: TextView
    private lateinit var manualEntryFab: FloatingActionButton

    private lateinit var gatherAdapter: GatherAdapter

    // Get row repository for row status updates
    private val app: BerryHarvestApplication by lazy { requireActivity().application as BerryHarvestApplication }
    private val rowRepository by lazy { app.repositoryProvider.rowRepository }

    // Scanner launcher
    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val scanResult = IntentIntegrator.parseActivityResult(
            IntentIntegrator.REQUEST_CODE, result.resultCode, result.data
        )

        scanResult?.let {
            if (scanResult.contents == null) {
                Toast.makeText(activity, "Сканування припинено", Toast.LENGTH_LONG).show()
            } else {
                viewModel.handleScanResult(scanResult.contents)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_gather, container, false)
        viewModel = ViewModelProvider(this).get(GatherViewModel::class.java)

        // Find views
        punnetPriceTextView = view.findViewById(R.id.punnetPriceTextView)
        scanButton = view.findViewById(R.id.scanButton)
        recentScansRecyclerView = view.findViewById(R.id.recentScansRecyclerView)
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        loadingProgressBar = view.findViewById(R.id.loadingProgressBar)
        emptyStateTextView = view.findViewById(R.id.emptyStateTextView)
        syncStatusTextView = view.findViewById(R.id.syncStatusTextView)
        totalPunnetsTextView = view.findViewById(R.id.totalPunnetsTextView)
        totalAmountTextView = view.findViewById(R.id.totalAmountTextView)
        manualEntryFab = view.findViewById(R.id.manualEntryFab)

        setupUI()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup observers with proper lifecycle awareness
        setupObservers()

        // Ensure data is loaded
        viewModel.ensureDataLoaded()
    }

    private fun setupUI() {
        // Setup price long-click
        punnetPriceTextView.setOnLongClickListener {
            showPriceChangeMenu()
            true
        }

        // Setup scan button
        scanButton.setOnClickListener {
            startScanner()
        }

        // Setup SwipeRefreshLayout
        swipeRefreshLayout.setOnRefreshListener {
            refreshData()
        }

        // Setup RecyclerView
        gatherAdapter = GatherAdapter(
            onEditClick = { gather -> showEditGatherDialog(gather) },
            onDeleteClick = { gather -> showDeleteGatherConfirmation(gather) }
        )
        recentScansRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        recentScansRecyclerView.adapter = gatherAdapter

        // Setup FAB for manual entry
        manualEntryFab.setOnClickListener {
            showManualEntryDialog()
        }
    }

    private fun setupObservers() {
        // StateFlow observers with proper lifecycle management
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.punnetPrice.collect { price ->
                    Log.d(TAG, "Punnet price updated: $price")
                    updatePriceDisplay(price)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recentGathers.collect { gathers ->
                    Log.d(TAG, "Recent gathers updated: ${gathers.size} items")
                    updateGathersDisplay(gathers)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.todayStats.collect { stats ->
                    Log.d(TAG, "Today's stats updated: $stats")
                    updateStatsDisplay(stats)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isLoading.collect { isLoading ->
                    Log.d(TAG, "Loading state: $isLoading")
                    loadingProgressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                    if (!isLoading) {
                        swipeRefreshLayout.isRefreshing = false
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.unsyncedCount.collect { count ->
                    Log.d(TAG, "Unsynced count: $count")
                    updateUnsyncedIndicator(count)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.connectionState.collect { state ->
                    updateConnectionState(state)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.dataInitialized.collect { initialized ->
                    if (initialized) {
                        loadingProgressBar.visibility = View.GONE
                    }
                }
            }
        }

        // LiveData observers (keeping these for compatibility with existing ViewModel methods)
        viewModel.errorMessage.observe(viewLifecycleOwner) { message ->
            message?.let {
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                viewModel.clearErrorMessage()
            }
        }

        viewModel.workerAssignment.observe(viewLifecycleOwner) { pair ->
            pair?.let { (worker, rowNumber) ->
                if (rowNumber == -1) {
                    showAssignWorkerToRowDialog(worker)
                } else {
                    showGatherConfirmationDialog(worker, rowNumber)
                }
                viewModel.clearWorkerAssignment()
            }
        }

        viewModel.gatherSummary.observe(viewLifecycleOwner) { summaryData ->
            summaryData?.let {
                showGatherSummaryDialog(it)
            }
        }
    }

    private fun updateConnectionState(state: ConnectionState) {
        when (state) {
            is ConnectionState.Connected -> {
                syncStatusTextView.setBackgroundResource(R.drawable.rounded_status_background)
                syncStatusTextView.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_sync_small, 0, 0, 0)
            }
            is ConnectionState.Disconnected -> {
                syncStatusTextView.setBackgroundResource(R.drawable.rounded_status_background)
                syncStatusTextView.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_offline, 0, 0, 0)
                syncStatusTextView.text = "Офлайн режим"
            }
            is ConnectionState.Error -> {
                syncStatusTextView.setBackgroundResource(R.drawable.rounded_status_background)
                syncStatusTextView.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_offline, 0, 0, 0)
                syncStatusTextView.text = "Помилка з'єднання"
            }
        }
    }

    private fun updateUnsyncedIndicator(count: Int) {
        if (count > 0) {
            syncStatusTextView.text = "$count запис(ів) не синхронізовано"
            syncStatusTextView.setOnClickListener {
                viewModel.syncPendingChanges()
            }
        } else {
            if (viewModel.connectionState.value is ConnectionState.Connected) {
                syncStatusTextView.text = "Всі дані синхронізовано"
                syncStatusTextView.setOnClickListener(null)
            }
        }
    }

    private fun updateGathersDisplay(gathers: List<GatherWithDetails>) {
        gatherAdapter.submitList(gathers)

        if (gathers.isEmpty()) {
            emptyStateTextView.visibility = View.VISIBLE
            recentScansRecyclerView.visibility = View.GONE
        } else {
            emptyStateTextView.visibility = View.GONE
            recentScansRecyclerView.visibility = View.VISIBLE
        }
    }

    private fun updateStatsDisplay(stats: TodayStats) {
        totalPunnetsTextView.text = stats.totalPunnets.toString()
        totalAmountTextView.text = String.format(Locale.getDefault(), "%.0f₴", stats.totalAmount)
    }

    private fun updatePriceDisplay(price: Float) {
        punnetPriceTextView.text = String.format(Locale.getDefault(), "%.2f₴", price)
    }

    private fun startScanner() {
        val integrator = IntentIntegrator.forSupportFragment(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("Наведіть камеру на QR-код працівника")
        integrator.setBeepEnabled(true)
        integrator.setBarcodeImageEnabled(false)
        integrator.setOrientationLocked(false)

        scannerLauncher.launch(integrator.createScanIntent())
    }

    private fun showPriceChangeMenu() {
        AlertDialog.Builder(requireContext())
            .setTitle("Зміна ціни")
            .setMessage("Бажаєте змінити вартість пінетки?")
            .setPositiveButton("Так") { _, _ ->
                showPriceEditDialog()
            }
            .setNegativeButton("Ні", null)
            .show()
    }

    private fun showPriceEditDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_price, null)
        val priceEditText = dialogView.findViewById<EditText>(R.id.priceEditText)

        val currentPrice = viewModel.punnetPrice.value
        priceEditText.setText(String.format(Locale.getDefault(), "%.2f", currentPrice))

        AlertDialog.Builder(requireContext())
            .setTitle("Введіть нову ціну")
            .setView(dialogView)
            .setPositiveButton("Зберегти") { _, _ ->
                val priceText = priceEditText.text.toString().replace(",", ".")
                val newPrice = priceText.toFloatOrNull()

                if (newPrice == null) {
                    Toast.makeText(requireContext(), "Невірний формат ціни", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (newPrice != currentPrice) {
                    showPriceConfirmationDialog(newPrice)
                }
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun showPriceConfirmationDialog(newPrice: Float) {
        AlertDialog.Builder(requireContext())
            .setTitle("Підтвердження")
            .setMessage("Ви впевнені, що хочете змінити ціну на ${String.format(Locale.getDefault(), "%.2f", newPrice)}₴?")
            .setPositiveButton("Так") { _, _ ->
                viewModel.updatePunnetPrice(newPrice)
                Toast.makeText(requireContext(), "Ціну оновлено", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Ні", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun showGatherConfirmationDialog(worker: Worker, rowNumber: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_gather_confirmation, null)
        val workerNameTextView: TextView = dialogView.findViewById(R.id.workerNameTextView)
        val rowNumberTextView: TextView = dialogView.findViewById(R.id.rowNumberTextView)
        val punnetsEditText: EditText = dialogView.findViewById(R.id.punnetsEditText)
        val priceTextView: TextView = dialogView.findViewById(R.id.priceTextView)
        val markRowGatheredCheckBox: CheckBox = dialogView.findViewById(R.id.markRowGatheredCheckBox)

        val formattedPrice = String.format(Locale.getDefault(), "%.2f", viewModel.punnetPrice.value)

        workerNameTextView.text = "[${worker.sequenceNumber}] ${worker.fullName}"
        rowNumberTextView.text = "Ряд: $rowNumber"
        punnetsEditText.setText("10")
        priceTextView.text = "Ціна за пінетку: $formattedPrice₴"

        AlertDialog.Builder(requireContext())
            .setTitle("Підтвердження збору")
            .setView(dialogView)
            .setPositiveButton("Зберегти") { _, _ ->
                val numOfPunnetsText = punnetsEditText.text.toString()
                val numOfPunnets = numOfPunnetsText.toIntOrNull()
                val markRowGathered = markRowGatheredCheckBox.isChecked

                when {
                    numOfPunnetsText.isBlank() -> {
                        Toast.makeText(activity, "Введіть кількість пінеток", Toast.LENGTH_SHORT).show()
                    }
                    numOfPunnets == null -> {
                        Toast.makeText(activity, "Невірний формат кількості", Toast.LENGTH_SHORT).show()
                    }
                    numOfPunnets <= 0 -> {
                        Toast.makeText(activity, "Кількість пінеток повинна бути більше 0", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        // Save gather data and mark row as gathered if requested
                        saveGatherWithRowUpdate(worker._id, rowNumber, numOfPunnets, markRowGathered)
                    }
                }
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    /**
     * Save gather data and optionally mark row as gathered
     */
    private fun saveGatherWithRowUpdate(workerId: String, rowNumber: Int, numOfPunnets: Int, markRowGathered: Boolean) {
        lifecycleScope.launch {
            try {
                // First save the gather data
                viewModel.saveGatherData(workerId, rowNumber, numOfPunnets)

                // Then mark row as gathered if requested
                if (markRowGathered) {
                    markRowAsGathered(rowNumber)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving gather with row update", e)
                Toast.makeText(requireContext(), "Помилка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Mark a row as gathered by finding it by row number
     */
    private suspend fun markRowAsGathered(rowNumber: Int) {
        try {
            val realm = app.getRealmInstance()
            val row = realm.query<com.example.berryharvest.data.model.Row>(
                "rowNumber == $0 AND isDeleted == false", rowNumber
            ).first().find()

            row?.let {
                val result = rowRepository.updateRowCollectionStatus(it._id, true)
                when (result) {
                    is com.example.berryharvest.data.repository.Result.Success -> {
                        Log.d(TAG, "Marked row $rowNumber as gathered")
                        Toast.makeText(requireContext(), "Ряд $rowNumber позначено як зібраний", Toast.LENGTH_SHORT).show()
                    }
                    is com.example.berryharvest.data.repository.Result.Error -> {
                        Log.e(TAG, "Error marking row as gathered: ${result.message}")
                        Toast.makeText(requireContext(), "Помилка позначення ряду: ${result.message}", Toast.LENGTH_SHORT).show()
                    }
                    is com.example.berryharvest.data.repository.Result.Loading -> {
                        // Loading state
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding row to mark as gathered", e)
            Toast.makeText(requireContext(), "Помилка знаходження ряду", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAssignWorkerToRowDialog(worker: Worker) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_move_or_delete_worker, null)
        val newRowEditText = dialogView.findViewById<EditText>(R.id.newRowEditText)
        val moveButton = dialogView.findViewById<Button>(R.id.moveButton)
        val deleteButton = dialogView.findViewById<Button>(R.id.deleteButton)

        moveButton.text = "Призначити"
        deleteButton.text = "Скасувати"

        AlertDialog.Builder(requireContext())
            .setTitle("Працівник не призначений на ряд")
            .setMessage("Працівник ${worker.fullName} [${worker.sequenceNumber}] не призначений на жоден ряд. Призначте ряд, щоб продовжити:")
            .setView(dialogView)
            .setCancelable(false)
            .create()
            .apply {
                moveButton.setOnClickListener {
                    val rowNumberText = newRowEditText.text.toString().trim()
                    val rowNumber = rowNumberText.toIntOrNull()

                    if (rowNumberText.isEmpty() || rowNumber == null || rowNumber !in 1..999) {
                        Toast.makeText(context, "Введіть корректний номер ряду (1-999)", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    viewModel.assignWorkerToRow(worker._id, rowNumber).observe(viewLifecycleOwner) { success ->
                        if (success) {
                            dismiss()
                            Toast.makeText(context, "Працівник успішно призначений на ряд $rowNumber", Toast.LENGTH_SHORT).show()
                            showGatherConfirmationDialog(worker, rowNumber)
                        }
                    }
                }

                deleteButton.setOnClickListener {
                    dismiss()
                }

                show()
            }
    }

    private fun showEditGatherDialog(gather: Gather) {
        viewModel.getGatherById(gather._id).observe(viewLifecycleOwner) { currentGather ->
            if (currentGather == null) {
                Toast.makeText(context, "Помилка: запис не знайдено", Toast.LENGTH_SHORT).show()
                return@observe
            }

            val dialogView = layoutInflater.inflate(R.layout.dialog_gather_confirmation, null)
            val workerNameTextView: TextView = dialogView.findViewById(R.id.workerNameTextView)
            val rowNumberTextView: TextView = dialogView.findViewById(R.id.rowNumberTextView)
            val punnetsEditText: EditText = dialogView.findViewById(R.id.punnetsEditText)
            val priceTextView: TextView = dialogView.findViewById(R.id.priceTextView)
            val markRowGatheredCheckBox: CheckBox = dialogView.findViewById(R.id.markRowGatheredCheckBox)

            val workerDetails = viewModel.recentGathers.value.find { it.gather._id == gather._id }

            workerNameTextView.text = workerDetails?.workerName ?: "Невідомий працівник"
            rowNumberTextView.text = "Ряд: ${currentGather.rowNumber ?: "N/A"}"
            punnetsEditText.setText(currentGather.numOfPunnets?.toString() ?: "0")

            val formattedPrice = String.format(
                Locale.getDefault(),
                "%.2f",
                currentGather.punnetCost ?: viewModel.punnetPrice.value
            )
            priceTextView.text = "Ціна за пінетку: $formattedPrice₴"

            // For edit mode, show checkbox to mark row as gathered
            markRowGatheredCheckBox.text = "Позначити ряд ${currentGather.rowNumber} як зібраний"

            AlertDialog.Builder(requireContext())
                .setTitle("Редагування запису")
                .setView(dialogView)
                .setPositiveButton("Оновити") { _, _ ->
                    val numOfPunnetsText = punnetsEditText.text.toString()
                    val numOfPunnets = numOfPunnetsText.toIntOrNull()
                    val markRowGathered = markRowGatheredCheckBox.isChecked

                    when {
                        numOfPunnetsText.isBlank() -> {
                            Toast.makeText(activity, "Введіть кількість пінеток", Toast.LENGTH_SHORT).show()
                        }
                        numOfPunnets == null -> {
                            Toast.makeText(activity, "Невірний формат кількості", Toast.LENGTH_SHORT).show()
                        }
                        numOfPunnets <= 0 -> {
                            Toast.makeText(activity, "Кількість пінеток повинна бути більше 0", Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            lifecycleScope.launch {
                                // Update gather details
                                viewModel.updateGatherDetails(gather._id, numOfPunnets)

                                // Mark row as gathered if requested
                                if (markRowGathered && currentGather.rowNumber != null) {
                                    markRowAsGathered(currentGather.rowNumber!!)
                                }
                            }
                        }
                    }
                }
                .setNegativeButton("Скасувати", null)
                .show()
        }
    }

    private fun showDeleteGatherConfirmation(gather: Gather) {
        AlertDialog.Builder(requireContext())
            .setTitle("Видалення запису")
            .setMessage("Ви впевнені, що хочете видалити цей запис?")
            .setPositiveButton("Так") { _, _ ->
                viewModel.deleteGather(gather._id)
            }
            .setNegativeButton("Ні", null)
            .show()
    }

    private fun showManualEntryDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_gather_confirmation, null)
        val workerNameTextView: TextView = dialogView.findViewById(R.id.workerNameTextView)
        val rowNumberTextView: TextView = dialogView.findViewById(R.id.rowNumberTextView)
        val punnetsEditText: EditText = dialogView.findViewById(R.id.punnetsEditText)
        val priceTextView: TextView = dialogView.findViewById(R.id.priceTextView)

        // Create a SearchableSpinnerView for worker selection
        val workerSpinner = SearchableSpinnerView(requireContext())

        // Hide the worker name text view and add the spinner in its place
        workerNameTextView.visibility = View.GONE

        // Add the spinner to the dialog layout
        val parentLayout = workerNameTextView.parent as ViewGroup
        val index = parentLayout.indexOfChild(workerNameTextView)
        parentLayout.addView(workerSpinner, index)

        // Hide row number initially (will show when worker is selected)
        rowNumberTextView.visibility = View.GONE

        val formattedPrice = String.format(Locale.getDefault(), "%.2f", viewModel.punnetPrice.value)
        priceTextView.text = "Ціна за пінетку: $formattedPrice₴"
        punnetsEditText.setText("10") // Default value

        // Variables to store selected worker and assignment info
        var selectedWorker: Worker? = null
        var selectedRowNumber: Int? = null

        // Create the dialog instance
        var dialog: AlertDialog? = null

        // Load workers and setup spinner
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Get all active workers from repository
                when (val result = app.repositoryProvider.workerRepository.getAll().first()) {
                    is com.example.berryharvest.data.repository.Result.Success -> {
                        val workers = result.data.filter { !it.isDeleted } // Only show active workers

                        if (workers.isEmpty()) {
                            Toast.makeText(requireContext(), "Немає доступних працівників", Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        // Convert workers to searchable items
                        val searchableWorkers = workers.map { it.toSearchableItem() }

                        // Setup the spinner
                        workerSpinner.setAdapter(searchableWorkers)
                        workerSpinner.setOnItemSelectedListener { selectedItem ->
                            val workerItem = selectedItem as com.example.berryharvest.ui.common.WorkerSearchableItem
                            selectedWorker = workerItem.worker

                            // Check worker assignment and handle accordingly
                            checkWorkerAssignmentForManualEntry(
                                workerItem.worker,
                                rowNumberTextView,
                                dialog
                            ) { worker, rowNumber ->
                                // Callback when assignment is resolved
                                selectedWorker = worker
                                selectedRowNumber = rowNumber

                                // Update UI to show the assignment
                                rowNumberTextView.text = "Ряд: $rowNumber"
                                rowNumberTextView.visibility = View.VISIBLE
                            }
                        }
                    }
                    is com.example.berryharvest.data.repository.Result.Error -> {
                        Toast.makeText(requireContext(), "Помилка завантаження працівників: ${result.message}", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    else -> {
                        Toast.makeText(requireContext(), "Завантаження працівників...", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Помилка: ${e.message}", Toast.LENGTH_SHORT).show()
                return@launch
            }
        }

        dialog = AlertDialog.Builder(requireContext())
            .setTitle("Ручне введення збору")
            .setView(dialogView)
            .setPositiveButton("Зберегти") { _, _ ->
                val worker = selectedWorker
                val rowNumber = selectedRowNumber
                val numOfPunnetsText = punnetsEditText.text.toString()
                val numOfPunnets = numOfPunnetsText.toIntOrNull()

                when {
                    worker == null -> {
                        Toast.makeText(activity, "Оберіть працівника", Toast.LENGTH_SHORT).show()
                    }
                    rowNumber == null || rowNumber == -1 -> {
                        Toast.makeText(activity, "Працівник не призначений на ряд", Toast.LENGTH_SHORT).show()
                    }
                    numOfPunnetsText.isBlank() -> {
                        Toast.makeText(activity, "Введіть кількість пінеток", Toast.LENGTH_SHORT).show()
                    }
                    numOfPunnets == null -> {
                        Toast.makeText(activity, "Невірний формат кількості", Toast.LENGTH_SHORT).show()
                    }
                    numOfPunnets <= 0 -> {
                        Toast.makeText(activity, "Кількість пінеток повинна бути більше 0", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        // Save the gather data
                        viewModel.saveGatherData(worker._id, rowNumber, numOfPunnets)
                        Toast.makeText(activity, "Збір успішно додано", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Скасувати", null)
            .create()

        // Handle dialog dismiss to cleanup spinner
        dialog.setOnDismissListener {
            workerSpinner.hideDropdownForced()
        }

        dialog.show()
    }

    /**
     * Check worker assignment for manual entry and handle assignment flow
     */
    private fun checkWorkerAssignmentForManualEntry(
        worker: Worker,
        rowNumberTextView: TextView,
        parentDialog: AlertDialog?,
        onAssignmentResolved: (Worker, Int) -> Unit
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Check if worker has assignment
                when (val assignmentResult = app.repositoryProvider.assignmentRepository.getWorkerAssignment(worker._id)) {
                    is com.example.berryharvest.data.repository.Result.Success -> {
                        val assignment = assignmentResult.data
                        if (assignment != null && assignment.rowNumber != null) {
                            // Worker has assignment - proceed normally
                            onAssignmentResolved(worker, assignment.rowNumber!!)
                        } else {
                            // Worker has no assignment - show assignment dialog
                            rowNumberTextView.text = "Працівник не призначений на ряд"
                            rowNumberTextView.visibility = View.VISIBLE

                            // Temporarily hide the parent dialog and show assignment dialog
                            parentDialog?.hide()
                            showAssignWorkerToRowDialogForManualEntry(worker, parentDialog) { assignedWorker, assignedRowNumber ->
                                // After successful assignment, resolve and show parent dialog again
                                onAssignmentResolved(assignedWorker, assignedRowNumber)
                                parentDialog?.show()
                            }
                        }
                    }
                    is com.example.berryharvest.data.repository.Result.Error -> {
                        rowNumberTextView.text = "Помилка перевірки призначення"
                        rowNumberTextView.visibility = View.VISIBLE
                        Log.e("GatherFragment", "Error checking assignment: ${assignmentResult.message}")
                        Toast.makeText(requireContext(), "Помилка перевірки призначення: ${assignmentResult.message}", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        rowNumberTextView.text = "Перевірка призначення..."
                        rowNumberTextView.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                rowNumberTextView.text = "Помилка: ${e.message}"
                rowNumberTextView.visibility = View.VISIBLE
                Log.e("GatherFragment", "Error checking worker assignment", e)
                Toast.makeText(requireContext(), "Помилка перевірки призначення: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Show assignment dialog specifically for manual entry flow
     */
    private fun showAssignWorkerToRowDialogForManualEntry(
        worker: Worker,
        parentDialog: AlertDialog?,
        onAssignmentComplete: (Worker, Int) -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_move_or_delete_worker, null)
        val newRowEditText = dialogView.findViewById<EditText>(R.id.newRowEditText)
        val moveButton = dialogView.findViewById<Button>(R.id.moveButton)
        val deleteButton = dialogView.findViewById<Button>(R.id.deleteButton)

        moveButton.text = "Призначити"
        deleteButton.text = "Скасувати"

        val assignmentDialog = AlertDialog.Builder(requireContext())
            .setTitle("Працівник не призначений на ряд")
            .setMessage("Працівник ${worker.fullName} [${worker.sequenceNumber}] не призначений на жоден ряд. Призначте ряд, щоб продовжити:")
            .setView(dialogView)
            .setCancelable(false)
            .create()

        moveButton.setOnClickListener {
            val rowNumberText = newRowEditText.text.toString().trim()
            val rowNumber = rowNumberText.toIntOrNull()

            if (rowNumberText.isEmpty() || rowNumber == null || rowNumber !in 1..999) {
                Toast.makeText(requireContext(), "Введіть корректний номер ряду (1-999)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.assignWorkerToRow(worker._id, rowNumber).observe(viewLifecycleOwner) { success ->
                if (success) {
                    assignmentDialog.dismiss()
                    Toast.makeText(requireContext(), "Працівник успішно призначений на ряд $rowNumber", Toast.LENGTH_SHORT).show()

                    // Notify that assignment is complete
                    onAssignmentComplete(worker, rowNumber)
                }
            }
        }

        deleteButton.setOnClickListener {
            assignmentDialog.dismiss()
            // Show the parent dialog again if user cancels
            parentDialog?.show()
        }

        // Handle back button or outside touch
        assignmentDialog.setOnDismissListener {
            // If dismissed without assignment, show parent dialog again
            parentDialog?.show()
        }

        assignmentDialog.show()
    }

    private fun showGatherSummaryDialog(summaryData: GatherSummaryData) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_gather_summary, null)

        // Find views
        val workerNameSummaryTextView: TextView = dialogView.findViewById(R.id.workerNameSummaryTextView)
        val totalPunnetsTextView: TextView = dialogView.findViewById(R.id.totalPunnetsTextView)
        val closeSummaryButton: Button = dialogView.findViewById(R.id.closeSummaryButton)

        // Set data
        workerNameSummaryTextView.text = summaryData.workerName
        totalPunnetsTextView.text = "${summaryData.totalPunnetsToday} пінеток"

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()

        // Set up close button
        closeSummaryButton.setOnClickListener {
            dialog.dismiss()
        }

        // Clear summary when dialog is dismissed
        dialog.setOnDismissListener {
            viewModel.clearGatherSummary()
        }

        dialog.show()
    }

    /**
     * Check worker assignment and update the dialog with row information
     */
    private fun checkWorkerAssignmentAndUpdateDialog(
        worker: Worker,
        rowNumberTextView: TextView,
        onRowFound: (Int) -> Unit
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Check if worker has assignment
                when (val assignmentResult = app.repositoryProvider.assignmentRepository.getWorkerAssignment(worker._id)) {
                    is com.example.berryharvest.data.repository.Result.Success -> {
                        val assignment = assignmentResult.data
                        if (assignment != null && assignment.rowNumber != null) {
                            // Worker has assignment
                            rowNumberTextView.text = "Ряд: ${assignment.rowNumber}"
                            rowNumberTextView.visibility = View.VISIBLE
                            onRowFound(assignment.rowNumber!!)
                        } else {
                            // Worker has no assignment
                            rowNumberTextView.text = "Працівник не призначений на ряд"
                            rowNumberTextView.visibility = View.VISIBLE
                            onRowFound(-1) // Indicate no assignment
                        }
                    }
                    is com.example.berryharvest.data.repository.Result.Error -> {
                        rowNumberTextView.text = "Помилка перевірки призначення"
                        rowNumberTextView.visibility = View.VISIBLE
                        Log.e("GatherFragment", "Error checking assignment: ${assignmentResult.message}")
                    }
                    else -> {
                        rowNumberTextView.text = "Перевірка призначення..."
                        rowNumberTextView.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                rowNumberTextView.text = "Помилка: ${e.message}"
                rowNumberTextView.visibility = View.VISIBLE
                Log.e("GatherFragment", "Error checking worker assignment", e)
            }
        }
    }

    private fun refreshData() {
        viewModel.refreshData()
    }
}