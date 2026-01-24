package me.timschneeberger.reflectionexplorer.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import me.timschneeberger.reflectionexplorer.MainActivity
import me.timschneeberger.reflectionexplorer.R
import me.timschneeberger.reflectionexplorer.model.MainViewModel
import me.timschneeberger.reflectionexplorer.model.InspectorViewModel
import me.timschneeberger.reflectionexplorer.adapter.BreadcrumbAdapter
import me.timschneeberger.reflectionexplorer.adapter.MembersAdapter
import me.timschneeberger.reflectionexplorer.databinding.FragmentInspectorBinding
import me.timschneeberger.reflectionexplorer.utils.reflection.ClassHeaderInfo
import me.timschneeberger.reflectionexplorer.utils.reflection.CollectionMember
import me.timschneeberger.reflectionexplorer.utils.Dialogs.showEditValueDialog
import me.timschneeberger.reflectionexplorer.utils.Dialogs.showErrorDialog
import me.timschneeberger.reflectionexplorer.utils.Dialogs.showMethodInvocationDialog
import me.timschneeberger.reflectionexplorer.utils.castOrNull
import me.timschneeberger.reflectionexplorer.utils.reflection.ElementInfo
import me.timschneeberger.reflectionexplorer.utils.reflection.FieldInfo
import me.timschneeberger.reflectionexplorer.utils.reflection.MapEntryInfo
import me.timschneeberger.reflectionexplorer.utils.reflection.MethodInfo
import me.timschneeberger.reflectionexplorer.utils.reflection.MemberInfo
import me.timschneeberger.reflectionexplorer.utils.reflection.ReflectionParser
import me.timschneeberger.reflectionexplorer.utils.reflection.appendToArray
import me.timschneeberger.reflectionexplorer.utils.reflection.canInspectType
import me.timschneeberger.reflectionexplorer.utils.reflection.formatObject
import me.timschneeberger.reflectionexplorer.utils.reflection.getField
import me.timschneeberger.reflectionexplorer.utils.reflection.listMembers
import me.timschneeberger.reflectionexplorer.utils.reflection.replaceReferences
import java.lang.reflect.Modifier

class InspectorFragment : Fragment() {
    private var argIndex: Int = -1
    private var bcAdapter: BreadcrumbAdapter? = null
    private lateinit var binding: FragmentInspectorBinding

    // make adapter a property so we can update it from observers
    private var membersAdapter: MembersAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        argIndex = arguments?.getInt(ARG_STACK_INDEX, -1) ?: -1
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentInspectorBinding.inflate(inflater, container, false)

        val activity = activity.castOrNull<MainActivity>()
        val mainVm = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        // resolve instance from MainViewModel stack; if missing, show empty state
        val instance = mainVm.inspectionStack.getOrNull(argIndex)

        // Compute breadcrumb trail from the shared MainViewModel instead of calling into MainActivity
        val trail = getInspectionTrailFromVm(mainVm, instance)

        bcAdapter = BreadcrumbAdapter(trail, trail.size - 1) { idx ->
            // update UI immediately with live trail
            getInspectionTrailFromVm(mainVm, instance).let { live ->
                bcAdapter?.update(live, idx)
                binding.breadcrumbs.post { if ((bcAdapter?.itemCount ?: 0) > idx) binding.breadcrumbs.smoothScrollToPosition(idx) }
            }
            // perform navigation to the requested level
            popToLevel(idx)
        }

        binding.breadcrumbs.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = bcAdapter
        }

        // filter button opens sheet; highlighting is handled via LiveData observer below
        binding.filterButton.setOnClickListener {
            FilterBottomSheetFragment.newInstance().show(parentFragmentManager, "filters")
        }

        val inst = instance ?: return binding.root

        // collection info chip (moved after inst is known to avoid null assertions)
        updateCollectionChip(inst)

        val membersRaw = inst.listMembers()
        val members = applyFilters(membersRaw, mainVm)

        val vm = ViewModelProvider(requireActivity())[InspectorViewModel::class.java]

        // create and assign adapter to property
        membersAdapter = MembersAdapter(
            members,
            inst,
            argIndex,
            vm.collapsedClasses,
            { idx, obj -> replaceStackAt(idx, obj) }
        ) { member ->
            when (member) {
                is FieldInfo -> try {
                    instance.getField(member.field).let { activity?.openInspectorFor(it) }
                } catch (e: Exception) {
                    activity?.showErrorDialog(e)
                }
                is MethodInfo -> activity?.showMethodInvocationDialog(instance, member.method) { result ->
                    binding.detailsContainer.isVisible = true
                    binding.detailsText.text = getString(R.string.invoked_result, member.method.name, result?.toString() ?: "null")
                    binding.detailsMenuButton.apply {
                        tag = result
                        setOnClickListener(::openReturnValueMenu)
                    }
                }
                is ElementInfo -> activity?.openInspectorFor(member.value)
                is MapEntryInfo -> activity?.openInspectorFor(member.value)
                is ClassHeaderInfo -> { /* no-op: adapter handles expand/collapse */ }
            }
        }

        binding.membersList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = membersAdapter
        }

        // observe filter changes and update members list & button highlight immediately
        mainVm.memberFilterLive.observe(viewLifecycleOwner) { f ->
            val updated = applyFilters(inst.listMembers(), mainVm)
            membersAdapter?.update(updated, inst)
            // re-apply current search query so search + filters compose
            membersAdapter?.filter(mainVm.searchQueryLive.value)
            binding.filterButton.isChecked = f.anyFiltersActive()
        }

        // ensure initial highlight reflects current VM state
        binding.filterButton.isCheckable = true
        binding.filterButton.isChecked = mainVm.memberFilter.anyFiltersActive()
        binding.filterButton.addOnCheckedChangeListener { _, _ ->
            binding.filterButton.isChecked = mainVm.memberFilter.anyFiltersActive()
        }

        // Observe shared search query and apply filtering to members displayed
        mainVm.searchQueryLive.observe(viewLifecycleOwner) { query ->
            // First, apply member filters to get the base set
            val base = applyFilters(inst.listMembers(), mainVm)
            // update adapter's original list to the base set
            membersAdapter?.update(base, inst)
            // then apply adapter-level search filter
            membersAdapter?.filter(query)
        }

        // Set up add-element button when the inspected instance is a collection/array/map
        binding.addElementButton.apply {
            visibility = View.GONE
            setOnClickListener(null)
        }

        // Determine collection type for adding new elements; null if not a collection
        val firstCollectionItem = membersRaw
            .firstOrNull { it is CollectionMember && ReflectionParser.canParseType(it.getType(inst)) }

        val collectionType = when {
            inst is Map<*, *> && firstCollectionItem != null -> (firstCollectionItem as? MapEntryInfo)?.getTypes(inst)?.second
            firstCollectionItem != null -> (firstCollectionItem as? CollectionMember)?.getType(inst)
            else -> null
        }
        val keyType = when {
            inst is Map<*, *> && firstCollectionItem != null -> (firstCollectionItem as? MapEntryInfo)?.getTypes(inst)?.first
            else -> null
        }

        if (collectionType != null) {
            binding.addElementButton.visibility = View.VISIBLE
            binding.addElementButton.setOnClickListener {
                // Show a simple dialog to enter a value and append it
                activity ?: return@setOnClickListener
                activity.showEditValueDialog("Add element", "", collectionType, null, keyType) { ok, parsed, _ ->
                    if (!ok || parsed == null) return@showEditValueDialog
                    when {
                        // Prefer in-place mutation when the concrete list supports it.
                        inst is MutableList<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            val ml = inst as MutableList<Any?>
                            val added = try {
                                ml.add(parsed)
                                true
                            } catch (e: Exception) {
                                e.printStackTrace()
                                false
                            }

                            if (added) {
                                // mutated in-place
                                replaceStackAt(argIndex, inst)
                            } else {
                                // underlying list is fixed-size (e.g., Arrays.asList); create a new mutable copy
                                val newList = ArrayList(ml)
                                newList.add(parsed)
                                replaceStackAt(argIndex, newList)
                            }
                        }
                        inst is List<*> -> {
                            // Non-mutable List: create mutable copy and replace
                            val newList = ArrayList(inst)
                            newList.add(parsed)
                            replaceStackAt(argIndex, newList)
                        }
                        inst is Map<*, *> -> {
                            val result = parsed as Pair<Any?, Any?>
                            val newMap = LinkedHashMap(inst)
                            newMap[result.first] = result.second
                            replaceStackAt(argIndex, newMap)
                        }
                        inst.javaClass.isArray -> {
                            replaceStackAt(
                                argIndex,
                                appendToArray(inst, parsed)
                            )
                        }
                        else -> {
                            // unsupported container
                            throw IllegalStateException("Unsupported collection type for adding elements")
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                super.onResume(owner)
                refreshMembers()
            }
        })

        return binding.root
    }

    private fun openReturnValueMenu(view: View) {
        val value = view.tag
        val popup = PopupMenu(requireContext(), view)
        popup.menu.add(R.string.action_copy_string).apply {
            setOnMenuItemClickListener {
                requireContext().getSystemService<ClipboardManager>()?.setPrimaryClip(
                    ClipData.newPlainText("Value", value?.toString() ?: "null")
                )
                true
            }
        }
        if (value != null && value.canInspectType()) {
            popup.menu.add(R.string.action_inspect).apply {
                setOnMenuItemClickListener {
                    MainActivity.pendingInspection = value
                    startActivity(Intent(requireContext(), MainActivity::class.java))
                    true
                }
            }
        }
        popup.show()
    }

    private fun applyFilters(members: List<MemberInfo>, mainVm: MainViewModel): List<MemberInfo> {
        val f = mainVm.memberFilter

        // helper: return visibility string for modifiers
        fun visOf(mods: Int): String = when {
            Modifier.isPublic(mods) -> "public"
            Modifier.isProtected(mods) -> "protected"
            Modifier.isPrivate(mods) -> "private"
            else -> "package"
        }

        // helper: whether visibility filters are active
        val visibilityActive = f.visibilityPublic || f.visibilityProtected || f.visibilityPrivate || f.visibilityPackage

        // helper: check whether a given modifiers value passes the visibility filter
        fun visibilityAllowed(mods: Int): Boolean {
            if (!visibilityActive) return true
            return when (visOf(mods)) {
                "public" -> f.visibilityPublic
                "protected" -> f.visibilityProtected
                "private" -> f.visibilityPrivate
                "package" -> f.visibilityPackage
                else -> true
            }
        }

        // helper: tri-state matcher for a predicate
        fun <T> triMatches(value: T, state: MainViewModel.TriState, predicate: (T) -> Boolean): Boolean = when (state) {
            MainViewModel.TriState.DEFAULT -> true
            MainViewModel.TriState.INCLUDE -> predicate(value)
            MainViewModel.TriState.EXCLUDE -> !predicate(value)
        }

        return members.filter { m ->
            when (m) {
                is ClassHeaderInfo -> true

                is FieldInfo, is MethodInfo -> {
                    val mods = when (m) {
                        is FieldInfo -> m.field.modifiers
                        is MethodInfo -> m.method.modifiers
                        else -> 0
                    }

                    // visibility
                    if (!visibilityAllowed(mods)) return@filter false

                    // kind filter: if any kind flag is set, only allow matching kind
                    val kindActive = f.kindFields || f.kindMethods
                    if (kindActive) {
                        if (m is FieldInfo && !f.kindFields) return@filter false
                        if (m is MethodInfo && !f.kindMethods) return@filter false
                    }

                    // static/final tri-state checks
                    if (!triMatches(mods, f.isStatic) { Modifier.isStatic(it) }) return@filter false
                    if (!triMatches(mods, f.isFinal) { Modifier.isFinal(it) }) return@filter false

                    // lambda tri-state: only applies to methods
                    if (m is MethodInfo) {
                        val isLambdaMethod = try {
                            m.method.isSynthetic
                        } catch (_: Exception) {
                            false
                        }

                        if (!triMatches(isLambdaMethod, f.isLambda) { it }) return@filter false
                    }

                    true
                }

                else -> true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBreadcrumb()
    }

    fun refreshBreadcrumb() {
        // Use the shared MainViewModel to compute the current trail rather than calling into MainActivity
        val mainVm = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        val trail = getInspectionTrailFromVm(mainVm, null)
        bcAdapter?.update(trail, trail.size - 1)
        binding.breadcrumbs.post {
            val cnt = bcAdapter?.itemCount ?: 0
            if (cnt > 0)
                binding.breadcrumbs.smoothScrollToPosition(cnt - 1)
        }
    }

    // Pop fragment backstack and trim the shared inspection stack to the requested level
    fun popToLevel(idx: Int) {
        val mainVm = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        if (idx < 0) return
        if (idx >= mainVm.inspectionStack.size - 1) return
        val toPop = mainVm.inspectionStack.size - 1 - idx
        val fm = requireActivity().supportFragmentManager
        repeat(toPop) { if (fm.backStackEntryCount > 0) fm.popBackStack() }
        while (mainVm.inspectionStack.size > idx + 1) mainVm.inspectionStack.removeAt(mainVm.inspectionStack.lastIndex)
    }

    // Replace the inspection stack entry at index `idx` with `newInstance` and refresh current inspector if shown.
    fun replaceStackAt(idx: Int, newInstance: Any) {
        val mainVm = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        if (idx < 0 || idx >= mainVm.inspectionStack.size) return
        val oldInstance = mainVm.inspectionStack[idx]

        for (pIdx in 0 until idx) {
            val parent = mainVm.inspectionStack[pIdx]
            try {
                val (_, replacement) = replaceReferences(parent, oldInstance, newInstance)
                if (replacement != null) {
                    // replacement is a new root for this parent position; recurse to replace in the stack
                    replaceStackAt(pIdx, replacement)
                }
            } catch (e: Exception) {
                // ignore best-effort failures
                e.printStackTrace()
            }
        }

        // Finally store the new instance in the inspection stack
        mainVm.inspectionStack[idx] = newInstance

        // Refresh its members to reflect the new object. Post to avoid in-layout mutations.
        view?.post(::refreshMembers)

        // Update title to reflect changed contents
        (activity as? MainActivity)?.updateTitle()
    }

    private fun getInspectionTrailFromVm(mainVm: MainViewModel, instanceFallback: Any?): List<String> {
        val trail = mainVm.inspectionStack.map { it::class.java.simpleName }
        return trail.ifEmpty { listOf(instanceFallback?.javaClass?.simpleName ?: "root") }
    }

    fun refreshMembers() {
        val mainVm = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        val instance = mainVm.inspectionStack.getOrNull(argIndex) ?: return
        applyFilters(instance.listMembers(), mainVm).let { items ->
            membersAdapter?.update(items, instance)
        }

        updateCollectionChip(instance)
    }

    private fun updateCollectionChip(inst: Any) {
        binding.collectionInfoChip.apply {
            text = inst.formatObject(requireContext(), additionalTypeInfo = null)
            visibility = if(inst is Collection<*> || inst.javaClass.isArray || inst is Map<*, *>) View.VISIBLE else View.GONE
        }
    }

    companion object {
        private const val ARG_STACK_INDEX = "arg_stack_index"

        fun newInstance(stackIndex: Int): InspectorFragment = InspectorFragment().apply {
            arguments = Bundle().apply { putInt(ARG_STACK_INDEX, stackIndex) }
        }
    }
}
