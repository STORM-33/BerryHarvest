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

class AddWorkerFragment : Fragment() {

    private lateinit var viewModel: AddWorkerViewModel
    private lateinit var workerAdapter: WorkerAdapter

    private lateinit var editTextFullName: EditText
    private lateinit var editTextPhoneNumber: EditText
    private lateinit var buttonAddWorker: Button
    private lateinit var recyclerViewWorkers: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_add_worker, container, false)

        editTextFullName = view.findViewById(R.id.editTextFullName)
        editTextPhoneNumber = view.findViewById(R.id.editTextPhoneNumber)
        buttonAddWorker = view.findViewById(R.id.buttonAddWorker)
        recyclerViewWorkers = view.findViewById(R.id.recyclerViewWorkers)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this).get(AddWorkerViewModel::class.java)

        buttonAddWorker.setOnClickListener {
            val fullName = editTextFullName.text.toString()
            val phoneNumber = editTextPhoneNumber.text.toString()

            if (fullName.isNotEmpty() && phoneNumber.isNotEmpty()) {
                viewModel.addWorker(fullName, phoneNumber)
                editTextFullName.text.clear()
                editTextPhoneNumber.text.clear()
            } else {
                Toast.makeText(context, "Заполните все поля", Toast.LENGTH_SHORT).show()
            }
        }

        workerAdapter = WorkerAdapter()
        recyclerViewWorkers.adapter = workerAdapter
        recyclerViewWorkers.layoutManager = LinearLayoutManager(context)

        lifecycleScope.launch {
            viewModel.workers.collect { results ->
                workerAdapter.submitList(results)
            }
        }
    }
}
