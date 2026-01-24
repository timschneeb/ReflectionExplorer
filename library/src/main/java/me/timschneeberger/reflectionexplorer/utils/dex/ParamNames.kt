package me.timschneeberger.reflectionexplorer.utils.dex

import android.content.Context
import android.util.Log
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Small helper to obtain parameter names from DEX files using dexlib2, with caching.
 */
object ParamNames {
    private const val TAG = "ParamNames"

    private val cache: ConcurrentHashMap<Method, Array<String>> = ConcurrentHashMap()
    val additionalDexSearchPaths = mutableListOf<String>()

    /**
     * Returns parameter names for [method] or null if not found.
     */
    fun lookup(context: Context, method: Method): Array<String>? {
        val arr = cache.computeIfAbsent(method) {
            val dexPaths = DexLocator.findLoadedPaths(context, additionalDexSearchPaths)?.filter {
                // Skip resource-only APKs
                !it.contains("/overlay/") &&
                !it.startsWith("/data/resource-cache/") &&
                it != "/system/framework/framework-res.apk"
            }

            try {
                lookupFromDex(dexPaths?.toTypedArray(), method) ?: emptyArray()
            } catch (t: Throwable) {
                Log.w(TAG, "lookup failed for ${method.declaringClass.name}.${method.name}: ${t.message}", t)
                emptyArray()
            }
        }
        return if (arr.isEmpty()) null else arr
    }

    /**
     * Read DEX/APK files and extract parameter names using dexlib2.
     */
    private fun lookupFromDex(paths: Array<String>?, method: Method): Array<String>? {
        val cls = method.declaringClass
        if (paths.isNullOrEmpty()) return null

        val klassDescriptor = "L${cls.name.replace('.', '/')};"
        val methodName = method.name
        val paramCount = method.parameterCount

        for (p in paths) {
            val f = File(p)
            if (!f.exists()) continue
            // If file is .dex, parse it directly
            if (f.extension.equals("dex", ignoreCase = true)) {
                parseDexFileForMethod(f, klassDescriptor, methodName, paramCount)?.let { return it }
                continue
            }
            // If file is archive (.apk/.jar/.zip), scan for classes*.dex
            if (f.extension.equals("apk", true) || f.extension.equals("jar", true) || f.extension.equals("zip", true)) {
                try {
                    ZipFile(f).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val ze = entries.nextElement()
                            val name = ze.name
                            if (!name.startsWith("classes") || !name.endsWith(".dex")) continue
                            zip.getInputStream(ze).use { ins ->
                                val bis = if (ins.markSupported()) ins else BufferedInputStream(ins)
                                try {
                                    parseDexStreamForMethod(bis, klassDescriptor, methodName, paramCount)?.let { return it }
                                } catch (t: Throwable) {
                                    Log.w(TAG, "Failed to parse dex entry $name in ${f.absolutePath}: ${t.message}", t)
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to inspect archive ${f.absolutePath}: ${t.message}", t)
                }
            }
        }
        return null
    }

    private fun parseDexFileForMethod(file: File, klassDescriptor: String, methodName: String, paramCount: Int): Array<String>? {
        try {
            FileInputStream(file).use { fis ->
                val bis = if (fis.markSupported()) fis else BufferedInputStream(fis)
                return parseDexStreamForMethod(bis, klassDescriptor, methodName, paramCount)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse dex file ${file.absolutePath}: ${t.message}", t)
            return null
        }
    }

    private fun parseDexStreamForMethod(input: InputStream, klassDescriptor: String, methodName: String, paramCount: Int): Array<String>? {
        var methodSignatureFound = false
        try {
            val dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input)
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

        // If method signature was found but parameter names are missing/incomplete, generate new names
        if (methodSignatureFound) {
            return Array(paramCount) { idx -> "param$idx" }
        }

        return null
    }
}
