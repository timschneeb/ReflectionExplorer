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
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.checkbox.MaterialCheckBox
import me.timschneeberger.reflectionexplorer.R
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import me.timschneeberger.reflectionexplorer.utils.reflection.ReflectionParser

/**
 * Base class that holds shared helpers for building parameter-input dialogs.
 */
abstract class BaseParamDialogBuilder<R>(
    protected val context: Context,
    protected val title: String
) {
    protected val layout: LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    protected val inputs: MutableList<View> = mutableListOf()
    protected val preview: TextView = TextView(context).apply { text = context.getString(R.string.preview_label, "[]") }

    protected fun addLabel(text: String) = TextView(context).apply { this.text = text }.also { layout.addView(it) }

    protected fun attachListener(v: View, onChanged: () -> Unit) {
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

    // Numeric/class helper used by buildInput
    protected fun isNumericClass(c: Class<*>): Boolean = when (c) {
        Int::class.java, Int::class.javaPrimitiveType,
        Long::class.java, Long::class.javaPrimitiveType,
        Double::class.java, Double::class.javaPrimitiveType,
        Float::class.java, Float::class.javaPrimitiveType -> true
        else -> false
    }

    // Centralized parser for inputs at an index, used by preview and final argument construction
    protected fun getParsedInputAt(index: Int, expected: Class<*>, gen: Type?, chosen: Class<*>?): Any? {
        val view = inputs.getOrNull(index) ?: return null
        return when (view) {
            is MaterialCheckBox -> view.isChecked
            is TextInputEditText -> ReflectionParser.parseValue(view.text.toString(), expected, gen, chosen)
            is MaterialAutoCompleteTextView -> if (expected.isEnum)
                ReflectionParser.enumConstantFor(expected, view.text.toString())
            else
                ReflectionParser.parseValue(view.text.toString(), expected, gen, chosen)
            else -> null
        }
    }

    /**
     * Build an input view for [pClass]. The [onChanged] callback is invoked when the input changes (so callers can update preview).
     * chosenSetter is used to receive a selected element type when the input needs an element-type selector.
     */
    protected fun buildInput(pClass: Class<*>, init: String = "", gen: Type? = null, chosenSetter: ((Class<*>?) -> Unit)? = null, onChanged: (() -> Unit)? = null): View {
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
            onChanged?.let { attachListener(auto, it) }
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
            val map = mapOf(
                "String" to String::class.java,
                "Int" to Int::class.javaObjectType,
                "Long" to Long::class.javaObjectType,
                "Double" to Double::class.javaObjectType,
                "Boolean" to Boolean::class.javaObjectType
            )
            val auto = MaterialAutoCompleteTextView(context).apply {
                setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, options))
                setOnItemClickListener { _, _, pos, _ ->
                    val choice = options[pos]
                    if (choice == "Custom...") {
                        val inputClass = TextInputEditText(context).apply { setPadding(24.dpToPx(), 12.dpToPx(), 24.dpToPx(), 0) }
                        MaterialAlertDialogBuilder(context).setTitle(context.getString(R.string.enter_element_class)).setView(inputClass)
                            .setPositiveButton(context.getString(R.string.ok)) { _, _ ->
                                chosenSetter?.invoke(runCatching { Class.forName(inputClass.text.toString().trim()) }.getOrNull() ?: String::class.java)
                                onChanged?.invoke()
                            }
                            .setNegativeButton(context.getString(R.string.cancel)) { _, _ ->
                                chosenSetter?.invoke(String::class.java)
                                onChanged?.invoke()
                            }
                            .show()
                    } else {
                        chosenSetter?.invoke(map[choice])
                        onChanged?.invoke()
                    }
                }
            }
            til.addView(auto)
            layout.addView(til)
            inputs.add(auto)
            onChanged?.let { attachListener(auto, it) }
            return auto
        }

        // boolean
        if (pClass == java.lang.Boolean.TYPE || pClass == java.lang.Boolean::class.javaObjectType) {
            val cb = MaterialCheckBox(context)
            cb.isChecked = false
            layout.addView(cb)
            inputs.add(cb)
            onChanged?.let { attachListener(cb, it) }
            return cb
        }

        // numeric
        if (isNumericClass(pClass)) {
            val til = TextInputLayout(context)
            val inputType = when (pClass) {
                Int::class.java, Int::class.javaPrimitiveType -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                Long::class.java, Long::class.javaPrimitiveType -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            val def = if (pClass == Double::class.java || pClass == Double::class.javaPrimitiveType ||
                pClass == Float::class.java || pClass == Float::class.javaPrimitiveType) "0.0" else "0"
            val num = createParamTextInput(context, default = init.ifBlank { def }, inputType = inputType, onChanged = onChanged)
            til.addView(num)
            layout.addView(til)
            inputs.add(num)
            return num
        }

        // fallback text input
        val til = TextInputLayout(context)
        val hintTxt = if (pClass.isArray || java.util.Collection::class.java.isAssignableFrom(pClass))
            context.getString(R.string.use_array_hint)
        else if (java.util.Map::class.java.isAssignableFrom(pClass))
            context.getString(R.string.use_map_hint)
        else
            ""
        val txt = createParamTextInput(context, hint = hintTxt, default = init, onChanged = onChanged)
        til.addView(txt)
        layout.addView(til)
        inputs.add(txt)
        return txt
    }

    protected fun showDialogWithPreview(onPositive: (dialog: AlertDialog) -> Unit, positiveLabelRes: Int = R.string.ok) {
        layout.addView(preview)
        layout.setPadding(24.dpToPx(), 12.dpToPx(), 24.dpToPx(), 0)
        val maxHeightPx = (context.resources.displayMetrics.heightPixels * 0.6f).toInt()
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(
                NestedScrollView(context).apply {
                    isFillViewport = true
                    addView(layout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeightPx)
                }
            )
            .setPositiveButton(context.getString(positiveLabelRes), null)
            .setNegativeButton(context.getString(R.string.cancel), null)
            .create()
            .also { dialog ->
                dialog.show()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onPositive(dialog) }
            }
    }

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

    abstract fun show(callback: (success: Boolean, result: R?, error: String?) -> Unit)
}

/**
 * Reusable builder for a single-parameter input dialog (used for field edit, collection element add, map entry value, ...)
 */
class SingleParamDialogBuilder(
    context: Context,
    title: String,
    private val paramType: Class<*>,
    private val genericType: Type? = null,
    private val keyType: Class<*>? = null,
    private val initialText: String = ""
) : BaseParamDialogBuilder<Any>(context, title) {
    // callback(success, parsedValue or Pair(key,value), error)
    override fun show(callback: (success: Boolean, parsedValue: Any?, error: String?) -> Unit) {
        val chosenKey = arrayOfNulls<Class<*>>(1)
        val chosenVal = arrayOfNulls<Class<*>>(1)
        val chosenSingle = arrayOfNulls<Class<*>>(1)

        // update preview for single-param dialog
        fun updatePreview() {
            val txt = if (keyType != null) {
                val k = getParsedInputAt(0, keyType, null, chosenKey[0])
                val v = getParsedInputAt(1, paramType, genericType, chosenVal[0])
                listOf(k, v).toString()
            } else {
                getParsedInputAt(0, paramType, genericType, chosenSingle[0]).toString()
            }
            preview.text = context.getString(R.string.preview_label, txt)
        }

        // build UI
        if (keyType != null) {
            addLabel(context.getString(R.string.key_label))
            buildInput(keyType, "", null, chosenSetter = { chosenKey[0] = it }, onChanged = ::updatePreview)
            addLabel(context.getString(R.string.enter_value))
            buildInput(paramType, initialText, genericType, chosenSetter = { chosenVal[0] = it }, onChanged = ::updatePreview)
        } else {
            addLabel("${context.getString(R.string.enter_value)} (${paramType.simpleName})")
            buildInput(paramType, initialText, genericType, chosenSetter = { chosenSingle[0] = it }, onChanged = ::updatePreview)
        }
        updatePreview()

        showDialogWithPreview(
            onPositive = { dialog ->
                if (keyType != null && inputs.size >= 2) {
                    val k = getParsedInputAt(0, keyType, null, chosenKey[0])
                        ?: run { Toast.makeText(context, context.getString(R.string.error_prefix, "Could not parse key"), Toast.LENGTH_SHORT).show(); return@showDialogWithPreview }
                    val v = getParsedInputAt(1, paramType, genericType, chosenVal[0])
                        ?: run { Toast.makeText(context, context.getString(R.string.error_prefix, "Could not parse value"), Toast.LENGTH_SHORT).show(); return@showDialogWithPreview }
                    callback(true, Pair(k, v), null)
                    dialog.dismiss()
                    return@showDialogWithPreview
                }
                val parsed = getParsedInputAt(0, paramType, genericType, chosenSingle[0])
                if (parsed != null) {
                    callback(true, parsed, null)
                    dialog.dismiss()
                } else {
                    Toast.makeText(context, context.getString(R.string.error_prefix, "Could not parse input"), Toast.LENGTH_SHORT).show()
                }
            },
            positiveLabelRes = R.string.ok
        )
    }
}

/**
 * General multi-parameter dialog reused for method invocation and other multi-arg cases.
 */
class MultiParamDialogBuilder(
    context: Context,
    title: String,
    private val paramTypes: Array<Class<*>>,
    private val genericTypes: Array<Type?>? = null,
    private val paramNames: Array<String>? = null,
    private val initialTexts: Array<String?>? = null
) : BaseParamDialogBuilder<Array<Any?>>(context, title) {
    override fun show(callback: (Boolean, Array<Any?>?, String?) -> Unit) {
        val chosenElementClasses = MutableList<Class<*>?>(paramTypes.size) { null }

        fun updatePreview() {
            val previews = paramTypes.mapIndexed { i, pClasspath ->
                getParsedInputAt(
                    i,
                    pClasspath,
                    genericTypes?.getOrNull(i),
                    chosenElementClasses.getOrNull(i)
                )
            }
            preview.text = context.getString(R.string.preview_label, previews.toString())
        }
        paramTypes.forEachIndexed { i, pClass ->
            val init = initialTexts?.getOrNull(i) ?: ""
            val label = paramNames?.getOrNull(i) ?: "param$i"
            // show a label for the parameter (name + type)
            addLabel(context.getString(R.string.param_label, label, pClass.simpleName))
            buildInput(pClass, init, genericTypes?.getOrNull(i), chosenSetter = { chosenElementClasses[i] = it }, onChanged = ::updatePreview)
        }

        // ensure preview wired
        updatePreview()

        showDialogWithPreview(
            onPositive = { dialog ->
                val args = paramTypes.mapIndexed { i, t ->
                    getParsedInputAt(i, t, genericTypes?.getOrNull(i), chosenElementClasses.getOrNull(i))
                }.toTypedArray()
                callback(true, args, null)
                dialog.dismiss()
            },
            positiveLabelRes = R.string.invoke
        )
    }
}
