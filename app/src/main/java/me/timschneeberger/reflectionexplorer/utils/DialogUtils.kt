package me.timschneeberger.reflectionexplorer.utils

import android.content.Context
import android.view.View
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import me.timschneeberger.reflectionexplorer.R
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView

// TODO: deduplicate this and cleanup
object Dialogs {
    private fun createTextInput(context: Context, hint: String = "", default: String = "", inputType: Int = InputType.TYPE_CLASS_TEXT, onChanged: (() -> Unit)? = null): TextInputEditText {
        return TextInputEditText(context).apply {
            this.text = Editable.Factory.getInstance().newEditable(default)
            this.hint = hint
            this.inputType = inputType
            onChanged?.let { callback ->
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { callback() }
                    override fun afterTextChanged(s: Editable?) {}
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
        initialText: String,
        type: Class<*>,
        genericType: Type? = null,
        keyType: Class<*>? = null,
        anchor: View?,
        callback: (Boolean, Any?, String?) -> Unit
    ) {
        // Use the richer single-parameter dialog so callers get consistent input widgets
        showParameterInputDialog(context, title, type, genericType, keyType, initialText, anchor) { ok, value, err ->
            callback(ok, value, err)
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
        // Use the richer parameter-style dialog for single-field edits so users can use checkboxes/enums/etc.
        showParameterInputDialog(
            context = context,
            title = context.getString(R.string.set_field_title, field.name),
            paramType = field.type,
            genericType = field.genericType,
            keyType = null,
            initialText = "",
            anchor = anchor
        ) { ok, value, err ->
            if (!ok) { callback(false, err); return@showParameterInputDialog }
            // apply via reflection and show snackbar on failure
            runWithErrorSnackbar(context, anchor) { instance.setField(field, value) }
                .onSuccess { callback(true, null) }
                .onFailure { e -> callback(false, e.message) }
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
            invokeAndShowResult(context, instance, method, emptyArray(), detailsText, anchor)
            return
        }

        val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val inputViews = mutableListOf<View>()
        val chosenElementClasses = MutableList<Class<*>?>(params.size) { null }
        val preview = TextView(context).apply { text = context.getString(R.string.preview_label, "[]") }
        val paramNames = ParamNames.lookup(method)

        fun updatePreview() {
            val parsed = params.mapIndexed { i, pClasspath ->
                runCatching {
                    when (val view = inputViews[i]) {
                        is MaterialCheckBox -> view.isChecked
                        is TextInputEditText -> ReflectionParser.parseValue(view.text.toString(), pClasspath, genericTypes.getOrNull(i), chosenElementClasses.getOrNull(i))
                        is MaterialAutoCompleteTextView -> if (pClasspath.isEnum) ReflectionParser.enumConstantFor(pClasspath, (view.text?.toString() ?: "")) else "<type-selector>"
                        else -> null
                    }
                }.getOrNull() ?: "<err>"
            }
            preview.text = context.getString(R.string.preview_label, parsed.toString())
        }

        // Build parameter input views using a helper to avoid duplicated logic
        fun addParamInput(i: Int, pClass: Class<*>, paramName: String?) {
            val label = paramName?.takeIf { it.isNotBlank() } ?: "param$i"
            layout.addView(TextView(context).apply { text = context.getString(R.string.param_label, label, pClass.simpleName) })

            // enum -> dropdown
            if (pClass.isEnum) {
                val enums = pClass.enumConstants?.map { (it as Enum<*>).name } ?: emptyList()
                val til = TextInputLayout(context)
                val auto = MaterialAutoCompleteTextView(context).apply {
                    setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, enums))
                    setOnItemClickListener { _, _, _, _ -> updatePreview() }
                    threshold = 0
                }
                til.addView(auto)
                inputViews.add(auto)
                layout.addView(til)
                return
            }

            // Now handle simple types
            when (pClass) {
                Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType!! -> {
                    val cb = MaterialCheckBox(context).apply {
                        isChecked = false
                        setOnCheckedChangeListener { _, _ -> updatePreview() }
                    }
                    inputViews.add(cb)
                    layout.addView(cb)
                }
                Int::class.java, Int::class.javaPrimitiveType!!,
                Long::class.java, Long::class.javaPrimitiveType!!,
                Double::class.java, Double::class.javaPrimitiveType!! -> {
                    val til = TextInputLayout(context)
                    val inputType = when (pClass) {
                        Int::class.java, Int::class.javaPrimitiveType!! -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                        Long::class.java, Long::class.javaPrimitiveType!! -> InputType.TYPE_CLASS_NUMBER
                        else -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    }
                    val def = when (pClass) {
                        Int::class.java, Int::class.javaPrimitiveType!! -> "0"
                        Long::class.java, Long::class.javaPrimitiveType!! -> "0"
                        else -> "0.0"
                    }
                    val numInput = createTextInput(context, default = def, inputType = inputType) { updatePreview() }
                    til.addView(numInput)
                    inputViews.add(numInput)
                    layout.addView(til)
                }
                else -> {
                    val til = TextInputLayout(context)
                    val hintTxt = if (pClass.isArray || java.util.Collection::class.java.isAssignableFrom(pClass) || java.util.Map::class.java.isAssignableFrom(pClass)) context.getString(R.string.use_array_hint) else ""
                    val txt = createTextInput(context, hint = hintTxt) { updatePreview() }
                    til.addView(txt)
                    inputViews.add(txt)
                    layout.addView(til)
                }
            }

            // attach listeners for preview only (initialText not applicable for method parameters)
            inputViews.lastOrNull()?.let { v ->
                when (v) {
                    is MaterialAutoCompleteTextView -> v.setOnItemClickListener { _, _, _, _ -> updatePreview() }
                    is TextInputEditText -> v.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updatePreview() }
                        override fun afterTextChanged(s: Editable?) {}
                    })
                    is MaterialCheckBox -> v.setOnCheckedChangeListener { _, _ -> updatePreview() }
                }
            }
        }

        params.forEachIndexed { i, pClass -> addParamInput(i, pClass, paramNames?.getOrNull(i)) }

        layout.addView(preview)
        layout.setPadding(24.dpToPx(), 12.dpToPx(), 24.dpToPx(), 0)
        updatePreview()

        // Wrap the parameter layout in a NestedScrollView so long dialogs can scroll.
        val maxHeightPx = (context.resources.displayMetrics.heightPixels * 0.6f).toInt()
        val scroll = NestedScrollView(context).apply {
            isFillViewport = true
            // add the vertical layout as the child
            addView(layout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // constrain the scroll container height so the dialog won't exceed the screen
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeightPx)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.invoke_title, method.name))
            .setView(scroll)
            .setPositiveButton(context.getString(R.string.invoke), null)
            .setNegativeButton(context.getString(R.string.cancel), null)
            .create()
            .apply {
                show()

                getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                    val args = params.mapIndexed { i, t ->
                        when (val view = inputViews[i]) {
                            is MaterialCheckBox -> view.isChecked as Any
                            is TextInputEditText -> ReflectionParser.parseValue(view.text.toString(), t, genericTypes.getOrNull(i), chosenElementClasses.getOrNull(i))
                            is MaterialAutoCompleteTextView -> if (t.isEnum) ReflectionParser.enumConstantFor(t, view.text.toString()) else null
                            else -> null
                        }
                    }.toTypedArray()
                    invokeAndShowResult(context, instance, method, args, detailsText, anchor)
                    dismiss()
                }
            }
    }

    // Invoke method with [args], show snackbar on failure (anchored if provided) and update [detailsText] with result or error.
    private fun invokeAndShowResult(context: Context, instance: Any, method: Method, args: Array<Any?>, detailsText: TextView, anchor: View?) {
        runWithErrorSnackbar(context, anchor) { instance.invokeMethod(method, args) }
            .onSuccess { r -> detailsText.text = context.getString(R.string.invoked_result, method.name, r?.toString() ?: "null") }
            .onFailure { e -> detailsText.text = context.getString(R.string.invoke_error, e.message ?: "") }
    }

    // Helper that runs [block] and shows an error snackbar on failure using [anchor]. Returns the Result so callers can handle success/failure.
    private fun <T> runWithErrorSnackbar(context: Context, anchor: View?, block: () -> T): Result<T> {
        val res = runCatching { block() }
        res.onFailure { e -> anchor?.let { Snackbar.make(it, context.getString(R.string.error_prefix, e.message ?: ""), Snackbar.LENGTH_SHORT).show() } }
        return res
    }

    /**
     * Show an input dialog for a single parameter-like type (checkbox for booleans, enum dropdown, typed input for primitives,
     * element-type selector for collections/arrays/maps). Returns parsed value via callback.
     */
    fun showParameterInputDialog(
        context: Context,
        title: String,
        paramType: Class<*>,
        genericType: Type? = null,
        keyType: Class<*>? = null,
        initialText: String = "",
        anchor: View?,
        callback: (Boolean, Any?, String?) -> Unit
    ) {
        val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val inputs = mutableListOf<View>()
         val chosenKey = arrayOfNulls<Class<*>>(1)
         val chosenVal = arrayOfNulls<Class<*>>(1)
         val chosenSingle = arrayOfNulls<Class<*>>(1)

        // helper: detect numeric primitive/wrapper classes
        fun isNumericClass(c: Class<*>): Boolean = c == Int::class.java || c == Int::class.javaPrimitiveType || c == Long::class.java || c == Long::class.javaPrimitiveType || c == Double::class.java || c == Double::class.javaPrimitiveType || c == Float::class.java || c == Float::class.javaPrimitiveType

        fun addLabel(text: String) = TextView(context).apply { this.text = text }.also { layout.addView(it) }

        fun attachListener(v: View, onChanged: () -> Unit) = apply {
            when (v) {
                is MaterialAutoCompleteTextView -> v.setOnItemClickListener { _, _, _, _ -> onChanged() }
                is TextInputEditText -> v.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onChanged() }
                    override fun afterTextChanged(s: Editable?) {}
                })
                is MaterialCheckBox -> v.setOnCheckedChangeListener { _, _ -> onChanged() }
            }
        }

        fun parseView(v: View, expected: Class<*>, gen: Type?, chosen: Class<*>?): Any? = runCatching {
            when (v) {
                is MaterialCheckBox -> v.isChecked as Any
                is TextInputEditText -> ReflectionParser.parseValue(v.text.toString(), expected, gen, chosen)
                is MaterialAutoCompleteTextView -> if (expected.isEnum) ReflectionParser.enumConstantFor(expected, v.text.toString()) else ReflectionParser.parseValue(v.text.toString(), expected, gen, chosen)
                else -> null
            }
        }.getOrNull()

        fun buildInput(pClass: Class<*>, init: String = "", gen: Type? = null, chosenSetter: ((Class<*>?) -> Unit)? = null): View {
            // enum
            if (pClass.isEnum) {
                val enums = pClass.enumConstants?.map { (it as Enum<*>).name } ?: emptyList()
                val til = TextInputLayout(context)
                val auto = MaterialAutoCompleteTextView(context).apply {
                    setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, enums))
                    threshold = 0
                }
                til.addView(auto)
                layout.addView(til)
                inputs.add(auto)
                return auto
            }

            // element selector
            var needsSelector = false
            if (pClass.isArray) {
                val comp = pClass.componentType
                if (comp != null && comp != Any::class.java) chosenSetter?.invoke(comp) else needsSelector = true
            } else if (java.util.List::class.java.isAssignableFrom(pClass) || java.util.Collection::class.java.isAssignableFrom(pClass)) {
                if (gen is ParameterizedType) (gen.actualTypeArguments.getOrNull(0) as? Class<*>)?.let { chosenSetter?.invoke(it) } ?: run { needsSelector = true } else needsSelector = true
            } else if (java.util.Map::class.java.isAssignableFrom(pClass)) {
                if (gen is ParameterizedType) (gen.actualTypeArguments.getOrNull(1) as? Class<*>)?.let { chosenSetter?.invoke(it) } ?: run { needsSelector = true } else needsSelector = true
            }

            if (needsSelector) {
                val til = TextInputLayout(context)
                val options = listOf("String", "Int", "Long", "Double", "Boolean", "Custom...")
                val map = mapOf("String" to String::class.java, "Int" to Int::class.javaObjectType, "Long" to Long::class.javaObjectType, "Double" to Double::class.javaObjectType, "Boolean" to Boolean::class.javaObjectType)
                val auto = MaterialAutoCompleteTextView(context).apply {
                    setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, options))
                    setOnItemClickListener { _, _, pos, _ ->
                        val choice = options[pos]
                        if (choice == "Custom...") {
                            val inputClass = TextInputEditText(context).apply { setPadding(24.dpToPx(), 12.dpToPx(), 24.dpToPx(), 0) }
                            MaterialAlertDialogBuilder(context).setTitle(context.getString(R.string.enter_element_class)).setView(inputClass)
                                .setPositiveButton(context.getString(R.string.ok)) { _, _ -> chosenSetter?.invoke(runCatching { Class.forName(inputClass.text.toString().trim()) }.getOrNull() ?: String::class.java) }
                                .setNegativeButton(context.getString(R.string.cancel)) { _, _ -> chosenSetter?.invoke(String::class.java) }.show()
                        } else chosenSetter?.invoke(map[choice])
                    }
                }
                til.addView(auto)
                layout.addView(til)
                inputs.add(auto)
                return auto
            }

            // boolean
            if (pClass == java.lang.Boolean.TYPE || pClass == java.lang.Boolean::class.javaObjectType) return MaterialCheckBox(context).also { it.isChecked = false; layout.addView(it); inputs.add(it) }

            // numeric
            if (isNumericClass(pClass)) {
                val til = TextInputLayout(context)
                val inputType = when (pClass) {
                    Int::class.java, Int::class.javaPrimitiveType -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                    Long::class.java, Long::class.javaPrimitiveType -> InputType.TYPE_CLASS_NUMBER
                    else -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                }
                val def = if (pClass == Double::class.java || pClass == Double::class.javaPrimitiveType || pClass == Float::class.java || pClass == Float::class.javaPrimitiveType) "0.0" else "0"
                val num = createTextInput(context, default = if (init.isNotBlank()) init else def, inputType = inputType)
                til.addView(num); layout.addView(til); inputs.add(num); return num
            }

            // fallback text input
            val til = TextInputLayout(context)
            val hintTxt = if (pClass.isArray || java.util.Collection::class.java.isAssignableFrom(pClass) || java.util.Map::class.java.isAssignableFrom(pClass)) context.getString(R.string.use_array_hint) else ""
            val txt = createTextInput(context, hint = hintTxt, default = init)
            til.addView(txt); layout.addView(til); inputs.add(txt); return txt
        }

        // preview widget (create before wiring listeners)
        val preview = TextView(context).apply { text = context.getString(R.string.preview_label, "[]") }
        // build UI
        if (keyType != null) {
            addLabel(context.getString(R.string.key_label))
            buildInput(keyType, "", null, chosenSetter = { chosenKey[0] = it }).also { v ->
                attachListener(v) { preview.text = context.getString(R.string.preview_label, (parseView(v, keyType, null, chosenKey[0]) to "").toString()) }
            }
            addLabel(context.getString(R.string.enter_value))
            buildInput(paramType, initialText, genericType, chosenSetter = { chosenVal[0] = it }).also { v ->
                attachListener(v) { preview.text = context.getString(R.string.preview_label, (parseView(inputs.getOrNull(0)!!, keyType, null, chosenKey[0]) to parseView(v, paramType, genericType, chosenVal[0])).toString()) }
            }
        } else {
            buildInput(paramType, initialText, genericType, chosenSetter = { chosenSingle[0] = it }).also { v ->
                attachListener(v) { preview.text = context.getString(R.string.preview_label, parseView(v, paramType, genericType, chosenSingle[0]).toString()) }
            }
        }

        layout.addView(preview)
        layout.setPadding(24.dpToPx(), 12.dpToPx(), 24.dpToPx(), 0)

        val maxHeightPx = (context.resources.displayMetrics.heightPixels * 0.6f).toInt()
        val scroll = NestedScrollView(context).apply { isFillViewport = true; addView(layout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)); layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeightPx) }

        MaterialAlertDialogBuilder(context).setTitle(title).setView(scroll).setPositiveButton(context.getString(R.string.ok), null).setNegativeButton(context.getString(R.string.cancel), null).create().also { dialog ->
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (keyType != null && inputs.size >= 2) {
                    val k = parseView(inputs[0], keyType, null, chosenKey[0]) ?: run { anchor?.let { Snackbar.make(it, context.getString(R.string.error_prefix, "Could not parse key"), Snackbar.LENGTH_SHORT).show() }; return@setOnClickListener }
                    val v = parseView(inputs[1], paramType, genericType, chosenVal[0]) ?: run { anchor?.let { Snackbar.make(it, context.getString(R.string.error_prefix, "Could not parse value"), Snackbar.LENGTH_SHORT).show() }; return@setOnClickListener }
                    callback(true, Pair(k, v), null); dialog.dismiss(); return@setOnClickListener
                }
                val view = inputs.firstOrNull()
                val parsed = view?.let { parseView(it, paramType, genericType, chosenSingle[0]) }
                if (parsed != null) { callback(true, parsed, null); dialog.dismiss() } else { anchor?.let { Snackbar.make(it, context.getString(R.string.error_prefix, "Could not parse input"), Snackbar.LENGTH_SHORT).show() } }
            }
        }
    }

}
