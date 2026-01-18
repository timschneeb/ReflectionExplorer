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
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView

// Local helper for creating text inputs (kept here to avoid depending on Dialogs.createTextInput)
private fun createParamTextInput(context: Context, hint: String = "", default: String = "", inputType: Int = InputType.TYPE_CLASS_TEXT, onChanged: (() -> Unit)? = null): TextInputEditText {
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
 * Reusable builder for a single-parameter input dialog (used for field edit, collection element add, map entry value, ...)
 */
class SingleParamDialogBuilder(
    private val context: Context,
    private val title: String,
    private val paramType: Class<*>,
    private val genericType: Type? = null,
    private val keyType: Class<*>? = null,
    private val initialText: String = "",
    private val anchor: View?
) {
    // callback(success, parsedValue or Pair(key,value), error)
    fun show(callback: (Boolean, Any?, String?) -> Unit) {
        val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val inputs = mutableListOf<View>()
        val chosenKey = arrayOfNulls<Class<*>>(1)
        val chosenVal = arrayOfNulls<Class<*>>(1)
        val chosenSingle = arrayOfNulls<Class<*>>(1)

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

        fun isNumericClass(c: Class<*>): Boolean = when (c) {
            Int::class.java, Int::class.javaPrimitiveType,
            Long::class.java, Long::class.javaPrimitiveType,
            Double::class.java, Double::class.javaPrimitiveType,
            Float::class.java, Float::class.javaPrimitiveType -> true
            else -> false
        }

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

            if (pClass == java.lang.Boolean.TYPE || pClass == java.lang.Boolean::class.javaObjectType) return MaterialCheckBox(context).also { it.isChecked = false; layout.addView(it); inputs.add(it) }

            if (isNumericClass(pClass)) {
                val til = TextInputLayout(context)
                val inputType = when (pClass) {
                    Int::class.java, Int::class.javaPrimitiveType -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                    Long::class.java, Long::class.javaPrimitiveType -> InputType.TYPE_CLASS_NUMBER
                    else -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                }
                val def = if (pClass == Double::class.java || pClass == Double::class.javaPrimitiveType || pClass == Float::class.java || pClass == Float::class.javaPrimitiveType) "0.0" else "0"
                val num = createParamTextInput(context, default = if (init.isNotBlank()) init else def, inputType = inputType)
                til.addView(num); layout.addView(til); inputs.add(num); return num
            }

            val til = TextInputLayout(context)
            val hintTxt = if (pClass.isArray || java.util.Collection::class.java.isAssignableFrom(pClass) || java.util.Map::class.java.isAssignableFrom(pClass)) context.getString(R.string.use_array_hint) else ""
            val txt = createParamTextInput(context, hint = hintTxt, default = init)
            til.addView(txt); layout.addView(til); inputs.add(txt); return txt
        }

        // preview
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
            addLabel("${context.getString(R.string.enter_value)} (${paramType.simpleName})")
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

/**
 * General multi-parameter dialog reused for method invocation and other multi-arg cases.
 */
class MultiParamDialogBuilder(
    private val context: Context,
    private val title: String,
    private val paramTypes: Array<Class<*>>,
    private val genericTypes: Array<Type?>? = null,
    private val paramNames: Array<String>? = null,
    private val initialTexts: Array<String?>? = null,
    private val anchor: View?
) {
    fun show(callback: (Boolean, Array<Any?>?, String?) -> Unit) {
        val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val inputs = mutableListOf<View>()
        val chosenElementClasses = MutableList<Class<*>?>(paramTypes.size) { null }
        val preview = TextView(context).apply { text = context.getString(R.string.preview_label, "[]") }

        val typeClassMap: Map<String, Class<*>> = mapOf(
            "String" to String::class.java,
            "Int" to Int::class.javaObjectType,
            "Long" to Long::class.javaObjectType,
            "Double" to Double::class.javaObjectType,
            "Boolean" to Boolean::class.javaObjectType
        )
        val typeOptions = listOf("String", "Int", "Long", "Double", "Boolean", "Custom...")

        fun updatePreview() {
            val parsed = paramTypes.mapIndexed { i, pClasspath ->
                runCatching {
                    when (val view = inputs.getOrNull(i)) {
                        is MaterialCheckBox -> view.isChecked
                        is TextInputEditText -> ReflectionParser.parseValue(view.text.toString(), pClasspath, genericTypes?.getOrNull(i), chosenElementClasses.getOrNull(i))
                        is MaterialAutoCompleteTextView -> if (pClasspath.isEnum) ReflectionParser.enumConstantFor(pClasspath, (view.text?.toString() ?: "")) else "<type-selector>"
                        else -> "<err>"
                    }
                }.getOrNull() ?: "<err>"
            }
            preview.text = context.getString(R.string.preview_label, parsed.toString())
        }

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
                inputs.add(auto)
                layout.addView(til)
                return
            }

            // element selector for arrays/collections/maps (infer from generic type if possible)
            var needsElementSelector = false
            if (pClass.isArray) {
                val comp = pClass.componentType
                if (comp != null && comp != Any::class.java) chosenElementClasses[i] = comp else needsElementSelector = true
            } else if (java.util.List::class.java.isAssignableFrom(pClass) || java.util.Collection::class.java.isAssignableFrom(pClass)) {
                val gen = genericTypes?.getOrNull(i)
                if (gen is ParameterizedType) {
                    val arg = gen.actualTypeArguments.getOrNull(0)
                    if (arg is Class<*>) chosenElementClasses[i] = arg else needsElementSelector = true
                } else needsElementSelector = true
            } else if (java.util.Map::class.java.isAssignableFrom(pClass)) {
                val gen = genericTypes?.getOrNull(i)
                if (gen is ParameterizedType) {
                    val arg = gen.actualTypeArguments.getOrNull(1)
                    if (arg is Class<*>) chosenElementClasses[i] = arg else needsElementSelector = true
                } else needsElementSelector = true
            }

            if (needsElementSelector) {
                val til = TextInputLayout(context)
                val auto = MaterialAutoCompleteTextView(context).apply {
                    setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, typeOptions))
                    setOnItemClickListener { _, _, pos, _ ->
                        val choice = typeOptions[pos]
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
                inputs.add(auto)
                // continue to attach listeners
            }

            // simple types: boolean, numeric, fallback text
            when (pClass) {
                Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType!! -> {
                    val cb = MaterialCheckBox(context).apply { setOnCheckedChangeListener { _, _ -> updatePreview() } }
                    inputs.add(cb); layout.addView(cb); return
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
                    val num = createParamTextInput(context, default = def, inputType = inputType) { updatePreview() }
                    til.addView(num)
                    inputs.add(num)
                    layout.addView(til)
                    return
                }

                else -> {
                    val til = TextInputLayout(context)
                    val hintTxt = if (pClass.isArray || java.util.Collection::class.java.isAssignableFrom(pClass) || java.util.Map::class.java.isAssignableFrom(pClass)) context.getString(R.string.use_array_hint) else ""
                    val txt = createParamTextInput(context, hint = hintTxt, default = initialTexts?.getOrNull(i) ?: "") { updatePreview() }
                    til.addView(txt)
                    inputs.add(txt)
                    layout.addView(til)
                    return
                }
            }
        }

        // attach listeners for preview
        fun attachPreviewListeners() {
            inputs.forEach { v ->
                when (v) {
                    is MaterialAutoCompleteTextView -> v.setOnItemClickListener { _, _, _, _ -> updatePreview() }
                    is TextInputEditText -> v.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {} override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updatePreview() } override fun afterTextChanged(s: Editable?) {} })
                    is MaterialCheckBox -> v.setOnCheckedChangeListener { _, _ -> updatePreview() }
                }
            }
        }

        paramTypes.forEachIndexed { i, pClass -> addParamInput(i, pClass, paramNames?.getOrNull(i)) }
        attachPreviewListeners()
        layout.addView(preview)
        layout.setPadding(24.dpToPx(), 12.dpToPx(), 24.dpToPx(), 0)
        updatePreview()

        val maxHeightPx = (context.resources.displayMetrics.heightPixels * 0.6f).toInt()
        val scroll = NestedScrollView(context).apply { isFillViewport = true; addView(layout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)); layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeightPx) }

        MaterialAlertDialogBuilder(context).setTitle(title).setView(scroll).setPositiveButton(context.getString(R.string.invoke), null).setNegativeButton(context.getString(R.string.cancel), null).create().also { dialog ->
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val args = paramTypes.mapIndexed { i, t ->
                    val view = inputs.getOrNull(i)
                    when (view) {
                        is MaterialCheckBox -> view.isChecked as Any
                        is TextInputEditText -> ReflectionParser.parseValue(view.text.toString(), t, genericTypes?.getOrNull(i), chosenElementClasses.getOrNull(i))
                        is MaterialAutoCompleteTextView -> if (t.isEnum) ReflectionParser.enumConstantFor(t, view.text.toString()) else ReflectionParser.parseValue(view.text.toString(), t, genericTypes?.getOrNull(i), chosenElementClasses.getOrNull(i))
                        else -> null
                    }
                }.toTypedArray()
                callback(true, args, null)
                dialog.dismiss()
            }
        }
    }
}
