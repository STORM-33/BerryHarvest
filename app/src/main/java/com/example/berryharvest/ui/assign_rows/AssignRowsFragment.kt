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
        setupValidationAndFiltering()
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

    private fun setupValidationAndFiltering() {
        rowEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val text = s.toString().trim()
                filterAssignments(text)

                if (rowNumberInputLayout.error != null) {
                    rowNumberInputLayout.error = null
                }
            }
        })

        rowEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validateRowNumber(rowEditText.text.toString())
            }
        }
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

        checkExistingAssignment(worker)
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
        val rowNumber = rowEditText.text.toString().toInt()

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
                        rowNumberInputLayout.error = null
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

            if (newRowText.isEmpty()) {
                newRowInputLayout.error = "Введіть номер"
                return@setOnClickListener
            }

            val newRowNumber = newRowText.toIntOrNull()
            if (newRowNumber == null || newRowNumber !in 1..999) {
                newRowInputLayout.error = "Введіть номер (1-999)"
                return@setOnClickListener
            }

            newRowInputLayout.error = null
            loadingProgressBar.visibility = View.VISIBLE
            dialog.dismiss()

            lifecycleScope.launch {
                try {
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

        deleteButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Вилучити працівника з ряду?")
                .setPositiveButton("Так") { _, _ ->
                    loadingProgressBar.visibility = View.VISIBLE
                    dialog.dismiss()

                    lifecycleScope.launch {
                        try {
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