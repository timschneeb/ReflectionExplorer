package me.timschneeberger.reflectionexplorer.utils

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.timschneeberger.reflectionexplorer.R
import me.timschneeberger.reflectionexplorer.databinding.DialogTextinputBinding
import me.timschneeberger.reflectionexplorer.utils.dex.ParamNames
import me.timschneeberger.reflectionexplorer.utils.reflection.FieldInfo
import me.timschneeberger.reflectionexplorer.utils.reflection.invokeMethod
import me.timschneeberger.reflectionexplorer.utils.reflection.setField
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Type

object Dialogs {
    fun Context.createProgressDialog(message: String): AlertDialog {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24.dpToPx(), 16.dpToPx(), 24.dpToPx(), 16.dpToPx())
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        container.addView(
            ProgressBar(this).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(40.dpToPx(), 40.dpToPx()) .apply {
                    rightMargin = 16.dpToPx()
                }
            }
        )
        container.addView(
            TextView(this).apply {
                text = message
                setTextAppearance(android.R.style.TextAppearance_Large)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        )

        return MaterialAlertDialogBuilder(this)
            .setTitle(null)
            .setView(container)
            .setCancelable(false)
            .create()
    }


    /**
     * Show a single-value edit dialog for arbitrary types.
     * callback: (success, parsedValue?, errorMessage?)
     */
    fun Context.showEditValueDialog(
        title: String,
        initialText: String,
        type: Class<*>,
        genericType: Type? = null,
        keyType: Class<*>? = null,
        callback: (Boolean, Any?, String?) -> Unit
    ) {
        // Delegate to the reusable single-parameter dialog builder
        SingleParamDialogBuilder(this, title, type, genericType, keyType, initialText).show(callback)
    }

    fun Context.showSetFieldDialog(
        instance: Any,
        fieldInfo: FieldInfo,
        callback: (Boolean, String?) -> Unit
    ) {
        val field = fieldInfo.field
        SingleParamDialogBuilder(
            this,
            getString(R.string.set_field_title, field.name),
            field.type,
            field.genericType,
            null,
            fieldInfo.getValue(instance)?.toString() ?: ""
        )
            .show { ok, value, err ->
                if (!ok) { callback(false, err); return@show }
                runWithErrorDialog { instance.setField(field, value) }
                    .onSuccess { callback(true, null) }
                    .onFailure { e -> callback(false, e.message) }
            }
    }

    fun Context.showMethodInvocationDialog(
        instance: Any,
        method: Method,
        onInvoked: (Any?) -> Unit
    ) {
        fun invokeWithFeedback(args: Array<Any?>?) {
            runWithErrorDialog { instance.invokeMethod(method, args ?: emptyArray()) }
                .onSuccess { onInvoked(it) }
        }

        val params = method.parameterTypes
        val generics = method.genericParameterTypes

        if (params.isEmpty()) {
            invokeWithFeedback(null)
            return
        }

        MultiParamDialogBuilder(
            this,
            getString(R.string.invoke_title, method.name),
            params, generics,
            ParamNames.lookup(this, method), null,
        ).show { ok, args, _ -> if (ok) invokeWithFeedback(args) }
    }

    fun Context.showErrorDialog(e: Throwable) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error)
            .setMessage(getString(R.string.error_prefix, e.stackTraceToString()))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun Context.showConfirmDialog(
        title: String,
        message: String,
        yesText: String = getString(android.R.string.ok),
        noText: String = getString(android.R.string.cancel),
        onResult: (Boolean) -> Unit
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(yesText) { _, _ -> onResult(true) }
            .setNegativeButton(noText) { _, _ -> onResult(false) }
            .show()
    }


    fun Context.showInputAlert(
        layoutInflater: LayoutInflater,
        @StringRes title: Int,
        @StringRes hint: Int,
        value: String = "",
        isNumberInput: Boolean = false,
        callback: ((String) -> Unit)
    ) {
        showInputAlert(layoutInflater, getString(title), getString(hint), value, isNumberInput, callback)
    }

    fun Context.showInputAlert(
        layoutInflater: LayoutInflater,
        title: String?,
        hint: String?,
        value: String = "",
        isNumberInput: Boolean = false,
        callback: ((String) -> Unit)
    ) {
        val content = DialogTextinputBinding.inflate(layoutInflater).apply {
            textInputLayout.hint = hint
            text1.text = Editable.Factory.getInstance().newEditable(value)
            if(isNumberInput)
                text1.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(content.root)
            .setPositiveButton(android.R.string.ok) { inputDialog, _ ->
                (inputDialog as AlertDialog)
                    .findViewById<TextView>(android.R.id.text1)
                    ?.let {
                        callback.invoke(it.text.toString())
                    }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .create()
            .show()
    }

    // Helper that runs [block] and shows an error dialog on failure. Returns the Result so callers can handle success/failure.
    private fun <T> Context.runWithErrorDialog(block: () -> T): Result<T> =
        runCatching { block() }.apply {
            onFailure { e ->
                val unwrapped = when (e) {
                    // Unwrap InvocationTargetException to get the actual cause
                    is InvocationTargetException -> e.cause ?: e
                    else -> e
                }
                showErrorDialog(unwrapped)
            }
        }
}


