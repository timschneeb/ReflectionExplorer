package me.timschneeberger.reflectionexplorer.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.reflectionexplorer.MainActivity
import me.timschneeberger.reflectionexplorer.ReflectionExplorer
import me.timschneeberger.reflectionexplorer.adapter.InstancesAdapter
import me.timschneeberger.reflectionexplorer.model.InstancesViewModel
import me.timschneeberger.reflectionexplorer.model.MainViewModel
import me.timschneeberger.reflectionexplorer.utils.cast
import me.timschneeberger.reflectionexplorer.utils.castOrNull
import me.timschneeberger.reflectionexplorer.utils.dpToPx

class InstancesFragment : Fragment() {

    private var recyclerView: RecyclerView? = null
    private lateinit var vm: InstancesViewModel
    private var instancesAdapter: InstancesAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        vm = ViewModelProvider(this)[InstancesViewModel::class.java]
        val mainVm = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        instancesAdapter = InstancesAdapter(ReflectionExplorer.instances.toList(), vm.collapsedGroups) {
            activity
                ?.cast<MainActivity>()
                ?.handleInstanceSelected(it.instance)
        }

        // Observe shared search query to filter displayed instances
        mainVm.searchQueryLive.observe(viewLifecycleOwner) { query ->
            instancesAdapter?.filter(query)
        }

        return RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    setMargins(8.dpToPx(), 0, 8.dpToPx(), 8.dpToPx())
                }
            }
            adapter = instancesAdapter
        }.also {
            // Restore scroll after view is laid out
            it.post { restoreScrollPositionIfAny() }
            recyclerView = it
        }
    }

    override fun onPause() {
        super.onPause()
        saveScrollPositionIfAny()
    }

    override fun onDestroyView() {
        recyclerView = null
        instancesAdapter = null
        super.onDestroyView()
    }

    private fun saveScrollPositionIfAny() {
        val rv = recyclerView ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val firstPos = lm.findFirstVisibleItemPosition()
        if (firstPos != RecyclerView.NO_POSITION) {
            val firstView = lm.findViewByPosition(firstPos)
            val offset = firstView?.top ?: 0
            vm.savedPosition = firstPos
            vm.savedOffset = offset
        }
    }

    private fun restoreScrollPositionIfAny() {
        val rv = recyclerView ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        if (vm.savedPosition != RecyclerView.NO_POSITION) {
            lm.scrollToPositionWithOffset(vm.savedPosition, vm.savedOffset)
        }
    }
}
