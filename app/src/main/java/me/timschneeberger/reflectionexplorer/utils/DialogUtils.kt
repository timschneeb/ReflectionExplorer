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
import me.timschneeberger.reflectionexplorer.R
import java.lang.reflect.Method
import java.lang.reflect.Type

@SuppressLint("SetTextI18n")
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

    // Parsing helpers are centralized in ReflectionParser

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
            parseValueWithSnackbar(context, anchor, text, type, genericType, elementClass)
                .onSuccess { parsed -> callback(true, parsed, null) }
                .onFailure { e -> callback(false, null, e.message) }
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
        showSimpleInputDialog(context, context.getString(R.string.set_field_title, field.name), context.getString(R.string.set_field_hint, field.name, field.type.simpleName), "") { text ->
             setFieldFromText(context, anchor, instance, field, text)
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
        fun addParamInput(i: Int, pClass: Class<*>) {
            layout.addView(TextView(context).apply { text = "param${i}: ${pClass.simpleName}" })

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
                if (gen is java.lang.reflect.ParameterizedType) {
                    val arg = gen.actualTypeArguments.getOrNull(0)
                    if (arg is Class<*>) chosenElementClasses[i] = arg else needsElementSelector = true
                } else needsElementSelector = true
            } else if (java.util.Map::class.java.isAssignableFrom(pClass)) {
                val gen = genericTypes.getOrNull(i)
                if (gen is java.lang.reflect.ParameterizedType) {
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
                                .setTitle(context.getString(R.string.enter_element_class))
                                .setView(inputClass)
                                .setPositiveButton("OK") { _, _ ->
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
                    val hintTxt = if (pClass.isArray || java.util.Collection::class.java.isAssignableFrom(pClass) || java.util.Map::class.java.isAssignableFrom(pClass)) context.getString(R.string.use_array_hint) else ""
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
            .setTitle(context.getString(R.string.invoke_title, method.name))
             .setView(layout)
             .setPositiveButton(context.getString(R.string.invoke), null)
             .setNegativeButton(context.getString(R.string.cancel), null)
             .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                 val args = params.mapIndexed { i, t ->
                 when (val view = inputViews[i]) {
                     is MaterialCheckBox -> view.isChecked as Any
                     is TextInputEditText -> ReflectionParser.parseValue(view.text.toString(), t, genericTypes.getOrNull(i), chosenElementClasses.getOrNull(i))
                     is MaterialAutoCompleteTextView -> if (t.isEnum) ReflectionParser.enumConstantFor(t, view.text.toString()) else null
                     else -> null
                 }
             }.toTypedArray()
             invokeAndShowResult(context, instance, method, args, detailsText, anchor)
                 dialog.dismiss()
             }
     }

    // Parse a value and show an error snackbar anchored at [anchor] on failure. Returns Result of parseValue.
    private fun parseValueWithSnackbar(context: Context, anchor: View?, text: String, type: Class<*>, genericType: Type?, elementClass: Class<*>?): Result<Any?> {
        return runWithErrorSnackbar(context, anchor) { ReflectionParser.parseValue(text, type, genericType, elementClass) }
    }

    // Parse [text] as [field.type] and set the field on [instance]; show snackbar on parse/set failure when [anchor] is provided.
    private fun setFieldFromText(context: Context, anchor: View?, instance: Any, field: java.lang.reflect.Field, text: String): Result<Unit> {
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
}
