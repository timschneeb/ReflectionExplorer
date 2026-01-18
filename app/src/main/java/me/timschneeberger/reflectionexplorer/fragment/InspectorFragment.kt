package me.timschneeberger.reflectionexplorer.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import me.timschneeberger.reflectionexplorer.utils.ClassHeaderInfo
import me.timschneeberger.reflectionexplorer.utils.CollectionMember
import me.timschneeberger.reflectionexplorer.utils.Dialogs.showEditValueDialog
import me.timschneeberger.reflectionexplorer.utils.Dialogs.showErrorDialog
import me.timschneeberger.reflectionexplorer.utils.Dialogs.showMethodInvocationDialog
import me.timschneeberger.reflectionexplorer.utils.ElementInfo
import me.timschneeberger.reflectionexplorer.utils.FieldInfo
import me.timschneeberger.reflectionexplorer.utils.MapEntryInfo
import me.timschneeberger.reflectionexplorer.utils.MethodInfo
import me.timschneeberger.reflectionexplorer.utils.MemberInfo
import me.timschneeberger.reflectionexplorer.utils.ReflectionParser
import me.timschneeberger.reflectionexplorer.utils.appendToArray
import me.timschneeberger.reflectionexplorer.utils.canInspectType
import me.timschneeberger.reflectionexplorer.utils.formatObject
import me.timschneeberger.reflectionexplorer.utils.getField
import me.timschneeberger.reflectionexplorer.utils.listMembers
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

        val activity = activity as? MainActivity
        val mainVm = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        // resolve instance from MainViewModel stack; if missing, show empty state
        val instance = mainVm.inspectionStack.getOrNull(argIndex)

        val trail = activity?.getInspectionTrail() ?: listOf(instance?.javaClass?.simpleName ?: "root")

        bcAdapter = BreadcrumbAdapter(trail, trail.size - 1) { idx ->
            activity?.getInspectionTrail()?.let { live ->
                bcAdapter?.update(live, idx)
                binding.breadcrumbs.post { if ((bcAdapter?.itemCount ?: 0) > idx) binding.breadcrumbs.smoothScrollToPosition(idx) }
            }
            activity?.popToLevel(idx)
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
        membersAdapter = MembersAdapter(members, inst, argIndex, vm.collapsedClasses) { member ->
            when (member) {
                is FieldInfo -> try {
                    instance.getField(member.field).let { activity?.openInspectorFor(it) }
                } catch (e: Exception) {
                    activity?.showErrorDialog(e)
                }
                is MethodInfo -> activity?.showMethodInvocationDialog(instance, member.method, binding.root) {
                    binding.detailsContainer.isVisible = true
                    binding.detailsText.text = getString(R.string.invoked_result, member.method.name, it?.toString() ?: "null")
                    binding.detailsMenuButton.apply {
                        isVisible = it.canInspectType()
                        setOnClickListener(::openNewInspectorActivityFor)
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
            binding.filterButton.isChecked = f.anyFiltersActive()
        }

        // ensure initial highlight reflects current VM state
        binding.filterButton.isCheckable = true
        binding.filterButton.isChecked = mainVm.memberFilter.anyFiltersActive()
        binding.filterButton.addOnCheckedChangeListener { _, _ ->
            binding.filterButton.isChecked = mainVm.memberFilter.anyFiltersActive()
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
                activity.showEditValueDialog("Add element", "", collectionType, null, keyType, binding.root) { ok, parsed, _ ->
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
                                activity.replaceStackAt(argIndex, inst)
                            } else {
                                // underlying list is fixed-size (e.g., Arrays.asList); create a new mutable copy
                                val newList = ArrayList(ml)
                                newList.add(parsed)
                                activity.replaceStackAt(argIndex, newList)
                            }
                        }
                        inst is List<*> -> {
                            // Non-mutable List: create mutable copy and replace
                            val newList = ArrayList(inst)
                            newList.add(parsed)
                            activity.replaceStackAt(argIndex, newList)
                        }
                        inst is Map<*, *> -> {
                            val result = parsed as Pair<Any?, Any?>
                            val newMap = LinkedHashMap(inst)
                            newMap[result.first] = result.second
                            activity.replaceStackAt(argIndex, newMap)
                        }
                        inst.javaClass.isArray -> {
                            activity.replaceStackAt(
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

    private fun openNewInspectorActivityFor(instance: Any?) {
        // TODO implement
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

        // helper: tri-state matcher for a predicate on modifiers (e.g., isStatic/isFinal)
        fun triMatches(mods: Int, state: MainViewModel.TriState, predicate: (Int) -> Boolean): Boolean = when (state) {
            MainViewModel.TriState.DEFAULT -> true
            MainViewModel.TriState.INCLUDE -> predicate(mods)
            MainViewModel.TriState.EXCLUDE -> !predicate(mods)
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
        (activity as? MainActivity)?.getInspectionTrail()?.let { trail ->
            bcAdapter?.update(trail, trail.size - 1)
            binding.breadcrumbs.post {
                val cnt = bcAdapter?.itemCount ?: 0
                if (cnt > 0)
                    binding.breadcrumbs.smoothScrollToPosition(cnt - 1)
            }
        }
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
