package me.timschneeberger.reflectionexplorer

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.ViewModelProvider
import me.timschneeberger.reflectionexplorer.databinding.ActivityMainBinding
import me.timschneeberger.reflectionexplorer.fragment.InspectorFragment
import me.timschneeberger.reflectionexplorer.fragment.InstancesFragment
import me.timschneeberger.reflectionexplorer.model.MainViewModel
import me.timschneeberger.reflectionexplorer.utils.Dialogs.showSetFieldDialog
import me.timschneeberger.reflectionexplorer.utils.FieldInfo
import me.timschneeberger.reflectionexplorer.utils.canInspectType
import me.timschneeberger.reflectionexplorer.utils.replaceReferences
import me.timschneeberger.reflectionexplorer.utils.listMembers

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by lazy { ViewModelProvider(this)[MainViewModel::class.java] }

    companion object {
        var pendingInspection: Any? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater).also { setContentView(it.root) }

        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> { leftMargin = insets.left; topMargin = insets.top; rightMargin = insets.right }
            WindowInsetsCompat.CONSUMED
        }

        if (savedInstanceState == null) {
            val pending = pendingInspection
            if (pending != null) {
                // An object is waiting to be inspected
                pendingInspection = null
                vm.instanceWasProvidedByCaller = true
                handleInstanceSelected(pending)
            } else {
                InstancesFragment().also {
                    supportFragmentManager.beginTransaction().replace(R.id.container, it).commit()
                }
            }
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(supportFragmentManager.backStackEntryCount > 0)
        updateTitle()

        // Keep inspectionStack in sync with fragment backstack and update toolbar/back button and breadcrumbs.
        supportFragmentManager.addOnBackStackChangedListener {
            // If instance was provided by external caller, skip the instance selection when returning
            if(vm.instanceWasProvidedByCaller && supportFragmentManager.backStackEntryCount < 1) {
                finish()
                return@addOnBackStackChangedListener
            }

            val canGoBack = supportFragmentManager.backStackEntryCount > 0
            supportActionBar?.setDisplayHomeAsUpEnabled(canGoBack)

            // trim vm stack to match backstack
            val backCount = supportFragmentManager.backStackEntryCount
            while (vm.inspectionStack.size > backCount) vm.inspectionStack.removeAt(vm.inspectionStack.lastIndex)

            // Post breadcrumb refresh to avoid modifying FragmentManager while it is executing transactions.
            binding.root.post { (supportFragmentManager.findFragmentById(R.id.container) as? InspectorFragment)?.refreshBreadcrumb() }

            // update toolbar menu (refresh action visibility)
            invalidateOptionsMenu()

            // update title to reflect current inspection target
            updateTitle()
        }

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun updateTitle() {
        val title = if (vm.inspectionStack.isNotEmpty()) {
            val top = vm.inspectionStack.last()
            val count = top.listMembers().size
            getString(R.string.header_title, top::class.java.simpleName, count)
        } else {
            getString(R.string.app_name)
        }
        supportActionBar?.title = title
    }

    // Called by InstancesFragment when user selects an instance
    fun handleInstanceSelected(instance: Any) {
        vm.inspectionStack.clear()
        openInspectorFor(instance)
    }

    fun openInspectorFor(instance: Any?) {
        if (instance == null || !instance.canInspectType())
            return

        vm.inspectionStack.add(instance)
        val idx = vm.inspectionStack.size - 1
        InspectorFragment.newInstance(idx).also { fragment ->
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, fragment)
                .addToBackStack(null)
                .commit()
            // menu should update to show inspector actions
            invalidateOptionsMenu()

            // update title to reflect newly opened inspector
            updateTitle()
        }
    }

    fun getInspectionTrail(): List<String> = vm.inspectionStack.map { it::class.java.simpleName }

    fun popToLevel(idx: Int) {
        if (idx < 0) return
        if (idx >= vm.inspectionStack.size - 1) return
        val toPop = vm.inspectionStack.size - 1 - idx
        repeat(toPop) { if (supportFragmentManager.backStackEntryCount > 0) supportFragmentManager.popBackStack() }
        while (vm.inspectionStack.size > idx + 1) vm.inspectionStack.removeAt(vm.inspectionStack.lastIndex)
    }

    // Show a dialog to set a field value. callback receives (success, errorMessage?)
    fun showSetFieldDialog(instance: Any, fieldInfo: FieldInfo, callback: (Boolean, String?) -> Unit) {
        showSetFieldDialog(instance, fieldInfo, binding.root, callback)
    }

    // Replace the inspection stack entry at index `idx` with `newInstance` and refresh current inspector if shown.
    fun replaceStackAt(idx: Int, newInstance: Any) {
        if (idx < 0 || idx >= vm.inspectionStack.size) return
        val oldInstance = vm.inspectionStack[idx]

        for (pIdx in 0 until idx) {
            val parent = vm.inspectionStack[pIdx]
            try {
                val (_, replacement) = replaceReferences(parent, oldInstance, newInstance)
                if (replacement != null) {
                    // replacement is a new root for this parent position; recurse to replace in the stack
                    replaceStackAt(pIdx, replacement)
                }
            } catch (e: Exception) {
                // ignore best-effort failures
                e.printStackTrace()
            }
        }

        // finally store the new instance in the inspection stack
        vm.inspectionStack[idx] = newInstance

        // Ask the current InspectorFragment (if visible) to refresh its members to reflect the new object. Post to avoid in-layout mutations.
        val frag = supportFragmentManager.findFragmentById(R.id.container) as? InspectorFragment
        frag?.view?.post { frag.refreshMembers() }

        // update title to reflect changed contents
        updateTitle()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_inspector, menu)
        // Initially hide refresh action unless inspector is shown
        menu?.findItem(R.id.action_refresh_fields)?.isVisible = supportFragmentManager.findFragmentById(R.id.container) is InspectorFragment
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh_fields -> {
                performRefreshFields()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun performRefreshFields() {
        // Notify current InspectorFragment to refresh values (this will re-fetch field values and update adapter)
        val frag = supportFragmentManager.findFragmentById(R.id.container) as? InspectorFragment
        frag?.refreshMembers()
    }
}
