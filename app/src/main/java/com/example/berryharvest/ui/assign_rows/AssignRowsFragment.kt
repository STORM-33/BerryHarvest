package com.example.berryharvest.ui.assign_rows

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
import com.example.berryharvest.ui.add_worker.Worker
import com.example.berryharvest.ui.components.SearchableSpinnerView
import com.example.berryharvest.ui.components.WorkerSearchableItem
import com.example.berryharvest.ui.components.toSearchableItem
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AssignRowsFragment : Fragment() {
    private var realm: Realm? = null
    private val viewModel: AssignRowsViewModel by viewModels()

    private lateinit var rowEditText: EditText
    private lateinit var workerSearchView: SearchableSpinnerView
    private lateinit var assignButton: Button
    private lateinit var assignmentsRecyclerView: RecyclerView
    private lateinit var assignmentAdapter: AssignmentAdapter

    private var workerList: List<Worker> = listOf()
    private var selectedWorker: Worker? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_assign_rows, container, false)

        // Настройка UI компонентов
        rowEditText = view.findViewById(R.id.rowEditText)
        workerSearchView = view.findViewById(R.id.workerSearchView)
        assignButton = view.findViewById(R.id.assignButton)
        assignmentsRecyclerView = view.findViewById(R.id.assignmentsRecyclerView)

        setupUI()
        setupWorkerSearch() // Добавлен вызов метода настройки поиска работников
        observeViewModel()

        return view
    }

    private fun setupUI() {
        assignButton.setOnClickListener {
            assignWorkerToRow()
        }

        assignmentAdapter = AssignmentAdapter(
            onMoveWorkerClick = { assignment -> showMoveWorkerDialog(assignment) },
            onRemoveRowClick = { rowNumber -> showRemoveRowConfirmation(rowNumber) },
            realmProvider = { viewModel.obtainRealm() }
        )
        assignmentsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        assignmentsRecyclerView.adapter = assignmentAdapter
    }

    private fun observeViewModel() {
        // Наблюдаем за изменениями в списке назначений
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.assignments.collect { assignments ->
                Log.d("AssignRows", "Получено ${assignments.size} групп назначений")
                assignmentAdapter.submitList(ArrayList(assignments)) // Принудительно создаем новый список
            }
        }

        // Наблюдаем за ошибками
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.error.collect { errorMessage ->
                errorMessage?.let {
                    Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Наблюдаем за состоянием загрузки
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                // Отображаем индикатор загрузки
            }
        }
    }

    private fun setupWorkerSearch() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Ждем инициализации Realm
            viewModel.realmInitialized.collect { initialized ->
                if (initialized) {
                    viewModel.obtainRealm()?.let { realm ->
                        realm.query<Worker>().asFlow().collect { results ->
                            val workers = results.list
                            workerList = workers

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
                viewModel.executeRealmOperation { realm ->
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

                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Працівника призначено на ряд $rowNumber", Toast.LENGTH_SHORT).show()
                        rowEditText.text.clear()
                        workerSearchView.clearSelection()
                        selectedWorker = null
                    }
                }
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
                    viewModel.executeRealmOperation { realm ->
                        realm.write {
                            val managedAssignment = findLatest(assignment)
                            managedAssignment?.rowNumber = newRowNumber
                            managedAssignment?.isSynced = false
                        }

                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "Працівник переміщений на ряд $newRowNumber", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
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
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.executeRealmOperation { realm ->
                            realm.write {
                                delete(assignment)
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "Працівник вилучений", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
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
            .setMessage("Ви впенені що бажаєте видалити рядок $rowNumber? Дані про назначення працівників буде видалено.")
            .setPositiveButton("Так") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.executeRealmOperation { realm ->
                        realm.write {
                            val assignments = query<Assignment>("rowNumber == $0", rowNumber).find()
                            delete(assignments)
                        }

                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "Рядок $rowNumber видалено", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Ні", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        realm?.close() // Закрываем Realm при уничтожении фрагмента
    }
}

