package com.example.berryharvest.ui.assign_rows

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.R
import com.example.berryharvest.data.model.Assignment
import com.example.berryharvest.data.repository.ConnectionState
import com.example.berryharvest.data.model.Worker
import com.example.berryharvest.ui.common.SearchableSpinnerView
import com.example.berryharvest.ui.common.WorkerSearchableItem
import com.example.berryharvest.ui.common.toSearchableItem
import io.realm.kotlin.ext.query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AssignRowsFragment : Fragment() {
    private val TAG = "AssignRowsFragment"
    private val viewModel: AssignRowsViewModel by viewModels()

    private lateinit var rowEditText: EditText
    private lateinit var workerSearchView: SearchableSpinnerView
    private lateinit var assignButton: Button
    private lateinit var assignmentsRecyclerView: RecyclerView
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var networkStatusTextView: TextView

    private var selectedWorker: Worker? = null
    private lateinit var assignmentAdapter: AssignmentAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_assign_rows, container, false)

        // Setup UI components
        rowEditText = view.findViewById(R.id.rowEditText)
        workerSearchView = view.findViewById(R.id.workerSearchView)
        assignButton = view.findViewById(R.id.assignButton)
        assignmentsRecyclerView = view.findViewById(R.id.assignmentsRecyclerView)
        loadingProgressBar = view.findViewById(R.id.loadingProgressBar)
        networkStatusTextView = view.findViewById(R.id.networkStatusTextView)

        setupUI()
        setupWorkerSearch()
        observeViewModel()

        return view
    }

    private fun setupUI() {
        assignButton.setOnClickListener {
            assignWorkerToRow()
        }

        // Initialize adapter
        val adapter = createAssignmentAdapter()
        assignmentsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        assignmentsRecyclerView.adapter = adapter
        assignmentAdapter = adapter
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
                assignmentAdapter.submitList(ArrayList(assignments))
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

        // Observe connection state
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionState.collect { state ->
                updateConnectionStatusUI(state)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateConnectionStatusUI(state: ConnectionState) {
        when (state) {
            is ConnectionState.Connected -> {
                networkStatusTextView.text = "Підключено"
                networkStatusTextView.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
            }
            is ConnectionState.Disconnected -> {
                networkStatusTextView.text = "Офлайн режим"
                networkStatusTextView.setTextColor(resources.getColor(android.R.color.holo_orange_dark, null))
            }
            is ConnectionState.Error -> {
                networkStatusTextView.text = "Помилка з'єднання"
                networkStatusTextView.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
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

        if (rowNumberText.isEmpty() || selectedWorker == null) {
            Toast.makeText(requireContext(), "Введіть номер рядка та виберіть працівників", Toast.LENGTH_SHORT).show()
            return
        }

        val rowNumber = rowNumberText.toIntOrNull()
        if (rowNumber == null || rowNumber !in 1..999) {
            Toast.makeText(requireContext(), "Введіть корректний номер (1-999)", Toast.LENGTH_SHORT).show()
            return
        }

        val worker = selectedWorker!!

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
                val isNetworkAvailable = app.networkManager?.isNetworkAvailable() ?: false
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

                // If we're online but the assignment is still not synced, try an explicit sync
                if (isNetworkAvailable && savedAssignment?.isSynced == false) {
                    Log.d(TAG, "Assignment needs explicit syncing")
                    try {
                        app.safeWriteTransaction {
                            query<Assignment>("_id == $0", assignmentId)
                                .first().find()?.apply {
                                    isSynced = true
                                    Log.d(TAG, "Assignment explicitly marked as synced: $assignmentId")
                                }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error explicitly syncing assignment", e)
                    }
                }

                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        loadingProgressBar.visibility = View.GONE
                        assignButton.isEnabled = true

                        if (savedAssignment != null) {
                            Toast.makeText(requireContext(), "Працівника призначено на ряд $finalRowNumber", Toast.LENGTH_SHORT).show()
                            rowEditText.text.clear()
                            workerSearchView.clearSelection()
                            selectedWorker = null
                        } else {
                            Toast.makeText(requireContext(), "Помилка: Призначення не збережено", Toast.LENGTH_SHORT).show()
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
        val newRowEditText = dialogView.findViewById<EditText>(R.id.newRowEditText)
        val moveButton = dialogView.findViewById<Button>(R.id.moveButton)
        val deleteButton = dialogView.findViewById<Button>(R.id.deleteButton)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Змінити призначення")
            .setView(dialogView)
            .create()

        moveButton.setOnClickListener {
            val newRowText = newRowEditText.text.toString().trim()
            val newRowNumber = newRowText.toIntOrNull()
            if (newRowText.isNotEmpty() && newRowNumber != null && newRowNumber in 1..999) {
                // Show loading
                loadingProgressBar.visibility = View.VISIBLE
                dialog.dismiss()

                // Direct move operation
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
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
                                Toast.makeText(requireContext(), "Працівник переміщений на ряд $newRowNumber", Toast.LENGTH_SHORT).show()
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
            } else {
                Toast.makeText(requireContext(), "Введіть корректний номер (1-999)", Toast.LENGTH_SHORT).show()
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
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
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
            .setMessage("Ви впенені що бажаєте видалити рядок $rowNumber? Дані про назначення працівників буде видалено.")
            .setPositiveButton("Так") { _, _ ->
                // Show loading
                loadingProgressBar.visibility = View.VISIBLE

                // Direct row deletion
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
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
                                Toast.makeText(requireContext(), "Рядок $rowNumber видалено", Toast.LENGTH_SHORT).show()
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

