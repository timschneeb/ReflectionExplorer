package me.timschneeberger.reflectionexplorer.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.reflectionexplorer.MainActivity
import me.timschneeberger.reflectionexplorer.ReflectionExplorer
import me.timschneeberger.reflectionexplorer.TestInstancesProvider
import me.timschneeberger.reflectionexplorer.adapter.InstancesAdapter
import me.timschneeberger.reflectionexplorer.utils.MarginItemDecoration
import me.timschneeberger.reflectionexplorer.utils.dpToPx

class InstancesFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // TODO: remove test objects
        val testInstances = TestInstancesProvider.instances.toMutableList() + container!! + inflater

        return RecyclerView(requireContext()).apply {
            addItemDecoration(MarginItemDecoration(8.dpToPx()))
            layoutManager = LinearLayoutManager(requireContext())
            adapter = InstancesAdapter(ReflectionExplorer.instances.toList() + testInstances) {
                (activity as? MainActivity)?.handleInstanceSelected(it)
            }
        }
    }
}
