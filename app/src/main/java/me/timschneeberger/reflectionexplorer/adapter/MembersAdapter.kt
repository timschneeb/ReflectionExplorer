package me.timschneeberger.reflectionexplorer.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
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
import me.timschneeberger.reflectionexplorer.utils.Dialogs
import me.timschneeberger.reflectionexplorer.utils.dpToPx
import me.timschneeberger.reflectionexplorer.utils.getFieldDrawable
import me.timschneeberger.reflectionexplorer.utils.getMethodDrawable
import java.lang.reflect.Array
import java.util.LinkedHashMap

private const val TYPE_HEADER = 0
private const val TYPE_MEMBER = 1

class MembersAdapter(
    items: List<MemberInfo>,
    // rootInstance can change when we replace arrays/lists/maps; make it mutable
    var rootInstance: Any,
    // index of this inspected instance in the global inspection stack (used to replace arrays/lists/maps)
    private val stackIndex: Int,
    // external set (from ViewModel) to persist collapsed state across rotations
    private val collapsedClasses: MutableSet<String>,
    private val onClick: (MemberInfo) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var fullItems: List<MemberInfo> = items
    private var visibleItems: MutableList<MemberInfo> = mutableListOf()

    init { rebuildVisible() }

    // Helpers to safely access arrays without throwing when concurrently modified
    private fun safeArrayLength(arr: Any?): Int = try { if (arr != null && arr.javaClass.isArray) Array.getLength(arr) else 0 } catch (_: Exception) { 0 }
    private fun safeArrayGet(arr: Any?, idx: Int): Any? = try { if (arr != null && arr.javaClass.isArray) { val len = Array.getLength(arr); if (idx in 0 until len) Array.get(arr, idx) else null } else null } catch (_: Exception) { null }

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

            holder.binding.btnSet.isVisible = false
            holder.binding.btnDelete.isVisible = false
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

            btnSet.isVisible = Dialogs.canParseType(item.field.type)
            btnSet.setOnClickListener {
                val act = root.context as? MainActivity ?: return@setOnClickListener
                // show dialog to set field value; on success, propagate change into stack
                act.showSetFieldDialog(rootInstance, item) { ok, _ ->
                    if (ok) act.replaceStackAt(stackIndex, rootInstance)
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
            root.setOnClickListener { onClick(item) }
        }
    }

    private fun bindElement(hv: VH, item: ElementInfo, preview: String) {
        hv.binding.apply {
            memberTitle.text = item.name

            // Safely resolve the current element value from the (possibly replaced) rootInstance.
            val currentValue: Any? = when {
                rootInstance is List<*> -> {
                    val lst = rootInstance as List<*>
                    if (item.index in 0 until lst.size) lst[item.index] else null
                }
                rootInstance.javaClass.isArray -> {
                    val len = try { Array.getLength(rootInstance) } catch (_: Exception) { 0 }
                    if (item.index in 0 until len) Array.get(rootInstance, item.index) else null
                }
                else -> null
            }

            memberIcon.setImageResource(R.drawable.ic_class)
            memberSubtitle.text = currentValue?.let { it::class.java.simpleName + " -> " + formatPreview(it) } ?: "null"

            // show edit/delete when applicable; guard by currentValue presence and index bounds
            btnSet.isVisible = currentValue != null && Dialogs.canParseType(currentValue::class.java)
            btnDelete.isVisible = when (rootInstance) {
                is List<*> -> item.index in 0 until (rootInstance as List<*>).size
                else -> rootInstance.javaClass.isArray && try { item.index in 0 until Array.getLength(rootInstance) } catch (_: Exception) { false }
            }

            btnDelete.setOnClickListener {
                val act = root.context as? MainActivity ?: return@setOnClickListener
                when (rootInstance) {
                    is List<*> -> {
                        // create a mutable copy to avoid UnsupportedOperationException from fixed-size lists
                        @Suppress("UNCHECKED_CAST")
                        val copy = (rootInstance as List<Any?>).toMutableList()
                        if (item.index in 0 until copy.size) {
                            try {
                                copy.removeAt(item.index)
                            } catch (_: UnsupportedOperationException) {
                                // fallback: create a new list excluding the index
                                val filtered = copy.filterIndexed { idx, _ -> idx != item.index }.toMutableList()
                                act.replaceStackAt(stackIndex, filtered)
                                return@setOnClickListener
                            }
                            act.replaceStackAt(stackIndex, copy)
                        }
                    }
                    else -> if (rootInstance.javaClass.isArray) {
                        val comp = rootInstance.javaClass.componentType ?: return@setOnClickListener
                        val len = Array.getLength(rootInstance)
                        if (item.index in 0 until len) {
                            val newArr = Array.newInstance(comp, (len - 1).coerceAtLeast(0))
                            var dst = 0
                            for (i in 0 until len) {
                                if (i == item.index) continue
                                Array.set(newArr, dst++, Array.get(rootInstance, i))
                            }
                            act.replaceStackAt(stackIndex, newArr)
                        }
                    }
                }
            }

            btnSet.setOnClickListener {
                val act = root.context as? MainActivity ?: return@setOnClickListener
                val elemClass = currentValue?.javaClass ?: return@setOnClickListener
                val initial = currentValue?.toString() ?: ""
                Dialogs.showEditValueDialog(act, "Edit element", "Value", initial, elemClass, null, null, root) { ok, parsed, _ ->
                    if (!ok || parsed == null) return@showEditValueDialog
                    when (rootInstance) {
                        is MutableList<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            val lst = rootInstance as MutableList<Any?>
                            if (item.index in 0 until lst.size) lst[item.index] = parsed
                            act.replaceStackAt(stackIndex, rootInstance)
                        }
                        is List<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            val copy = (rootInstance as List<Any?>).toMutableList()
                            if (item.index in 0 until copy.size) copy[item.index] = parsed
                            act.replaceStackAt(stackIndex, copy)
                        }
                        else -> if (rootInstance.javaClass.isArray) {
                            val len = Array.getLength(rootInstance)
                            if (item.index in 0 until len) Array.set(rootInstance, item.index, parsed)
                            act.replaceStackAt(stackIndex, rootInstance)
                        }
                    }
                }
            }

            root.setOnClickListener { onClick(item) }
        }
    }

    private fun bindMapEntry(hv: VH, item: MapEntryInfo, preview: String) {
        hv.binding.apply {
            memberTitle.text = item.key?.toString() ?: ""
            memberSubtitle.text = item.value?.let { it::class.java.simpleName + " -> " + preview } ?: "null"
            memberIcon.setImageResource(R.drawable.ic_field)
            btnSet.isVisible = false
            btnDelete.isVisible = false

            // maps: allow delete and edit of value when possible
            if (rootInstance is MutableMap<*, *>) {
                btnDelete.isVisible = true
                btnSet.isVisible = item.value != null && Dialogs.canParseType(item.value::class.java)
            } else if (rootInstance is Map<*, *>) {
                btnDelete.isVisible = true
                btnSet.isVisible = item.value != null && Dialogs.canParseType(item.value::class.java)
            }

            btnDelete.setOnClickListener {
                val activity = root.context as? MainActivity ?: return@setOnClickListener
                if (rootInstance is MutableMap<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val m = rootInstance as MutableMap<Any?, Any?>
                    m.remove(item.key)
                    activity.replaceStackAt(stackIndex, rootInstance)
                } else if (rootInstance is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val copy = LinkedHashMap(rootInstance as Map<Any?, Any?>)
                    copy.remove(item.key)
                    activity.replaceStackAt(stackIndex, copy)
                }
            }

            btnSet.setOnClickListener {
                val activity = root.context as? MainActivity ?: return@setOnClickListener
                val valueClass = item.value?.javaClass
                if (valueClass != null) {
                    Dialogs.showEditValueDialog(activity, "Edit value", "Value", item.value?.toString() ?: "", valueClass, null, null, root) { ok, parsed, _ ->
                        if (!ok || parsed == null) return@showEditValueDialog
                        if (rootInstance is MutableMap<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val m = rootInstance as MutableMap<Any?, Any?>
                            m[item.key] = parsed
                            activity.replaceStackAt(stackIndex, rootInstance)
                        } else if (rootInstance is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val copy = LinkedHashMap(rootInstance as Map<Any?, Any?>)
                            copy[item.key] = parsed
                            activity.replaceStackAt(stackIndex, copy)
                        }
                    }
                }
            }

            root.setOnClickListener { onClick(item) }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun update(newItems: List<MemberInfo>, newRootInstance: Any? = null) {
        newRootInstance?.let { this.rootInstance = it }
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
