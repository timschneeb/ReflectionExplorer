package me.timschneeberger.reflectionexplorer.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.reflectionexplorer.MainActivity
import me.timschneeberger.reflectionexplorer.ReflectionExplorer
import me.timschneeberger.reflectionexplorer.adapter.InstancesAdapter
import me.timschneeberger.reflectionexplorer.model.InstancesViewModel
import me.timschneeberger.reflectionexplorer.utils.dpToPx

class InstancesFragment : Fragment() {

    private var recyclerView: RecyclerView? = null
    private lateinit var vm: InstancesViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        vm = ViewModelProvider(this)[InstancesViewModel::class.java]

        val rv = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    setMargins(8.dpToPx(), 0, 8.dpToPx(), 8.dpToPx())
                }
            }
            adapter = InstancesAdapter(ReflectionExplorer.instances.toList(), vm.collapsedGroups) {
                (activity as? MainActivity)?.handleInstanceSelected(it.instance)
            }
        }
        recyclerView = rv

        // Restore scroll after view is laid out
        rv.post {
            restoreScrollPositionIfAny()
        }

        return rv
    }

    override fun onPause() {
        super.onPause()
        saveScrollPositionIfAny()
    }

    override fun onDestroyView() {
        recyclerView = null
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
