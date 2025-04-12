package com.example.berryharvest.ui.gather

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.R
import com.example.berryharvest.data.model.Gather
import java.text.SimpleDateFormat
import java.util.Locale

class GatherAdapter(
    private val onEditClick: (Gather) -> Unit,
    private val onDeleteClick: (Gather) -> Unit
) : ListAdapter<GatherWithDetails, GatherAdapter.GatherViewHolder>(GatherDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GatherViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gather, parent, false)
        return GatherViewHolder(view)
    }

    override fun onBindViewHolder(holder: GatherViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class GatherViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val timeTextView: TextView = itemView.findViewById(R.id.timeTextView)
        private val syncStatusIcon: ImageView = itemView.findViewById(R.id.syncStatusIcon)
        private val workerNameTextView: TextView = itemView.findViewById(R.id.workerNameTextView)
        private val rowNumberTextView: TextView = itemView.findViewById(R.id.rowNumberTextView)
        private val punnetsCountTextView: TextView = itemView.findViewById(R.id.punnetsCountTextView)
        private val amountTextView: TextView = itemView.findViewById(R.id.amountTextView)
        private val editButton: Button = itemView.findViewById(R.id.editButton)
        private val deleteButton: Button = itemView.findViewById(R.id.deleteButton)

        @SuppressLint("SetTextI18n")
        fun bind(gatherWithDetails: GatherWithDetails) {
            val gather = gatherWithDetails.gather

            // Format time
            val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            gather.dateTime?.let {
                try {
                    val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(it)
                    timeTextView.text = dateFormat.format(date)
                } catch (e: Exception) {
                    timeTextView.text = "N/A"
                }
            } ?: run {
                timeTextView.text = "N/A"
            }

            // Show sync status
            syncStatusIcon.visibility = if (gather.isSynced == false) View.VISIBLE else View.GONE

            // Worker info
            workerNameTextView.text = gatherWithDetails.workerName
            rowNumberTextView.text = gather.rowNumber?.toString() ?: "N/A"

            // Punnets info
            punnetsCountTextView.text = gather.numOfPunnets?.toString() ?: "0"

            // Calculate amount
            val amount = (gather.numOfPunnets ?: 0) * (gather.punnetCost ?: 0f)
            amountTextView.text = String.format(Locale.getDefault(), "%.2f₴", amount)

            // Set up buttons
            editButton.setOnClickListener { onEditClick(gather) }
            deleteButton.setOnClickListener { onDeleteClick(gather) }
        }
    }

    class GatherDiffCallback : DiffUtil.ItemCallback<GatherWithDetails>() {
        override fun areItemsTheSame(oldItem: GatherWithDetails, newItem: GatherWithDetails): Boolean {
            return oldItem.gather._id == newItem.gather._id
        }

        override fun areContentsTheSame(oldItem: GatherWithDetails, newItem: GatherWithDetails): Boolean {
            return oldItem == newItem
        }
    }
}

data class GatherWithDetails(
    val gather: Gather,
    val workerName: String,
    val dateTime: String? = null
)