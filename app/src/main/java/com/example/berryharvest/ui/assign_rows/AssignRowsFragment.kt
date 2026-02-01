package com.example.berryharvest.ui.assign_rows

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.widget.*
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.BaseFragment
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.R
import com.example.berryharvest.data.model.Assignment
import com.example.berryharvest.data.model.Worker
import com.example.berryharvest.ui.common.SearchableSpinnerView
import com.example.berryharvest.ui.common.WorkerSearchableItem
import com.example.berryharvest.ui.common.toSearchableItem
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.realm.kotlin.ext.query
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

class AssignRowsFragment : BaseFragment() {
    private val TAG = "AssignRowsFragment"
    private val viewModel: AssignRowsViewModel by viewModels()

    private lateinit var rowNumberInputLayout: TextInputLayout
    private lateinit var rowEditText: TextInputEditText
    private lateinit var workerSearchView: SearchableSpinnerView
    private lateinit var assignButton: Button
    private lateinit var assignmentsRecyclerView: RecyclerView
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var rowCountTextView: TextView

    private var selectedWorker: Worker? = null
    private lateinit var assignmentAdapter: AssignmentAdapter

    // For filtering
    private var allAssignments = listOf<AssignmentGroup>()
    private var filteredAssignments = listOf<AssignmentGroup>()

    // Get row repository for row status updates
    private val app: BerryHarvestApplication by lazy { requireActivity().application as BerryHarvestApplication }
    private val rowRepository by lazy { app.repositoryProvider.rowRepository }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_assign_rows, container, false)

        // Setup UI components
        rowNumberInputLayout = view.findViewById(R.id.rowNumberInputLayout)
        rowEditText = view.findViewById(R.id.rowEditText)
        workerSearchView = view.findViewById(R.id.workerSearchView)
        assignButton = view.findViewById(R.id.assignButton)
        assignmentsRecyclerView = view.findViewById(R.id.assignmentsRecyclerView)
        loadingProgressBar = view.findViewById(R.id.loadingProgressBar)
        rowCountTextView = view.findViewById(R.id.rowCountTextView)

        setupUI()
        setupFiltering()
        setupWorkerSearch()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup observers with proper lifecycle awareness
        setupObservers()

        // Ensure data is loaded
        viewModel.ensureDataLoaded()
    }

    override fun onPause() {
        super.onPause()
        workerSearchView.hideDropdownForced()
        selectedWorker = null
    }

    override fun onResume() {
        super.onResume()
        workerSearchView.clearSelection()
        selectedWorker = null
        viewModel.ensureLoadingStateReset()
    }

    private fun setupUI() {
        assignButton.setOnClickListener {
            if (validateInputs()) {
                assignWorkerToRow()
            }
        }

        // Initialize adapter
        assignmentAdapter = createAssignmentAdapter()

        // Setup recycler view
        assignmentsRecyclerView.apply {
            visibility = View.VISIBLE
            adapter = assignmentAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        // Debug RecyclerView dimensions
        assignmentsRecyclerView.post {
            Log.d(TAG, "RecyclerView width: ${assignmentsRecyclerView.width}, " +
                    "height: ${assignmentsRecyclerView.height}, " +
                    "visibility: ${assignmentsRecyclerView.visibility == View.VISIBLE}")
        }
    }

    private fun setupFiltering() {
        rowEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val text = s.toString().trim()
                filterAssignments(text)
            }
        })
    }

    private fun filterAssignments(query: String) {
        if (query.isEmpty()) {
            filteredAssignments = allAssignments
            assignmentAdapter.submitList(filteredAssignments)
            updateRowCount(filteredAssignments.size)
            return
        }

        filteredAssignments = allAssignments.filter { assignmentGroup ->
            assignmentGroup.rowNumber.toString().contains(query)
        }

        assignmentAdapter.submitList(filteredAssignments)
        updateRowCount(filteredAssignments.size)
    }

    private fun updateRowCount(count: Int) {
        val total = allAssignments.size
        rowCountTextView.text = if (count < total) {
            "($count з $total)"
        } else {
            "($count)"
        }
    }

    private fun validateInputs(): Boolean {
        val rowNumberText = rowEditText.text.toString().trim()

        if (rowNumberText.isEmpty()) {
            Toast.makeText(requireContext(), "Введіть номер ряду", Toast.LENGTH_SHORT).show()
            rowEditText.requestFocus()
            return false
        }

        val rowNumber = rowNumberText.toIntOrNull()
        if (rowNumber == null || rowNumber !in 1..999) {
            Toast.makeText(requireContext(), "Введіть коректний номер ряду (1-999)", Toast.LENGTH_SHORT).show()
            rowEditText.requestFocus()
            return false
        }

        if (selectedWorker == null) {
            Toast.makeText(requireContext(), "Виберіть працівника", Toast.LENGTH_SHORT).show()
            workerSearchView.requestFocus()
            return false
        }

        return true
    }

    private fun createAssignmentAdapter(): AssignmentAdapter {
        return AssignmentAdapter(
            onMoveWorkerClick = { assignment -> showMoveWorkerDialog(assignment) },
            onRemoveRowClick = { rowNumber -> showRemoveRowConfirmation(rowNumber) }
        )
    }

    private fun setupObservers() {
        // Observe assignments with proper lifecycle handling
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.assignments.collect { assignments ->
                    Log.d(TAG, "Received ${assignments.size} assignment groups")

                    allAssignments = assignments
                    val query = rowEditText.text?.toString()?.trim() ?: ""

                    if (query.isNotEmpty()) {
                        filterAssignments(query)
                    } else {
                        filteredAssignments = assignments
                        assignmentAdapter.submitList(ArrayList(assignments))
                        updateRowCount(assignments.size)
                    }
                }
            }
        }

        // Observe worker details
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.workerDetails.collect { workerMap ->
                    Log.d(TAG, "Updating worker details: ${workerMap.size} workers")
                    assignmentAdapter.updateWorkerDetails(workerMap)
                }
            }
        }

        // Observe loading state
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isLoading.collect { isLoading ->
                    loadingProgressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                    assignButton.isEnabled = !isLoading
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
                        Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                        viewModel.clearError()
                    }
                }
            }
        }
    }

    private fun setupWorkerSearch() {
        workerSearchView.clearSelection()
        selectedWorker = null

        var adapterInitialized = false

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allWorkers.collect { workers ->
                    if (workers.isEmpty()) {
                        Log.d(TAG, "Worker list is empty, waiting for data")
                        return@collect
                    }

                    Log.d(TAG, "Setting up worker search with ${workers.size} workers")

                    val searchableItems = workers.map { it.toSearchableItem() }

                    if (!adapterInitialized || searchableItems.isNotEmpty()) {
                        workerSearchView.setAdapter(searchableItems)
                        adapterInitialized = true
                        workerSearchView.clearSelection()
                        selectedWorker = null
                    }

                    workerSearchView.setOnItemSelectedListener { searchableItem ->
                        val workerItem = searchableItem as WorkerSearchableItem
                        selectedWorker = workerItem.worker
                        Log.d(TAG, "Selected worker: ${workerItem.worker.fullName}")
                    }
                }
            }
        }
    }

    private fun assignWorkerToRow() {
        val rowNumberText = rowEditText.text.toString().trim()
        val rowNumber = rowNumberText.toIntOrNull() ?: return
        val worker = selectedWorker ?: return

        // Check if target row is collected before assigning
        checkRowCollectionStatus(rowNumber) { isCollected ->
            if (isCollected) {
                showCollectedRowWarning(rowNumber) {
                    checkExistingAssignment(worker)
                }
            } else {
                checkExistingAssignment(worker)
            }
        }
    }

    /**
     * Check if a row is already collected
     */
    private fun checkRowCollectionStatus(rowNumber: Int, callback: (Boolean) -> Unit) {
        lifecycleScope.launch {
            try {
                // Find row by row number
                val realm = app.getRealmInstance()
                val row = realm.query<com.example.berryharvest.data.model.Row>(
                    "rowNumber == $0 AND isDeleted == false", rowNumber
                ).first().find()

                callback(row?.isCollected == true)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking row collection status", e)
                callback(false) // Assume not collected on error
            }
        }
    }

    /**
     * Show warning when trying to assign worker to collected row
     */
    private fun showCollectedRowWarning(rowNumber: Int, onProceed: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle("Ряд вже зібраний")
            .setMessage("Ряд $rowNumber вже позначений як зібраний. Ви впевнені, що хочете призначити туди працівника?")
            .setPositiveButton("Так, призначити") { _, _ -> onProceed() }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun checkExistingAssignment(worker: Worker) {
        lifecycleScope.launch {
            loadingProgressBar.visibility = View.VISIBLE

            try {
                val result = viewModel.checkWorkerAssignment(worker._id)

                when (result) {
                    is com.example.berryharvest.data.repository.Result.Success -> {
                        val rowNumber = result.data
                        if (rowNumber > 0) {
                            showReassignConfirmationDialog(worker, rowNumber)
                        } else {
                            proceedWithAssignment(worker)
                        }
                    }
                    is com.example.berryharvest.data.repository.Result.Error -> {
                        Toast.makeText(requireContext(), "Помилка: ${result.message}", Toast.LENGTH_SHORT).show()
                    }
                    is com.example.berryharvest.data.repository.Result.Loading -> {
                        // Loading is handled by progress bar
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking assignment", e)
                Toast.makeText(requireContext(), "Помилка: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                loadingProgressBar.visibility = View.GONE
            }
        }
    }

    private fun showReassignConfirmationDialog(worker: Worker, currentRow: Int) {
        val newRowNumber = rowEditText.text.toString().toInt()

        if (currentRow == newRowNumber) {
            Toast.makeText(requireContext(),
                "Працівник вже на ряду $currentRow",
                Toast.LENGTH_SHORT).show()
            return
        }

        // Create a custom dialog view programmatically
        val dialogView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        // Add message text
        val messageText = TextView(requireContext()).apply {
            text = "[${worker.sequenceNumber}] ${worker.fullName} зараз на ряду $currentRow.\nПеремістити на ряд $newRowNumber?"
            textSize = 16f
            setTextColor(resources.getColor(android.R.color.black, null))
            setPadding(0, 0, 0, 24)
        }
        dialogView.addView(messageText)

        // Add checkbox for marking row as gathered
        val checkBox = CheckBox(requireContext()).apply {
            text = "Позначити ряд $currentRow як зібраний"
            setTextColor(resources.getColor(android.R.color.black, null))
            setPadding(0, 16, 0, 0)
        }
        dialogView.addView(checkBox)

        AlertDialog.Builder(requireContext())
            .setTitle("Зміна призначення")
            .setView(dialogView)
            .setPositiveButton("Так") { _, _ ->
                val markRowGathered = checkBox.isChecked
                proceedWithReassignmentAndRowMarking(worker, currentRow, newRowNumber, markRowGathered)
            }
            .setNegativeButton("Ні", null)
            .show()
    }

    private fun proceedWithReassignmentAndRowMarking(worker: Worker, currentRow: Int, newRowNumber: Int, markPreviousRowGathered: Boolean) {
        loadingProgressBar.visibility = View.VISIBLE
        assignButton.isEnabled = false

        lifecycleScope.launch {
            try {
                // First mark previous row as gathered if requested
                if (markPreviousRowGathered) {
                    markRowAsGathered(currentRow)
                }

                // Then proceed with the assignment
                proceedWithAssignment(worker)
            } catch (e: Exception) {
                Log.e(TAG, "Error in reassignment with row marking", e)
                Toast.makeText(requireContext(), "Помилка: ${e.message}", Toast.LENGTH_LONG).show()
                loadingProgressBar.visibility = View.GONE
                assignButton.isEnabled = true
            }
        }
    }

    private fun proceedWithAssignment(worker: Worker) {
        val rowNumber = rowEditText.text.toString().toInt()

        loadingProgressBar.visibility = View.VISIBLE
        assignButton.isEnabled = false

        lifecycleScope.launch {
            try {
                val result = viewModel.assignWorkerToRow(worker._id, rowNumber)

                when (result) {
                    is com.example.berryharvest.data.repository.Result.Success -> {
                        Toast.makeText(requireContext(), "Призначено на ряд $rowNumber", Toast.LENGTH_SHORT).show()

                        workerSearchView.clearSelection()
                        selectedWorker = null
                    }
                    is com.example.berryharvest.data.repository.Result.Error -> {
                        Toast.makeText(requireContext(), "Помилка: ${result.message}", Toast.LENGTH_SHORT).show()
                    }
                    is com.example.berryharvest.data.repository.Result.Loading -> {
                        // Loading is handled by progress bar
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error assigning worker", e)
                Toast.makeText(requireContext(), "Помилка: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                loadingProgressBar.visibility = View.GONE
                assignButton.isEnabled = true
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showMoveWorkerDialog(assignment: Assignment) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_move_or_delete_worker, null)
        val newRowInputLayout = dialogView.findViewById<TextInputLayout>(R.id.newRowInputLayout)
        val newRowEditText = dialogView.findViewById<EditText>(R.id.newRowEditText)
        val markRowGatheredCheckBox = dialogView.findViewById<CheckBox>(R.id.markRowGatheredCheckBox)
        val moveButton = dialogView.findViewById<Button>(R.id.moveButton)
        val deleteButton = dialogView.findViewById<Button>(R.id.deleteButton)

        // Show checkbox only if worker is currently assigned to a row
        if (assignment.rowNumber > 0) {
            markRowGatheredCheckBox.visibility = View.VISIBLE
            markRowGatheredCheckBox.text = "Позначити ряд ${assignment.rowNumber} як зібраний"
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Змінити призначення")
            .setView(dialogView)
            .create()

        moveButton.setOnClickListener {
            val newRowText = newRowEditText.text.toString().trim()

            if (newRowText.isEmpty()) {
                Toast.makeText(requireContext(), "Введіть новий номер ряду", Toast.LENGTH_SHORT).show()
                newRowEditText.requestFocus()
                return@setOnClickListener
            }

            val newRowNumber = newRowText.toIntOrNull()
            if (newRowNumber == null || newRowNumber !in 1..999) {
                Toast.makeText(requireContext(), "Введіть коректний номер ряду (1-999)", Toast.LENGTH_SHORT).show()
                newRowEditText.requestFocus()
                return@setOnClickListener
            }

            // Check if target row is collected
            checkRowCollectionStatus(newRowNumber) { isCollected ->
                if (isCollected) {
                    showCollectedRowWarning(newRowNumber) {
                        proceedWithMove(assignment, newRowNumber, markRowGatheredCheckBox.isChecked, dialog)
                    }
                } else {
                    proceedWithMove(assignment, newRowNumber, markRowGatheredCheckBox.isChecked, dialog)
                }
            }
        }

        deleteButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Вилучити працівника з ряду?")
                .setPositiveButton("Так") { _, _ ->
                    val shouldMarkGathered = markRowGatheredCheckBox.isChecked
                    loadingProgressBar.visibility = View.VISIBLE
                    dialog.dismiss()

                    lifecycleScope.launch {
                        try {
                            // First mark row as gathered if requested
                            if (shouldMarkGathered) {
                                markRowAsGathered(assignment.rowNumber)
                            }

                            val result = viewModel.deleteWorkerAssignment(assignment._id)

                            when (result) {
                                is com.example.berryharvest.data.repository.Result.Success -> {
                                    Toast.makeText(requireContext(), "Працівник вилучений", Toast.LENGTH_SHORT).show()
                                }
                                is com.example.berryharvest.data.repository.Result.Error -> {
                                    Toast.makeText(requireContext(), "Помилка: ${result.message}", Toast.LENGTH_SHORT).show()
                                }
                                is com.example.berryharvest.data.repository.Result.Loading -> {
                                    // Loading is handled by progress bar
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error deleting worker assignment", e)
                            Toast.makeText(requireContext(), "Помилка: ${e.message}", Toast.LENGTH_SHORT).show()
                        } finally {
                            loadingProgressBar.visibility = View.GONE
                        }
                    }
                }
                .setNegativeButton("Ні") { _, _ -> dialog.dismiss() }
                .show()
        }

        dialog.show()
    }

    private fun proceedWithMove(assignment: Assignment, newRowNumber: Int, markPreviousRowGathered: Boolean, dialog: AlertDialog) {
        loadingProgressBar.visibility = View.VISIBLE
        dialog.dismiss()

        lifecycleScope.launch {
            try {
                // First mark previous row as gathered if requested
                if (markPreviousRowGathered) {
                    markRowAsGathered(assignment.rowNumber)
                }

                val result = viewModel.moveWorkerToRow(assignment._id, newRowNumber)

                when (result) {
                    is com.example.berryharvest.data.repository.Result.Success -> {
                        Toast.makeText(requireContext(), "Переміщено на ряд $newRowNumber", Toast.LENGTH_SHORT).show()

                        val currentQuery = rowEditText.text?.toString()?.trim() ?: ""
                        if (currentQuery.isNotEmpty() && !newRowNumber.toString().contains(currentQuery)) {
                            rowEditText.text?.clear()
                        }
                    }
                    is com.example.berryharvest.data.repository.Result.Error -> {
                        Toast.makeText(requireContext(), "Помилка: ${result.message}", Toast.LENGTH_SHORT).show()
                    }
                    is com.example.berryharvest.data.repository.Result.Loading -> {
                        // Loading is handled by progress bar
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error moving worker", e)
                Toast.makeText(requireContext(), "Помилка: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                loadingProgressBar.visibility = View.GONE
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
                    }
                    is com.example.berryharvest.data.repository.Result.Error -> {
                        Log.e(TAG, "Error marking row as gathered: ${result.message}")
                    }
                    is com.example.berryharvest.data.repository.Result.Loading -> {
                        // Loading state
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding row to mark as gathered", e)
        }
    }

    private fun showRemoveRowConfirmation(rowNumber: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle("Видалити призначення")
            .setMessage("Видалити всі призначення з ряду $rowNumber?")
            .setPositiveButton("Так") { _, _ ->
                loadingProgressBar.visibility = View.VISIBLE

                lifecycleScope.launch {
                    try {
                        val result = viewModel.deleteEntireRow(rowNumber)

                        when (result) {
                            is com.example.berryharvest.data.repository.Result.Success -> {
                                Toast.makeText(requireContext(), "Ряд $rowNumber видалено", Toast.LENGTH_SHORT).show()
                            }
                            is com.example.berryharvest.data.repository.Result.Error -> {
                                Toast.makeText(requireContext(), "Помилка: ${result.message}", Toast.LENGTH_SHORT).show()
                            }
                            is com.example.berryharvest.data.repository.Result.Loading -> {
                                // Loading is handled by progress bar
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deleting row", e)
                        Toast.makeText(requireContext(), "Помилка: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        loadingProgressBar.visibility = View.GONE
                    }
                }
            }
            .setNegativeButton("Ні", null)
            .show()
    }
}