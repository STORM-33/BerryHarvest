package com.example.berryharvest.ui.row_collection

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.BaseFragment
import com.example.berryharvest.R
import com.example.berryharvest.data.repository.ConnectionState
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

class RowCollectionFragment : BaseFragment() {
    private val TAG = "RowCollectionFragment"
    private lateinit var viewModel: RowCollectionViewModel

    // UI Components
    private lateinit var recyclerViewRows: RecyclerView
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var quarterChipGroup: ChipGroup
    private lateinit var filterModeSpinner: Spinner
    private lateinit var connectionStatusView: View

    private lateinit var rowAdapter: RowAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this).get(RowCollectionViewModel::class.java)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_row_collection, container, false)

        initializeViews(view)
        setupRecyclerView()
        setupFilters()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupObservers()
    }

    private fun initializeViews(view: View) {
        recyclerViewRows = view.findViewById(R.id.recyclerViewRows)
        loadingProgressBar = view.findViewById(R.id.loadingProgressBar)
        quarterChipGroup = view.findViewById(R.id.quarterChipGroup)
        filterModeSpinner = view.findViewById(R.id.filterModeSpinner)
        connectionStatusView = view.findViewById(R.id.connectionStatusView)
    }

    private fun setupRecyclerView() {
        rowAdapter = RowAdapter { row ->
            viewModel.toggleRowCollection(row._id)
        }

        recyclerViewRows.adapter = rowAdapter
        recyclerViewRows.layoutManager = LinearLayoutManager(context)
    }

    private fun setupFilters() {
        setupQuarterChips()
        setupFilterModeSpinner()
    }

    private fun setupQuarterChips() {
        // Create chips for quarters 1-4
        for (quarter in 1..4) {
            val chip = Chip(requireContext()).apply {
                text = "К$quarter"
                isCheckable = true
                isChecked = true // All selected by default
                setOnCheckedChangeListener { _, _ ->
                    updateSelectedQuarters()
                }
            }
            quarterChipGroup.addView(chip)
        }
    }

    private fun setupFilterModeSpinner() {
        val filterModes = arrayOf("Всі рядки", "Зібрані", "Не зібрані")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, filterModes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        filterModeSpinner.adapter = adapter

        filterModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = when (position) {
                    0 -> RowCollectionViewModel.FilterMode.ALL
                    1 -> RowCollectionViewModel.FilterMode.COLLECTED
                    2 -> RowCollectionViewModel.FilterMode.UNCOLLECTED
                    else -> RowCollectionViewModel.FilterMode.ALL
                }
                viewModel.setFilterMode(mode)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateSelectedQuarters() {
        val selectedQuarters = mutableSetOf<Int>()
        for (i in 0 until quarterChipGroup.childCount) {
            val chip = quarterChipGroup.getChildAt(i) as Chip
            if (chip.isChecked) {
                selectedQuarters.add(i + 1) // Quarter numbers are 1-based
            }
        }
        viewModel.setSelectedQuarters(selectedQuarters)
    }

    private fun setupObservers() {
        // Observe rows data
        launchWhenStarted("rows-flow") {
            viewModel.groupedRows.collect { groupedRows ->
                val actualCounts = viewModel.getActualCollectedCounts()
                rowAdapter.submitGroupedRowsWithActualCounts(groupedRows, actualCounts)
            }
        }

        // Observe loading state
        launchWhenStarted("loading-state") {
            viewModel.isLoading.collect { isLoading ->
                loadingProgressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }

        // Observe errors
        launchWhenStarted("error-flow") {
            viewModel.error.collect { errorMessage ->
                errorMessage?.let {
                    Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                    viewModel.clearError()
                }
            }
        }

        // Observe connection state
        launchWhenStarted("connection-state") {
            viewModel.connectionState.collect { connectionState ->
                updateConnectionStatus(connectionState)
            }
        }
    }

    private fun updateConnectionStatus(connectionState: ConnectionState) {
        when (connectionState) {
            is ConnectionState.Connected -> {
                connectionStatusView.setBackgroundColor(resources.getColor(android.R.color.holo_green_light, null))
            }
            is ConnectionState.Disconnected -> {
                connectionStatusView.setBackgroundColor(resources.getColor(android.R.color.holo_red_light, null))
            }

            is ConnectionState.Error -> {}
        }
    }
}