package com.example.berryharvest.ui.add_worker

import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.R

class WorkerAdapter(private val onItemLongClick: (Worker) -> Unit) :
    ListAdapter<Worker, WorkerAdapter.WorkerViewHolder>(WorkerDiffCallback()) {

    class WorkerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val idTextView: TextView = view.findViewById(R.id.textViewId)
        val fullNameTextView: TextView = view.findViewById(R.id.textViewFullName)
        val phoneNumberTextView: TextView = view.findViewById(R.id.textViewPhoneNumber)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WorkerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_worker, parent, false)
        return WorkerViewHolder(view)
    }

    override fun onBindViewHolder(holder: WorkerViewHolder, position: Int) {
        val worker = getItem(position)
        holder.idTextView.text = worker._id
        holder.fullNameTextView.text = worker.fullName
        holder.phoneNumberTextView.text = worker.phoneNumber

        // Установка цвета фона в зависимости от статуса синхронизации
        when {
            worker.isDeleted -> holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            !worker.isSynced -> holder.itemView.setBackgroundColor(Color.RED)
            else -> holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }

        // Если работник удалён, перечёркиваем текст
        val paintFlags = if (worker.isDeleted) Paint.STRIKE_THRU_TEXT_FLAG else 0
        holder.fullNameTextView.paintFlags = paintFlags
        holder.phoneNumberTextView.paintFlags = paintFlags

        // Обработка долгого нажатия для открытия диалога с опциями
        holder.itemView.setOnLongClickListener {
            onItemLongClick(worker)
            true // Возвращаем true, чтобы указать, что событие обработано
        }
    }

    class WorkerDiffCallback : DiffUtil.ItemCallback<Worker>() {
        override fun areItemsTheSame(oldItem: Worker, newItem: Worker): Boolean {
            return oldItem._id == newItem._id
        }

        override fun areContentsTheSame(oldItem: Worker, newItem: Worker): Boolean {
            return oldItem == newItem && oldItem.isSynced == newItem.isSynced && oldItem.isDeleted == newItem.isDeleted
        }
    }
}



