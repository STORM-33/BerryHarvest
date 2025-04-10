package com.example.berryharvest.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.R

class SearchableSpinnerAdapter<T : SearchableItem>(
    private val allItems: List<T>,
    private val onItemClick: (T) -> Unit
) : RecyclerView.Adapter<SearchableSpinnerAdapter<T>.ViewHolder>() {

    private var filteredItems: List<T> = allItems

    inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(R.id.itemTextView)

        fun bind(item: T) {
            textView.text = item.getDisplayText()
            view.setOnClickListener { onItemClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_searchable_spinner, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(filteredItems[position])
    }

    override fun getItemCount() = filteredItems.size

    fun filter(query: String) {
        filteredItems = if (query.isEmpty()) {
            allItems
        } else {
            allItems.filter { item ->
                item.getSearchableText().contains(query, ignoreCase = true)
            }
        }
        notifyDataSetChanged()
    }
}