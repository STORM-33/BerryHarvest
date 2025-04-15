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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AssignRowsFragment : Fragment() {
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

        // Force initial data load when the view is created
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.loadInitialData()
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
        viewLifecycleOwner.lifecycleScope.launch {
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
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.workerDetails.collect { workerMap ->
                assignmentAdapter.updateWorkerDetails(workerMap)
            }
        }

        // Observe errors
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.error.collect { errorMessage ->
                errorMessage?.let {
                    Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                    viewModel.clearError()
                }
            }
        }

        // Observe loading state
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                loadingProgressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                assignButton.isEnabled = !isLoading
            }
        }
    }

    private fun setupWorkerSearch() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Observe all workers for search
            viewModel.allWorkers.collect { workers ->
                Log.d(TAG, "Loaded ${workers.size} workers")
                workerSearchView.setAdapter(workers.map { it.toSearchableItem() })
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
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val app = requireActivity().application as BerryHarvestApplication
                val realm = app.getRealmInstance()

                val existingAssignment = realm.query<Assignment>("workerId == $0", worker._id)
                    .first().find()

                withContext(Dispatchers.Main) {
                    if (existingAssignment != null) {
                        // Worker already assigned to a row, show confirmation dialog
                        showReassignConfirmationDialog(worker, existingAssignment.rowNumber)
                    } else {
                        // Worker not assigned, proceed directly
                        proceedWithAssignment(worker)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking existing assignment", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Помилка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
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
            .setMessage("${worker.fullName} вже на ряду $currentRow. Змінити на ряд $rowNumber?")
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

        // Store worker and row data locally
        val workerId = worker._id
        val finalRowNumber = rowNumber

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting assignment with safe transaction: workerId=$workerId, rowNumber=$finalRowNumber")

                val app = requireActivity().application as BerryHarvestApplication
                val realm = app.getRealmInstance()

                // Check for existing assignment first
                val existingAssignment = realm.query<Assignment>("workerId == $0", workerId)
                    .first().find()

                Log.d(TAG, "Existing assignment check: ${existingAssignment?._id ?: "none"}")

                // Check network status
                val isNetworkAvailable = app.networkStatusManager.isNetworkAvailable()
                Log.d(TAG, "Network available for assignment: $isNetworkAvailable")

                // Use the safe transaction wrapper
                val assignmentId = app.safeWriteTransaction {
                    if (existingAssignment != null) {
                        // Update existing assignment
                        val liveAssignment = query<Assignment>("_id == $0", existingAssignment._id)
                            .first().find()

                        liveAssignment?.let {
                            it.rowNumber = finalRowNumber
                            // Set isSynced based on network availability
                            it.isSynced = isNetworkAvailable
                            Log.d(TAG, "Updated existing assignment: ${existingAssignment._id}, isSynced=$isNetworkAvailable")
                        }
                        existingAssignment._id
                    } else {
                        // Create new assignment
                        val newAssignmentId = UUID.randomUUID().toString()
                        copyToRealm(Assignment().apply {
                            _id = newAssignmentId
                            this.workerId = workerId
                            this.rowNumber = finalRowNumber
                            // Set isSynced based on network availability
                            this.isSynced = isNetworkAvailable
                        })
                        Log.d(TAG, "Created new assignment with ID: $newAssignmentId, isSynced=$isNetworkAvailable")
                        newAssignmentId
                    }
                }

                // Verify the assignment was saved
                val savedAssignment = realm.query<Assignment>("_id == $0", assignmentId).first().find()
                Log.d(TAG, "Assignment saved successfully: ${savedAssignment != null}, row: ${savedAssignment?.rowNumber}, isSynced: ${savedAssignment?.isSynced}")

                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        loadingProgressBar.visibility = View.GONE
                        assignButton.isEnabled = true

                        if (savedAssignment != null) {
                            Toast.makeText(requireContext(), "Призначено на ряд $finalRowNumber", Toast.LENGTH_SHORT).show()

                            // Clear selection
                            workerSearchView.clearSelection()
                            selectedWorker = null

                            // Clear row number only if successful assignment
                            rowEditText.text?.clear()
                            rowNumberInputLayout.error = null

                            // If searching for this row, make sure it stays visible
                            val searchQuery = searchRowEditText.text.toString()
                            if (searchQuery.isNotEmpty() && !finalRowNumber.toString().contains(searchQuery)) {
                                // Clear search to show all rows including the new assignment
                                searchRowEditText.text?.clear()
                            }
                        } else {
                            Toast.makeText(requireContext(), "Помилка: Не збережено", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in assignment", e)

                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        loadingProgressBar.visibility = View.GONE
                        assignButton.isEnabled = true
                        Toast.makeText(requireContext(), "Помилка: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
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

            // Direct move operation
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val app = requireActivity().application as BerryHarvestApplication
                    val realm = app.getRealmInstance()

                    // Store the assignment ID
                    val assignmentId = assignment._id

                    // Quick, focused transaction
                    realm.write {
                        query<Assignment>("_id == $0", assignmentId)
                            .first().find()?.apply {
                                rowNumber = newRowNumber
                                isSynced = false
                            }
                    }

                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            loadingProgressBar.visibility = View.GONE
                            Toast.makeText(requireContext(), "Переміщено на ряд $newRowNumber", Toast.LENGTH_SHORT).show()

                            // If searching, make sure the row stays visible
                            val searchQuery = searchRowEditText.text.toString()
                            if (searchQuery.isNotEmpty() && !newRowNumber.toString().contains(searchQuery)) {
                                // Clear search to show all rows including the moved assignment
                                searchRowEditText.text?.clear()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error moving worker", e)
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            loadingProgressBar.visibility = View.GONE
                            Toast.makeText(requireContext(), "Помилка: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
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

                // Direct row deletion
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val app = requireActivity().application as BerryHarvestApplication
                        val realm = app.getRealmInstance()

                        // Get the IDs of assignments to delete
                        val assignmentIds = realm.query<Assignment>("rowNumber == $0", rowNumber)
                            .find()
                            .map { it._id }

                        // Quick, focused transaction
                        realm.write {
                            assignmentIds.forEach { id ->
                                query<Assignment>("_id == $0", id)
                                    .first().find()?.let {
                                        delete(it)
                                    }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            if (isAdded) {
                                loadingProgressBar.visibility = View.GONE
                                Toast.makeText(requireContext(), "Ряд $rowNumber видалено", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deleting row", e)
                        withContext(Dispatchers.Main) {
                            if (isAdded) {
                                loadingProgressBar.visibility = View.GONE
                                Toast.makeText(requireContext(), "Помилка: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Ні", null)
            .show()
    }
}

