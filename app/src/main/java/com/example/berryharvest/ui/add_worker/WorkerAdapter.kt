import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.R
import com.example.berryharvest.ui.add_worker.Worker

class WorkerAdapter(private val onItemLongClick: (Worker) -> Unit) : ListAdapter<Worker, WorkerAdapter.WorkerViewHolder>(WorkerDiffCallback()) {

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
        holder.idTextView.text = worker.id
        holder.fullNameTextView.text = worker.fullName
        holder.phoneNumberTextView.text = worker.phoneNumber

        holder.itemView.setOnLongClickListener {
            onItemLongClick(worker)
            true
        }
    }

    class WorkerDiffCallback : DiffUtil.ItemCallback<Worker>() {
        override fun areItemsTheSame(oldItem: Worker, newItem: Worker): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Worker, newItem: Worker): Boolean {
            return oldItem == newItem
        }
    }
}

