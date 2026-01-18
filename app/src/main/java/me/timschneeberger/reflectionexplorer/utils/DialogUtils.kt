package me.timschneeberger.reflectionexplorer.utils

import android.content.Context
import android.view.View
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import me.timschneeberger.reflectionexplorer.R
import java.lang.reflect.Method
import java.lang.reflect.Type

object Dialogs {
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
        // Delegate to the reusable single-parameter dialog builder
        SingleParamDialogBuilder(context, title, type, genericType, keyType, initialText, anchor).show(callback)
    }

    fun showSetFieldDialog(
        context: Context,
        instance: Any,
        fieldInfo: FieldInfo,
        anchor: View?,
        callback: (Boolean, String?) -> Unit
    ) {
        val field = fieldInfo.field
        SingleParamDialogBuilder(context, context.getString(R.string.set_field_title, field.name), field.type, field.genericType, null, "", anchor)
            .show { ok, value, err ->
                if (!ok) { callback(false, err); return@show }
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
        val generics = method.genericParameterTypes

        if (params.isEmpty()) {
            // invoke with empty args and show result
            runWithErrorSnackbar(context, anchor) { instance.invokeMethod(method, emptyArray()) }
                .onSuccess { r -> detailsText.text = context.getString(R.string.invoked_result, method.name, r?.toString() ?: "null") }
                .onFailure { e -> detailsText.text = context.getString(R.string.invoke_error, e.message ?: "") }
            return
        }

        val names = ParamNames.lookup(method)
        MultiParamDialogBuilder(context, context.getString(R.string.invoke_title, method.name), params, generics, names, null, anchor)
            .show { ok, args, _ ->
                if (!ok) return@show
                val arr = args ?: emptyArray()
                runWithErrorSnackbar(context, anchor) { instance.invokeMethod(method, arr) }
                    .onSuccess { r -> detailsText.text = context.getString(R.string.invoked_result, method.name, r?.toString() ?: "null") }
                    .onFailure { e -> detailsText.text = context.getString(R.string.invoke_error, e.message ?: "") }
            }
    }

    // Helper that runs [block] and shows an error snackbar on failure using [anchor]. Returns the Result so callers can handle success/failure.
    private fun <T> runWithErrorSnackbar(context: Context, anchor: View?, block: () -> T): Result<T> {
        val res = runCatching { block() }
        res.onFailure { e -> anchor?.let { Snackbar.make(it, context.getString(R.string.error_prefix, e.message ?: ""), Snackbar.LENGTH_SHORT).show() } }
        return res
    }
}
