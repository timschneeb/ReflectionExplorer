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
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView

object Dialogs {
    private fun showSimpleInputDialog(
        context: Context,
        title: String,
        hint: String,
        initialText: String,
        onOk: (String) -> Unit
    ) {
        val til = TextInputLayout(context).apply {
            addView(TextInputEditText(context).apply { this.hint = hint; setText(initialText) })
            setPadding(24.dpToPx(), 12.dpToPx(), 24.dpToPx(), 0)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(til)
            .setPositiveButton(context.getString(R.string.ok), null)
            .setNegativeButton(context.getString(R.string.cancel), null)
            .create()
            .also { dialog ->
                dialog.show()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    onOk((til.editText as? TextInputEditText)?.text?.toString() ?: "")
                    dialog.dismiss()
                }
            }
    }

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
        hint: String,
        initialText: String,
        type: Class<*>,
        genericType: Type? = null,
        elementClass: Class<*>? = null,
        anchor: View?,
        callback: (Boolean, Any?, String?) -> Unit
    ) {
        // Use the richer single-parameter dialog so callers get consistent input widgets
        showParameterInputDialog(context, title, type, genericType, elementClass, initialText, anchor) { ok, value, err ->
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
            elementClass = null,
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

        val typeOptions = listOf("String", "Int", "Long", "Double", "Boolean", "Custom...")
        val typeClassMap: Map<String, Class<*>> = mapOf(
            "String" to String::class.java,
            "Int" to Int::class.javaObjectType,
            "Long" to Long::class.javaObjectType,
            "Double" to Double::class.javaObjectType,
            "Boolean" to Boolean::class.javaObjectType
        )

        // Build parameter input views using a helper to avoid duplicated logic
        fun addParamInput(i: Int, pClass: Class<*>, paramName: String?) {
            val label = paramName?.takeIf { it.isNotBlank() } ?: "param$i"
            layout.addView(TextView(context).apply { text = "$label: ${pClass.simpleName}" })

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
                val typeOptions = listOf("String", "Int", "Long", "Double", "Boolean", "Custom...")
                val auto = MaterialAutoCompleteTextView(context).apply {
                    setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, typeOptions))
                    setOnItemClickListener { _, _, position, _ ->
                        val choice = typeOptions[position]
                        if (choice == "Custom...") {
                            val inputClass = TextInputEditText(context).apply { setPadding(24.dpToPx(), 12.dpToPx(), 24.dpToPx(), 0) }
                            MaterialAlertDialogBuilder(context)
                                .setTitle(context.getString(R.string.enter_element_class))
                                .setView(inputClass)
                                .setPositiveButton(context.getString(R.string.ok)) { _, _ ->
                                    val fqcn = inputClass.text.toString().trim()
                                    chosenElementClasses[i] = runCatching { Class.forName(fqcn) }.getOrElse {
                                        anchor?.let { Snackbar.make(it, context.getString(R.string.could_not_load_class, fqcn), Snackbar.LENGTH_SHORT).show() }
                                        String::class.java
                                    }
                                    updatePreview()
                                }
                                .setNegativeButton(context.getString(R.string.cancel)) { _, _ -> chosenElementClasses[i] = String::class.java; updatePreview() }
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
                // do not return; attach listeners below
            }

            // Now handle simple types
            when (pClass) {
                Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType!! -> {
                    val cb = MaterialCheckBox(context).apply {
                        isChecked = false;
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

    // Parse a value and show an error snackbar anchored at [anchor] on failure. Returns Result of parseValue.
    private fun parseValueWithSnackbar(context: Context, anchor: View?, text: String, type: Class<*>, genericType: Type?, elementClass: Class<*>?): Result<Any?> {
        return runWithErrorSnackbar(context, anchor) { ReflectionParser.parseValue(text, type, genericType, elementClass) }
    }

    // Parse [text] as [field.type] and set the field on [instance]; show snackbar on parse/set failure when [anchor] is provided.
    private fun setFieldFromText(context: Context, anchor: View?, instance: Any, field: Field, text: String): Result<Unit> {
        return runWithErrorSnackbar(context, anchor) {
            val parsed = ReflectionParser.parseValue(text, field.type, null, null) ?: throw IllegalArgumentException("Could not parse value for type ${field.type.simpleName}")
            instance.setField(field, parsed)
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

    // Parsing helpers are centralized in ReflectionParser

    /**
     * Show an input dialog for a single parameter-like type (checkbox for booleans, enum dropdown, typed input for primitives,
     * element-type selector for collections/arrays/maps). Returns parsed value via callback.
     */
    fun showParameterInputDialog(
        context: Context,
        title: String,
        paramType: Class<*>,
        genericType: Type? = null,
        elementClass: Class<*>? = null,
        initialText: String = "",
        anchor: View?,
        callback: (Boolean, Any?, String?) -> Unit
    ) {
        val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val inputViews = mutableListOf<View>()
        val chosenElement = arrayOfNulls<Class<*>>(1)
        var preview = TextView(context)

        fun updatePreview() {
            val parsed = runCatching {
                when (val view = inputViews.firstOrNull()) {
                    is MaterialCheckBox -> view.isChecked
                    is TextInputEditText -> ReflectionParser.parseValue(view.text.toString(), paramType, genericType, chosenElement[0])
                    is MaterialAutoCompleteTextView -> if (paramType.isEnum) ReflectionParser.enumConstantFor(paramType, view.text.toString()) else "<type-selector>"
                    else -> "<err>"
                }
            }.getOrDefault("<err>")
            preview.text = context.getString(R.string.preview_label, parsed.toString())
        }

        fun addSingleParamInput(pClass: Class<*>) {
            layout.addView(TextView(context).apply { text = "param: ${pClass.simpleName}" })

            // Choose exactly one input widget based on type
            when {
                pClass.isEnum -> {
                    val enums = pClass.enumConstants?.map { (it as Enum<*>).name } ?: emptyList()
                    val til = TextInputLayout(context)
                    val auto = MaterialAutoCompleteTextView(context).apply {
                        setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, enums))
                        threshold = 0
                    }
                    til.addView(auto)
                    layout.addView(til)
                    inputViews.add(auto)
                    return
                }
                pClass == java.lang.Boolean.TYPE || pClass == java.lang.Boolean::class.javaObjectType -> {
                    val cb = MaterialCheckBox(context)
                    layout.addView(cb)
                    inputViews.add(cb)
                }
                pClass == java.lang.Integer.TYPE || pClass == java.lang.Integer::class.javaObjectType
                        || pClass == java.lang.Long.TYPE || pClass == java.lang.Long::class.javaObjectType
                        || pClass == java.lang.Double.TYPE || pClass == java.lang.Double::class.javaObjectType
                        || pClass == java.lang.Float.TYPE || pClass == java.lang.Float::class.javaObjectType -> {
                    val til = TextInputLayout(context)
                    val def = when (pClass) {
                        java.lang.Double.TYPE, java.lang.Double::class.javaObjectType,
                        java.lang.Float.TYPE, java.lang.Float::class.javaObjectType -> "0.0"
                        else -> "0"
                    }
                    val type = when (pClass) {
                        java.lang.Double.TYPE, java.lang.Double::class.javaObjectType,
                        java.lang.Float.TYPE, java.lang.Float::class.javaObjectType -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                        else -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                    }
                    val numInput = createTextInput(context, default = def, inputType = type)
                    til.addView(numInput)
                    layout.addView(til)
                    inputViews.add(numInput)
                }
                else -> {
                    val til = TextInputLayout(context)
                    val hintTxt = if (pClass.isArray || java.util.Collection::class.java.isAssignableFrom(pClass) || java.util.Map::class.java.isAssignableFrom(pClass)) context.getString(R.string.use_array_hint) else ""
                    val txt = createTextInput(context, hint = hintTxt)
                    til.addView(txt)
                    layout.addView(til)
                    inputViews.add(txt)
                }
            }

            // attach listeners for preview and initial value
            inputViews.lastOrNull()?.let { v ->
                when (v) {
                    is MaterialAutoCompleteTextView -> {
                        v.setOnItemClickListener { _, _, _, _ -> updatePreview() }
                        if (initialText.isNotBlank()) v.setText(initialText, false)
                    }
                    is TextInputEditText -> {
                        v.addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updatePreview() }
                            override fun afterTextChanged(s: Editable?) {}
                        })
                        if (initialText.isNotBlank()) v.setText(initialText)
                    }
                    is MaterialCheckBox -> {
                        v.setOnCheckedChangeListener { _, _ -> updatePreview() }
                        if (initialText.equals("true", ignoreCase = true)) v.isChecked = true
                    }
                }
            }
        }

        addSingleParamInput(paramType)
        // Ensure there's at least one input view (fallback)
        if (inputViews.isEmpty()) {
            val til = TextInputLayout(context)
            val txt = createTextInput(context, hint = if (paramType.isArray || java.util.Collection::class.java.isAssignableFrom(paramType) || java.util.Map::class.java.isAssignableFrom(paramType)) context.getString(R.string.use_array_hint) else "")
            til.addView(txt)
            layout.addView(til)
            inputViews.add(txt)
            // attach listener
            txt.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updatePreview() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        // preview and initial update
        preview = TextView(context).apply { text = context.getString(R.string.preview_label, "[]") }
        // initial preview update
        updatePreview()
        layout.addView(preview)
        layout.setPadding(24.dpToPx(), 12.dpToPx(), 24.dpToPx(), 0)

        val maxHeightPx = (context.resources.displayMetrics.heightPixels * 0.6f).toInt()
        val scroll = NestedScrollView(context).apply {
            isFillViewport = true
            addView(layout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeightPx)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(context.getString(R.string.ok), null)
            .setNegativeButton(context.getString(R.string.cancel), null)
            .create()
            .apply {
                show()
                getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                    val value: Any? = when (val view = inputViews.firstOrNull()) {
                        is MaterialCheckBox -> view.isChecked
                        is TextInputEditText -> ReflectionParser.parseValue(view.text.toString(), paramType, genericType, chosenElement[0])
                        is MaterialAutoCompleteTextView -> if (paramType.isEnum) ReflectionParser.enumConstantFor(paramType, view.text.toString()) else null
                        else -> null
                    }
                    if (value != null) {
                        callback(true, value, null)
                    } else {
                        callback(false, null, "Could not parse input")
                    }
                    dismiss()
                }
            }
    }
}
