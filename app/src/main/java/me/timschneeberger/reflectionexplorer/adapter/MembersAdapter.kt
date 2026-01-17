package me.timschneeberger.reflectionexplorer.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import me.timschneeberger.reflectionexplorer.MainActivity
import me.timschneeberger.reflectionexplorer.utils.ClassHeaderInfo
import me.timschneeberger.reflectionexplorer.utils.ElementInfo
import me.timschneeberger.reflectionexplorer.utils.FieldInfo
import me.timschneeberger.reflectionexplorer.utils.MapEntryInfo
import me.timschneeberger.reflectionexplorer.utils.MemberInfo
import me.timschneeberger.reflectionexplorer.utils.MethodInfo
import me.timschneeberger.reflectionexplorer.utils.CollectionMember
import me.timschneeberger.reflectionexplorer.R
import me.timschneeberger.reflectionexplorer.utils.ReflectionInspector
import me.timschneeberger.reflectionexplorer.databinding.ItemMemberBinding
import me.timschneeberger.reflectionexplorer.databinding.ItemMemberHeaderBinding
import me.timschneeberger.reflectionexplorer.utils.Dialogs
import me.timschneeberger.reflectionexplorer.utils.ReflectionInspector.formatPreview
import me.timschneeberger.reflectionexplorer.utils.dpToPx
import me.timschneeberger.reflectionexplorer.utils.getFieldDrawable
import me.timschneeberger.reflectionexplorer.utils.getMethodDrawable
import java.lang.reflect.Array

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

    // Small helpers to reduce duplication
    private fun isList(): Boolean = rootInstance is List<*>
    private fun asList(): List<Any?>? = rootInstance as? List<Any?>

    private fun isMap(): Boolean = rootInstance is Map<*, *>

    private fun isArray(): Boolean = try { rootInstance.javaClass.isArray } catch (_: Exception) { false }
    private fun arrayLength(): Int = try { if (isArray()) ReflectionInspector.getArrayLength(rootInstance) else 0 } catch (_: Exception) { 0 }

    private fun boxPrimitive(c: Class<*>?): Class<*>? {
        if (c == null) return null
        if (!c.isPrimitive) return c
        return when (c) {
            java.lang.Integer.TYPE -> Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            else -> c
        }
    }

    private fun replaceStack(activity: MainActivity, newValue: Any) {
        activity.replaceStackAt(stackIndex, newValue)
    }

    // Helper: safely get MainActivity from a view and run an action
    private fun runWithActivity(anchor: View, block: (MainActivity) -> Unit) {
        val act = anchor.context as? MainActivity ?: return
        block(act)
    }

    private fun performDelete(activity: MainActivity, item: MemberInfo) {
        if (item is CollectionMember) {
            val newRoot = item.applyDelete(rootInstance)
            if (newRoot != null) activity.replaceStackAt(stackIndex, newRoot)
        }
    }

    private fun performEdit(activity: MainActivity, item: MemberInfo, anchor: View) {
        when (item) {
            is FieldInfo -> activity.showSetFieldDialog(rootInstance, item) { ok, _ -> if (ok) replaceStack(activity, rootInstance) }
            is CollectionMember -> {
                val value = item.getValue(rootInstance)?.toString() ?: ""
                val type = item.getType(rootInstance)

                if (type == Any::class.java) {
                    // cannot determine element type for editing
                    Snackbar.make(anchor, "Cannot determine element type to edit", Snackbar.LENGTH_SHORT).show()
                    return
                }

                Dialogs.showEditValueDialog(activity, activity.getString(R.string.action_set_value),
                    activity.getString(R.string.action_set_value),
                    value, type, null, null, anchor) { ok, parsed, _ ->
                    if (!ok || parsed == null) return@showEditValueDialog
                    val newRoot = item.applyEdit(rootInstance, parsed)
                    if (newRoot != null) activity.replaceStackAt(stackIndex, newRoot)
                }
            }
            else -> {
                // no-op for other types (e.g., Method invocation is handled elsewhere)
            }
        }
    }

    // Element/map mutation helpers moved into ReflectionInspector; adapter calls those through performEdit/performDelete.

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

            // reset common state
            holder.binding.btnSet.isVisible = false
            holder.binding.btnDelete.isVisible = false
        }

        when (item) {
             is ClassHeaderInfo -> bindHeader(holder as HeaderVH, item)
             else -> bindMember((holder as VH), item)
         }
     }

     // Consolidated member binder: handles FieldInfo, MethodInfo, ElementInfo and MapEntryInfo
     private fun bindMember(hv: VH, item: MemberInfo) {
         hv.binding.apply {
             when (item) {
                 is FieldInfo -> {
                     memberTitle.text = item.name
                     memberSubtitle.text = try {
                         val v = ReflectionInspector.getField(rootInstance, item.field)
                         if (v == null) root.context.getString(R.string.member_value_null) else item.field.type.simpleName + " -> " + formatPreview(root.context, v)
                     } catch (_: Exception) { root.context.getString(R.string.error_prefix, "") }
                     memberIcon.setImageDrawable(item.field.getFieldDrawable(root.context))

                     btnSet.isVisible = Dialogs.canParseType(item.field.type)
                     btnSet.setOnClickListener { runWithActivity(root) { act -> act.showSetFieldDialog(rootInstance, item) { ok, _ -> if (ok) act.replaceStackAt(stackIndex, rootInstance) } } }
                 }

                 is MethodInfo -> {
                     val params = item.method.parameterTypes.joinToString(",") { it.simpleName }
                     memberTitle.text = root.context.getString(R.string.member_signature, item.name, params)
                     memberSubtitle.text = root.context.getString(R.string.method_return, item.method.returnType.simpleName)
                     memberIcon.setImageDrawable(item.method.getMethodDrawable(root.context) ?: ContextCompat.getDrawable(root.context, R.drawable.ic_method))
                 }

                 is ElementInfo -> {
                     memberTitle.text = item.name
                     val currentValue: Any? = (item as CollectionMember).getValue(rootInstance)
                     memberIcon.setImageResource(R.drawable.ic_class)
                     memberSubtitle.text = currentValue?.let { root.context.getString(R.string.member_value_format, it::class.java.simpleName, formatPreview(root.context, it)) } ?: root.context.getString(R.string.element_is_null)

                     // Determine whether this element is editable (allow editing arrays even when element is null by checking component type)
                     btnSet.isVisible = Dialogs.canParseType(item.getType(rootInstance))
                     btnDelete.isVisible = when {
                         isList() -> item.index in 0 until (asList()?.size ?: 0)
                         isArray() -> item.index in 0 until arrayLength()
                         else -> false
                     }

                     btnDelete.setOnClickListener { runWithActivity(root) { act -> performDelete(act, item) } }
                     btnSet.setOnClickListener { runWithActivity(root) { act -> performEdit(act, item, root) } }
                 }

                 is MapEntryInfo -> {
                     memberTitle.text = item.key?.toString() ?: ""
                     val currentValue = (item as CollectionMember).getValue(rootInstance)
                     memberSubtitle.text = currentValue?.let { root.context.getString(R.string.member_value_format, it::class.java.simpleName, formatPreview(root.context, it)) } ?: root.context.getString(R.string.member_value_null)
                     memberIcon.setImageResource(R.drawable.ic_field)

                     val canDelete = isMap()
                     val canEdit = currentValue != null && Dialogs.canParseType(currentValue::class.java)
                     btnDelete.isVisible = canDelete
                     btnSet.isVisible = canEdit

                     btnDelete.setOnClickListener { runWithActivity(root) { act -> performDelete(act, item) } }
                     btnSet.setOnClickListener { runWithActivity(root) { act -> performEdit(act, item, root) } }
                 }

                 else -> {
                     // unknown MemberInfo implementation - no-op
                 }
             }

             root.setOnClickListener { onClick(item) }
         }
     }

    private fun bindHeader(hv: HeaderVH, item: ClassHeaderInfo) {
        val pkg = item.cls.`package`?.name ?: "<default>"
        val collapsed = collapsedClasses.contains(item.cls.name)
        val count = fullItems.dropWhile { it !== item }.drop(1).takeWhile { it !is ClassHeaderInfo }.count()

        hv.binding.apply {
            headerChevron.rotation = if (collapsed) 90f else -90f
            headerTitle.text = root.context.getString(R.string.header_title, item.cls.simpleName, count)
            headerSubtitle.text = pkg
            headerCard.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = if (collapsed) 0.dpToPx() else 7.dpToPx() }
            root.setOnClickListener {
                if (collapsed) collapsedClasses.remove(item.cls.name) else collapsedClasses.add(item.cls.name)
                rebuildVisible()
                notifyDataSetChanged()
            }
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
