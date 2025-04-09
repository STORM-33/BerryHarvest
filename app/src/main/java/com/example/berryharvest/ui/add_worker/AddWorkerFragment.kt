package com.example.berryharvest.ui.add_worker

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.R
import com.example.berryharvest.data.repository.ConnectionState
import kotlinx.coroutines.launch

class AddWorkerFragment : Fragment() {

    private lateinit var viewModel: AddWorkerViewModel
    private lateinit var workerAdapter: WorkerAdapter

    private lateinit var editTextFullName: EditText
    private lateinit var editTextPhoneNumber: EditText
    private lateinit var buttonAddWorker: Button
    private lateinit var recyclerViewWorkers: RecyclerView
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var networkStatusTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this).get(AddWorkerViewModel::class.java)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_add_worker, container, false)

        // Initialize UI components
        editTextFullName = view.findViewById(R.id.editTextFullName)
        editTextPhoneNumber = view.findViewById(R.id.editTextPhoneNumber)
        buttonAddWorker = view.findViewById(R.id.buttonAddWorker)
        recyclerViewWorkers = view.findViewById(R.id.recyclerViewWorkers)

        // These views might need to be added to your layout
        loadingProgressBar = view.findViewById(R.id.loadingProgressBar)
        networkStatusTextView = view.findViewById(R.id.networkStatusTextView)

        // Setup RecyclerView
        workerAdapter = WorkerAdapter { worker ->
            showWorkerOptionsDialog(worker)
        }
        recyclerViewWorkers.adapter = workerAdapter
        recyclerViewWorkers.layoutManager = LinearLayoutManager(context)

        // Setup button click listener
        buttonAddWorker.setOnClickListener {
            addWorker()
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Observe workers
        lifecycleScope.launch {
            viewModel.workers.collect { workers ->
                workerAdapter.submitList(workers)
            }
        }

        // Observe loading state
        lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                buttonAddWorker.isEnabled = !isLoading
                loadingProgressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }

        // Observe errors
        lifecycleScope.launch {
            viewModel.error.collect { errorMessage ->
                errorMessage?.let {
                    Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                    viewModel.clearError()
                }
            }
        }

        // Observe connection state
        lifecycleScope.launch {
            viewModel.connectionState.collect { state ->
                updateConnectionStatusUI(state)
            }
        }
    }

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

    private fun addWorker() {
        val fullName = editTextFullName.text.toString().trim()
        val phoneNumber = editTextPhoneNumber.text.toString().trim()

        if (fullName.isNotEmpty() && phoneNumber.isNotEmpty()) {
            viewModel.addWorker(fullName, phoneNumber)
            editTextFullName.text.clear()
            editTextPhoneNumber.text.clear()
        } else {
            Toast.makeText(context, "Заповніть всі поля", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showWorkerOptionsDialog(worker: Worker) {
        val options = arrayOf("Змінити", "Видалити")
        AlertDialog.Builder(requireContext())
            .setTitle("Оберіть дію")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditWorkerDialog(worker)
                    1 -> showDeleteWorkerConfirmation(worker)
                }
            }
            .show()
    }

    private fun showEditWorkerDialog(worker: Worker) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_worker, null)
        val editTextFullName = dialogView.findViewById<EditText>(R.id.editTextFullName)
        val editTextPhoneNumber = dialogView.findViewById<EditText>(R.id.editTextPhoneNumber)

        editTextFullName.setText(worker.fullName)
        editTextPhoneNumber.setText(worker.phoneNumber)

        AlertDialog.Builder(requireContext())
            .setTitle("Змінити дані")
            .setView(dialogView)
            .setPositiveButton("Зберегти") { _, _ ->
                val newFullName = editTextFullName.text.toString().trim()
                val newPhoneNumber = editTextPhoneNumber.text.toString().trim()
                if (newFullName.isNotEmpty() && newPhoneNumber.isNotEmpty()) {
                    viewModel.updateWorker(worker._id, newFullName, newPhoneNumber)
                } else {
                    Toast.makeText(context, "Заповніть всі поля", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Відміна", null)
            .show()
    }

    private fun showDeleteWorkerConfirmation(worker: Worker) {
        AlertDialog.Builder(requireContext())
            .setTitle("Видалити дані")
            .setMessage("Ви впевнені, що хочете видалити дані цього працівника?")
            .setPositiveButton("Так") { _, _ ->
                viewModel.deleteWorker(worker._id)
            }
            .setNegativeButton("Ні", null)
            .show()
    }
}
