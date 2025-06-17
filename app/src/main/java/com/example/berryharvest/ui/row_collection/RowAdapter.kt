package com.example.berryharvest.ui.row_collection

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.R
import com.example.berryharvest.data.model.Row

class RowAdapter(
    private val onRowClick: (Row) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items = listOf<RowItem>()

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ROW = 1
        private const val ROWS_PER_QUARTER = 100 // Configurable if needed
    }

    sealed class RowItem {
        data class Header(val quarter: Int, val collectedCount: Int, val totalCount: Int) : RowItem()
        data class RowData(val row: Row) : RowItem()
    }

    fun submitGroupedRows(groupedRows: Map<Int, List<Row>>) {
        val newItems = mutableListOf<RowItem>()

        // Sort quarters and create headers with rows
        groupedRows.toSortedMap().forEach { (quarter, rows) ->
            // For the header, we need to show the actual collected count for this quarter
            // regardless of the current filter. We'll calculate this from the rows we have.
            val collectedCount = rows.count { it.isCollected }

            // Always show total as ROWS_PER_QUARTER, not the filtered count
            newItems.add(RowItem.Header(quarter, collectedCount, ROWS_PER_QUARTER))
            rows.sortedBy { it.rowNumber }.forEach { row ->
                newItems.add(RowItem.RowData(row))
            }
        }

        items = newItems
        notifyDataSetChanged()
    }

    fun submitGroupedRowsWithActualCounts(groupedRows: Map<Int, List<Row>>, actualCounts: Map<Int, Int>) {
        val newItems = mutableListOf<RowItem>()

        // Sort quarters and create headers with rows
        groupedRows.toSortedMap().forEach { (quarter, rows) ->
            // Use the actual collected count for this quarter from all rows (not just filtered)
            val actualCollectedCount = actualCounts[quarter] ?: 0

            newItems.add(RowItem.Header(quarter, actualCollectedCount, ROWS_PER_QUARTER))
            rows.sortedBy { it.rowNumber }.forEach { row ->
                newItems.add(RowItem.RowData(row))
            }
        }

        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is RowItem.Header -> TYPE_HEADER
            is RowItem.RowData -> TYPE_ROW
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_quarter_header, parent, false)
                QuarterHeaderViewHolder(view)
            }
            TYPE_ROW -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_row, parent, false)
                RowViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is RowItem.Header -> (holder as QuarterHeaderViewHolder).bind(item)
            is RowItem.RowData -> (holder as RowViewHolder).bind(item.row)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class QuarterHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val quarterTitleTextView: TextView = itemView.findViewById(R.id.quarterTitleTextView)
        private val progressTextView: TextView = itemView.findViewById(R.id.progressTextView)

        @SuppressLint("SetTextI18n")
        fun bind(header: RowItem.Header) {
            quarterTitleTextView.text = "Квартал ${header.quarter}"
            progressTextView.text = "${header.collectedCount}/${header.totalCount} зібрано"

            // Use theme-aware text color that works in both light and dark modes
            val context = itemView.context
            val attrs = intArrayOf(android.R.attr.textColorPrimary)
            val typedArray = context.obtainStyledAttributes(attrs)
            val textColor = typedArray.getColor(0, context.getColor(android.R.color.black))
            typedArray.recycle()
            progressTextView.setTextColor(textColor)
        }
    }

    inner class RowViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val rowNumberTextView: TextView = itemView.findViewById(R.id.rowNumberTextView)
        private val collectedCheckBox: CheckBox = itemView.findViewById(R.id.collectedCheckBox)
        private val syncStatusIcon: ImageView = itemView.findViewById(R.id.syncStatusIcon)

        @SuppressLint("SetTextI18n")
        fun bind(row: Row) {
            rowNumberTextView.text = "Рядок ${row.rowNumber}"

            // Set checkbox without triggering listener
            collectedCheckBox.setOnCheckedChangeListener(null)
            collectedCheckBox.isChecked = row.isCollected
            collectedCheckBox.setOnCheckedChangeListener { _, _ ->
                onRowClick(row)
            }

            // Show sync status for unsynced rows
            syncStatusIcon.visibility = if (!row.isSynced) View.VISIBLE else View.GONE

            // Set background using theme-appropriate shading
            val backgroundColor = when {
                row.isCollected -> {
                    // Light green tint that works in both themes
                    Color.argb(38, 76, 175, 80) // Light green with alpha
                }
                !row.isSynced -> {
                    // Light orange tint that works in both themes
                    Color.argb(38, 255, 152, 0) // Light orange with alpha
                }
                else -> Color.TRANSPARENT
            }
            itemView.setBackgroundColor(backgroundColor)

            // Set click listener for entire row
            itemView.setOnClickListener {
                onRowClick(row)
            }
        }
    }
}