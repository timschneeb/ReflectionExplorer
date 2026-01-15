package me.timschneeberger.reflectionexplorer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class InstancesAdapter(
    private val items: List<Any>,
    private val onClick: (Any) -> Unit
) : RecyclerView.Adapter<InstancesAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.item_title)
        val subtitle: TextView = view.findViewById(R.id.item_subtitle)
        val icon: android.widget.ImageView = view.findViewById(R.id.item_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_instance, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item::class.java.simpleName
        holder.subtitle.text = item.toString()
        holder.icon.setImageResource(R.drawable.ic_class)
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
