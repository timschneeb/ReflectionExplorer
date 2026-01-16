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
import me.timschneeberger.reflectionexplorer.InspectorViewModel
import me.timschneeberger.reflectionexplorer.MainActivity
import me.timschneeberger.reflectionexplorer.adapter.BreadcrumbAdapter
import me.timschneeberger.reflectionexplorer.adapter.MembersAdapter
import me.timschneeberger.reflectionexplorer.databinding.FragmentInspectorBinding
import me.timschneeberger.reflectionexplorer.utils.ClassHeaderInfo
import me.timschneeberger.reflectionexplorer.utils.ElementInfo
import me.timschneeberger.reflectionexplorer.utils.FieldInfo
import me.timschneeberger.reflectionexplorer.utils.MapEntryInfo
import me.timschneeberger.reflectionexplorer.utils.MethodInfo
import me.timschneeberger.reflectionexplorer.utils.ReflectionInspector
import java.lang.reflect.Array

class InspectorFragment : Fragment() {
    private var instance: Any? = null
    private var bcAdapter: BreadcrumbAdapter? = null
    private lateinit var binding: FragmentInspectorBinding

    companion object {
        fun newInstance(instance: Any): InspectorFragment = InspectorFragment().apply { this.instance = instance }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentInspectorBinding.inflate(inflater, container, false)

        val activity = activity as? MainActivity
        val trail = activity?.getInspectionTrail() ?: listOf(instance?.javaClass?.simpleName ?: "root")

        bcAdapter = BreadcrumbAdapter(trail, trail.size - 1) { idx ->
            activity?.getInspectionTrail()?.let { live ->
                bcAdapter?.update(live, idx)
                binding.breadcrumbs.post { binding.breadcrumbs.smoothScrollToPosition(idx) }
            }
            activity?.popToLevel(idx)
        }

        binding.breadcrumbs.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = bcAdapter
            post { smoothScrollToPosition((bcAdapter?.itemCount ?: 1) - 1) }
        }

        // collection info chip
        binding.collectionInfoChip.apply {
            when (val instVal = instance) {
                is Collection<*> -> { text = "Collection: size=${instVal.size}"; visibility = View.VISIBLE }
                else -> {
                    val cls = instVal?.javaClass
                    when {
                        cls?.isArray == true -> { text = "Array: size=${Array.getLength(instVal!!)}"; visibility = View.VISIBLE }
                        instVal is Map<*, *> -> { text = "Map: size=${instVal.size}"; visibility = View.VISIBLE }
                        else -> visibility = View.GONE
                    }
                }
            }
        }

        val inst = instance ?: return binding.root
        val members = ReflectionInspector.listMembers(inst)
        val vm = ViewModelProvider(requireActivity())[InspectorViewModel::class.java]

        val membersAdapter = MembersAdapter(members, inst, vm.collapsedClasses) { member ->
            when (member) {
                is FieldInfo -> activity?.onInspectField(inst, member, binding.detailsText)
                is MethodInfo -> activity?.onInvokeMethod(inst, member, binding.detailsText)
                is ElementInfo -> activity?.onInspectElement(member.value, binding.detailsText)
                is MapEntryInfo -> activity?.onInspectElement(member.value, binding.detailsText)
                is ClassHeaderInfo -> { /* no-op: adapter handles expand/collapse */
                }
            }
        }

        binding.membersList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = membersAdapter
        }

        viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                super.onResume(owner)
                membersAdapter.update(ReflectionInspector.listMembers(inst))
            }
        })

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        refreshBreadcrumb()
    }

    fun refreshBreadcrumb() {
        (activity as? MainActivity)?.getInspectionTrail()?.let { trail ->
            bcAdapter?.update(trail, trail.size - 1)
            binding.breadcrumbs.post { binding.breadcrumbs.smoothScrollToPosition((bcAdapter?.itemCount ?: 1) - 1) }
        }
    }
}
