package me.timschneeberger.reflectionexplorer.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.reflectionexplorer.InstancesProvider
import me.timschneeberger.reflectionexplorer.MainActivity
import me.timschneeberger.reflectionexplorer.adapter.InstancesAdapter

class InstancesFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val testInstances = InstancesProvider.instances.toList() + container!! + inflater

        return RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = InstancesAdapter(testInstances) {
                (activity as? MainActivity)?.handleInstanceSelected(it)
            }
        }
    }
}
