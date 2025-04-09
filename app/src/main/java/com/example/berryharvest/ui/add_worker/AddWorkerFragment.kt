package com.example.berryharvest.ui.add_worker

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.MyApplication
import com.example.berryharvest.R
import com.example.berryharvest.data.repository.ConnectionState
import io.realm.kotlin.ext.query
import io.realm.kotlin.query.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AddWorkerFragment : Fragment() {
    private val TAG = "AddWorkerFragment"
    private lateinit var viewModel: AddWorkerViewModel

    private lateinit var editTextFullName: EditText
    private lateinit var editTextPhoneNumber: EditText
    private lateinit var buttonAddWorker: Button
    private lateinit var recyclerViewWorkers: RecyclerView
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var networkStatusTextView: TextView
    private lateinit var workerAdapter: WorkerAdapter

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

        setupObservers()

        return view
    }

    private fun setupObservers() {
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

    private fun addWorker() {
        val fullName = editTextFullName.text.toString().trim()
        val phoneNumber = editTextPhoneNumber.text.toString().trim()

        if (fullName.isNotEmpty() && phoneNumber.isNotEmpty()) {
            // Show loading state immediately
            loadingProgressBar.visibility = View.VISIBLE
            buttonAddWorker.isEnabled = false

            // Store input values to clear after success
            val savedFullName = fullName
            val savedPhoneNumber = phoneNumber

            // Direct add worker operation
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Starting direct worker add operation")

                    // Small delay to ensure UI has updated
                    delay(100)

                    // Get realm instance
                    val app = requireActivity().application as MyApplication
                    val realm = app.getRealmInstance()

                    // Get next sequence number
                    val maxSequence = realm.query<Worker>().max<Int>("sequenceNumber").find() ?: 0
                    val nextSequence = maxSequence + 1

                    // Generate UUID here
                    val workerId = UUID.randomUUID().toString()

                    // Quick, focused transaction
                    realm.write {
                        copyToRealm(Worker().apply {
                            _id = workerId
                            sequenceNumber = nextSequence
                            this.fullName = savedFullName
                            this.phoneNumber = savedPhoneNumber
                            isSynced = false
                        })
                    }

                    // Update UI on main thread
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            loadingProgressBar.visibility = View.GONE
                            buttonAddWorker.isEnabled = true
                            Toast.makeText(context, "Працівника додано", Toast.LENGTH_SHORT).show()

                            // Clear input fields
                            editTextFullName.text.clear()
                            editTextPhoneNumber.text.clear()
                        }
                    }

                    Log.d(TAG, "Worker added successfully. ID: $workerId")
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding worker", e)

                    // Update UI on main thread
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            loadingProgressBar.visibility = View.GONE
                            buttonAddWorker.isEnabled = true
                            Toast.makeText(context, "Помилка: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
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

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Змінити дані")
            .setView(dialogView)
            .setPositiveButton("Зберегти") { dialogInterface, _ ->
                val newFullName = editTextFullName.text.toString().trim()
                val newPhoneNumber = editTextPhoneNumber.text.toString().trim()

                if (newFullName.isNotEmpty() && newPhoneNumber.isNotEmpty()) {
                    // Dismiss dialog and show loading
                    dialogInterface.dismiss()
                    loadingProgressBar.visibility = View.VISIBLE

                    // Direct update operation
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val app = requireActivity().application as MyApplication
                            val realm = app.getRealmInstance()

                            // Store worker ID
                            val workerId = worker._id

                            // Quick, focused transaction
                            realm.write {
                                query<Worker>("_id == $0", workerId)
                                    .first().find()?.apply {
                                        fullName = newFullName
                                        phoneNumber = newPhoneNumber
                                        isSynced = false
                                    }
                            }

                            withContext(Dispatchers.Main) {
                                if (isAdded) {
                                    loadingProgressBar.visibility = View.GONE
                                    Toast.makeText(context, "Дані оновлено", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error updating worker", e)
                            withContext(Dispatchers.Main) {
                                if (isAdded) {
                                    loadingProgressBar.visibility = View.GONE
                                    Toast.makeText(context, "Помилка: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                } else {
                    Toast.makeText(context, "Заповніть всі поля", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Відміна", null)
            .create()

        dialog.show()
    }

    private fun showDeleteWorkerConfirmation(worker: Worker) {
        AlertDialog.Builder(requireContext())
            .setTitle("Видалити дані")
            .setMessage("Ви впевнені, що хочете видалити дані цього працівника?")
            .setPositiveButton("Так") { _, _ ->
                // Show loading
                loadingProgressBar.visibility = View.VISIBLE

                // Direct delete operation
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val app = requireActivity().application as MyApplication
                        val realm = app.getRealmInstance()

                        // Store worker ID
                        val workerId = worker._id

                        // Quick, focused transaction
                        realm.write {
                            query<Worker>("_id == $0", workerId)
                                .first().find()?.let {
                                    delete(it)
                                }
                        }

                        withContext(Dispatchers.Main) {
                            if (isAdded) {
                                loadingProgressBar.visibility = View.GONE
                                Toast.makeText(context, "Працівника видалено", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deleting worker", e)
                        withContext(Dispatchers.Main) {
                            if (isAdded) {
                                loadingProgressBar.visibility = View.GONE
                                Toast.makeText(context, "Помилка: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Ні", null)
            .show()
    }
}
