package me.timschneeberger.reflectionexplorer.adapter

import android.annotation.SuppressLint
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
import me.timschneeberger.reflectionexplorer.databinding.ItemMemberBinding
import me.timschneeberger.reflectionexplorer.databinding.ItemMemberHeaderBinding
import me.timschneeberger.reflectionexplorer.utils.Dialogs
import me.timschneeberger.reflectionexplorer.utils.dpToPx
import me.timschneeberger.reflectionexplorer.utils.formatObject
import me.timschneeberger.reflectionexplorer.utils.getField
import me.timschneeberger.reflectionexplorer.utils.getFieldDrawable
import me.timschneeberger.reflectionexplorer.utils.getMethodDrawable

class MembersAdapter(
    items: List<MemberInfo>,
    // rootInstance can change when we replace arrays/lists/maps; make it mutable
    var rootInstance: Any,
    // index of this inspected instance in the global inspection stack (used to replace arrays/lists/maps)
    private val stackIndex: Int,
    // external set (from ViewModel) to persist collapsed state across rotations
    private val collapsedClasses: MutableSet<String>,
    private val onClick: (MemberInfo) -> Unit
) : ExpandableListAdapter<MemberInfo>(items, collapsedClasses) {

    private fun runWithActivity(anchor: View, block: (MainActivity) -> Unit) =
        (anchor.context as? MainActivity)?.let(block)

    override fun isHeader(item: MemberInfo): Boolean = item is ClassHeaderInfo
    override fun headerKey(item: MemberInfo): String = (item as? ClassHeaderInfo)?.cls?.name ?: item.name

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_HEADER) HeaderVH(ItemMemberHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        else VH(ItemMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun bindHeaderVH(holder: RecyclerView.ViewHolder, item: MemberInfo) {
        val hv = holder as HeaderVH
        val header = item as ClassHeaderInfo
        val pkg = header.cls.`package`?.name ?: "<default>"
        val collapsed = collapsedClasses.contains(header.cls.name)
        val count = fullItems.dropWhile { it !== item }.drop(1).takeWhile { it !is ClassHeaderInfo }.count()

        hv.binding.apply {
            headerChevron.rotation = if (collapsed) 90f else -90f
            headerTitle.text = root.context.getString(R.string.header_title, header.cls.simpleName, count)
            headerSubtitle.text = pkg
            headerCard.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = if (collapsed) 0.dpToPx() else 7.dpToPx() }
            root.setOnClickListener {
                toggleHeaderCollapsed(item)
                notifyDataSetChanged()
            }
        }

    }

    override fun bindItemVH(holder: RecyclerView.ViewHolder, item: MemberInfo) {
        val hv = holder as VH
        val position = visibleItems.indexOf(item)

        // Apply rounded background using base helper
        applyRoundedBackground(hv.binding.memberContainer, position)

        hv.binding.apply {
            // Reset common state
            btnSet.isVisible = false
            btnDelete.isVisible = false

            when (item) {
                is FieldInfo -> {
                    memberTitle.text = item.name
                    memberSubtitle.text = try {
                        val v = rootInstance.getField(item.field)
                        if (v == null) root.context.getString(R.string.member_value_null) else formatObject(root.context, v, item)
                    } catch (e: Exception) { root.context.getString(R.string.error_prefix, e) }
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
                    val currentValue: Any? = (item as CollectionMember).getValue(rootInstance)

                    memberTitle.text = item.name
                    memberIcon.setImageResource(R.drawable.ic_class)
                    memberSubtitle.text = formatObject(root.context, currentValue, item)

                    // Determine whether this element is editable
                    btnSet.isVisible = Dialogs.canParseType(item.getType(rootInstance))
                    btnDelete.isVisible = rootInstance is Collection<*> || rootInstance.javaClass.isArray

                    btnDelete.setOnClickListener { runWithActivity(root) { act -> performDelete(act, item) } }
                    btnSet.setOnClickListener { runWithActivity(root) { act -> performEdit(act, item, root) } }
                }

                is MapEntryInfo -> {
                    val currentValue = (item as CollectionMember).getValue(rootInstance)

                    memberTitle.text = item.key?.toString() ?: "null"
                    memberSubtitle.text = formatObject(root.context, currentValue, item)
                    memberIcon.setImageResource(R.drawable.ic_field)

                    btnDelete.isVisible = rootInstance is Map<*, *>
                    btnSet.isVisible = currentValue != null && Dialogs.canParseType(currentValue::class.java)

                    btnDelete.setOnClickListener { runWithActivity(root) { act -> performDelete(act, item) } }
                    btnSet.setOnClickListener { runWithActivity(root) { act -> performEdit(act, item, root) } }
                }

                else -> {
                    throw IllegalArgumentException("Unknown MemberInfo type: ${item::class.java.name}")
                }
            }

            root.setOnClickListener { onClick(item) }
        }
    }

    // Helpers to mutate collection/map elements via ReflectionInspector
    private fun performDelete(activity: MainActivity, item: MemberInfo) {
        if (item is CollectionMember) {
            val newRoot = item.applyDelete(rootInstance)
            if (newRoot != null) activity.replaceStackAt(stackIndex, newRoot)
        }
    }

    private fun performEdit(activity: MainActivity, item: MemberInfo, anchor: View) {
        when (item) {
            is FieldInfo -> activity.showSetFieldDialog(rootInstance, item) { ok, _ -> if (ok) activity.replaceStackAt(stackIndex, rootInstance) }
            is CollectionMember -> {
                val value = item.getValue(rootInstance)?.toString() ?: ""
                val type = item.getType(rootInstance)
                if (type == Any::class.java) {
                    Snackbar.make(anchor, "Cannot determine element type to edit", Snackbar.LENGTH_SHORT).show()
                    return
                }
                Dialogs.showEditValueDialog(activity, activity.getString(R.string.action_set_value), activity.getString(R.string.action_set_value), value, type, null, null, anchor) { ok, parsed, _ ->
                    if (!ok || parsed == null) return@showEditValueDialog
                    val newRoot = item.applyEdit(rootInstance, parsed)
                    if (newRoot != null) activity.replaceStackAt(stackIndex, newRoot)
                }
            }
            else -> {
                // no-op for other member types
            }
        }
    }

    // Adapter concrete view holder classes
    class VH(val binding: ItemMemberBinding) : RecyclerView.ViewHolder(binding.root)
    class HeaderVH(val binding: ItemMemberHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    @SuppressLint("NotifyDataSetChanged")
    fun update(newItems: List<MemberInfo>, newRootInstance: Any? = null) {
        newRootInstance?.let { this.rootInstance = it }
        super.update(newItems)
    }
}
