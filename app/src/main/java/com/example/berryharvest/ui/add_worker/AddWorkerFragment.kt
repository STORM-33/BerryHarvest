package com.example.berryharvest.ui.add_worker

import WorkerAdapter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.R
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import android.app.AlertDialog
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import io.realm.kotlin.Realm

class AddWorkerFragment : Fragment() {
    private val _realmLiveData = MutableLiveData<Realm>()
    val realmLiveData: LiveData<Realm> = _realmLiveData

    private lateinit var viewModel: AddWorkerViewModel
    private lateinit var workerAdapter: WorkerAdapter
    private lateinit var realm: Realm

    private lateinit var editTextFullName: EditText
    private lateinit var editTextPhoneNumber: EditText
    private lateinit var buttonAddWorker: Button
    private lateinit var recyclerViewWorkers: RecyclerView



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this).get(AddWorkerViewModel::class.java)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        viewModel.realmLiveData.observe(viewLifecycleOwner, Observer { realm ->
            this.realm = realm
            initUI()
        })

        viewModel.realmLiveData.observe(viewLifecycleOwner) { realm ->
            // Realm is now initialized and ready to use
            // You can start observing the workers flow here
            lifecycleScope.launch {
                viewModel.workers.collect { workers ->
                    workerAdapter.submitList(workers)
                }
            }
        }

        val view = inflater.inflate(R.layout.fragment_add_worker, container, false)

        editTextFullName = view.findViewById(R.id.editTextFullName)
        editTextPhoneNumber = view.findViewById(R.id.editTextPhoneNumber)
        buttonAddWorker = view.findViewById(R.id.buttonAddWorker)
        recyclerViewWorkers = view.findViewById(R.id.recyclerViewWorkers)

        return view
    }

    private fun initUI() {
        buttonAddWorker.setOnClickListener {
            val fullName = editTextFullName.text.toString()
            val phoneNumber = editTextPhoneNumber.text.toString()

            if (fullName.isNotEmpty() && phoneNumber.isNotEmpty()) {
                viewModel.addWorker(fullName, phoneNumber)
                editTextFullName.text.clear()
                editTextPhoneNumber.text.clear()
            } else {
                Toast.makeText(context, "Заповніть всі поля", Toast.LENGTH_SHORT).show()
            }
        }

        workerAdapter = WorkerAdapter { worker ->
            showWorkerOptionsDialog(worker)
        }
        recyclerViewWorkers.adapter = workerAdapter
        recyclerViewWorkers.layoutManager = LinearLayoutManager(context)

        lifecycleScope.launch {
            viewModel.workers.collect { results ->
                workerAdapter.submitList(results)
            }
        }
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this).get(AddWorkerViewModel::class.java)


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
                val newFullName = editTextFullName.text.toString()
                val newPhoneNumber = editTextPhoneNumber.text.toString()
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
