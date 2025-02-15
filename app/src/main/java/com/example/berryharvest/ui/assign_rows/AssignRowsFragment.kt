package com.example.berryharvest.ui.assign_rows

import android.app.AlertDialog
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.MyApplication
import com.example.berryharvest.R
import com.example.berryharvest.ui.add_worker.Worker
import com.example.berryharvest.ui.components.SearchableSpinnerView
import com.example.berryharvest.ui.components.WorkerSearchableItem
import com.example.berryharvest.ui.components.toSearchableItem
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import kotlinx.coroutines.launch

class AssignRowsFragment : Fragment() {
    private lateinit var realm: Realm
    private lateinit var viewModel: AssignRowsViewModel

    private lateinit var rowEditText: EditText
    private lateinit var workerSearchView: SearchableSpinnerView  // заменили workerSpinner
    private lateinit var assignButton: Button
    private lateinit var assignmentsRecyclerView: RecyclerView
    private lateinit var assignmentAdapter: AssignmentAdapter

    private var workerList: List<Worker> = listOf()
    private var selectedWorker: Worker? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        viewModel = ViewModelProvider(this).get(AssignRowsViewModel::class.java)
        realm = (requireActivity().application as MyApplication).getRealmInstance()

        val view = inflater.inflate(R.layout.fragment_assign_rows, container, false)

        rowEditText = view.findViewById(R.id.rowEditText)
        workerSearchView = view.findViewById(R.id.workerSearchView)
        assignButton = view.findViewById(R.id.assignButton)
        assignmentsRecyclerView = view.findViewById(R.id.assignmentsRecyclerView)

        assignmentAdapter = AssignmentAdapter(
            realm = realm,
            onMoveWorkerClick = { assignment ->
                showMoveWorkerDialog(assignment)
            },
            onRemoveRowClick = { rowNumber ->
                showRemoveRowConfirmation(rowNumber)
            }
        )

        assignmentsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        assignmentsRecyclerView.adapter = assignmentAdapter

        setupWorkerSearch()  // заменили setupSpinners()

        assignButton.setOnClickListener {
            assignWorkerToRow()
        }

        observeAssignments()

        return view
    }


    private fun setupWorkerSearch() {
        lifecycleScope.launch {
            val workers = realm.query<Worker>().find()
            workerList = workers

            workerSearchView.setAdapter(workers.map { it.toSearchableItem() })
            workerSearchView.setOnItemSelectedListener { searchableItem ->
                val workerItem = searchableItem as WorkerSearchableItem
                selectedWorker = workerItem.worker
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

            lifecycleScope.launch {
                realm.write {
                    val existingAssignment = query<Assignment>("workerId == $0", worker._id).first().find()
                    if (existingAssignment != null) {
                        existingAssignment.rowNumber = rowNumber
                        existingAssignment.isSynced = false
                    } else {
                        copyToRealm(Assignment().apply {
                            this.rowNumber = rowNumber
                            this.workerId = worker._id
                            this.isSynced = false
                        })
                    }
                }
                Toast.makeText(requireContext(), "Працівника призначено на ряд $rowNumber", Toast.LENGTH_SHORT).show()
                rowEditText.text.clear()
                workerSearchView.clearSelection()
                selectedWorker = null
            }
        } else {
            Toast.makeText(requireContext(), "Введіть номер рядка та виберіть працівників", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeAssignments() {
        lifecycleScope.launch {
            viewModel.assignments.collect { assignments ->
                // Group assignments by row number
                val assignmentsByRow = assignments.groupBy { it.rowNumber }

                val assignmentGroups = assignmentsByRow.map { (rowNumber, assignments) ->
                    AssignmentGroup(rowNumber, assignments)
                }

                assignmentAdapter.submitList(assignmentGroups)
            }
        }
    }

    private fun showMoveWorkerDialog(assignment: Assignment) {
        // Создаем кастомный макет для диалога
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
                lifecycleScope.launch {
                    realm.write {
                        val managedAssignment = findLatest(assignment)
                        managedAssignment?.rowNumber = newRowNumber
                        managedAssignment?.isSynced = false
                    }
                }
                Toast.makeText(requireContext(), "Працівник переміщений на ряд $newRowNumber", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                Toast.makeText(requireContext(), "Введіть корректний номер (1-999)", Toast.LENGTH_SHORT).show()
            }
        }

        deleteButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Видалити працівника з ряду?")
                // .setMessage("Are you sure you want to remove this worker from the row?")
                .setPositiveButton("Так") { _, _ ->
                    lifecycleScope.launch {
                        realm.write {
                            delete(assignment)
                        }
                    }
                    Toast.makeText(requireContext(), "Працівник видалений", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Ні", null)
                .show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showRemoveRowConfirmation(rowNumber: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle("Видалити рядок")
            .setMessage("Ви впененні що бажаєте видалити рядок $rowNumber? Дані про назначення працівників буде видалено.")
            .setPositiveButton("Так") { _, _ ->
                // Remove all assignments for this row
                lifecycleScope.launch {
                    realm.write {
                        val assignments = query<Assignment>("rowNumber == $0", rowNumber).find()
                        delete(assignments)
                    }
                }
                Toast.makeText(requireContext(), "Рядок $rowNumber видалено", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Ні", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        realm.close()
    }
}

