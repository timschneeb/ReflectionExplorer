package me.timschneeberger.reflectionexplorer.utils

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.timschneeberger.reflectionexplorer.R
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Type

object Dialogs {
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
        anchor: View?,
        callback: (Boolean, Any?, String?) -> Unit
    ) {
        // Delegate to the reusable single-parameter dialog builder
        SingleParamDialogBuilder(this, title, type, genericType, keyType, initialText, anchor).show(callback)
    }

    fun Context.showSetFieldDialog(
        instance: Any,
        fieldInfo: FieldInfo,
        anchor: View?,
        callback: (Boolean, String?) -> Unit
    ) {
        val field = fieldInfo.field
        SingleParamDialogBuilder(
            this,
            getString(R.string.set_field_title, field.name),
            field.type,
            field.genericType,
            null,
            fieldInfo.getValue(instance)?.toString() ?: "",
            anchor
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
        anchor: View?,
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
            params, generics, ParamNames.lookup(this, method),
            null, anchor
        ).show { ok, args, _ -> if (ok) invokeWithFeedback(args) }
    }

    fun Context.showErrorDialog(e: Throwable) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error)
            .setMessage(getString(R.string.error_prefix, e.stackTraceToString()))
            .setPositiveButton(android.R.string.ok, null)
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


