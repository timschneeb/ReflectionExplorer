package me.timschneeberger.reflectionexplorer.utils.dex

import android.content.Context
import android.util.Log
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Small helper to obtain parameter names from DEX files using dexlib2, with caching.
 */
object ParamNames {
    private const val TAG = "ParamNames"

    private val cache: ConcurrentHashMap<Method, Array<String>> = ConcurrentHashMap()

    /**
     * Returns parameter names for [method] or null if not found.
     */
    fun lookup(context: Context, method: Method): Array<String> = cache.computeIfAbsent(method) {
        try {
            val klassDescriptor = "L${method.declaringClass.name.replace('.', '/')};"
            val methodName = method.name
            val paramCount = method.parameterCount

            DexLocator.findLoadedPaths(context)?.forEach {
                for(dex in DexLocator.openPackage(it)) {
                    parseDexForMethod(dex, klassDescriptor, methodName, paramCount)?.let { names ->
                        return@computeIfAbsent names
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "lookup failed for ${method.declaringClass.name}.${method.name}: ${t.message}", t)
        }

        return@computeIfAbsent emptyArray()
    }

    private fun parseDexForMethod(dex: DexBackedDexFile, klassDescriptor: String, methodName: String, paramCount: Int): Array<String>? {
        var methodSignatureFound = false
        try {
            for (dexClass in dex.classes) {
                if (dexClass.type != klassDescriptor) continue
                if (methodSignatureFound) {
                    // Already found method in a previous class, though no parameter names were available.
                    // We only break after scanning all methods in the previous class to ensure no other overloads exist.
                    break
                }

                for (dm in dexClass.methods) {
                    if (dm.name != methodName) continue
                    try {
                        // Found potential method, set flag
                        methodSignatureFound = true

                        val names = dm.parameters.mapNotNull { it.name }
                        if (names.size == paramCount && names.all { it.isNotBlank() }) {
                            return names.toTypedArray()
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to read parameters for method $methodName in $klassDescriptor: ${t.message}", t)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "dexlib2 parse error: ${t.message}", t)
        }

        // If parameter names are missing/incomplete, generate new names
        if (methodSignatureFound) {
            return Array(paramCount) { idx -> "param$idx" }
        }

        return null
    }
}
