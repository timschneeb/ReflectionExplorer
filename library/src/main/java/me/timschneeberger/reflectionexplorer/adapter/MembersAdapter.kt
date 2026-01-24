package me.timschneeberger.reflectionexplorer.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.reflectionexplorer.MainActivity
import me.timschneeberger.reflectionexplorer.utils.reflection.ClassHeaderInfo
import me.timschneeberger.reflectionexplorer.utils.reflection.ElementInfo
import me.timschneeberger.reflectionexplorer.utils.reflection.FieldInfo
import me.timschneeberger.reflectionexplorer.utils.reflection.MapEntryInfo
import me.timschneeberger.reflectionexplorer.utils.reflection.MemberInfo
import me.timschneeberger.reflectionexplorer.utils.reflection.MethodInfo
import me.timschneeberger.reflectionexplorer.utils.reflection.CollectionMember
import me.timschneeberger.reflectionexplorer.R
import me.timschneeberger.reflectionexplorer.databinding.ItemMemberBinding
import me.timschneeberger.reflectionexplorer.databinding.ItemMemberHeaderBinding
import me.timschneeberger.reflectionexplorer.utils.Dialogs.showEditValueDialog
import me.timschneeberger.reflectionexplorer.utils.Dialogs.showSetFieldDialog
import me.timschneeberger.reflectionexplorer.utils.cast
import me.timschneeberger.reflectionexplorer.utils.castOrNull
import me.timschneeberger.reflectionexplorer.utils.reflection.ReflectionParser
import me.timschneeberger.reflectionexplorer.utils.dpToPx
import me.timschneeberger.reflectionexplorer.utils.reflection.formatObject
import me.timschneeberger.reflectionexplorer.utils.reflection.getField
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

    // Keep original list including ClassHeaderInfo entries so we can filter in-adapter
    private val originalFullItems: MutableList<MemberInfo> = items.toMutableList()

    private fun activityOrNull(anchor: View): MainActivity? =
        anchor.context.castOrNull<MainActivity>()

    override fun isHeader(item: MemberInfo): Boolean = item is ClassHeaderInfo
    override fun headerKey(item: MemberInfo): String = item.castOrNull<ClassHeaderInfo>()?.cls?.name ?: item.name

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
            headerSubtitle.isVisible = headerSubtitle.text.isNotEmpty()
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
                        rootInstance.getField(item.field).formatObject(root.context, item)
                    } catch (e: Exception) {
                        root.context.getString(R.string.error_prefix, e)
                    }
                    memberIcon.setImageDrawable(item.field.getFieldDrawable(root.context))

                    btnSet.isVisible = ReflectionParser.canParseType(item.field.type)
                    btnSet.setOnClickListener {
                        root.context.showSetFieldDialog(rootInstance, item) {
                                ok, _ -> if (ok) activityOrNull(root)?.replaceStackAt(stackIndex, rootInstance)
                        }
                    }
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
                    memberSubtitle.text = currentValue.formatObject(root.context, item)

                    // Determine whether this element is editable
                    btnSet.isVisible = ReflectionParser.canParseType(item.getType(rootInstance))
                    btnDelete.isVisible = rootInstance is Collection<*> || rootInstance.javaClass.isArray

                    btnDelete.setOnClickListener { performDelete(activityOrNull(root)!!, item) }
                    btnSet.setOnClickListener { performEdit(activityOrNull(root)!!, item) }
                }

                is MapEntryInfo -> {
                    val currentValue = (item as CollectionMember).getValue(rootInstance)

                    memberTitle.text = item.key?.toString() ?: "null"
                    memberSubtitle.text = currentValue.formatObject(root.context, item)
                    memberIcon.setImageResource(R.drawable.ic_field)

                    btnDelete.isVisible = rootInstance is Map<*, *>
                    btnSet.isVisible = currentValue != null && ReflectionParser.canParseType(currentValue::class.java)

                    btnDelete.setOnClickListener { performDelete(activityOrNull(root)!!, item) }
                    btnSet.setOnClickListener { performEdit(activityOrNull(root)!!, item) }
                }

                else -> {
                    throw IllegalArgumentException("Unknown MemberInfo type: ${item::class.java.name}")
                }
            }

            root.setOnClickListener { onClick(item) }
        }
    }

    fun filter(query: String?) {
        val q = query?.trim()?.lowercase() ?: ""
        if (q.isEmpty()) {
            // show full original set
            super.update(originalFullItems.toList())
            return
        }

        val out = mutableListOf<MemberInfo>()
        var currentHeader: ClassHeaderInfo? = null
        var buffer = mutableListOf<MemberInfo>()

        fun flushBuffer() {
            if (buffer.isNotEmpty()) {
                currentHeader?.let { out.add(it) }
                out.addAll(buffer)
            }
            buffer = mutableListOf()
        }

        for (m in originalFullItems) {
            if (m is ClassHeaderInfo) {
                flushBuffer()
                currentHeader = m
            } else {
                val matches = when (m) {
                    is ElementInfo -> {
                        try { m.cast<CollectionMember>().getValue(rootInstance)?.toString() ?: "" } catch (_: Exception) { "" }
                            .lowercase()
                            .contains(q)
                    }
                    is MapEntryInfo -> {
                        m.key.toString().lowercase().contains(q)
                    }
                    else -> m.name.lowercase().contains(q)
                }

                if (matches) buffer.add(m)
            }
        }

        flushBuffer()
        super.update(out)
    }

    // Helpers to mutate collection/map elements via ReflectionInspector
    private fun performDelete(activity: MainActivity, item: MemberInfo) {
        if (item is CollectionMember) {
            val newRoot = item.applyDelete(rootInstance)
            if (newRoot != null) activity.replaceStackAt(stackIndex, newRoot)
        }
    }

    private fun performEdit(activity: MainActivity, item: MemberInfo) {
        when (item) {
            is FieldInfo -> activity.showSetFieldDialog(rootInstance, item) { ok, _ -> if (ok) activity.replaceStackAt(stackIndex, rootInstance) }
            is CollectionMember -> {
                val value = item.getValue(rootInstance)?.toString() ?: ""
                val type = item.getType(rootInstance)
                if (type == Any::class.java) {
                    Toast.makeText(activity, "Cannot determine element type to edit", Toast.LENGTH_SHORT).show()
                    return
                }
                activity.showEditValueDialog(
                    activity.getString(R.string.action_set_value),
                    value, type, null, null
                ) { ok, parsed, _ ->
                    if (!ok || parsed == null) return@showEditValueDialog
                    val newRoot = item.applyEdit(rootInstance, parsed)
                    if (newRoot != null)
                        activity.replaceStackAt(stackIndex, newRoot)
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
        // keep original list in sync
        originalFullItems.clear()
        originalFullItems.addAll(newItems)
        super.update(newItems)
    }
}
