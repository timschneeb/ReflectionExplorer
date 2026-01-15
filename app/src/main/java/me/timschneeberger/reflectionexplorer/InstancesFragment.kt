package me.timschneeberger.reflectionexplorer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager

class InstancesFragment : Fragment() {
    var onInstanceSelected: ((Any) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val rv = androidx.recyclerview.widget.RecyclerView(requireContext())
        rv.layoutManager = LinearLayoutManager(requireContext())
        val adapter = InstancesAdapter((InstancesProvider.instances.toList() + container) as List<Any>) { inst ->
            onInstanceSelected?.invoke(inst)
        }
        rv.adapter = adapter
        return rv
    }
}

