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
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.R
import com.example.berryharvest.data.model.Worker
import com.example.berryharvest.data.repository.ConnectionState
import io.realm.kotlin.ext.query
import io.realm.kotlin.query.max
import kotlinx.coroutines.Dispatchers
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
    }

    private fun addWorker() {
        val fullName = editTextFullName.text.toString().trim()
        val phoneNumber = editTextPhoneNumber.text.toString().trim()

        if (fullName.isEmpty() || phoneNumber.isEmpty()) {
            Toast.makeText(context, "Заповніть всі поля", Toast.LENGTH_SHORT).show()
            return
        }

        // Show loading state immediately
        loadingProgressBar.visibility = View.VISIBLE
        buttonAddWorker.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting worker add with safe transaction")

                val app = requireActivity().application as BerryHarvestApplication

                // Generate a new worker with UUID
                val workerId = UUID.randomUUID().toString()

                // Get next sequence number - do this outside transaction
                val realm = app.getRealmInstance()
                val maxSequence = realm.query<Worker>().max<Int>("sequenceNumber").find() ?: 0
                val nextSequence = maxSequence + 1

                // Check network status
                val isNetworkAvailable = app.networkStatusManager.isNetworkAvailable()
                Log.d(TAG, "Network available: $isNetworkAvailable")

                Log.d(TAG, "Using safe transaction wrapper with worker ID: $workerId, sequence: $nextSequence")

                // Use the safe transaction wrapper
                app.safeWriteTransaction {
                    copyToRealm(Worker().apply {
                        _id = workerId
                        sequenceNumber = nextSequence
                        this.fullName = fullName
                        this.phoneNumber = phoneNumber
                        // Set sync status based on network availability
                        isSynced = isNetworkAvailable
                    })
                }

                // Verify worker was added
                val savedWorker = realm.query<Worker>("_id == $0", workerId).first().find()
                Log.d(TAG, "Worker saved successfully: ${savedWorker != null}, isSynced: ${savedWorker?.isSynced}")

                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        loadingProgressBar.visibility = View.GONE
                        buttonAddWorker.isEnabled = true

                        if (savedWorker != null) {
                            Toast.makeText(context, "Працівника додано", Toast.LENGTH_SHORT).show()

                            // Clear input fields
                            editTextFullName.text.clear()
                            editTextPhoneNumber.text.clear()
                        } else {
                            Toast.makeText(context, "Помилка: Працівника не збережено", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding worker", e)

                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        loadingProgressBar.visibility = View.GONE
                        buttonAddWorker.isEnabled = true
                        Toast.makeText(context, "Помилка: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
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
                            val app = requireActivity().application as BerryHarvestApplication
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
                        val app = requireActivity().application as BerryHarvestApplication
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
