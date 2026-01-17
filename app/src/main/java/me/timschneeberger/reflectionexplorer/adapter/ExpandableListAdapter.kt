package me.timschneeberger.reflectionexplorer.adapter

import android.annotation.SuppressLint
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.reflectionexplorer.R

/**
 * Generic base adapter that manages a list with header items (expand/collapse groups) and visible filtering.
 * Subclasses must implement header recognition and binding helpers.
 */
abstract class ExpandableListAdapter<T : Any>(items: List<T>,
                                              private val collapsedKeys: MutableSet<String>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    protected var fullItems: List<T> = items
    protected val visibleItems: MutableList<T> = mutableListOf()

    init { rebuildVisible() }

    protected abstract fun isHeader(item: T): Boolean
    protected abstract fun headerKey(item: T): String

    // binders to be implemented by subclasses
    protected abstract fun bindHeaderVH(holder: RecyclerView.ViewHolder, item: T)
    protected abstract fun bindItemVH(holder: RecyclerView.ViewHolder, item: T)

    override fun getItemViewType(position: Int): Int = if (isHeader(visibleItems[position])) TYPE_HEADER else TYPE_MEMBER
    override fun getItemCount(): Int = visibleItems.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        // default dispatch binding to subclass implementations
        val item = visibleItems[position]
        if (isHeader(item)) bindHeaderVH(holder, item) else bindItemVH(holder, item)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun update(newItems: List<T>) {
        fullItems = newItems
        rebuildVisible()
        notifyDataSetChanged()
    }

    private fun rebuildVisible() {
        visibleItems.clear()
        var currentHeader: T? = null
        var skip = false
        for (it in fullItems) {
            if (isHeader(it)) {
                currentHeader = it
                visibleItems.add(it)
                skip = collapsedKeys.contains(headerKey(it))
            } else {
                if (currentHeader == null || !skip) visibleItems.add(it)
            }
        }
    }

    protected fun toggleHeaderCollapsed(item: T) {
        val key = headerKey(item)
        if (collapsedKeys.contains(key)) collapsedKeys.remove(key) else collapsedKeys.add(key)
        rebuildVisible()
    }

    /** Apply rounded background resource selection logic to any container view based on header groups. */
    protected fun applyRoundedBackground(container: View, position: Int,
                                         topRes: Int = R.drawable.bg_member_top,
                                         bottomRes: Int = R.drawable.bg_member_bottom,
                                         middleRes: Int = R.drawable.bg_member_middle,
                                         singleRes: Int = R.drawable.bg_member_single) {
        val headerIndex = (position - 1 downTo 0).firstOrNull { isHeader(visibleItems[it]) } ?: -1
        if (headerIndex == -1) {
            // TODO doesn't handle row group before first header
            container.setBackgroundResource(singleRes)
        } else {
            val start = headerIndex + 1
            var end = start
            while (end < visibleItems.size && !isHeader(visibleItems[end])) end++
            val count = end - start
            val bg = when {
                count <= 1 -> singleRes
                position - start == 0 -> topRes
                position - start == count - 1 -> bottomRes
                else -> middleRes
            }
            container.setBackgroundResource(bg)
        }
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_MEMBER = 1
    }
}
