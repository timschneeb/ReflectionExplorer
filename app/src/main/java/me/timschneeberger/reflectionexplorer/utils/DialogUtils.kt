package me.timschneeberger.reflectionexplorer.utils

import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.checkbox.MaterialCheckBox
import me.timschneeberger.reflectionexplorer.utils.ReflectionInspector
import me.timschneeberger.reflectionexplorer.utils.FieldInfo
import me.timschneeberger.reflectionexplorer.utils.dpToPx
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

object Dialogs {
    fun canParseType(type: Class<*>): Boolean = when (type) {
        String::class.java,
        Int::class.java, Integer.TYPE,
        Long::class.java, java.lang.Long.TYPE,
        java.lang.Boolean::class.java, java.lang.Boolean.TYPE,
        Double::class.java, java.lang.Double.TYPE -> true
        else -> false
    }

    fun showSetFieldDialog(
        activity: AppCompatActivity?,
        instance: Any,
        fieldInfo: FieldInfo,
        anchor: View?,
        callback: (Boolean, String?) -> Unit
    ) {
        if (activity == null) {
            callback(false, "No activity")
            return
        }
        val field = fieldInfo.field
        val ctx = activity
        val til = TextInputLayout(ctx)
        val input = TextInputEditText(ctx)
        input.hint = "New value for ${field.name} (${field.type.simpleName})"
        til.addView(input)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("Set ${field.name}")
            .setView(til)
            .setPositiveButton("Set", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = input.text?.toString() ?: ""
            try {
                val parsed = parseSimpleInput(text, field.type)
                ReflectionInspector.setField(instance, field, parsed)
                callback(true, null)
                dialog.dismiss()
            } catch (e: Exception) {
                // show brief snackbar if anchor is available
                if (anchor != null) Snackbar.make(anchor, "Error: ${e.message}", Snackbar.LENGTH_SHORT).show()
                callback(false, e.message)
            }
        }
    }

    fun showMethodInvocationDialog(
        activity: AppCompatActivity?,
        instance: Any,
        method: Method,
        detailsText: TextView,
        anchor: View?
    ) {
        if (activity == null) {
            detailsText.text = "No activity"
            return
        }
        val params = method.parameterTypes
        val genericTypes = method.genericParameterTypes
        val ctx = activity
        if (params.isEmpty()) {
            try {
                val r = ReflectionInspector.invokeMethod(instance, method)
                detailsText.text = "Invoked ${method.name} -> $r"
            } catch (e: Exception) {
                detailsText.text = "Error invoking: $e"
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
                    when (val view = inputViews[i]) {
                        is MaterialCheckBox -> view.isChecked
                        is TextInputEditText -> parseValue(view.text.toString(), pClasspath, genericTypes.getOrNull(i), chosenElementClasses.getOrNull(i))
                        is MaterialAutoCompleteTextView -> if (pClasspath.isEnum) enumConstantFor(pClasspath, view.text.toString()) else "<type-selector>"
                        else -> null
                    }
                } catch (_: Exception) { "<err>" }
            }
            preview.text = "Preview: $parsed"
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
                        val inputClass = TextInputEditText(ctx).apply {
                            setPadding(24.dpToPx(), 12.dpToPx(), 24.dpToPx(), 0)
                        }
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle("Enter element class (e.g. java.lang.Integer)")
                            .setView(inputClass)
                            .setPositiveButton("OK") { _, _ ->
                                val fqcn = inputClass.text.toString().trim()
                                try {
                                    val cls = Class.forName(fqcn)
                                    chosenElementClasses[i] = cls
                                } catch (_: Exception) {
                                    if (anchor != null) Snackbar.make(anchor, "Could not load $fqcn, using String", Snackbar.LENGTH_SHORT).show()
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

            when (pClass) {
                Boolean::class.java, java.lang.Boolean.TYPE -> {
                    val cb = MaterialCheckBox(ctx)
                    cb.isChecked = false
                    cb.setOnCheckedChangeListener { _, _ -> updatePreview() }
                    inputViews.add(cb)
                    layout.addView(cb)
                }
                Int::class.java, Integer.TYPE, Long::class.java, java.lang.Long.TYPE, Double::class.java, java.lang.Double.TYPE -> {
                    val til = TextInputLayout(ctx)
                    val numInput = TextInputEditText(ctx)
                    numInput.inputType = when (pClass) {
                        Int::class.java, Integer.TYPE -> android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                        Long::class.java, java.lang.Long.TYPE -> android.text.InputType.TYPE_CLASS_NUMBER
                        else -> android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
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
                    txt.inputType = android.text.InputType.TYPE_CLASS_TEXT
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
        layout.setPadding(24.dpToPx(), 12.dpToPx(), 24.dpToPx(), 0)
        updatePreview()

        // helper to fill defaults
        fun fillDefaults() {
            params.forEachIndexed { i, pClass ->
                when (val view = inputViews[i]) {
                    is MaterialCheckBox -> view.isChecked = false
                    is TextInputEditText -> {
                        val def = when (pClass) {
                            Int::class.java, Integer.TYPE -> "0"
                            Long::class.java, java.lang.Long.TYPE -> "0"
                            Double::class.java, java.lang.Double.TYPE -> "0.0"
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

        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle("Invoke ${method.name}")
            .setView(layout)
            .setPositiveButton("Invoke") { dialog, _ ->
                // positive button click will be handled after show() to access current input values
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Defaults", null) // we'll override to prevent dismiss

        val dialog = builder.create()
        dialog.show()

        // override neutral button to keep dialog open and apply defaults
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            fillDefaults()
        }

        // handle positive button click using the shown dialog's button so we can read input values
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            try {
                val args = params.mapIndexed { i, t ->
                    when (val view = inputViews[i]) {
                        is MaterialCheckBox -> view.isChecked as Any
                        is TextInputEditText -> parseValue(view.text.toString(), t, genericTypes.getOrNull(i), chosenElementClasses.getOrNull(i))
                        is MaterialAutoCompleteTextView -> if (t.isEnum) enumConstantFor(t, view.text.toString()) else null
                        else -> null
                    }
                }.toTypedArray()
                val r = ReflectionInspector.invokeMethod(instance, method, args)
                detailsText.text = "Invoked ${method.name} -> $r"
                dialog.dismiss()
            } catch (e: Exception) {
                detailsText.text = "Error invoking: ${e.message}"
            }
        }
    }

    private fun parseSimpleInput(text: String, type: Class<*>): Any {
        return when (type) {
            String::class.java -> text
            Int::class.java, Integer.TYPE -> text.toInt()
            Long::class.java, java.lang.Long.TYPE -> text.toLong()
            java.lang.Boolean::class.java, java.lang.Boolean.TYPE -> when (text.lowercase()) { "true" -> true; else -> false }
            Double::class.java, java.lang.Double.TYPE -> text.toDouble()
            else -> throw IllegalArgumentException("Unsupported field type: ${type.simpleName}")
        }
    }

    private fun parseValue(text: String, type: Class<*>, genericType: Type? = null, elementClass: Class<*>? = null): Any? {
        // Helper: try primitive/boxed types first
        fun parsePrimitive(): Any? {
            return when (type) {
                String::class.java -> text
                Int::class.java, Integer.TYPE -> text.toIntOrNull() ?: 0
                Long::class.java, java.lang.Long.TYPE -> text.toLongOrNull() ?: 0L
                Boolean::class.java, java.lang.Boolean.TYPE -> when (text.lowercase()) { "true" -> true; else -> false }
                Double::class.java, java.lang.Double.TYPE -> text.toDoubleOrNull() ?: 0.0
                else -> null
            }
        }

        fun parseArrayValue(): Any? {
            val base = type.componentType ?: elementClass ?: return null
            val content = text.trim()
            if (!content.startsWith("[") || !content.endsWith("]")) return null
            val inner = content.substring(1, content.length - 1)
            val parts = if (inner.isBlank()) emptyList() else inner.split(",").map { it.trim() }
            val arr = java.lang.reflect.Array.newInstance(base, parts.size)
            for (i in parts.indices) {
                java.lang.reflect.Array.set(arr, i, parseValue(parts[i], base, null, null))
            }
            return arr
        }

        fun parseCollectionValue(): Any? {
            val content = text.trim()
            if (!content.startsWith("[") || !content.endsWith("]")) return null
            val inner = content.substring(1, content.length - 1)
            val parts = if (inner.isBlank()) emptyList() else inner.split(",").map { it.trim() }
            val elementCls: Class<*>? = elementClass ?: when (genericType) {
                is ParameterizedType -> (genericType.actualTypeArguments.getOrNull(0) as? Class<*>)
                else -> null
            }
            val list = ArrayList<Any?>()
            for (p in parts) {
                if (elementCls != null) list.add(parseValue(p, elementCls, null, null)) else list.add(p)
            }
            return list
        }

        fun parseMapValue(): Any? {
            val content = text.trim()
            if (!content.startsWith("{") || !content.endsWith("}")) return null
            val inner = content.substring(1, content.length - 1)
            val map = mutableMapOf<String, Any?>()
            val valueCls: Class<*>? = elementClass ?: when (genericType) {
                is ParameterizedType -> (genericType.actualTypeArguments.getOrNull(1) as? Class<*>)
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

        // Try primitive types quickly
        parsePrimitive()?.let { return it }

        // Arrays
        if (type.isArray) return parseArrayValue()

        // Collections
        if (java.util.List::class.java.isAssignableFrom(type) || java.util.Collection::class.java.isAssignableFrom(type)) {
            return parseCollectionValue()
        }

        // Maps
        if (java.util.Map::class.java.isAssignableFrom(type)) {
            return parseMapValue()
        }

        // Fallback: not supported complex parsing
        return null
    }

    private fun enumConstantFor(enumClass: Class<*>, name: String): Any? {
        if (!enumClass.isEnum) return null
        val consts = enumClass.enumConstants ?: return null
        for (c in consts) {
            if ((c as Enum<*>).name == name) return c
        }
        return null
    }
}

