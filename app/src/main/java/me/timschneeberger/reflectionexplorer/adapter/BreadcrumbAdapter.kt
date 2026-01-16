package me.timschneeberger.reflectionexplorer.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.reflectionexplorer.databinding.ItemBreadcrumbBinding

class BreadcrumbAdapter(
    private var items: List<String>,
    private var selectedIndex: Int = items.size - 1,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<BreadcrumbAdapter.VH>() {

    class VH(val binding: ItemBreadcrumbBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemBreadcrumbBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val label = items.getOrNull(position) ?: ""
        holder.binding.breadcrumbChip.apply {
            text = if (position < items.size - 1) "$label ›" else label
            isCheckable = true
            isChecked = position == selectedIndex
            setOnCheckedChangeListener { _, _ ->
                if (position == selectedIndex)
                    holder.binding.breadcrumbChip.isChecked = true
                onClick(position)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun update(newItems: List<String>, newSelectedIndex: Int = newItems.size - 1) {
        val oldItems = items
        val oldSelected = selectedIndex
        val coercedSelected = if (newItems.isEmpty()) 0 else newSelectedIndex.coerceIn(0, newItems.size - 1)

        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItems[oldItemPosition] == newItems[newItemPosition]
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItems[oldItemPosition] == newItems[newItemPosition]
        })

        items = newItems
        selectedIndex = coercedSelected
        diff.dispatchUpdatesTo(this)

        if (oldSelected != selectedIndex) {
            if (oldSelected in 0 until oldItems.size) notifyItemChanged(oldSelected)
            if (selectedIndex in 0 until items.size) notifyItemChanged(selectedIndex)
        }
    }
}
