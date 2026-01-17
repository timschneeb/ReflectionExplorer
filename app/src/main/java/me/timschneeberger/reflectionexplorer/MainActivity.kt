package me.timschneeberger.reflectionexplorer

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import android.util.Log
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
import me.timschneeberger.reflectionexplorer.utils.Dialogs
import me.timschneeberger.reflectionexplorer.utils.FieldInfo
import me.timschneeberger.reflectionexplorer.utils.MethodInfo
import me.timschneeberger.reflectionexplorer.utils.ReflectionInspector

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by lazy { ViewModelProvider(this)[MainViewModel::class.java] }

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
            InstancesFragment().also {
                supportFragmentManager.beginTransaction().replace(R.id.container, it).commit()
            }
        }

        setSupportActionBar(binding.toolbar)

        // Make sure the Up arrow reflects the current back stack on activity recreate
        supportActionBar?.setDisplayHomeAsUpEnabled(supportFragmentManager.backStackEntryCount > 0)

        // Keep inspectionStack in sync with fragment backstack and update toolbar/back button and breadcrumbs.
        supportFragmentManager.addOnBackStackChangedListener {
            val canGoBack = supportFragmentManager.backStackEntryCount > 0
            supportActionBar?.setDisplayHomeAsUpEnabled(canGoBack)

            // trim vm stack to match backstack
            val backCount = supportFragmentManager.backStackEntryCount
            while (vm.inspectionStack.size > backCount) vm.inspectionStack.removeAt(vm.inspectionStack.lastIndex)

            // Post breadcrumb refresh to avoid modifying FragmentManager while it is executing transactions.
            binding.root.post { (supportFragmentManager.findFragmentById(R.id.container) as? InspectorFragment)?.refreshBreadcrumb() }

            // update toolbar menu (refresh action visibility)
            invalidateOptionsMenu()
        }

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    // Called by InstancesFragment when user selects an instance
    fun handleInstanceSelected(instance: Any) {
        vm.inspectionStack.clear()
        openInspectorFor(instance)
    }

    private fun openInspectorFor(instance: Any) {
        // If primitive or simple boxed type, do not open inspector
        if (instance::class.java.isPrimitive ||
            instance is java.lang.String || instance is java.lang.Number ||
            instance is java.lang.Boolean || instance is Character
        ) {
            Toast.makeText(this, "Cannot inspect primitive or simple types", Toast.LENGTH_SHORT).show()
            return
        }

        vm.inspectionStack.add(instance)
        val idx = vm.inspectionStack.size - 1
        InspectorFragment.newInstance(idx).also { fragment ->
            supportFragmentManager.beginTransaction().replace(R.id.container, fragment).addToBackStack(null).commit()
            // menu should update to show inspector actions
            invalidateOptionsMenu()
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

    fun onInspectField(instance: Any, fieldInfo: FieldInfo, detailsText: TextView) {
        val field = fieldInfo.field
        try {
            ReflectionInspector.getField(instance, field)?.let { openInspectorFor(it) } ?: run { detailsText.text = getString(R.string.member_value_null) }
        } catch (e: Exception) {
            detailsText.text = getString(R.string.error_prefix, e.message ?: "")
        }
    }

    // Show a dialog to set a field value. callback receives (success, errorMessage?)
    fun showSetFieldDialog(instance: Any, fieldInfo: FieldInfo, callback: (Boolean, String?) -> Unit) {
        Dialogs.showSetFieldDialog(this, instance, fieldInfo, binding.root, callback)
    }

    fun onInspectElement(value: Any?, detailsText: TextView) {
        value?.let { openInspectorFor(it) } ?: run { detailsText.text = getString(R.string.element_is_null) }
    }

    // Return the inspection stack entry at index or null
    fun getStackEntry(idx: Int): Any? = vm.inspectionStack.getOrNull(idx)

    fun onInvokeMethod(instance: Any, methodInfo: MethodInfo, detailsText: TextView) {
        Dialogs.showMethodInvocationDialog(this, instance, methodInfo.method, detailsText, binding.root)
    }

    // Replace the inspection stack entry at index `idx` with `newInstance` and refresh current inspector if shown.
    fun replaceStackAt(idx: Int, newInstance: Any) {
        if (idx < 0 || idx >= vm.inspectionStack.size) return
        val oldInstance = vm.inspectionStack[idx]

        // Search all earlier entries in the inspection stack (not only the immediate parent)
        var replacedAny = false
        for (pIdx in 0 until idx) {
            val parent = vm.inspectionStack[pIdx]
            try {
                when {
                    parent.javaClass.isArray -> {
                        val len = java.lang.reflect.Array.getLength(parent)
                        for (i in 0 until len) {
                            if (java.lang.reflect.Array.get(parent, i) === oldInstance) {
                                java.lang.reflect.Array.set(parent, i, newInstance)
                                replacedAny = true
                            }
                        }
                    }

                    parent is MutableList<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val lst = parent as MutableList<Any?>
                        for (i in 0 until lst.size) if (lst[i] === oldInstance) { lst[i] = newInstance; replacedAny = true }
                    }

                    parent is List<*> -> {
                        val copy = ArrayList(parent as List<Any?>)
                        var changed = false
                        for (i in 0 until copy.size) if (copy[i] === oldInstance) { copy[i] = newInstance; changed = true }
                        if (changed) {
                            replacedAny = true
                            replaceStackAt(pIdx, copy)
                            // continue: the replaceStackAt call will refresh fragments for that level
                        }
                    }

                    parent is MutableMap<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        val m = parent as MutableMap<Any?, Any?>
                        val keys = m.keys.toList()
                        for (k in keys) if (m[k] === oldInstance) { m[k] = newInstance; replacedAny = true }
                    }

                    parent is Map<*, *> -> {
                        val copy = LinkedHashMap(parent as Map<Any?, Any?>)
                        var changed = false
                        val keys = copy.keys.toList()
                        for (k in keys) if (copy[k] === oldInstance) { copy[k] = newInstance; changed = true }
                        if (changed) { replacedAny = true; replaceStackAt(pIdx, copy) }
                    }

                    else -> {
                        var cur: Class<*>? = parent.javaClass
                        while (cur != null && cur != Any::class.java) {
                            try {
                                for (f in cur.declaredFields) {
                                    try {
                                        f.isAccessible = true
                                        val v = f.get(parent)
                                        when {
                                            v === oldInstance -> { setFieldValue(parent, f, newInstance); replacedAny = true }
                                            v != null && v.javaClass.isArray -> {
                                                val alen = java.lang.reflect.Array.getLength(v)
                                                for (ai in 0 until alen) {
                                                    if (java.lang.reflect.Array.get(v, ai) === oldInstance) {
                                                        java.lang.reflect.Array.set(v, ai, newInstance)
                                                        replacedAny = true
                                                    }
                                                }
                                            }
                                            v is MutableList<*> -> {
                                                @Suppress("UNCHECKED_CAST")
                                                val ml = v as MutableList<Any?>
                                                for (i in 0 until ml.size) if (ml[i] === oldInstance) { ml[i] = newInstance; replacedAny = true }
                                            }
                                            v is List<*> -> {
                                                val copyList = ArrayList(v as List<Any?>)
                                                var changedList = false
                                                for (i in 0 until copyList.size) if (copyList[i] === oldInstance) { copyList[i] = newInstance; changedList = true }
                                                if (changedList) { setFieldValue(parent, f, copyList); replacedAny = true }
                                            }
                                            v is MutableMap<*, *> -> {
                                                @Suppress("UNCHECKED_CAST")
                                                val mm = v as MutableMap<Any?, Any?>
                                                val keys = mm.keys.toList()
                                                for (k in keys) if (mm[k] === oldInstance) { mm[k] = newInstance; replacedAny = true }
                                            }
                                            v is Map<*, *> -> {
                                                val copyMap = LinkedHashMap(v as Map<Any?, Any?>)
                                                var changedMap = false
                                                val keys = copyMap.keys.toList()
                                                for (k in keys) if (copyMap[k] === oldInstance) { copyMap[k] = newInstance; changedMap = true }
                                                if (changedMap) { setFieldValue(parent, f, copyMap); replacedAny = true }
                                            }
                                        }
                                    } catch (_: Exception) { /* ignore field access exceptions */ }
                                }
                            } catch (_: Exception) { /* ignore */ }
                            cur = cur.superclass
                        }
                    }
                }
            } catch (_: Exception) {
                // best-effort only — ignore any reflection failures
            }
        }

        Log.d("ReflectionExplorer", "replaceStackAt(idx=$idx) replacedAny=$replacedAny for oldInstance=${oldInstance.javaClass.name}")

        // finally store the new instance in the inspection stack
        vm.inspectionStack[idx] = newInstance

        // Ask the current InspectorFragment (if visible) to refresh its members to reflect the new object. Post to avoid in-layout mutations.
        val frag = supportFragmentManager.findFragmentById(R.id.container) as? InspectorFragment
        frag?.view?.post { frag.refreshMembers() }
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

    // Helper to set a field value on an object, attempting to temporarily clear final modifiers when necessary.
    private fun setFieldValue(target: Any, field: java.lang.reflect.Field, value: Any?) {
        try {
            field.isAccessible = true
            val modsField = java.lang.reflect.Field::class.java.getDeclaredField("modifiers")
            modsField.isAccessible = true
            val origMods = modsField.getInt(field)
            // clear final bit if present
            if ((origMods and java.lang.reflect.Modifier.FINAL) != 0) {
                modsField.setInt(field, origMods and java.lang.reflect.Modifier.FINAL.inv())
                try { field.set(target, value) } catch (_: Exception) {}
                // restore
                modsField.setInt(field, origMods)
            } else {
                field.set(target, value)
            }
        } catch (_: Exception) {
            try { field.set(target, value) } catch (_: Exception) { /* ignore */ }
        }
    }

    // Try setter method fallback if direct field write failed
    // TODO: remove this fallback
    private fun tryInvokeSetter(target: Any, field: java.lang.reflect.Field, value: Any?) {
        try {
            val cls = target.javaClass
            val setterName = "set" + field.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            var cur: Class<*>? = cls
            while (cur != null && cur != Any::class.java) {
                try {
                    val methods = cur.declaredMethods
                    for (m in methods) {
                        if (m.name == setterName && m.parameterTypes.size == 1) {
                            m.isAccessible = true
                            try { m.invoke(target, value); return } catch (_: Exception) { }
                        }
                    }
                } catch (_: Exception) { }
                cur = cur.superclass
            }
        } catch (_: Exception) { }
    }
}
