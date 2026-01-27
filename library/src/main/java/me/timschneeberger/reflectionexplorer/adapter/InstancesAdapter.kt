package me.timschneeberger.reflectionexplorer.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.reflectionexplorer.ErrorInstance
import me.timschneeberger.reflectionexplorer.Instance
import me.timschneeberger.reflectionexplorer.R
import me.timschneeberger.reflectionexplorer.databinding.ItemMemberBinding
import me.timschneeberger.reflectionexplorer.databinding.ItemMemberHeaderBinding
import me.timschneeberger.reflectionexplorer.utils.dpToPx

// sentinel object used to mark header entries (so Instance can still be the single type T)
private val HEADER_SENTINEL = Any()

// build a list that injects header Instance objects before each group
private fun itemsWithHeaders(items: List<Instance>): List<Instance> {
    val out = mutableListOf<Instance>()
    var lastGroupName: String? = null
    for (it in items.sortedBy { it.group?.name }) {
        val g = it.group
        if (g != null) {
            if (g.name != lastGroupName) {
                // insert a header Instance for this group; use HEADER_SENTINEL as the instance marker
                out.add(Instance(instance = HEADER_SENTINEL, name = g.name, group = g))
                lastGroupName = g.name
            }
        } else {
            // reset group tracking when encountering ungrouped items
            lastGroupName = null
        }
        out.add(it)
    }
    return out
}

class InstancesAdapter(
    items: List<Instance>,
    private val collapsedGroups: MutableSet<String>,
    private val onClick: (Instance) -> Unit
) : ExpandableListAdapter<Instance>(
    itemsWithHeaders(items),
    collapsedGroups
) {
    private val originalItems: MutableList<Instance> = items.toMutableList()

    init {
        // In the instance list, collapse all groups by default
        collapsedGroups.addAll(items.mapNotNull { it.group?.name }.distinct())
    }

    /**
     * Apply a text filter (case-insensitive) on the original items and update the visible list.
     * Empty or blank query clears the filter and shows all items.
     */
    fun filter(query: String?) {
        val q = query?.trim()?.lowercase() ?: ""
        val filteredRaw = if (q.isEmpty()) originalItems.toList() else originalItems.filter { inst ->
            val name = inst.name ?: inst.instance::class.java.simpleName
            val combined = listOfNotNull(name, inst.instance::class.java.simpleName, inst.instance.toString()).joinToString(" ")
            combined.lowercase().contains(q)
        }
        super.update(itemsWithHeaders(filteredRaw))
    }

    // ViewHolders
    class VH(val binding: ItemMemberBinding) : RecyclerView.ViewHolder(binding.root)
    class HeaderVH(val binding: ItemMemberHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    override fun isHeader(item: Instance): Boolean = item.instance === HEADER_SENTINEL
    override fun headerKey(item: Instance): String = item.group?.name ?: ""

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) HeaderVH(ItemMemberHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        else VH(ItemMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun bindHeaderVH(holder: RecyclerView.ViewHolder, item: Instance) {
        val hv = holder as HeaderVH
        val group = item.group!!
        val collapsed = collapsedGroups.contains(group.name)

        // Count items under this header in fullItems (stop at next header)
        val count = fullItems.dropWhile { it !== item }.drop(1).takeWhile { !isHeader(it) }.count()

        hv.binding.apply {
            headerChevron.rotation = if (collapsed) 90f else -90f
            headerTitle.text = hv.binding.root.context.getString(R.string.header_title, group.name, count)
            headerSubtitle.text = group.subtitle ?: ""
            headerSubtitle.isVisible = headerSubtitle.text.isNotEmpty()
            headerCard.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = if (collapsed) 0.dpToPx() else 7.dpToPx() }
            root.setOnClickListener {
                toggleHeaderCollapsed(item)
                notifyDataSetChanged()
            }
        }
    }

    override fun bindItemVH(holder: RecyclerView.ViewHolder, item: Instance) {
        val hv = holder as VH
        val position = visibleItems.indexOf(item)

        // Apply rounded background using base helper
        applyRoundedBackground(hv.binding.memberContainer, position)

        hv.binding.apply {
            memberTitle.text = item.name ?: item.instance::class.java.simpleName
            memberSubtitle.text = item.instance.toString()
            memberIcon.setImageResource(if(item is ErrorInstance) R.drawable.ic_error_class else R.drawable.ic_class)
            root.setOnClickListener { onClick(item) }
        }
    }
}
