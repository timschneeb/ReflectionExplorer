package me.timschneeberger.reflectionexplorer.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.reflectionexplorer.Instance
import me.timschneeberger.reflectionexplorer.R
import me.timschneeberger.reflectionexplorer.ReflectionActivity
import me.timschneeberger.reflectionexplorer.ReflectionExplorer
import me.timschneeberger.reflectionexplorer.adapter.InstancesAdapter
import me.timschneeberger.reflectionexplorer.model.InstancesViewModel
import me.timschneeberger.reflectionexplorer.model.MainViewModel
import me.timschneeberger.reflectionexplorer.model.Shortcut
import me.timschneeberger.reflectionexplorer.model.StaticClass
import me.timschneeberger.reflectionexplorer.utils.ClassLoaderLocator
import me.timschneeberger.reflectionexplorer.utils.Dialogs.createProgressDialog
import me.timschneeberger.reflectionexplorer.utils.Dialogs.showErrorDialog
import me.timschneeberger.reflectionexplorer.utils.Dialogs.showInputAlert
import me.timschneeberger.reflectionexplorer.utils.cast
import me.timschneeberger.reflectionexplorer.utils.dex.StaticFields
import me.timschneeberger.reflectionexplorer.utils.dpToPx

class InstancesFragment : Fragment() {

    private var recyclerView: RecyclerView? = null
    private lateinit var vm: InstancesViewModel
    private var instancesAdapter: InstancesAdapter? = null

    private val shortcuts by lazy {
        listOf(
            Instance(
                instance = Shortcut(
                    getString(R.string.class_inspect_subtitle),
                    Shortcut.Type.EnterStaticClass
                ),
                name = getString(R.string.class_inspect),
                group = null
            ),
            Instance(
                instance = Shortcut(
                    getString(R.string.static_field_inspect_subtitle),
                    Shortcut.Type.ScanStaticFields
                ),
                name = getString(R.string.static_field_inspect),
                group = null
            ),
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // Use activity-scoped ViewModel so collapsedGroups and scroll state survive fragment
        // navigation (fragment replacement) within the same activity.
        vm = ViewModelProvider(requireActivity())[InstancesViewModel::class.java]
        val mainVm = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        val providerItems = ReflectionExplorer.instancesProvider?.provide(requireContext()) ?: emptyList()
        // Prepend a custom placeholder Instances
        val items = shortcuts.toMutableList()
        items.addAll(providerItems)

        if (vm.initialLoad) {
            vm.initialLoad = false
            vm.collapsedGroups.addAll(items.mapNotNull { it.group?.name }.distinct())
        }

        instancesAdapter = InstancesAdapter(items, vm.collapsedGroups) {
            when (val inst = it.instance) {
                is Shortcut if inst.type == Shortcut.Type.EnterStaticClass -> {
                    showClassInputDialog()
                }
                is Shortcut if inst.type == Shortcut.Type.ScanStaticFields -> {
                    runStaticFieldScan()
                }
                else -> {
                    activity
                        ?.cast<ReflectionActivity>()
                        ?.handleInstanceSelected(it.instance)
                }
            }
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

    private fun runStaticFieldScan() {
        val ctx = activity ?: return
        val progressDialog = ctx.createProgressDialog(getString(R.string.static_fields_scanning))

        progressDialog.show()
        Thread {
            try {
                val data = StaticFields.lookup(ctx) ?: throw Exception("Error during lookup")
                val tree = StaticFields.makeStaticInstanceTree(data)
                ctx.runOnUiThread {
                    progressDialog.dismiss()
                    activity
                        ?.cast<ReflectionActivity>()
                        ?.handleInstanceSelected(tree)
                }
            } catch (e: Exception) {
                ctx.runOnUiThread {
                    progressDialog.dismiss()
                    ctx.showErrorDialog(e)
                }
            }
        }.start()
    }

    private fun showClassInputDialog() {
        context?.showInputAlert(
            layoutInflater,
            getString(R.string.class_name_input),
            getString(R.string.class_name_hint),
            value = ""
        ) { name ->
            val trimmedName = name.trim()
            if (trimmedName.isEmpty())
                return@showInputAlert

            // Try to load class off the main thread
            lifecycleScope.launch {
                context?.let { ctx ->
                    val cls = withContext(Dispatchers.IO) {
                        ClassLoaderLocator.findClassInProcess(ctx, trimmedName)
                    }
                    if (cls != null) {
                        // Open inspector for a StaticClass wrapper holding the loaded Class
                        activity?.cast<ReflectionActivity>()
                            ?.handleInstanceSelected(StaticClass(cls))
                    } else {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.error)
                            .setMessage(
                                getString(
                                    R.string.class_not_found,
                                    trimmedName,
                                    ClassLoaderLocator.locateFromContext(ctx)
                                        .joinToString(
                                            "\n\n\n",
                                            transform = ClassLoader::toString
                                        )
                                )
                            ).setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
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

    fun refreshInstances() {
        // Re-run the provider off the main thread and update the adapter safely.
        val provider = ReflectionExplorer.instancesProvider ?: return
        val adapter = instancesAdapter ?: return

        // Save scroll state and collapsed groups (vm already holds collapsedGroups)
        saveScrollPositionIfAny()

        // Launch coroutine to run provider on IO dispatcher
        lifecycleScope.launch {
            val provided = withContext(Dispatchers.IO) { provider.provide(requireContext()) }
            val items = shortcuts.toMutableList()
            items.addAll(provided)
            adapter.setItems(items)

            // Reapply any active search filter from shared ViewModel
            val mainVm = ViewModelProvider(requireActivity())[MainViewModel::class.java]
            adapter.filter(mainVm.searchQueryLive.value)

            // Restore scroll position after layout
            recyclerView?.post { restoreScrollPositionIfAny() }
        }
    }
}
