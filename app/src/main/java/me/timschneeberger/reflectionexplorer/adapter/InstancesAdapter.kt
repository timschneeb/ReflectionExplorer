package me.timschneeberger.reflectionexplorer.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.reflectionexplorer.R
import me.timschneeberger.reflectionexplorer.databinding.ItemInstanceBinding

class InstancesAdapter(
    private val items: List<Any>,
    private val onClick: (Any) -> Unit
) : RecyclerView.Adapter<InstancesAdapter.VH>() {

    class VH(val binding: ItemInstanceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemInstanceBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.itemTitle.text = item::class.java.simpleName
        holder.binding.itemSubtitle.text = item.toString()
        holder.binding.itemIcon.setImageResource(R.drawable.ic_class)
        holder.binding.root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
