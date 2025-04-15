package com.example.berryharvest.ui.add_worker

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.BerryHarvestApplication
import com.example.berryharvest.R
import com.example.berryharvest.data.model.Worker
import com.example.berryharvest.data.repository.ConnectionState
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.realm.kotlin.ext.query
import io.realm.kotlin.query.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.regex.Pattern

class AddWorkerFragment : Fragment() {
    private val TAG = "AddWorkerFragment"
    private lateinit var viewModel: AddWorkerViewModel

    private lateinit var fullNameInputLayout: TextInputLayout
    private lateinit var editTextFullName: TextInputEditText
    private lateinit var phoneNumberInputLayout: TextInputLayout
    private lateinit var editTextPhoneNumber: TextInputEditText
    private lateinit var searchInputLayout: TextInputLayout
    private lateinit var searchEditText: TextInputEditText
    private lateinit var buttonAddWorker: Button
    private lateinit var recyclerViewWorkers: RecyclerView
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var workerAdapter: WorkerAdapter
    private lateinit var workerCountTextView: TextView

    // Filtered list of workers
    private var filteredWorkers = listOf<Worker>()
    private var allWorkers = listOf<Worker>()

    // Regex patterns for validation
    private val NAME_PATTERN = Pattern.compile("^[А-ЩЬЮЯІЇЄҐа-щьюяіїєґ'\\- ]+\\s+[А-ЩЬЮЯІЇЄҐа-щьюяіїєґ'\\- ]+$")
    private val PHONE_PATTERN = Pattern.compile("^\\+?[0-9]{10,13}$")

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
        fullNameInputLayout = view.findViewById(R.id.fullNameInputLayout)
        editTextFullName = view.findViewById(R.id.editTextFullName)
        phoneNumberInputLayout = view.findViewById(R.id.phoneNumberInputLayout)
        editTextPhoneNumber = view.findViewById(R.id.editTextPhoneNumber)
        searchInputLayout = view.findViewById(R.id.searchInputLayout)
        searchEditText = view.findViewById(R.id.searchEditText)
        buttonAddWorker = view.findViewById(R.id.buttonAddWorker)
        recyclerViewWorkers = view.findViewById(R.id.recyclerViewWorkers)
        loadingProgressBar = view.findViewById(R.id.loadingProgressBar)
        workerCountTextView = view.findViewById(R.id.workerCountTextView)

        // Setup RecyclerView
        setupRecyclerView()

        // Setup input validation
        setupInputValidation()

        // Setup search functionality
        setupSearch()

        // Setup button click listener
        buttonAddWorker.setOnClickListener {
            validateAndAddWorker()
        }

        setupObservers()

        return view
    }

    private fun setupRecyclerView() {
        // Create adapter with long-click handler
        workerAdapter = WorkerAdapter { worker ->
            showWorkerOptionsDialog(worker)
        }

        // Setup recycler view
        recyclerViewWorkers.adapter = workerAdapter
        recyclerViewWorkers.layoutManager = LinearLayoutManager(context)

        // Add dividers between items for better readability in compact layout
        val dividerItemDecoration = DividerItemDecoration(
            recyclerViewWorkers.context,
            (recyclerViewWorkers.layoutManager as LinearLayoutManager).orientation
        )
        recyclerViewWorkers.addItemDecoration(dividerItemDecoration)
    }

    private fun setupInputValidation() {
        // Set input change listeners to clear errors
        editTextFullName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validateFullName(editTextFullName.text.toString())
            } else {
                fullNameInputLayout.error = null
            }
        }

        editTextPhoneNumber.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validatePhoneNumber(editTextPhoneNumber.text.toString())
            } else {
                phoneNumberInputLayout.error = null
            }
        }
    }

    private fun setupSearch() {
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                filterWorkers(s.toString())
            }
        })
    }

    private fun filterWorkers(query: String) {
        if (query.isEmpty()) {
            // No query, show all workers
            filteredWorkers = allWorkers
            workerAdapter.submitList(filteredWorkers)
            updateWorkerCount(filteredWorkers.size)
            return
        }

        // Convert query to lowercase for case-insensitive search
        val lowercaseQuery = query.lowercase()

        // Filter by name or phone number
        filteredWorkers = allWorkers.filter { worker ->
            worker.fullName.lowercase().contains(lowercaseQuery) ||
                    worker.phoneNumber.contains(lowercaseQuery) ||
                    worker.sequenceNumber.toString().contains(lowercaseQuery)
        }

        // Update the adapter with filtered results
        workerAdapter.submitList(filteredWorkers)
        updateWorkerCount(filteredWorkers.size)
    }

    private fun updateWorkerCount(count: Int) {
        val total = allWorkers.size
        workerCountTextView.text = if (count < total) {
            "($count з $total)"
        } else {
            "($count)"
        }
    }

    private fun validateFullName(fullName: String): Boolean {
        if (fullName.isEmpty()) {
            fullNameInputLayout.error = "Поле не може бути порожнім"
            return false
        }

        if (!NAME_PATTERN.matcher(fullName).matches()) {
            fullNameInputLayout.error = "Введіть ім'я та прізвище"
            return false
        }

        fullNameInputLayout.error = null
        return true
    }

    private fun validatePhoneNumber(phoneNumber: String): Boolean {
        // Phone can be empty, but we'll show a confirmation dialog later
        if (phoneNumber.isEmpty()) {
            return true // We'll handle empty phone confirmation separately
        }

        if (!PHONE_PATTERN.matcher(phoneNumber).matches()) {
            phoneNumberInputLayout.error = "Введіть коректний номер"
            return false
        }

        phoneNumberInputLayout.error = null
        return true
    }

    private fun setupObservers() {
        // Observe workers
        lifecycleScope.launch {
            viewModel.workers.collect { workers ->
                allWorkers = workers

                // If there's a search query, apply filtering
                val query = searchEditText.text.toString()
                if (query.isNotEmpty()) {
                    filterWorkers(query)
                } else {
                    // Otherwise show all and update adapter
                    filteredWorkers = workers
                    workerAdapter.submitList(workers)
                    updateWorkerCount(workers.size)
                }
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

    private fun validateAndAddWorker() {
        val fullName = editTextFullName.text.toString().trim()
        val phoneNumber = editTextPhoneNumber.text.toString().trim()

        // Validate inputs
        val isFullNameValid = validateFullName(fullName)
        val isPhoneNumberValid = validatePhoneNumber(phoneNumber)

        if (!isFullNameValid || !isPhoneNumberValid) {
            return
        }

        // Check if phone number is empty and show confirmation dialog
        if (phoneNumber.isEmpty()) {
            showEmptyPhoneConfirmationDialog(fullName)
        } else {
            addWorker(fullName, phoneNumber)
        }
    }

    private fun showEmptyPhoneConfirmationDialog(fullName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Підтвердження")
            .setMessage("Номер телефону не вказано. Продовжити?")
            .setPositiveButton("Так") { _, _ ->
                addWorker(fullName, "")
            }
            .setNegativeButton("Ні", null)
            .show()
    }

    private fun addWorker(fullName: String, phoneNumber: String) {
        // Show loading state immediately
        loadingProgressBar.visibility = View.VISIBLE
        buttonAddWorker.isEnabled = false

        lifecycleScope.launch {
            try {
                // Use the ViewModel to add the worker
                viewModel.addWorker(fullName, phoneNumber)

                // Clear input fields
                editTextFullName.text?.clear()
                editTextPhoneNumber.text?.clear()

                // Clear any input errors
                fullNameInputLayout.error = null
                phoneNumberInputLayout.error = null

                // Scroll to the newly added worker (if visible in current filter)
                if (searchEditText.text.toString().isEmpty()) {
                    recyclerViewWorkers.scrollToPosition(0)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding worker", e)
                Toast.makeText(context, "Помилка: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                // This will get called even if there's an exception
                if (isAdded) {
                    loadingProgressBar.visibility = View.GONE
                    buttonAddWorker.isEnabled = true
                }
            }
        }
    }

    private fun showWorkerOptionsDialog(worker: Worker) {
        val options = arrayOf("Змінити", "Видалити")
        AlertDialog.Builder(requireContext())
            .setTitle("Дії")
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
        val editFullNameInputLayout = dialogView.findViewById<TextInputLayout>(R.id.editFullNameInputLayout)
        val editTextFullName = dialogView.findViewById<EditText>(R.id.editTextFullName)
        val editPhoneNumberInputLayout = dialogView.findViewById<TextInputLayout>(R.id.editPhoneNumberInputLayout)
        val editTextPhoneNumber = dialogView.findViewById<EditText>(R.id.editTextPhoneNumber)

        editTextFullName.setText(worker.fullName)
        editTextPhoneNumber.setText(worker.phoneNumber)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Змінити дані")
            .setView(dialogView)
            .setPositiveButton("Зберегти", null) // Set null initially to prevent auto-dismiss
            .setNegativeButton("Відміна", null)
            .create()

        dialog.show()

        // Override positive button to add validation
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newFullName = editTextFullName.text.toString().trim()
            val newPhoneNumber = editTextPhoneNumber.text.toString().trim()

            // Validate inputs
            var isValid = true

            if (newFullName.isEmpty()) {
                editFullNameInputLayout.error = "Ім'я не може бути порожнім"
                isValid = false
            } else if (!NAME_PATTERN.matcher(newFullName).matches()) {
                editFullNameInputLayout.error = "Введіть ім'я та прізвище"
                isValid = false
            } else {
                editFullNameInputLayout.error = null
            }

            if (newPhoneNumber.isNotEmpty() && !PHONE_PATTERN.matcher(newPhoneNumber).matches()) {
                editPhoneNumberInputLayout.error = "Введіть коректний номер"
                isValid = false
            } else {
                editPhoneNumberInputLayout.error = null
            }

            if (isValid) {
                // Check if phone is empty and show confirmation if needed
                if (newPhoneNumber.isEmpty()) {
                    // Show confirmation dialog for empty phone
                    val confirmDialog = AlertDialog.Builder(requireContext())
                        .setTitle("Підтвердження")
                        .setMessage("Номер телефону не вказано. Продовжити?")
                        .setPositiveButton("Так") { _, _ ->
                            dialog.dismiss()
                            updateWorker(worker._id, newFullName, newPhoneNumber)
                        }
                        .setNegativeButton("Ні", null)
                        .create()

                    confirmDialog.show()
                } else {
                    // Phone is valid, proceed with update
                    dialog.dismiss()
                    updateWorker(worker._id, newFullName, newPhoneNumber)
                }
            }
        }
    }

    private fun updateWorker(workerId: String, fullName: String, phoneNumber: String) {
        // Show loading state immediately
        loadingProgressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                // Use the ViewModel to update the worker
                viewModel.updateWorker(workerId, fullName, phoneNumber)

                Toast.makeText(context, "Дані оновлено", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error updating worker", e)
                if (isAdded) {
                    Toast.makeText(context, "Помилка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                if (isAdded) {
                    loadingProgressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun showDeleteWorkerConfirmation(worker: Worker) {
        AlertDialog.Builder(requireContext())
            .setTitle("Видалити")
            .setMessage("Видалити працівника ${worker.fullName}?")
            .setPositiveButton("Так") { _, _ ->
                // Show loading
                loadingProgressBar.visibility = View.VISIBLE

                lifecycleScope.launch {
                    try {
                        // Use the ViewModel to delete the worker
                        viewModel.deleteWorker(worker._id)

                        Toast.makeText(context, "Працівника видалено", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deleting worker", e)
                        if (isAdded) {
                            Toast.makeText(context, "Помилка: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } finally {
                        if (isAdded) {
                            loadingProgressBar.visibility = View.GONE
                        }
                    }
                }
            }
            .setNegativeButton("Ні", null)
            .show()
    }
}
