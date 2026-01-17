package me.timschneeberger.reflectionexplorer.utils

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.checkbox.MaterialCheckBox
import java.lang.reflect.Array
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

object Dialogs {
    fun canParseType(type: Class<*>): Boolean = when (type) {
        java.lang.String::class.java,
        java.lang.Integer::class.java, java.lang.Integer.TYPE,
        java.lang.Long::class.java, java.lang.Long.TYPE,
        java.lang.Boolean::class.java, java.lang.Boolean.TYPE,
        java.lang.Float::class.java, java.lang.Float.TYPE,
        java.lang.Double::class.java, java.lang.Double.TYPE -> true
        else -> false
    }

    // Helpers to build input views
    private fun createTextInputLayout(context: Context, hint: String = "", initial: String = ""): TextInputLayout =
        TextInputLayout(context).apply {
            addView(TextInputEditText(context).apply { this.hint = hint; setText(initial) })
        }

    private fun showSimpleInputDialog(
        context: Context,
        title: String,
        hint: String,
        initialText: String,
        onOk: (String) -> Unit
    ) {
        val til = createTextInputLayout(context, hint, initialText)

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(til)
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.show()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val text = (til.editText as? TextInputEditText)?.text?.toString() ?: ""
                    onOk(text)
                    dialog.dismiss()
                }
            }
    }

    /**
     * Show a single-value edit dialog for arbitrary types.
     * callback: (success, parsedValue?, errorMessage?)
     */
    fun showEditValueDialog(
        context: Context,
        title: String,
        hint: String,
        initialText: String,
        type: Class<*>,
        genericType: Type? = null,
        elementClass: Class<*>? = null,
        anchor: View?,
        callback: (Boolean, Any?, String?) -> Unit
    ) {
        showSimpleInputDialog(context, title, hint, initialText) { text ->
            runCatching { parseValue(text, type, genericType, elementClass) }
                .onSuccess { parsed -> callback(true, parsed, null) }
                .onFailure { e -> anchor?.let { Snackbar.make(it, "Error: ${e.message}", Snackbar.LENGTH_SHORT).show() }; callback(false, null, e.message) }
        }
    }

    fun showSetFieldDialog(
        context: Context,
        instance: Any,
        fieldInfo: FieldInfo,
        anchor: View?,
        callback: (Boolean, String?) -> Unit
    ) {
        val field = fieldInfo.field
        showSimpleInputDialog(context, "Set ${field.name}", "New value for ${field.name} (${field.type.simpleName})", "") { text ->
            runCatching { parseSimpleInput(text, field.type).also { instance.setField(field, it) } }
                .onSuccess { callback(true, null) }
                .onFailure { e -> anchor?.let { Snackbar.make(it, "Error: ${e.message}", Snackbar.LENGTH_SHORT).show() }; callback(false, e.message) }
        }
    }

    fun showMethodInvocationDialog(
        context: Context,
        instance: Any,
        method: Method,
        detailsText: TextView,
        anchor: View?
    ) {
        val params = method.parameterTypes
        val genericTypes = method.genericParameterTypes

        if (params.isEmpty()) {
            runCatching { instance.invokeMethod(method) }
                .onSuccess { r -> detailsText.text = "Invoked ${method.name} -> $r" }
                .onFailure { e -> detailsText.text = "Error invoking: ${e.message}" }
            return
        }

        val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val inputViews = mutableListOf<View>()
        val chosenElementClasses = MutableList<Class<*>?>(params.size) { null }
        val preview = TextView(context).apply { text = "Preview: []" }

        fun updatePreview() {
            val parsed = params.mapIndexed { i, pClasspath ->
                runCatching {
                    when (val view = inputViews[i]) {
                        is MaterialCheckBox -> view.isChecked
                        is TextInputEditText -> parseValue(view.text.toString(), pClasspath, genericTypes.getOrNull(i), chosenElementClasses.getOrNull(i))
                        is MaterialAutoCompleteTextView -> if (pClasspath.isEnum) enumConstantFor(pClasspath, (view.text?.toString() ?: "")) else "<type-selector>"
                        else -> null
                    }
                }.getOrNull() ?: "<err>"
            }
            preview.text = "Preview: $parsed"
        }

        val typeOptions = listOf("String", "Int", "Long", "Double", "Boolean", "Custom...")
        val typeClassMap: Map<String, Class<*>> = mapOf(
            "String" to String::class.java,
            "Int" to java.lang.Integer::class.java,
            "Long" to java.lang.Long::class.java,
            "Double" to java.lang.Double::class.java,
            "Boolean" to java.lang.Boolean::class.java
        )

        params.forEachIndexed { i, pClass ->
            layout.addView(TextView(context).apply { text = "param${i}: ${pClass.simpleName}" })

            if (pClass.isEnum) {
                val enums = pClass.enumConstants?.map { (it as Enum<*>).name } ?: emptyList()
                val til = TextInputLayout(context)
                val auto = MaterialAutoCompleteTextView(context).apply {
                    setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, enums))
                    setOnItemClickListener { _, _, _, _ -> updatePreview() }
                }
                til.addView(auto)
                inputViews.add(auto)
                layout.addView(til)
                return@forEachIndexed
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
                val til = TextInputLayout(context)
                val auto = MaterialAutoCompleteTextView(context).apply {
                    setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, typeOptions))
                    setOnItemClickListener { _, _, position, _ ->
                        val choice = typeOptions[position]
                        if (choice == "Custom...") {
                            val inputClass = TextInputEditText(context).apply { setPadding(24.dpToPx(), 12.dpToPx(), 24.dpToPx(), 0) }
                            MaterialAlertDialogBuilder(context)
                                .setTitle("Enter element class (e.g. java.lang.Integer)")
                                .setView(inputClass)
                                .setPositiveButton("OK") { _, _ ->
                                    val fqcn = inputClass.text.toString().trim()
                                    chosenElementClasses[i] = runCatching { Class.forName(fqcn) }.getOrElse {
                                        anchor?.let { Snackbar.make(it, "Could not load $fqcn, using String", Snackbar.LENGTH_SHORT).show() }
                                        String::class.java
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
                }
                til.addView(auto)
                layout.addView(til)
                inputViews.add(auto)
            }

            when (pClass) {
                java.lang.Boolean::class.java, java.lang.Boolean.TYPE -> {
                    val cb = MaterialCheckBox(context).apply { isChecked = false; setOnCheckedChangeListener { _, _ -> updatePreview() } }
                    inputViews.add(cb)
                    layout.addView(cb)
                }
                Int::class.java, java.lang.Integer.TYPE, java.lang.Long::class.java, java.lang.Long.TYPE, java.lang.Double::class.java, java.lang.Double.TYPE -> {
                    val til = TextInputLayout(context)
                    val numInput = TextInputEditText(context).apply {
                        inputType = when (pClass) {
                            Int::class.java, java.lang.Integer.TYPE -> android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                            java.lang.Long::class.java, java.lang.Long.TYPE -> android.text.InputType.TYPE_CLASS_NUMBER
                            else -> android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                        }
                        hint = when (pClass) {
                            Int::class.java, java.lang.Integer.TYPE -> "0"
                            java.lang.Long::class.java, java.lang.Long.TYPE -> "0"
                            else -> "0.0"
                        }
                        addTextChangedListener(object : android.text.TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {} override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updatePreview() } override fun afterTextChanged(s: android.text.Editable?) {} })
                    }
                    til.addView(numInput)
                    inputViews.add(numInput)
                    layout.addView(til)
                }
                else -> {
                    val til = TextInputLayout(context)
                    val txt = TextInputEditText(context).apply {
                        inputType = android.text.InputType.TYPE_CLASS_TEXT
                        hint = if (pClass.isArray || java.util.Collection::class.java.isAssignableFrom(pClass) || java.util.Map::class.java.isAssignableFrom(pClass)) "Use [a,b] or {k:v}" else ""
                        addTextChangedListener(object : android.text.TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {} override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updatePreview() } override fun afterTextChanged(s: android.text.Editable?) {} })
                    }
                    til.addView(txt)
                    inputViews.add(txt)
                    layout.addView(til)
                }
            }
        }

        layout.addView(preview)
        layout.setPadding(24.dpToPx(), 12.dpToPx(), 24.dpToPx(), 0)
        updatePreview()

        fun fillDefaults() {
            params.forEachIndexed { i, pClass ->
                when (val view = inputViews[i]) {
                    is MaterialCheckBox -> view.isChecked = false
                    is TextInputEditText -> {
                        val def = when (pClass) {
                            Int::class.java, java.lang.Integer.TYPE -> "0"
                            java.lang.Long::class.java, java.lang.Long.TYPE -> "0"
                            java.lang.Double::class.java, java.lang.Double.TYPE -> "0.0"
                            else -> ""
                        }
                        view.setText(def)
                    }
                    is MaterialAutoCompleteTextView -> { /* leave empty */ }
                }
            }
            updatePreview()
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Invoke ${method.name}")
            .setView(layout)
            .setPositiveButton("Invoke") { _, _ -> }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Defaults", null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { fillDefaults() }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            runCatching {
                params.mapIndexed { i, t ->
                    when (val view = inputViews[i]) {
                        is MaterialCheckBox -> view.isChecked as Any
                        is TextInputEditText -> parseValue(view.text.toString(), t, genericTypes.getOrNull(i), chosenElementClasses.getOrNull(i))
                        is MaterialAutoCompleteTextView -> if (t.isEnum) enumConstantFor(t, view.text.toString()) else null
                        else -> null
                    }
                }.toTypedArray().let { instance.invokeMethod(method, it) }
            }.onSuccess { r -> detailsText.text = "Invoked ${method.name} -> $r"; dialog.dismiss() }
                .onFailure { e -> detailsText.text = "Error invoking: ${e.message}" }
            dialog.dismiss()
        }
    }

    private fun parseSimpleInput(text: String, type: Class<*>): Any {
        return when (type) {
            java.lang.String::class.java -> text
            java.lang.Integer::class.java, Integer.TYPE -> text.toInt()
            java.lang.Long::class.java, java.lang.Long.TYPE -> text.toLong()
            java.lang.Boolean::class.java, java.lang.Boolean.TYPE -> when (text.lowercase()) { "true" -> true; else -> false }
            java.lang.Double::class.java, java.lang.Double.TYPE -> text.toDouble()
            java.lang.Float::class.java, java.lang.Float.TYPE -> text.toFloat()
            else -> throw IllegalArgumentException("Unsupported field type: ${type.simpleName}")
        }
    }

    private fun parseValue(text: String, type: Class<*>, genericType: Type? = null, elementClass: Class<*>? = null): Any? {
        fun parsePrimitive(): Any? {
            return when (type) {
                java.lang.String::class.java -> text
                java.lang.Integer::class.java, Integer.TYPE -> text.toInt()
                java.lang.Long::class.java, java.lang.Long.TYPE -> text.toLong()
                java.lang.Boolean::class.java, java.lang.Boolean.TYPE -> when (text.lowercase()) { "true" -> true; else -> false }
                java.lang.Double::class.java, java.lang.Double.TYPE -> text.toDouble()
                java.lang.Float::class.java, java.lang.Float.TYPE -> text.toFloat()
                else -> null
            }
        }

        fun parseArrayValue(): Any? {
            val base = type.componentType ?: elementClass ?: return null
            val content = text.trim()
            if (!content.startsWith("[") || !content.endsWith("]")) return null
            val inner = content.substring(1, content.length - 1)
            val parts = if (inner.isBlank()) emptyList() else inner.split(",").map { it.trim() }
            val arr = Array.newInstance(base, parts.size)
            for (i in parts.indices)
                Array.set(arr, i, parseValue(parts[i], base, null, null))
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
            for (p in parts) list.add(if (elementCls != null) parseValue(p, elementCls, null, null) else p)
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
            if (inner.isNotBlank()) inner.split(",").map { it.trim() }.forEach { pair ->
                val kv = pair.split(":", limit = 2).map { it.trim() }
                if (kv.size == 2) {
                    val key = kv[0]
                    val rawVal = kv[1]
                    map[key] = if (valueCls != null) parseValue(rawVal, valueCls, null, null) else rawVal
                }
            }
            return map
        }

        parsePrimitive()?.let { return it }
        if (type.isArray) return parseArrayValue()
        if (java.util.List::class.java.isAssignableFrom(type) || java.util.Collection::class.java.isAssignableFrom(type)) return parseCollectionValue()
        if (java.util.Map::class.java.isAssignableFrom(type)) return parseMapValue()
        return null
    }

    private fun enumConstantFor(enumClass: Class<*>, name: String): Any? {
        if (!enumClass.isEnum) return null
        val consts = enumClass.enumConstants ?: return null
        for (c in consts) if ((c as Enum<*>).name == name) return c
        return null
    }
}
