package me.timschneeberger.reflectionexplorer

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.reflectionexplorer.databinding.ItemMemberBinding
import me.timschneeberger.reflectionexplorer.databinding.ItemMemberHeaderBinding
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

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

    init {
        rebuildVisible()
    }

    class VH(val binding: ItemMemberBinding) : RecyclerView.ViewHolder(binding.root)

    class HeaderVH(val binding: ItemMemberHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    private fun rebuildVisible() {
        visibleItems.clear()
        var currentHeader: ClassHeaderInfo? = null
        var skip = false
        for (it in fullItems) {
            if (it is ClassHeaderInfo) {
                currentHeader = it
                visibleItems.add(it)
                skip = collapsedClasses.contains(it.cls.name)
            } else {
                // element/map entries (coming before headers) should always be visible
                if (currentHeader == null) {
                    visibleItems.add(it)
                } else {
                    if (!skip) visibleItems.add(it)
                }
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (visibleItems[position]) {
            is ClassHeaderInfo -> TYPE_HEADER
            else -> TYPE_MEMBER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val binding = ItemMemberHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            HeaderVH(binding)
        } else {
            val binding = ItemMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            VH(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = visibleItems[position]

        fun formatPreview(v: Any?): String {
            try {
                if (v == null) return "null"
                if (v is CharSequence) {
                    val s = v.toString()
                    return if (s.length > 80) "\"${s.substring(0, 80)}...\" (len=${s.length})" else "\"$s\""
                }
                if (v is Collection<*>) {
                    val size = v.size
                    val preview = v.take(3).map { el -> el?.toString() ?: "null" }
                    return "size=$size [${preview.joinToString(", ")}]"
                }
                if (v is Map<*, *>) {
                    val size = v.size
                    val preview = v.entries.take(3).map { e -> "${e.key}->${e.value?.toString() ?: "null"}" }
                    return "size=$size {${preview.joinToString(", ")}}"
                }
                val cls = v.javaClass
                if (cls.isArray) {
                    val len = java.lang.reflect.Array.getLength(v)
                    val previewList = mutableListOf<String>()
                    val max = Math.min(3, len)
                    for (i in 0 until max) {
                        val el = java.lang.reflect.Array.get(v, i)
                        previewList.add(el?.toString() ?: "null")
                    }
                    return "size=$len [${previewList.joinToString(", ")}]"
                }
                val s = v.toString()
                return if (s.length > 120) "${s.substring(0, 120)}... (len=${s.length})" else s
            } catch (_: Exception) {
                return "<error>"
            }
        }

        // apply rounded background only within header-separated groups
        if (holder is VH) {
            val container = holder.binding.memberContainer
            // scan backward for the previous header
            var headerIndex = -1
            for (i in position - 1 downTo 0) {
                if (visibleItems[i] is ClassHeaderInfo) { headerIndex = i; break }
            }
            if (headerIndex == -1) {
                // not in a header group -> plain background
                container?.setBackgroundResource(R.drawable.bg_member_single)
            } else {
                // compute group bounds: start after header, end at next header or end
                val start = headerIndex + 1
                var end = start
                while (end < visibleItems.size && visibleItems[end] !is ClassHeaderInfo) end++
                val count = end - start
                if (count <= 0) {
                    container?.setBackgroundResource(R.drawable.bg_member_single)
                } else {
                    val memberIndex = position - start
                    val bgRes = when {
                        count <= 1 -> R.drawable.bg_member_single
                        memberIndex == 0 -> R.drawable.bg_member_top
                        memberIndex == count - 1 -> R.drawable.bg_member_bottom
                        else -> R.drawable.bg_member_middle
                    }
                    container?.setBackgroundResource(bgRes)
                }
            }
        }

        when (item) {
            is ClassHeaderInfo -> {
                val hv = holder as HeaderVH
                val pkg = item.cls.`package`?.name ?: "<default>"
                val collapsed = collapsedClasses.contains(item.cls.name)
                // compute number of members belonging to this class (i.e., until next header or end)
                var count = 0
                var idx = fullItems.indexOf(item) + 1
                while (idx < fullItems.size && fullItems[idx] !is ClassHeaderInfo) {
                    count++
                    idx++
                }

                val chevron = hv.binding.headerChevron
                chevron.rotation = if (collapsed) 90f else -90f
                val card = hv.binding.headerCard
                card.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    // add bottom margin when expanded to separate from members
                    bottomMargin = if (collapsed) dpToPx(0) else dpToPx(7)
                }
                hv.binding.headerTitle.text = "${item.cls.simpleName} ($count)"
                hv.binding.headerSubtitle.text = pkg
                // toggle collapsed state on click
                hv.binding.root.setOnClickListener {
                    if (collapsed) collapsedClasses.remove(item.cls.name) else collapsedClasses.add(item.cls.name)
                    rebuildVisible()
                    notifyDataSetChanged()
                }
            }
            is FieldInfo -> {
                val hv = holder as VH
                hv.binding.memberTitle.text = item.name
                val valueDesc = try {
                    val v = ReflectionInspector.getField(rootInstance, item.field)
                    val typeName = item.field.type.simpleName
                    val preview = formatPreview(v)
                    "$typeName -> $preview"
                } catch (_: Exception) { "<error>" }
                hv.binding.memberSubtitle.text = valueDesc
                // choose icon by field type/value (use overlays for final/static)
                val fType = item.field.type
                val baseDrawable = when {
                    fType.isEnum -> ContextCompat.getDrawable(hv.binding.root.context, R.drawable.ic_public_class)
                    fType.isArray || java.util.Collection::class.java.isAssignableFrom(fType) -> ContextCompat.getDrawable(hv.binding.root.context, R.drawable.ic_field)
                    java.util.Map::class.java.isAssignableFrom(fType) -> ContextCompat.getDrawable(hv.binding.root.context, R.drawable.ic_field)
                    else -> getFieldDrawable(item.field, hv.binding.root)
                }
                hv.binding.memberIcon.setImageDrawable(baseDrawable)
                hv.binding.root.setOnClickListener { onClick(item) }
            }
            is MethodInfo -> {
                val hv = holder as VH
                val params = item.method.parameterTypes.joinToString(",") { it.simpleName }
                hv.binding.memberTitle.text = "${item.name}(${params})"
                hv.binding.memberSubtitle.text = "returns ${item.method.returnType.simpleName}"
                val md = getMethodDrawable(item.method, hv.binding.root) ?: ContextCompat.getDrawable(hv.binding.root.context, R.drawable.ic_method)
                hv.binding.memberIcon.setImageDrawable(md)
                hv.binding.root.setOnClickListener { onClick(item) }
            }
            is ElementInfo -> {
                val hv = holder as VH
                hv.binding.memberTitle.text = item.name
                hv.binding.memberSubtitle.text = item.value?.let { it::class.java.simpleName + " -> " + formatPreview(it) } ?: "null"
                hv.binding.memberIcon.setImageResource(R.drawable.ic_class)
                hv.binding.root.setOnClickListener { onClick(item) }
            }
            is MapEntryInfo -> {
                val hv = holder as VH
                hv.binding.memberTitle.text = item.key
                hv.binding.memberSubtitle.text = item.value?.let { it::class.java.simpleName + " -> " + formatPreview(it) } ?: "null"
                hv.binding.memberIcon.setImageResource(R.drawable.ic_field)
                hv.binding.root.setOnClickListener { onClick(item) }
            }
        }
    }

    override fun getItemCount(): Int = visibleItems.size

    @SuppressLint("NotifyDataSetChanged")
    fun update(newItems: List<MemberInfo>) {
        this.fullItems = newItems
        rebuildVisible()
        notifyDataSetChanged()
    }

    private fun dpToPx(dp: Int): Int = (dp * Resources.getSystem().displayMetrics.density).toInt()

    private fun composeWithOverlays(base: Drawable?, overlays: List<Drawable?>): Drawable? {
        if (base == null) return null
        if (overlays.isEmpty()) return base
        val layers = mutableListOf<Drawable>()
        layers.add(base)
        overlays.forEach { if (it != null) layers.add(it) }
        val layerArray = layers.toTypedArray()
        val ld = LayerDrawable(layerArray)

        // Use the base intrinsic size if available; otherwise fall back to 16dp.
        val baseW = base.intrinsicWidth.takeIf { it > 0 } ?: dpToPx(16)
        val baseH = base.intrinsicHeight.takeIf { it > 0 } ?: dpToPx(16)
        val defaultOverlay = dpToPx(8)
        val margin = dpToPx(1)

        // Ensure base layer occupies full bounds (no inset) so the LayerDrawable doesn't expand.
        ld.setLayerInset(0, 0, 0, 0, 0)

        // Position overlays snug in the corners but clamp coordinates to base bounds
        var overlayIdx = 1
        for (i in 1 until layerArray.size) {
            val overlay = layerArray[i]
            val oW = overlay.intrinsicWidth.takeIf { it > 0 } ?: defaultOverlay
            val oH = overlay.intrinsicHeight.takeIf { it > 0 } ?: defaultOverlay

            // desired placement relative to base
            val desiredLeft = baseW - oW - margin
            val desiredTop = if (overlayIdx == 1) margin else baseH - oH - margin

            // clamp so overlay stays fully inside base
            val left = desiredLeft.coerceIn(0, (baseW - oW).coerceAtLeast(0))
            val top = desiredTop.coerceIn(0, (baseH - oH).coerceAtLeast(0))
            val right = (baseW - left - oW).coerceAtLeast(0)
            val bottom = (baseH - top - oH).coerceAtLeast(0)

            ld.setLayerInset(i, left, top, right, bottom)
            overlayIdx++
        }

        return ld
    }

    private fun getFieldDrawable(field: Field, ctxView: View): Drawable? {
        val mod = field.modifiers
        // base icon selection
        val baseId = when {
            Modifier.isPublic(mod) -> R.drawable.ic_public_field
            Modifier.isPrivate(mod) -> R.drawable.ic_private_field
            Modifier.isProtected(mod) -> R.drawable.ic_protected_field
            else -> R.drawable.ic_field
        }
        val base = ContextCompat.getDrawable(ctxView.context, baseId)
        // overlays
        val overlays = mutableListOf<Drawable?>()
        if (Modifier.isFinal(mod)) overlays.add(ContextCompat.getDrawable(ctxView.context, R.drawable.ic_final_mark))
        if (Modifier.isStatic(mod)) overlays.add(ContextCompat.getDrawable(ctxView.context, R.drawable.ic_static_mark))
        return composeWithOverlays(base, overlays)
    }

    private fun getMethodDrawable(method: Method, ctxView: View): Drawable? {
        val mod = method.modifiers
        // base icon logic, following reference priority
        val baseId = when {
            Modifier.isAbstract(mod) -> R.drawable.ic_abstractmethod
            method.name == "<init>" -> R.drawable.ic_constructor_method
            Modifier.isPublic(mod) -> R.drawable.ic_public_method
            Modifier.isPrivate(mod) -> R.drawable.ic_private_method
            Modifier.isProtected(mod) -> R.drawable.ic_protected_method
            else -> R.drawable.ic_method
        }
        val base = ContextCompat.getDrawable(ctxView.context, baseId)
        val overlays = mutableListOf<Drawable?>()
        if (Modifier.isFinal(mod)) overlays.add(ContextCompat.getDrawable(ctxView.context, R.drawable.ic_final_mark))
        if (Modifier.isStatic(mod)) overlays.add(ContextCompat.getDrawable(ctxView.context, R.drawable.ic_static_mark))
        return composeWithOverlays(base, overlays)
    }
}
