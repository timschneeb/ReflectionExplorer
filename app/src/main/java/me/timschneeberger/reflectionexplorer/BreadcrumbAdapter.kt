package me.timschneeberger.reflectionexplorer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

class BreadcrumbAdapter(
    private var items: List<String>,
    private var selectedIndex: Int = items.size - 1,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<BreadcrumbAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val chip: com.google.android.material.chip.Chip = view.findViewById(R.id.breadcrumb_chip)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_breadcrumb, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val label = items.getOrNull(position) ?: ""
        val display = if (position < items.size - 1) "$label ›" else label
        holder.chip.text = display
        // selected chip uses checkable style
        holder.chip.isCheckable = true
        holder.chip.isChecked = position == selectedIndex
        holder.chip.isClickable = true
        holder.chip.setOnCheckedChangeListener { _, _ ->
            if (position == selectedIndex)
                holder.chip.isChecked = true // cannot uncheck the selected chip

            onClick(position)
        }
    }

    override fun getItemCount(): Int = items.size

    fun update(newItems: List<String>, newSelectedIndex: Int = newItems.size - 1) {
        val oldItems = items
        val oldSelected = selectedIndex
        // compute proper coerced new selected index
        val coercedSelected = if (newItems.isEmpty()) 0 else newSelectedIndex.coerceIn(0, newItems.size - 1)
        // use DiffUtil to compute item changes
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                // breadcrumb identity is the string content
                return oldItems[oldItemPosition] == newItems[newItemPosition]
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition] == newItems[newItemPosition]
            }
        })

        // apply update
        items = newItems
        selectedIndex = coercedSelected
        diff.dispatchUpdatesTo(this)

        // If selection moved, notify item changed for old and new positions so chips update visually
        if (oldItems.isEmpty() && items.isEmpty()) return
        if (oldSelected != selectedIndex) {
            if (oldSelected in 0 until (oldItems.size)) notifyItemChanged(oldSelected)
            if (selectedIndex in 0 until items.size) notifyItemChanged(selectedIndex)
        }
    }
}
