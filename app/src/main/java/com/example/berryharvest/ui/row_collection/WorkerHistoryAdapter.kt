package com.example.berryharvest.ui.row_collection

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.R
import com.example.berryharvest.ui.gather.GatherWithDetails
import java.text.SimpleDateFormat
import java.util.*

class WorkerHistoryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items = listOf<HistoryItem>()

    companion object {
        private const val TYPE_DATE_HEADER = 0
        private const val TYPE_WORKER_ENTRY = 1
    }

    sealed class HistoryItem {
        data class DateHeader(val date: String, val totalPunnets: Int) : HistoryItem()
        data class WorkerEntry(
            val workerName: String,
            val totalPunnets: Int
        ) : HistoryItem()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submitHistory(gathersWithDetails: List<GatherWithDetails>) {
        val newItems = mutableListOf<HistoryItem>()

        // Group by date first
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val displayDateFormat = SimpleDateFormat("d MMMM yyyy", Locale("uk", "UA"))

        val groupedByDate = gathersWithDetails.groupBy { gatherWithDetails ->
            try {
                val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .parse(gatherWithDetails.gather.dateTime ?: "")
                dateFormat.format(dateTime ?: Date())
            } catch (e: Exception) {
                "Невідома дата"
            }
        }

        // Sort dates in descending order (newest first)
        groupedByDate.toSortedMap(compareByDescending { it }).forEach { (dateKey, dayGathers) ->

            // Calculate total punnets for the day
            val totalPunnetsForDay = dayGathers.sumOf { it.gather.numOfPunnets ?: 0 }

            // Format date for display
            val displayDate = try {
                if (dateKey != "Невідома дата") {
                    val date = dateFormat.parse(dateKey)
                    displayDateFormat.format(date ?: Date())
                } else {
                    dateKey
                }
            } catch (e: Exception) {
                dateKey
            }

            // Add date header
            newItems.add(HistoryItem.DateHeader(displayDate, totalPunnetsForDay))

            // Group by worker within the day
            val groupedByWorker = dayGathers.groupBy { it.workerName }

            // Sort workers by total punnets descending
            groupedByWorker.toList()
                .sortedByDescending { (_, workerGathers) ->
                    workerGathers.sumOf { it.gather.numOfPunnets ?: 0 }
                }
                .forEach { (workerName, workerGathers) ->
                    val totalPunnets = workerGathers.sumOf { it.gather.numOfPunnets ?: 0 }

                    newItems.add(
                        HistoryItem.WorkerEntry(
                            workerName = workerName,
                            totalPunnets = totalPunnets
                        )
                    )
                }
        }

        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is HistoryItem.DateHeader -> TYPE_DATE_HEADER
            is HistoryItem.WorkerEntry -> TYPE_WORKER_ENTRY
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_DATE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_history_date_header, parent, false)
                DateHeaderViewHolder(view)
            }
            TYPE_WORKER_ENTRY -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_worker_history, parent, false)
                WorkerEntryViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is HistoryItem.DateHeader -> (holder as DateHeaderViewHolder).bind(item)
            is HistoryItem.WorkerEntry -> (holder as WorkerEntryViewHolder).bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class DateHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dateHeaderTextView: TextView = itemView.findViewById(R.id.dateHeaderTextView)

        @SuppressLint("SetTextI18n")
        fun bind(header: HistoryItem.DateHeader) {
            dateHeaderTextView.text = "${header.date} • ${header.totalPunnets} пін. всього"
        }
    }

    inner class WorkerEntryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val workerNameTextView: TextView = itemView.findViewById(R.id.workerNameTextView)
        private val totalPunnetsTextView: TextView = itemView.findViewById(R.id.totalPunnetsTextView)

        @SuppressLint("SetTextI18n")
        fun bind(workerEntry: HistoryItem.WorkerEntry) {
            workerNameTextView.text = workerEntry.workerName
            totalPunnetsTextView.text = "${workerEntry.totalPunnets} пін."
        }
    }
}