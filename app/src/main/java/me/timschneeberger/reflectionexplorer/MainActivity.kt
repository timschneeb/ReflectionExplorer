package me.timschneeberger.reflectionexplorer

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.checkbox.MaterialCheckBox
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import me.timschneeberger.reflectionexplorer.databinding.ActivityMainBinding
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

class MainActivity : AppCompatActivity() {
    private val inspectionStack = mutableListOf<Any>()
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Apply the insets as a margin to the view. This solution sets
            // only the bottom, left, and right dimensions, but you can apply whichever
            // insets are appropriate to your layout. You can also update the view padding
            // if that's more appropriate.
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = insets.left
                topMargin = insets.top
                rightMargin = insets.right
            }

            // Return CONSUMED if you don't want the window insets to keep passing
            // down to descendant views.
            WindowInsetsCompat.CONSUMED
        }

        if (savedInstanceState == null) {
            val f = InstancesFragment()
            f.onInstanceSelected = { inst ->
                inspectionStack.clear()
                openInspectorFor(inst)
            }
            supportFragmentManager.beginTransaction().replace(R.id.container, f).commit()
        }

        // use toolbarView found above
        setSupportActionBar(binding.toolbar)
        supportFragmentManager.addOnBackStackChangedListener {
            val canGoBack = supportFragmentManager.backStackEntryCount > 0
            supportActionBar?.setDisplayHomeAsUpEnabled(canGoBack)
            // Keep the inspectionStack size in sync with the fragment back stack
            val backCount = supportFragmentManager.backStackEntryCount
            while (inspectionStack.size > backCount) {
                inspectionStack.removeAt(inspectionStack.size - 1)
            }
            // Post refresh to next loop to ensure fragment view is available
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val current = supportFragmentManager.findFragmentById(R.id.container) as? InspectorFragment
                current?.refreshBreadcrumb()
            }
         }
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun openInspectorFor(instance: Any) {
        // push to stack
        inspectionStack.add(instance)
        val fragment = InspectorFragment.newInstance(instance)
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commit()
    }

    fun getInspectionTrail(): List<String> {
        return inspectionStack.map { it::class.java.simpleName }
    }

    fun popToLevel(idx: Int) {
        // ensure idx is within bounds
        if (idx < 0) return
        if (idx >= inspectionStack.size - 1) return // already at or below
        val toPop = inspectionStack.size - 1 - idx
        repeat(toPop) {
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
            }
        }
        // trim the stack
        while (inspectionStack.size > idx + 1) inspectionStack.removeAt(inspectionStack.size - 1)
    }

    fun onInspectField(instance: Any, fieldInfo: FieldInfo, detailsText: TextView) {
        val field = fieldInfo.field
        try {
            val v = ReflectionInspector.getField(instance, field)
            if (v != null) {
                openInspectorFor(v)
            } else {
                detailsText.text = "Member value is null"
            }
        } catch (e: Exception) {
            detailsText.text = "Error: ${e.message}"
        }
    }

    fun onInspectElement(value: Any?, detailsText: TextView) {
        if (value != null) {
            openInspectorFor(value)
        } else {
            detailsText.text = "Element is null"
        }
    }

    fun onInvokeMethod(instance: Any, methodInfo: MethodInfo, detailsText: TextView) {
        // If overloads exist, prompt user to pick the correct signature first
        val all = instance::class.java.declaredMethods.filter { it.name == methodInfo.method.name }
        if (all.size > 1) {
            val sigs = all.map { m ->
                val params = m.parameterTypes.joinToString(",") { it.simpleName }
                "${m.name}(${params}) : ${m.returnType.simpleName}"
            }
            val ctx = this
            MaterialAlertDialogBuilder(this)
                .setTitle("Choose overload for ${methodInfo.method.name}")
                .setItems(sigs.toTypedArray()) { _, which ->
                    val sel = all[which]
                    showMethodInvocationDialog(instance, sel, detailsText)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            showMethodInvocationDialog(instance, methodInfo.method, detailsText)
        }
    }

    // Show invocation dialog for a specific Method
    private fun showMethodInvocationDialog(instance: Any, method: java.lang.reflect.Method, detailsText: TextView) {
        val params = method.parameterTypes
        val genericTypes = method.genericParameterTypes
        val ctx = this
        if (params.isEmpty()) {
            try {
                val r = ReflectionInspector.invokeMethod(instance, method)
                detailsText.text = "Invoked ${method.name} -> $r"
            } catch (e: Exception) {
                detailsText.text = "Error invoking: ${e.message}"
            }
            return
        }

        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val inputViews = mutableListOf<View>()
        val chosenElementClasses = MutableList<Class<*>?>(params.size) { null }
        val preview = TextView(ctx).apply { text = "Preview: []" }

        fun updatePreview() {
            val parsed = params.mapIndexed { i, pClasspath ->
                try {
                    val view = inputViews[i]
                    when (view) {
                        is MaterialCheckBox -> view.isChecked
                        is TextInputEditText -> parseValue(view.text.toString(), pClasspath, genericTypes.getOrNull(i), chosenElementClasses.getOrNull(i))
                        is MaterialAutoCompleteTextView -> if (pClasspath.isEnum) enumConstantFor(pClasspath, view.text.toString()) else "<type-selector>"
                        else -> null
                    }
                } catch (_: Exception) { "<err>" }
            }
            preview.text = "Preview: ${parsed}"
        }

        val typeOptions = listOf("String", "Int", "Long", "Double", "Boolean", "Custom...")
        val typeClassMap: Map<String, Class<*>> = mapOf(
            "String" to String::class.java,
            "Int" to Integer::class.java,
            "Long" to java.lang.Long::class.java,
            "Double" to java.lang.Double::class.java,
            "Boolean" to java.lang.Boolean::class.java
        )

        for (i in params.indices) {
            val pClass = params[i]
            val label = TextView(ctx).apply { text = "param${i}: ${pClass.simpleName}" }
            layout.addView(label)

            if (pClass.isEnum) {
                val enums = pClass.enumConstants?.map { (it as Enum<*>).name } ?: emptyList()
                val til = TextInputLayout(ctx)
                val auto = MaterialAutoCompleteTextView(ctx)
                auto.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, enums))
                auto.setOnItemClickListener { _, _, _, _ -> updatePreview() }
                til.addView(auto)
                inputViews.add(auto)
                layout.addView(til)
                continue
            }

            var needsElementSelector = false
            if (pClass.isArray) {
                val comp = pClass.componentType
                if (comp != null && comp != Any::class.java) chosenElementClasses[i] = comp else needsElementSelector = true
            } else if (java.util.List::class.java.isAssignableFrom(pClass) || java.util.Collection::class.java.isAssignableFrom(pClass)) {
                val gen = genericTypes.getOrNull(i)
                if (gen is ParameterizedType) {
                    val arg = gen.actualTypeArguments.getOrNull(0)
                    if (arg is Class<*>) chosenElementClasses[i] = arg else needsElementSelector = true
                } else needsElementSelector = true
            } else if (java.util.Map::class.java.isAssignableFrom(pClass)) {
                val gen = genericTypes.getOrNull(i)
                if (gen is ParameterizedType) {
                    val arg = gen.actualTypeArguments.getOrNull(1)
                    if (arg is Class<*>) chosenElementClasses[i] = arg else needsElementSelector = true
                } else needsElementSelector = true
            }

            if (needsElementSelector) {
                val til = TextInputLayout(ctx)
                val auto = MaterialAutoCompleteTextView(ctx)
                auto.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, typeOptions))
                auto.setOnItemClickListener { _, _, position, _ ->
                    val choice = typeOptions[position]
                    if (choice == "Custom...") {
                        val inputClass = TextInputEditText(ctx)
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle("Enter element class (e.g. java.lang.Integer)")
                            .setView(inputClass)
                            .setPositiveButton("OK") { _, _ ->
                                val fqcn = inputClass.text.toString().trim()
                                try {
                                    val cls = Class.forName(fqcn)
                                    chosenElementClasses[i] = cls
                                } catch (_: Exception) {
                                    com.google.android.material.snackbar.Snackbar.make(binding.root, "Could not load $fqcn, using String", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                                    chosenElementClasses[i] = String::class.java
                                }
                                updatePreview()
                            }
                            .setNegativeButton("Cancel") { _, _ -> chosenElementClasses[i] = String::class.java; updatePreview() }
                            .show()
                    } else {
                        chosenElementClasses[i] = typeClassMap[choice]
                        updatePreview()
                    }
                }
                til.addView(auto)
                layout.addView(til)
            }

            when {
                pClass == Boolean::class.java || pClass == java.lang.Boolean.TYPE -> {
                    val cb = MaterialCheckBox(ctx)
                    cb.isChecked = false
                    cb.setOnCheckedChangeListener { _, _ -> updatePreview() }
                    inputViews.add(cb)
                    layout.addView(cb)
                }
                pClass == Int::class.java || pClass == Integer.TYPE || pClass == Long::class.java || pClass == java.lang.Long.TYPE || pClass == Double::class.java || pClass == java.lang.Double.TYPE -> {
                    val til = TextInputLayout(ctx)
                    val numInput = TextInputEditText(ctx)
                    numInput.inputType = when (pClass) {
                        Int::class.java, Integer.TYPE -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                        Long::class.java, java.lang.Long.TYPE -> InputType.TYPE_CLASS_NUMBER
                        else -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    }
                    numInput.hint = when (pClass) {
                        Int::class.java, Integer.TYPE -> "0"
                        Long::class.java, java.lang.Long.TYPE -> "0"
                        else -> "0.0"
                    }
                    numInput.addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updatePreview() }
                        override fun afterTextChanged(s: android.text.Editable?) {}
                    })
                    til.addView(numInput)
                    inputViews.add(numInput)
                    layout.addView(til)
                }
                else -> {
                    val til = TextInputLayout(ctx)
                    val txt = TextInputEditText(ctx)
                    txt.inputType = InputType.TYPE_CLASS_TEXT
                    txt.hint = if (pClass.isArray || java.util.Collection::class.java.isAssignableFrom(pClass) || java.util.Map::class.java.isAssignableFrom(pClass)) "Use [a,b] or {k:v}" else ""
                    txt.addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updatePreview() }
                        override fun afterTextChanged(s: android.text.Editable?) {}
                    })
                    til.addView(txt)
                    inputViews.add(txt)
                    layout.addView(til)
                }
            }
        }

        layout.addView(preview)
        updatePreview()

        // helper to fill defaults
        fun fillDefaults() {
            params.forEachIndexed { i, pClass ->
                val view = inputViews[i]
                when (view) {
                    is MaterialCheckBox -> view.isChecked = false
                    is TextInputEditText -> {
                        val def = when {
                            pClass == Int::class.java || pClass == Integer.TYPE -> "0"
                            pClass == Long::class.java || pClass == java.lang.Long.TYPE -> "0"
                            pClass == Double::class.java || pClass == java.lang.Double.TYPE -> "0.0"
                            else -> ""
                        }
                        view.setText(def)
                    }
                    is MaterialAutoCompleteTextView -> {
                        // leave empty
                    }
                }
            }
            updatePreview()
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Invoke ${method.name}")
            .setView(layout)
            .setNeutralButton("Defaults") { _, _ -> fillDefaults() }
            .setPositiveButton("Invoke") { _, _ ->
                try {
                    val args = params.mapIndexed { i, t ->
                        val view = inputViews[i]
                        when (view) {
                            is MaterialCheckBox -> view.isChecked as Any
                            is TextInputEditText -> parseValue(view.text.toString(), t, genericTypes.getOrNull(i), chosenElementClasses.getOrNull(i))
                            is MaterialAutoCompleteTextView -> if (t.isEnum) enumConstantFor(t, view.text.toString()) else null
                            else -> null
                        }
                    }.toTypedArray()
                    val r = ReflectionInspector.invokeMethod(instance, method, args)
                    detailsText.text = "Invoked ${method.name} -> $r"
                } catch (e: Exception) {
                    detailsText.text = "Error invoking: ${e.message}"
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun parseValue(text: String, type: Class<*>, genericType: Type? = null, elementClass: Class<*>? = null): Any? {
        if (type == String::class.java) return text
        if (type == Int::class.java || type == Integer.TYPE) return text.toIntOrNull() ?: 0
        if (type == Long::class.java || type == java.lang.Long.TYPE) return text.toLongOrNull() ?: 0L
        if (type == Boolean::class.java || type == java.lang.Boolean.TYPE) return when (text.lowercase()) { "true" -> true; else -> false }
        if (type == Double::class.java || type == java.lang.Double.TYPE) return text.toDoubleOrNull() ?: 0.0
        // Arrays: syntax [a,b,c]
        if (type.isArray) {
            val base = type.componentType ?: elementClass
            if (base != null) {
                val content = text.trim()
                if (content.startsWith("[") && content.endsWith("]")) {
                    val inner = content.substring(1, content.length - 1)
                    val parts = if (inner.isBlank()) emptyList() else inner.split(",").map { it.trim() }
                    val arr = java.lang.reflect.Array.newInstance(base, parts.size)
                    for (i in parts.indices) {
                        java.lang.reflect.Array.set(arr, i, parseValue(parts[i], base, null, null))
                    }
                    return arr
                }
            }
        }
        // Collections: try List or Set via simple parsing; use genericType or elementClass to infer element type where possible
        if (java.util.List::class.java.isAssignableFrom(type) || java.util.Collection::class.java.isAssignableFrom(type)) {
            val content = text.trim()
            if (content.startsWith("[") && content.endsWith("]")) {
                val inner = content.substring(1, content.length - 1)
                val parts = if (inner.isBlank()) emptyList() else inner.split(",").map { it.trim() }
                val elementCls: Class<*>? = elementClass ?: when (genericType) {
                    is ParameterizedType -> {
                        val arg = genericType.actualTypeArguments.getOrNull(0)
                        if (arg is Class<*>) arg else null
                    }
                    else -> null
                }
                val list = ArrayList<Any?>()
                for (p in parts) {
                    if (elementCls != null) list.add(parseValue(p, elementCls, null, null)) else list.add(p)
                }
                return list
            }
        }
        // Maps: syntax {k:v,k2:v2} (keys always strings; value type may be inferred or chosen)
        if (java.util.Map::class.java.isAssignableFrom(type)) {
            val content = text.trim()
            if (content.startsWith("{") && content.endsWith("}")) {
                val inner = content.substring(1, content.length - 1)
                val map = mutableMapOf<String, Any?>()
                val valueCls: Class<*>? = elementClass ?: when (genericType) {
                    is ParameterizedType -> {
                        val arg = genericType.actualTypeArguments.getOrNull(1)
                        if (arg is Class<*>) arg else null
                    }
                    else -> null
                }
                if (inner.isNotBlank()) {
                    inner.split(",").map { it.trim() }.forEach { pair ->
                        val kv = pair.split(":", limit = 2).map { it.trim() }
                        if (kv.size == 2) {
                            val key = kv[0]
                            val rawVal = kv[1]
                            val v = if (valueCls != null) parseValue(rawVal, valueCls, null, null) else rawVal
                            map[key] = v
                        }
                    }
                }
                return map
            }
        }
        // Fallback: attempt boxed Number parsing
        if (Number::class.java.isAssignableFrom(type)) {
            return when (type) {
                java.lang.Integer::class.java -> text.toIntOrNull() ?: 0
                java.lang.Long::class.java -> text.toLongOrNull() ?: 0L
                java.lang.Double::class.java -> text.toDoubleOrNull() ?: 0.0
                else -> null
            }
        }
        // Fallback: not supported complex parsing
        return null
    }

    // Helper: get enum constant by name without using Enum.valueOf to avoid generic capture issues
    private fun enumConstantFor(enumClass: Class<*>, name: String): Any? {
        if (!enumClass.isEnum) return null
        val consts = enumClass.enumConstants ?: return null
        for (c in consts) {
            if ((c as Enum<*>).name == name) return c
        }
        return null
    }
}