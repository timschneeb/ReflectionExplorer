package me.timschneeberger.reflectionexplorer.utils

import android.content.Context
import android.view.View
import android.annotation.SuppressLint
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
import java.lang.reflect.Array as JArray
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.emptyArray

@SuppressLint("SetTextI18n")
object Dialogs {
    fun canParseType(type: Class<*>): Boolean = when (type) {
        // Explicitly use Java boxed types to avoid confusion with Kotlin types
        String::class.java,
        Int::class.javaObjectType, Int::class.javaPrimitiveType!!,
        Long::class.javaObjectType, Long::class.javaPrimitiveType!!,
        Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType!!,
        Float::class.javaObjectType, Float::class.javaPrimitiveType!!,
        Double::class.javaObjectType, Double::class.javaPrimitiveType!! -> true
        else -> false
    }

    private fun showSimpleInputDialog(
        context: Context,
        title: String,
        hint: String,
        initialText: String,
        onOk: (String) -> Unit
    ) {
        val til = TextInputLayout(context).apply {
            addView(TextInputEditText(context).apply { this.hint = hint; setText(initialText) })
        }

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

    // Helper to create a TextInputEditText with optional text-change callback to avoid repeated TextWatcher code
    private fun createTextInput(context: Context, hint: String = "", inputType: Int = android.text.InputType.TYPE_CLASS_TEXT, onChanged: (() -> Unit)? = null): TextInputEditText {
        return TextInputEditText(context).apply {
            this.hint = hint
            this.inputType = inputType
            onChanged?.let { callback ->
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { callback() }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
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
            invokeAndShow(instance, method, emptyArray<Any?>(), detailsText)
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
            "Int" to Int::class.javaObjectType,
            "Long" to Long::class.javaObjectType,
            "Double" to Double::class.javaObjectType,
            "Boolean" to Boolean::class.javaObjectType
        )

        // Build parameter input views using a helper to avoid duplicated logic
        fun addParamInput(i: Int, pClass: Class<*>) {
            layout.addView(TextView(context).apply { text = paramLabel(i, pClass) })

            // enum -> dropdown
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
                return
            }

            // determine if we need an element type selector for arrays/collections/maps
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
                return
            }

            // Now handle simple types
            when (pClass) {
                Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType!! -> {
                    val cb = MaterialCheckBox(context).apply { isChecked = false; setOnCheckedChangeListener { _, _ -> updatePreview() } }
                    inputViews.add(cb)
                    layout.addView(cb)
                }
                Int::class.java, Int::class.javaPrimitiveType!!, Long::class.java, Long::class.javaPrimitiveType!!, Double::class.java, Double::class.javaPrimitiveType!! -> {
                    val til = TextInputLayout(context)
                    val inputType = when (pClass) {
                        Int::class.java, Int::class.javaPrimitiveType!! -> android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                        Long::class.java, Long::class.javaPrimitiveType!! -> android.text.InputType.TYPE_CLASS_NUMBER
                        else -> android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    }
                    val hint = when (pClass) {
                        Int::class.java, Int::class.javaPrimitiveType!! -> "0"
                        Long::class.java, Long::class.javaPrimitiveType!! -> "0"
                        else -> "0.0"
                    }
                    val numInput = createTextInput(context, hint = hint, inputType = inputType) { updatePreview() }
                    til.addView(numInput)
                    inputViews.add(numInput)
                    layout.addView(til)
                }
                else -> {
                    val til = TextInputLayout(context)
                    val hintTxt = if (pClass.isArray || java.util.Collection::class.java.isAssignableFrom(pClass) || java.util.Map::class.java.isAssignableFrom(pClass)) "Use [a,b] or {k:v}" else ""
                    val txt = createTextInput(context, hint = hintTxt) { updatePreview() }
                    til.addView(txt)
                    inputViews.add(txt)
                    layout.addView(til)
                }
            }
        }

        params.forEachIndexed { i, pClass -> addParamInput(i, pClass) }

        layout.addView(preview)
        layout.setPadding(24.dpToPx(), 12.dpToPx(), 24.dpToPx(), 0)
        updatePreview()

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Invoke ${method.name}")
            .setView(layout)
            .setPositiveButton("Invoke") { _, _ -> }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val args = params.mapIndexed { i, t ->
                when (val view = inputViews[i]) {
                    is MaterialCheckBox -> view.isChecked as Any
                    is TextInputEditText -> parseValue(view.text.toString(), t, genericTypes.getOrNull(i), chosenElementClasses.getOrNull(i))
                    is MaterialAutoCompleteTextView -> if (t.isEnum) enumConstantFor(t, view.text.toString()) else null
                    else -> null
                }
            }.toTypedArray()
            invokeAndShow(instance, method, args, detailsText)
            dialog.dismiss()
        }
    }

    // Helper: build a readable param label
    private fun paramLabel(index: Int, pClass: Class<*>): String = "param${index}: ${pClass.simpleName}"

    // Helper: invoke a method and show the result or error on the details TextView
    private fun invokeAndShow(instance: Any, method: Method, args: Array<Any?>, detailsText: TextView) {
        runCatching { instance.invokeMethod(method, args) }
            .onSuccess { r -> detailsText.text = "Invoked ${method.name} -> $r" }
            .onFailure { e -> detailsText.text = "Error invoking: ${e.message}" }
    }

    private fun parsePrimitiveType(text: String, type: Class<*>): Any? {
        return when (type) {
            String::class.java -> text
            Int::class.javaObjectType, Int::class.javaPrimitiveType!! -> text.toInt()
            Long::class.javaObjectType, Long::class.javaPrimitiveType!! -> text.toLong()
            Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType!! -> when (text.lowercase()) { "true" -> true; else -> false }
            Double::class.javaObjectType, Double::class.javaPrimitiveType!! -> text.toDouble()
            Float::class.javaObjectType, Float::class.javaPrimitiveType!! -> text.toFloat()
            else -> null
        }
    }

    private fun parseSimpleInput(text: String, type: Class<*>): Any {
        return parsePrimitiveType(text, type) ?: throw IllegalArgumentException("Unsupported field type: ${type.simpleName}")
    }

    private fun parseValue(text: String, type: Class<*>, genericType: Type? = null, elementClass: Class<*>? = null): Any? {
        // try primitive parse first
        parsePrimitiveType(text, type)?.let { return it }

        fun parseArrayValue(): Any? {
            val base = type.componentType ?: elementClass ?: return null
            val content = text.trim()
            if (!content.startsWith("[") || !content.endsWith("]")) return null
            val inner = content.substring(1, content.length - 1)
            val parts = if (inner.isBlank()) emptyList() else inner.split(",").map { it.trim() }
            val arr = JArray.newInstance(base, parts.size)
            for (i in parts.indices)
                JArray.set(arr, i, parseValue(parts[i], base, null, null))
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
