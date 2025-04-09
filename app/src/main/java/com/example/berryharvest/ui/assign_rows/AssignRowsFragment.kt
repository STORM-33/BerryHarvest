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
import com.example.berryharvest.R
import com.example.berryharvest.data.repository.ConnectionState
import com.example.berryharvest.data.repository.Result
import com.example.berryharvest.ui.add_worker.Worker
import com.example.berryharvest.ui.components.SearchableSpinnerView
import com.example.berryharvest.ui.components.WorkerSearchableItem
import com.example.berryharvest.ui.components.toSearchableItem
import kotlinx.coroutines.launch

class AssignRowsFragment : Fragment() {
    private val viewModel: AssignRowsViewModel by viewModels()

    private lateinit var rowEditText: EditText
    private lateinit var workerSearchView: SearchableSpinnerView
    private lateinit var assignButton: Button
    private lateinit var assignmentsRecyclerView: RecyclerView
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var networkStatusTextView: TextView

    private var selectedWorker: Worker? = null

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

        // Initialize adapter with refactored approach
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

    private lateinit var assignmentAdapter: AssignmentAdapter

    private fun observeViewModel() {
        // Observe assignments
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.assignments.collect { assignments ->
                Log.d("AssignRows", "Received ${assignments.size} assignment groups")
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
                Log.d("SearchableSpinner", "Loaded ${workers.size} workers")
                workerSearchView.setAdapter(workers.map { it.toSearchableItem() })
                workerSearchView.setOnItemSelectedListener { searchableItem ->
                    val workerItem = searchableItem as WorkerSearchableItem
                    selectedWorker = workerItem.worker
                    Log.d("SearchableSpinner", "Selected worker: ${workerItem.worker.fullName}")
                }
            }
        }
    }

    private fun assignWorkerToRow() {
        val rowNumberText = rowEditText.text.toString().trim()

        if (rowNumberText.isNotEmpty() && selectedWorker != null) {
            val rowNumber = rowNumberText.toIntOrNull()
            if (rowNumber == null || rowNumber !in 1..999) {
                Toast.makeText(requireContext(), "Введіть корректний номер (1-999)", Toast.LENGTH_SHORT).show()
                return
            }

            val worker = selectedWorker!!

            viewLifecycleOwner.lifecycleScope.launch {
                loadingProgressBar.visibility = View.VISIBLE
                assignButton.isEnabled = false

                val result = viewModel.assignWorkerToRow(worker._id, rowNumber)

                when (result) {
                    is Result.Success -> {
                        Toast.makeText(requireContext(), "Працівника призначено на ряд $rowNumber", Toast.LENGTH_SHORT).show()
                        rowEditText.text.clear()
                        workerSearchView.clearSelection()
                        selectedWorker = null
                    }
                    is Result.Error -> {
                        Toast.makeText(requireContext(), "Помилка: ${result.message}", Toast.LENGTH_SHORT).show()
                    }
                    is Result.Loading -> {
                        // Already handling loading state
                    }
                }

                loadingProgressBar.visibility = View.GONE
                assignButton.isEnabled = true
            }
        } else {
            Toast.makeText(requireContext(), "Введіть номер рядка та виберіть працівників", Toast.LENGTH_SHORT).show()
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
                viewLifecycleOwner.lifecycleScope.launch {
                    loadingProgressBar.visibility = View.VISIBLE

                    val result = viewModel.moveWorkerToRow(assignment._id, newRowNumber)

                    when (result) {
                        is Result.Success -> {
                            Toast.makeText(requireContext(), "Працівник переміщений на ряд $newRowNumber", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                        }
                        is Result.Error -> {
                            Toast.makeText(requireContext(), "Помилка: ${result.message}", Toast.LENGTH_SHORT).show()
                        }
                        is Result.Loading -> {
                            // Already handling loading state
                        }
                    }

                    loadingProgressBar.visibility = View.GONE
                }
            } else {
                Toast.makeText(requireContext(), "Введіть корректний номер (1-999)", Toast.LENGTH_SHORT).show()
            }
        }

        deleteButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Вилучити працівника з ряду?")
                .setPositiveButton("Так") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        loadingProgressBar.visibility = View.VISIBLE

                        val result = viewModel.deleteWorkerAssignment(assignment._id)

                        when (result) {
                            is Result.Success -> {
                                Toast.makeText(requireContext(), "Працівник вилучений", Toast.LENGTH_SHORT).show()
                            }
                            is Result.Error -> {
                                Toast.makeText(requireContext(), "Помилка: ${result.message}", Toast.LENGTH_SHORT).show()
                            }
                            is Result.Loading -> {
                                // Already handling loading state
                            }
                        }

                        loadingProgressBar.visibility = View.GONE
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Ні", null)
                .show()
        }

        dialog.show()
    }

    private fun showRemoveRowConfirmation(rowNumber: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle("Видалити рядок")
            .setMessage("Ви впенені що бажаєте видалити рядок $rowNumber? Дані про назначення працівників буде видалено.")
            .setPositiveButton("Так") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    loadingProgressBar.visibility = View.VISIBLE

                    val result = viewModel.deleteRow(rowNumber)

                    when (result) {
                        is Result.Success -> {
                            Toast.makeText(requireContext(), "Рядок $rowNumber видалено", Toast.LENGTH_SHORT).show()
                        }
                        is Result.Error -> {
                            Toast.makeText(requireContext(), "Помилка: ${result.message}", Toast.LENGTH_SHORT).show()
                        }
                        is Result.Loading -> {
                            // Already handling loading state
                        }
                    }

                    loadingProgressBar.visibility = View.GONE
                }
            }
            .setNegativeButton("Ні", null)
            .show()
    }
}

