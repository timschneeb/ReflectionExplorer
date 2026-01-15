package me.timschneeberger.reflectionexplorer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class InspectorFragment : Fragment() {
    private var instance: Any? = null
    private var bcAdapter: BreadcrumbAdapter? = null
    private var breadcrumbsRv: RecyclerView? = null

    companion object {
        fun newInstance(instance: Any): InspectorFragment {
            val f = InspectorFragment()
            f.instance = instance
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val root = inflater.inflate(R.layout.fragment_inspector, container, false)
        breadcrumbsRv = root.findViewById(R.id.breadcrumbs)
        val membersRv = root.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.members_list)
        val detailsText = root.findViewById<TextView>(R.id.details_text)

        breadcrumbsRv?.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        val activity = activity as? MainActivity
        val trail = activity?.getInspectionTrail() ?: listOf(instance?.javaClass?.simpleName ?: "root")
        bcAdapter = BreadcrumbAdapter(trail, trail.size - 1) { idx ->
            // fetch latest trail from activity (may have changed) and update selection, then pop
            val liveTrail = (activity?.getInspectionTrail() ?: trail)
            bcAdapter?.update(liveTrail, idx)
            breadcrumbsRv?.post { breadcrumbsRv?.smoothScrollToPosition(idx) }
            activity?.popToLevel(idx)
        }
        breadcrumbsRv?.adapter = bcAdapter
        // auto-scroll to end
        breadcrumbsRv?.post { breadcrumbsRv?.smoothScrollToPosition((bcAdapter?.itemCount ?: 1) - 1) }

        // show collection/array/map size info
        val infoChip = root.findViewById<com.google.android.material.chip.Chip>(R.id.collection_info_chip)
        when (val instVal = instance) {
            is Collection<*> -> {
                infoChip.text = "Collection: size=${instVal.size}"
                infoChip.visibility = View.VISIBLE
            }
            else -> {
                val cls = instVal?.javaClass
                if (cls?.isArray == true) {
                    val len = java.lang.reflect.Array.getLength(instVal!!)
                    infoChip.text = "Array: size=$len"
                    infoChip.visibility = View.VISIBLE
                } else if (instVal is Map<*, *>) {
                    val m = instVal as Map<*, *>
                    infoChip.text = "Map: size=${m.size}"
                    infoChip.visibility = View.VISIBLE
                } else {
                    infoChip.visibility = View.GONE
                }
            }
        }

        membersRv.layoutManager = LinearLayoutManager(requireContext())
        val inst = instance ?: return root
        val members = ReflectionInspector.listMembers(inst)
        // persist collapsed header state in a ViewModel so it survives rotation
        val vm = androidx.lifecycle.ViewModelProvider(requireActivity()).get(InspectorViewModel::class.java)
        val membersAdapter = MembersAdapter(members, inst, vm.collapsedClasses) { member ->
            when (member) {
                is FieldInfo -> (activity as? MainActivity)?.onInspectField(inst, member, detailsText)
                is MethodInfo -> (activity as? MainActivity)?.onInvokeMethod(inst, member, detailsText)
                is ElementInfo -> (activity as? MainActivity)?.onInspectElement(inst, member.index, member.value, detailsText)
                is MapEntryInfo -> (activity as? MainActivity)?.onInspectMapEntry(inst, member.key, member.value, detailsText)
                is ClassHeaderInfo -> { /* headers are handled in adapter (expand/collapse) */ }
            }
        }
        membersRv.adapter = membersAdapter
        // keep adapter up-to-date on resume (in case ViewModel state changed)
        viewLifecycleOwner.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                super.onResume(owner)
                membersAdapter.update(ReflectionInspector.listMembers(inst))
            }
        })
        return root
    }

    override fun onResume() {
        super.onResume()
        // refresh breadcrumb in case the stack changed
        val activity = activity as? MainActivity
        val trail = activity?.getInspectionTrail()
        if (trail != null) {
            bcAdapter?.update(trail, trail.size - 1)
            breadcrumbsRv?.post { breadcrumbsRv?.smoothScrollToPosition((bcAdapter?.itemCount ?: 1) - 1) }
        }
    }

    // Called by activity when back stack changes so fragment can refresh breadcrumb UI immediately
    fun refreshBreadcrumb() {
        val activity = activity as? MainActivity ?: return
        val trail = activity.getInspectionTrail()
        bcAdapter?.update(trail, trail.size - 1)
        breadcrumbsRv?.post { breadcrumbsRv?.smoothScrollToPosition((bcAdapter?.itemCount ?: 1) - 1) }
    }
}
