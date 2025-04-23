package com.example.berryharvest.ui.assign_rows

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AssignRowsFragment : BaseFragment() {
    private val TAG = "AssignRowsFragment"
    private val viewModel: AssignRowsViewModel by viewModels()

    private lateinit var rowNumberInputLayout: TextInputLayout
    private lateinit var rowEditText: TextInputEditText
    private lateinit var searchInputLayout: TextInputLayout
    private lateinit var searchRowEditText: TextInputEditText
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_assign_rows, container, false)

        // Setup UI components
        rowNumberInputLayout = view.findViewById(R.id.rowNumberInputLayout)
        rowEditText = view.findViewById(R.id.rowEditText)
        searchInputLayout = view.findViewById(R.id.searchInputLayout)
        searchRowEditText = view.findViewById(R.id.searchRowEditText)
        workerSearchView = view.findViewById(R.id.workerSearchView)
        assignButton = view.findViewById(R.id.assignButton)
        assignmentsRecyclerView = view.findViewById(R.id.assignmentsRecyclerView)
        loadingProgressBar = view.findViewById(R.id.loadingProgressBar)
        rowCountTextView = view.findViewById(R.id.rowCountTextView)

        setupUI()
        setupValidation()
        setupSearch()
        setupWorkerSearch()
        observeViewModel()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup initial visibility to handle first load better
        loadingProgressBar.visibility = View.VISIBLE

        // Observe when data is initialized
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.dataInitialized.collect { initialized ->
                if (initialized) {
                    // Only hide loading when data is actually initialized
                    loadingProgressBar.visibility = View.GONE
                }
            }
        }

        // Make sure loading state is observed early
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                loadingProgressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                assignButton.isEnabled = !isLoading
            }
        }

        // Force initial data load when the view is created
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.loadInitialData()

            // Add safety mechanism to ensure loading state isn't stuck
            delay(1500) // Give enough time for normal loading to complete
            viewModel.ensureLoadingStateReset()
        }
    }

    override fun onPause() {
        super.onPause()
        workerSearchView.hideDropdownForced()
        // Clear selection when pausing fragment
        selectedWorker = null
    }

    override fun onResume() {
        super.onResume()
        // Reset the searchable spinner completely
        workerSearchView.clearSelection()
        selectedWorker = null

        // Ensure loading state is reset if it gets stuck
        viewModel.ensureLoadingStateReset()
    }

    private fun setupUI() {
        assignButton.setOnClickListener {
            if (validateInputs()) {
                assignWorkerToRow()
            }
        }

        // Initialize adapter
        val adapter = createAssignmentAdapter()

        // Setup recycler view
        assignmentsRecyclerView.adapter = adapter
        assignmentsRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Add dividers between items for better readability in compact layout
        val dividerItemDecoration = DividerItemDecoration(
            assignmentsRecyclerView.context,
            (assignmentsRecyclerView.layoutManager as LinearLayoutManager).orientation
        )
        assignmentsRecyclerView.addItemDecoration(dividerItemDecoration)

        assignmentAdapter = adapter
    }

    private fun setupValidation() {
        // Set focus change listener for validation
        rowEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validateRowNumber(rowEditText.text.toString())
            } else {
                rowNumberInputLayout.error = null
            }
        }
    }

    private fun setupSearch() {
        searchRowEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                filterAssignments(s.toString())
            }
        })
    }

    private fun filterAssignments(query: String) {
        if (query.isEmpty()) {
            // No query, show all assignments
            filteredAssignments = allAssignments
            assignmentAdapter.submitList(filteredAssignments)
            updateRowCount(filteredAssignments.size)
            return
        }

        // Filter by row number
        filteredAssignments = allAssignments.filter { assignmentGroup ->
            assignmentGroup.rowNumber.toString().contains(query)
        }

        // Update the adapter with filtered results
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

    private fun validateRowNumber(rowNumberText: String): Boolean {
        if (rowNumberText.isEmpty()) {
            rowNumberInputLayout.error = "Введіть номер"
            return false
        }

        val rowNumber = rowNumberText.toIntOrNull()
        if (rowNumber == null || rowNumber !in 1..999) {
            rowNumberInputLayout.error = "Введіть номер (1-999)"
            return false
        }

        rowNumberInputLayout.error = null
        return true
    }

    private fun validateInputs(): Boolean {
        val rowNumberValid = validateRowNumber(rowEditText.text.toString())

        if (selectedWorker == null) {
            Toast.makeText(requireContext(), "Виберіть працівника", Toast.LENGTH_SHORT).show()
            return false
        }

        return rowNumberValid
    }

    private fun createAssignmentAdapter(): AssignmentAdapter {
        return AssignmentAdapter(
            onMoveWorkerClick = { assignment -> showMoveWorkerDialog(assignment) },
            onRemoveRowClick = { rowNumber -> showRemoveRowConfirmation(rowNumber) }
        )
    }

    private fun observeViewModel() {
        // Observe assignments
        launchWhenStarted("assignments-flow") {
            viewModel.assignments.collect { assignments ->
                Log.d(TAG, "Received ${assignments.size} assignment groups")

                // Store all assignments for filtering
                allAssignments = assignments

                // If there's a search query, apply filtering
                val query = searchRowEditText.text.toString()
                if (query.isNotEmpty()) {
                    filterAssignments(query)
                } else {
                    // Otherwise show all and update adapter
                    filteredAssignments = assignments
                    assignmentAdapter.submitList(ArrayList(assignments))
                    updateRowCount(assignments.size)
                }
            }
        }

        // Observe worker details
        launchWhenStarted("worker-details") {
            viewModel.workerDetails.collect { workerMap ->
                assignmentAdapter.updateWorkerDetails(workerMap)
            }
        }

        // Observe errors
        launchWhenStarted("error-flow") {
            viewModel.error.collect { errorMessage ->
                errorMessage?.let {
                    Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                    viewModel.clearError()
                }
            }
        }

        // Observe loading state
        launchWhenStarted("loading-state") {
            viewModel.isLoading.collect { isLoading ->
                loadingProgressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                assignButton.isEnabled = !isLoading
            }
        }
    }

    private fun setupWorkerSearch() {
        // Reset the spinner state first
        workerSearchView.clearSelection()
        selectedWorker = null

        // Set up a flag to track if adapter has been initialized
        var adapterInitialized = false

        viewLifecycleOwner.lifecycleScope.launch {
            // Observe all workers for search
            viewModel.allWorkers.collect { workers ->
                if (workers.isEmpty()) {
                    Log.d(TAG, "Worker list is empty, waiting for data")
                    return@collect
                }

                Log.d(TAG, "Setting up worker search with ${workers.size} workers")

                // Create a fresh adapter each time but only if we have workers
                val searchableItems = workers.map { it.toSearchableItem() }

                // Only set adapter if it wasn't already set with non-empty data
                if (!adapterInitialized || searchableItems.isNotEmpty()) {
                    workerSearchView.setAdapter(searchableItems)
                    adapterInitialized = true

                    // Reset selection when adapter changes
                    workerSearchView.clearSelection()
                    selectedWorker = null
                }

                // Set the listener each time to ensure it's registered
                workerSearchView.setOnItemSelectedListener { searchableItem ->
                    val workerItem = searchableItem as WorkerSearchableItem
                    selectedWorker = workerItem.worker
                    Log.d(TAG, "Selected worker: ${workerItem.worker.fullName}")
                }
            }
        }
    }

    private fun assignWorkerToRow() {
        val rowNumberText = rowEditText.text.toString().trim()
        val rowNumber = rowNumberText.toIntOrNull() ?: return
        val worker = selectedWorker ?: return

        // Check if worker is already assigned to another row
        checkExistingAssignment(worker)
    }

    private fun checkExistingAssignment(worker: Worker) {
        lifecycleScope.launch {
            loadingProgressBar.visibility = View.VISIBLE

            try {
                // Use ViewModel to check existing assignment
                val result = viewModel.checkWorkerAssignment(worker._id)

                when (result) {
                    is com.example.berryharvest.data.repository.Result.Success -> {
                        val rowNumber = result.data
                        if (rowNumber > 0) {
                            // Worker already assigned to a row, show confirmation dialog
                            showReassignConfirmationDialog(worker, rowNumber)
                        } else {
                            // Worker not assigned, proceed directly
                            proceedWithAssignment(worker)
                        }
                    }
                    is com.example.berryharvest.data.repository.Result.Error -> {
                        Toast.makeText(requireContext(), "Помилка: ${result.message}", Toast.LENGTH_SHORT).show()
                    }
                    is com.example.berryharvest.data.repository.Result.Loading -> {
                        // Loading is already handled
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
        val rowNumber = rowEditText.text.toString().toInt()

        // If trying to assign to the same row, just show a message
        if (currentRow == rowNumber) {
            Toast.makeText(requireContext(),
                "Працівник вже на ряду $currentRow",
                Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Зміна призначення")
            .setMessage("[${worker.sequenceNumber}] ${worker.fullName} вже на ряду $currentRow. Змінити на ряд $rowNumber?")
            .setPositiveButton("Так") { _, _ ->
                proceedWithAssignment(worker)
            }
            .setNegativeButton("Ні", null)
            .show()
    }

    private fun proceedWithAssignment(worker: Worker) {
        val rowNumber = rowEditText.text.toString().toInt()

        // Show loading immediately
        loadingProgressBar.visibility = View.VISIBLE
        assignButton.isEnabled = false

        lifecycleScope.launch {
            try {
                // Use ViewModel to assign worker to row
                val result = viewModel.assignWorkerToRow(worker._id, rowNumber)

                when (result) {
                    is com.example.berryharvest.data.repository.Result.Success -> {
                        Toast.makeText(requireContext(), "Призначено на ряд $rowNumber", Toast.LENGTH_SHORT).show()

                        // Clear selection
                        workerSearchView.clearSelection()
                        selectedWorker = null

                        // Clear row number
                        // rowEditText.text?.clear()
                        // rowNumberInputLayout.error = null

                        // If searching for this row, make sure it stays visible
                        val searchQuery = searchRowEditText.text.toString()
                        if (searchQuery.isNotEmpty() && !rowNumber.toString().contains(searchQuery)) {
                            // Clear search to show all rows including the new assignment
                            searchRowEditText.text?.clear()
                        }
                    }
                    is com.example.berryharvest.data.repository.Result.Error -> {
                        Toast.makeText(requireContext(), "Помилка: ${result.message}", Toast.LENGTH_SHORT).show()
                    }
                    is com.example.berryharvest.data.repository.Result.Loading -> {
                        // Loading is already handled
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

    private fun showMoveWorkerDialog(assignment: Assignment) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_move_or_delete_worker, null)
        val newRowInputLayout = dialogView.findViewById<TextInputLayout>(R.id.newRowInputLayout)
        val newRowEditText = dialogView.findViewById<EditText>(R.id.newRowEditText)
        val moveButton = dialogView.findViewById<Button>(R.id.moveButton)
        val deleteButton = dialogView.findViewById<Button>(R.id.deleteButton)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Змінити призначення")
            .setView(dialogView)
            .create()

        moveButton.setOnClickListener {
            val newRowText = newRowEditText.text.toString().trim()

            // Validate row number
            if (newRowText.isEmpty()) {
                newRowInputLayout.error = "Введіть номер"
                return@setOnClickListener
            }

            val newRowNumber = newRowText.toIntOrNull()
            if (newRowNumber == null || newRowNumber !in 1..999) {
                newRowInputLayout.error = "Введіть номер (1-999)"
                return@setOnClickListener
            }

            // Valid input, clear error
            newRowInputLayout.error = null

            // Show loading
            loadingProgressBar.visibility = View.VISIBLE
            dialog.dismiss()

            // Use ViewModel to move worker
            lifecycleScope.launch {
                try {
                    val result = viewModel.moveWorkerToRow(assignment._id, newRowNumber)

                    when (result) {
                        is com.example.berryharvest.data.repository.Result.Success -> {
                            Toast.makeText(requireContext(), "Переміщено на ряд $newRowNumber", Toast.LENGTH_SHORT).show()

                            // If searching, make sure the row stays visible
                            val searchQuery = searchRowEditText.text.toString()
                            if (searchQuery.isNotEmpty() && !newRowNumber.toString().contains(searchQuery)) {
                                // Clear search to show all rows including the moved assignment
                                searchRowEditText.text?.clear()
                            }
                        }
                        is com.example.berryharvest.data.repository.Result.Error -> {
                            Toast.makeText(requireContext(), "Помилка: ${result.message}", Toast.LENGTH_SHORT).show()
                        }
                        is com.example.berryharvest.data.repository.Result.Loading -> {
                            // Loading is already handled
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

        deleteButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Вилучити працівника з ряду?")
                .setPositiveButton("Так") { _, _ ->
                    // Show loading
                    loadingProgressBar.visibility = View.VISIBLE
                    dialog.dismiss()

                    // Direct delete operation
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val app = requireActivity().application as BerryHarvestApplication
                            val realm = app.getRealmInstance()

                            // Store the assignment ID
                            val assignmentId = assignment._id

                            // Quick, focused transaction
                            realm.write {
                                query<Assignment>("_id == $0", assignmentId)
                                    .first().find()?.let {
                                        delete(it)
                                    }
                            }

                            withContext(Dispatchers.Main) {
                                if (isAdded) {
                                    loadingProgressBar.visibility = View.GONE
                                    Toast.makeText(requireContext(), "Працівник вилучений", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error deleting worker assignment", e)
                            withContext(Dispatchers.Main) {
                                if (isAdded) {
                                    loadingProgressBar.visibility = View.GONE
                                    Toast.makeText(requireContext(), "Помилка: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
                .setNegativeButton("Ні") { _, _ -> dialog.dismiss() }
                .show()
        }

        dialog.show()
    }

    private fun showRemoveRowConfirmation(rowNumber: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle("Видалити рядок")
            .setMessage("Видалити ряд $rowNumber та всі призначення?")
            .setPositiveButton("Так") { _, _ ->
                // Show loading
                loadingProgressBar.visibility = View.VISIBLE

                // Use ViewModel to delete row
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
                                // Loading is already handled
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

