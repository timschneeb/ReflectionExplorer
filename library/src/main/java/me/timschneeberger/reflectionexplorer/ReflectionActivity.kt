package me.timschneeberger.reflectionexplorer

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.ViewModelProvider
import me.timschneeberger.reflectionexplorer.databinding.ActivityReflectionMainBinding
import me.timschneeberger.reflectionexplorer.fragment.InspectorFragment
import me.timschneeberger.reflectionexplorer.fragment.InstancesFragment
import me.timschneeberger.reflectionexplorer.model.MainViewModel
import me.timschneeberger.reflectionexplorer.model.StaticClass
import me.timschneeberger.reflectionexplorer.utils.castOrNull
import me.timschneeberger.reflectionexplorer.utils.dex.FlattenedPackage
import me.timschneeberger.reflectionexplorer.utils.reflection.canInspectType
import me.timschneeberger.reflectionexplorer.utils.reflection.listMembers

class ReflectionActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReflectionMainBinding
    private val vm: MainViewModel by lazy { ViewModelProvider(this)[MainViewModel::class.java] }

    companion object {
        var pendingInspection: Any? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityReflectionMainBinding.inflate(layoutInflater).also { setContentView(it.root) }

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
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        updateTitle()

        // Keep inspectionStack in sync with fragment backstack and update toolbar/back button and breadcrumbs.
        supportFragmentManager.addOnBackStackChangedListener {
            // If instance was provided by external caller, skip the instance selection when returning
            if(vm.instanceWasProvidedByCaller && supportFragmentManager.backStackEntryCount < 1) {
                finish()
                return@addOnBackStackChangedListener
            }

            // trim vm stack to match backstack
            while (vm.inspectionStack.size > supportFragmentManager.backStackEntryCount)
                vm.inspectionStack.removeAt(vm.inspectionStack.lastIndex)

            // Post breadcrumb refresh to avoid modifying FragmentManager while it is executing transactions.
            supportFragmentManager.findFragmentById(R.id.container)
                ?.castOrNull<InspectorFragment>()
                ?.let {
                    binding.root.post(it::refreshBreadcrumb)
                }

            // Update action bar
            invalidateOptionsMenu()
            updateTitle()
        }

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    fun updateTitle() {
        val title = if (vm.inspectionStack.isNotEmpty()) {
            val top = vm.inspectionStack.last()
            val count = top.listMembers().size
            getString(
                R.string.header_title,
                // Special case: handle static class wrapper
                when (top) {
                    is StaticClass -> top.target.simpleName
                    is FlattenedPackage -> top.name
                    else -> top::class.java.simpleName
                },
                count)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU){
            Process.myProcessName()
        } else {
            getString(R.string.app_name_reflection_explorer)
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

            invalidateOptionsMenu()
            updateTitle()
        }
    }

    @SuppressLint("PrivateResource")
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_inspector, menu)

        // Wire up search view to shared ViewModel
        val searchItem = menu?.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.queryHint = getString(androidx.appcompat.R.string.search_menu_title)
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                vm.setSearchQuery(query)
                // collapse keyboard / view by clearing focus
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                vm.setSearchQuery(newText)
                return true
            }
        })

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh_fields -> {
                performRefresh()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun performRefresh() {
        // Notify current fragment to refresh values
        supportFragmentManager
            .findFragmentById(R.id.container)
            .castOrNull<InspectorFragment>()
            ?.refreshMembers()

        supportFragmentManager
            .findFragmentById(R.id.container)
            .castOrNull<InstancesFragment>()
            ?.refreshInstances()
    }
}
