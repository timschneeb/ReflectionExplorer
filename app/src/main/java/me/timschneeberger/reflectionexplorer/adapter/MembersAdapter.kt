package me.timschneeberger.reflectionexplorer.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.reflectionexplorer.MainActivity
import me.timschneeberger.reflectionexplorer.utils.ClassHeaderInfo
import me.timschneeberger.reflectionexplorer.utils.ElementInfo
import me.timschneeberger.reflectionexplorer.utils.FieldInfo
import me.timschneeberger.reflectionexplorer.utils.MapEntryInfo
import me.timschneeberger.reflectionexplorer.utils.MemberInfo
import me.timschneeberger.reflectionexplorer.utils.MethodInfo
import me.timschneeberger.reflectionexplorer.R
import me.timschneeberger.reflectionexplorer.utils.ReflectionInspector
import me.timschneeberger.reflectionexplorer.databinding.ItemMemberBinding
import me.timschneeberger.reflectionexplorer.databinding.ItemMemberHeaderBinding
import me.timschneeberger.reflectionexplorer.utils.dpToPx
import me.timschneeberger.reflectionexplorer.utils.getFieldDrawable
import me.timschneeberger.reflectionexplorer.utils.getMethodDrawable
import java.lang.reflect.Array

private const val TYPE_HEADER = 0
private const val TYPE_MEMBER = 1

class MembersAdapter(
    items: List<MemberInfo>,
    private val rootInstance: Any,
    // external set (from ViewModel) to persist collapsed state across rotations
    private val collapsedClasses: MutableSet<String>,
    private val onClick: (MemberInfo) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var fullItems: List<MemberInfo> = items
    private var visibleItems: MutableList<MemberInfo> = mutableListOf()

    init { rebuildVisible() }

    class VH(val binding: ItemMemberBinding) : RecyclerView.ViewHolder(binding.root)
    class HeaderVH(val binding: ItemMemberHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int = if (visibleItems[position] is ClassHeaderInfo) TYPE_HEADER else TYPE_MEMBER

    override fun getItemCount(): Int = visibleItems.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_HEADER) HeaderVH(ItemMemberHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        else VH(ItemMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = visibleItems[position]

        // Apply rounded backgrounds within header groups
        if (holder is VH) {
            holder.binding.memberContainer.apply {
                // find previous header index
                val headerIndex = (position - 1 downTo 0).firstOrNull { visibleItems[it] is ClassHeaderInfo } ?: -1
                if (headerIndex == -1) {
                    setBackgroundResource(R.drawable.bg_member_single)
                } else {
                    val start = headerIndex + 1
                    var end = start
                    while (end < visibleItems.size && visibleItems[end] !is ClassHeaderInfo) end++
                    val count = end - start
                    val bg = when {
                        count <= 1 -> R.drawable.bg_member_single
                        position - start == 0 -> R.drawable.bg_member_top
                        position - start == count - 1 -> R.drawable.bg_member_bottom
                        else -> R.drawable.bg_member_middle
                    }
                    setBackgroundResource(bg)
                }
            }
        }

        when (item) {
            is ClassHeaderInfo -> bindHeader(holder as HeaderVH, item)
            is FieldInfo -> bindField(holder as VH, item)
            is MethodInfo -> bindMethod(holder as VH, item)
            is ElementInfo -> bindElement(holder as VH, item, formatPreview(item.value))
            is MapEntryInfo -> bindMapEntry(holder as VH, item, formatPreview(item.value))
        }
    }

    private fun formatPreview(v: Any?): String = try {
        when (v) {
            null -> "null"
            is CharSequence -> v.toString().let { s -> if (s.length > 80) "\"${s.take(80)}...\" (len=${s.length})" else "\"$s\"" }
            is Collection<*> -> "size=${v.size} [${v.take(3).joinToString(", ") { it?.toString() ?: "null" }}]"
            is Map<*, *> -> "size=${v.size} {${v.entries.take(3).joinToString(", ") { e -> "${e.key}->${e.value?.toString() ?: "null"}" }}}"
            else -> {
                val cls = v.javaClass
                if (cls.isArray) {
                    val len = Array.getLength(v)
                    val max = len.coerceAtMost(3)
                    val preview = (0 until max).map { i -> Array.get(v, i)?.toString() ?: "null" }
                    "size=$len [${preview.joinToString(", ")}]"
                } else {
                    v.toString().let { s -> if (s.length > 120) "${s.take(120)}... (len=${s.length})" else s }
                }
            }
        }
    } catch (_: Exception) { "<error>" }


    private fun bindHeader(hv: HeaderVH, item: ClassHeaderInfo) {
        val pkg = item.cls.`package`?.name ?: "<default>"
        val collapsed = collapsedClasses.contains(item.cls.name)
        val count = fullItems.dropWhile { it !== item }.drop(1).takeWhile { it !is ClassHeaderInfo }.count()

        hv.binding.apply {
            headerChevron.rotation = if (collapsed) 90f else -90f
            headerTitle.text = "${item.cls.simpleName} ($count)"
            headerSubtitle.text = pkg
            headerCard.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = if (collapsed) 0.dpToPx() else 7.dpToPx() }
            root.setOnClickListener {
                if (collapsed) collapsedClasses.remove(item.cls.name) else collapsedClasses.add(item.cls.name)
                rebuildVisible()
                notifyDataSetChanged()
            }
        }
    }

    private fun bindField(hv: VH, item: FieldInfo) {
        hv.binding.apply {
            memberTitle.text = item.name
            memberSubtitle.text = try {
                val v = ReflectionInspector.getField(rootInstance, item.field)
                "${item.field.type.simpleName} -> ${formatPreview(v = v)}"
            } catch (_: Exception) { "<error>" }
            memberIcon.setImageDrawable(item.field.getFieldDrawable(root.context))

            btnSet.isVisible = MainActivity.canParseType(item.field.type)
            btnSet.setOnClickListener {
                val ctx = root.context
                (ctx as? MainActivity)?.let { act ->
                    act.showSetFieldDialog(rootInstance, item) { ok, errMsg ->
                        if (ok) {
                            // refresh after successful set
                            try {
                                val newV = ReflectionInspector.getField(rootInstance, item.field)
                                memberSubtitle.text = "${item.field.type.simpleName} -> ${formatPreview(v = newV)}"
                            } catch (_: Exception) { /* ignore */ }
                        } else if (errMsg != null) {
                            memberSubtitle.text = "<error: $errMsg>"
                        }
                    }
                }
            }

            root.setOnClickListener { onClick(item) }
        }
    }

    private fun bindMethod(hv: VH, item: MethodInfo) {
        hv.binding.apply {
            val params = item.method.parameterTypes.joinToString(",") { it.simpleName }
            memberTitle.text = "${item.name}($params)"
            memberSubtitle.text = "-> ${item.method.returnType.simpleName}"
            memberIcon.setImageDrawable(item.method.getMethodDrawable(root.context) ?: ContextCompat.getDrawable(root.context, R.drawable.ic_method))
            btnSet.isVisible = false
            root.setOnClickListener { onClick(item) }
        }
    }

    private fun bindElement(hv: VH, item: ElementInfo, preview: String) {
        hv.binding.apply {
            memberTitle.text = item.name
            memberSubtitle.text = item.value?.let { it::class.java.simpleName + " -> " + preview } ?: "null"
            memberIcon.setImageResource(R.drawable.ic_class)
            btnSet.isVisible = false
            root.setOnClickListener { onClick(item) }
        }
    }

    private fun bindMapEntry(hv: VH, item: MapEntryInfo, preview: String) {
        hv.binding.apply {
            memberTitle.text = item.key
            memberSubtitle.text = item.value?.let { it::class.java.simpleName + " -> " + preview } ?: "null"
            memberIcon.setImageResource(R.drawable.ic_field)
            btnSet.isVisible = false
            root.setOnClickListener { onClick(item) }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun update(newItems: List<MemberInfo>) {
        fullItems = newItems
        rebuildVisible()
        notifyDataSetChanged()
    }

    private fun rebuildVisible() {
        visibleItems.clear()
        var currentHeader: ClassHeaderInfo? = null
        var skip = false
        for (it in fullItems) {
            when (it) {
                is ClassHeaderInfo -> {
                    currentHeader = it
                    visibleItems.add(it)
                    skip = collapsedClasses.contains(it.cls.name)
                }
                else -> {
                    if (currentHeader == null || !skip) visibleItems.add(it)
                }
            }
        }
    }
}
