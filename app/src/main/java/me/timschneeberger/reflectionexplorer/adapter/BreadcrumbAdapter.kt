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

    private var rvRef: RecyclerView? = null
    private var pendingUpdate: Pair<List<String>, Int>? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        rvRef = recyclerView
        // If there's a pending update from while computing layout, apply it now
        pendingUpdate?.let { (items, sel) ->
            pendingUpdate = null
            update(items, sel)
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        rvRef = null
        pendingUpdate = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemBreadcrumbBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val label = items.getOrNull(position) ?: ""
        holder.binding.breadcrumbChip.apply {
            // avoid triggering previous listeners when changing checked state
            setOnCheckedChangeListener(null)
            text = if (position < items.size - 1) "$label ›" else label
            isCheckable = true
            isChecked = position == selectedIndex
            setOnCheckedChangeListener { _, checked ->
                if (!checked) return@setOnCheckedChangeListener
                // if already selected, keep checked and do nothing
                if (position == selectedIndex) {
                    // ensure visual remains checked without invoking onClick
                    this@apply.post { this@apply.isChecked = true }
                    return@setOnCheckedChangeListener
                }
                onClick(position)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun update(newItems: List<String>, newSelectedIndex: Int = newItems.size - 1) {
        // If RecyclerView is computing layout, defer the update to avoid IllegalStateException
        val rv = rvRef
        if (rv?.isComputingLayout == true) {
            pendingUpdate = newItems to newSelectedIndex
            rv.post { pendingUpdate?.let { (i, s) -> pendingUpdate = null; update(i, s) } }
            return
        }

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
