package me.timschneeberger.reflectionexplorer.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.reflectionexplorer.R
import me.timschneeberger.reflectionexplorer.databinding.ItemMemberBinding

class InstancesAdapter(
    private val items: List<Any>,
    private val onClick: (Any) -> Unit
) : ExpandableListAdapter<Any>(items, mutableSetOf()) {

    class VH(val binding: ItemMemberBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun isHeader(item: Any): Boolean = false
    override fun headerKey(item: Any): String = ""

    override fun bindHeaderVH(holder: RecyclerView.ViewHolder, item: Any) {}

    override fun bindItemVH(
        holder: RecyclerView.ViewHolder,
        item: Any
    ) {
        (holder as VH).binding.apply {
            applyRoundedBackground(memberContainer, visibleItems.indexOf(item), forceSingle = true)

            memberTitle.text = item::class.java.simpleName
            memberSubtitle.text = item.toString()
            memberIcon.setImageResource(R.drawable.ic_class)
            root.setOnClickListener { onClick(item) }
        }
    }

    override fun getItemCount(): Int = items.size
}
