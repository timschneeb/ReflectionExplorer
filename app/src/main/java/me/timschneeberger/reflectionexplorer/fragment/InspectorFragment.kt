package me.timschneeberger.reflectionexplorer.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import me.timschneeberger.reflectionexplorer.MainActivity
import me.timschneeberger.reflectionexplorer.model.MainViewModel
import me.timschneeberger.reflectionexplorer.model.InspectorViewModel
import me.timschneeberger.reflectionexplorer.adapter.BreadcrumbAdapter
import me.timschneeberger.reflectionexplorer.adapter.MembersAdapter
import me.timschneeberger.reflectionexplorer.databinding.FragmentInspectorBinding
import me.timschneeberger.reflectionexplorer.utils.ClassHeaderInfo
import me.timschneeberger.reflectionexplorer.utils.ElementInfo
import me.timschneeberger.reflectionexplorer.utils.FieldInfo
import me.timschneeberger.reflectionexplorer.utils.MapEntryInfo
import me.timschneeberger.reflectionexplorer.utils.MethodInfo
import me.timschneeberger.reflectionexplorer.utils.MemberInfo
import me.timschneeberger.reflectionexplorer.utils.ReflectionInspector
import java.lang.reflect.Array

private const val ARG_STACK_INDEX = "arg_stack_index"

class InspectorFragment : Fragment() {
    private var argIndex: Int = -1
    private var bcAdapter: BreadcrumbAdapter? = null
    private lateinit var binding: FragmentInspectorBinding

    // make adapter a property so we can update it from observers
    private var membersAdapter: MembersAdapter? = null

    companion object {
        fun newInstance(stackIndex: Int): InspectorFragment = InspectorFragment().apply {
            arguments = Bundle().apply { putInt(ARG_STACK_INDEX, stackIndex) }
        }
    }

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

        // filter button
        binding.filterButton.setOnClickListener {
            FilterBottomSheetFragment.newInstance().show(parentFragmentManager, "filters")
        }

        // collection info chip
        binding.collectionInfoChip.apply {
            when (instance) {
                is Collection<*> -> { text = "Collection: size=${instance.size}"; visibility = View.VISIBLE }
                else -> {
                    val cls = instance?.javaClass
                    when {
                        cls?.isArray == true -> { text = "Array: size=${Array.getLength(instance!!)}"; visibility = View.VISIBLE }
                        instance is Map<*, *> -> { text = "Map: size=${instance.size}"; visibility = View.VISIBLE }
                        else -> visibility = View.GONE
                    }
                }
            }
        }

        val inst = instance ?: return binding.root
        val membersRaw = ReflectionInspector.listMembers(inst)
        val members = applyFilters(membersRaw, mainVm)

        val vm = ViewModelProvider(requireActivity())[InspectorViewModel::class.java]

        // create and assign adapter to property
        membersAdapter = MembersAdapter(members, inst, vm.collapsedClasses) { member ->
            when (member) {
                is FieldInfo -> activity?.onInspectField(inst, member, binding.detailsText)
                is MethodInfo -> activity?.onInvokeMethod(inst, member, binding.detailsText)
                is ElementInfo -> activity?.onInspectElement(member.value, binding.detailsText)
                is MapEntryInfo -> activity?.onInspectElement(member.value, binding.detailsText)
                is ClassHeaderInfo -> { /* no-op: adapter handles expand/collapse */ }
            }
        }

        binding.membersList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = membersAdapter
        }

        // observe filter changes and update members list immediately
        mainVm.memberFilterLive.observe(viewLifecycleOwner) { _ ->
            val updated = applyFilters(ReflectionInspector.listMembers(inst), mainVm)
            membersAdapter?.update(updated)
        }

        viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                super.onResume(owner)
                var updated = ReflectionInspector.listMembers(inst)
                updated = applyFilters(updated, mainVm)
                membersAdapter?.update(updated)
            }
        })

        return binding.root
    }

    private fun applyFilters(members: List<MemberInfo>, mainVm: MainViewModel): List<MemberInfo> {
        // basic filtering: currently supports visibility include/exclude (public/protected/private), kindFilters and modifier filters; keep headers
        val f = mainVm.memberFilter
        return members.filter { m ->
            when (m) {
                is ClassHeaderInfo -> true
                is FieldInfo -> {
                    val acc = m.field
                    // visibility check
                    val isPub = java.lang.reflect.Modifier.isPublic(acc.modifiers)
                    val isProt = java.lang.reflect.Modifier.isProtected(acc.modifiers)
                    val isPriv = java.lang.reflect.Modifier.isPrivate(acc.modifiers)
                    val vis = when {
                        isPub -> "public"
                        isProt -> "protected"
                        isPriv -> "private"
                        else -> "other"
                    }
                    // include precedence: if any INCLUDE present -> only allow those
                    val hasInclude = listOf(f.visibilityPublic, f.visibilityProtected, f.visibilityPrivate).any { it == MainViewModel.TriState.INCLUDE }
                    if (hasInclude) {
                        val allowed = mutableSetOf<String>()
                        if (f.visibilityPublic == MainViewModel.TriState.INCLUDE) allowed.add("public")
                        if (f.visibilityProtected == MainViewModel.TriState.INCLUDE) allowed.add("protected")
                        if (f.visibilityPrivate == MainViewModel.TriState.INCLUDE) allowed.add("private")
                        if (!allowed.contains(vis)) return@filter false
                    } else {
                        // apply excludes
                        if (f.visibilityPublic == MainViewModel.TriState.EXCLUDE && vis == "public") return@filter false
                        if (f.visibilityProtected == MainViewModel.TriState.EXCLUDE && vis == "protected") return@filter false
                        if (f.visibilityPrivate == MainViewModel.TriState.EXCLUDE && vis == "private") return@filter false
                    }

                    // kind filter for fields
                    when (f.kindFields) {
                        MainViewModel.TriState.EXCLUDE -> return@filter false
                        MainViewModel.TriState.INCLUDE -> { /* keep */ }
                        else -> { }
                    }

                    // static/final
                    if (f.isStatic == MainViewModel.TriState.INCLUDE && !java.lang.reflect.Modifier.isStatic(acc.modifiers)) return@filter false
                    if (f.isStatic == MainViewModel.TriState.EXCLUDE && java.lang.reflect.Modifier.isStatic(acc.modifiers)) return@filter false
                    if (f.isFinal == MainViewModel.TriState.INCLUDE && !java.lang.reflect.Modifier.isFinal(acc.modifiers)) return@filter false
                    if (f.isFinal == MainViewModel.TriState.EXCLUDE && java.lang.reflect.Modifier.isFinal(acc.modifiers)) return@filter false

                    true
                }
                is MethodInfo -> {
                    val acc = m.method
                    // visibility
                    val isPub = java.lang.reflect.Modifier.isPublic(acc.modifiers)
                    val isProt = java.lang.reflect.Modifier.isProtected(acc.modifiers)
                    val isPriv = java.lang.reflect.Modifier.isPrivate(acc.modifiers)
                    val vis = when {
                        isPub -> "public"
                        isProt -> "protected"
                        isPriv -> "private"
                        else -> "other"
                    }
                    val hasInclude = listOf(f.visibilityPublic, f.visibilityProtected, f.visibilityPrivate).any { it == MainViewModel.TriState.INCLUDE }
                    if (hasInclude) {
                        val allowed = mutableSetOf<String>()
                        if (f.visibilityPublic == MainViewModel.TriState.INCLUDE) allowed.add("public")
                        if (f.visibilityProtected == MainViewModel.TriState.INCLUDE) allowed.add("protected")
                        if (f.visibilityPrivate == MainViewModel.TriState.INCLUDE) allowed.add("private")
                        if (!allowed.contains(vis)) return@filter false
                    } else {
                        if (f.visibilityPublic == MainViewModel.TriState.EXCLUDE && vis == "public") return@filter false
                        if (f.visibilityProtected == MainViewModel.TriState.EXCLUDE && vis == "protected") return@filter false
                        if (f.visibilityPrivate == MainViewModel.TriState.EXCLUDE && vis == "private") return@filter false
                    }

                    // kind filter for methods
                    when (f.kindMethods) {
                        MainViewModel.TriState.EXCLUDE -> return@filter false
                        MainViewModel.TriState.INCLUDE -> { }
                        else -> { }
                    }

                    if (f.isStatic == MainViewModel.TriState.INCLUDE && !java.lang.reflect.Modifier.isStatic(acc.modifiers)) return@filter false
                    if (f.isStatic == MainViewModel.TriState.EXCLUDE && java.lang.reflect.Modifier.isStatic(acc.modifiers)) return@filter false
                    if (f.isFinal == MainViewModel.TriState.INCLUDE && !java.lang.reflect.Modifier.isFinal(acc.modifiers)) return@filter false
                    if (f.isFinal == MainViewModel.TriState.EXCLUDE && java.lang.reflect.Modifier.isFinal(acc.modifiers)) return@filter false

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
}
