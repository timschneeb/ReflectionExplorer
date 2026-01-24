package me.timschneeberger.reflectionexplorer.utils

import android.annotation.SuppressLint
import android.app.Application
import android.app.ContextImpl
import android.app.LoadedApk
import android.content.Context
import android.content.ContextWrapper
import android.util.ArrayMap
import android.util.Log
import dalvik.system.BaseDexClassLoader
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Small helper to obtain parameter names from DEX files using dexlib2, with caching.
 */
object ParamNames {
    private const val TAG = "ParamNames"

    val additionalDexSearchPaths = mutableListOf<String>()

    private val cache: ConcurrentHashMap<Method, Array<String>> = ConcurrentHashMap()

    /**
     * Returns parameter names for [method] or null if not found.
     */
    fun lookup(context: Context, method: Method): Array<String>? {
        val arr = cache.computeIfAbsent(method) {
            val dexPaths = try {
                locateDexFromContext(context)
            }
            catch (t: Throwable) {
                Log.w(TAG, "locateDexFromContext failed: ${t.message}", t)

                try {
                    locateDexFromMemoryMap()
                } catch (t: Throwable) {
                    Log.w(TAG, "locateDexFromMemoryMap failed: ${t.message}", t)
                    null
                }
            }

            Log.e(TAG, "Using DEX search paths:\n${dexPaths?.joinToString("\n")}")

            try {
                lookupFromDex(dexPaths, method) ?: emptyArray()
            } catch (t: Throwable) {
                Log.w(TAG, "lookup failed for ${method.declaringClass.name}.${method.name}: ${t.message}", t)
                emptyArray()
            }
        }
        return if (arr.isEmpty()) null else arr
    }


    @SuppressLint("DiscouragedPrivateApi")
    fun locateDexFromContext(context: Context): Array<String> {
        // Contexts may be wrapped, unwrap them to get the ContextImpl instance
        if(context is ContextWrapper) {
            val baseContext = context.baseContext
            if(baseContext != null && baseContext != context) {
                return locateDexFromContext(baseContext)
            }
            throw IllegalStateException("Unable to locate base context from ContextWrapper")
        }

        if(context !is ContextImpl) {
            throw IllegalArgumentException("Context is not an instance of ContextImpl: ${context::class.java.name}")
        }

        // Access mPackageInfo field (LoadedApk)
        val loadedApk = ContextImpl::class.java.getDeclaredField("mPackageInfo").run {
            isAccessible = true
            get(context) as LoadedApk
        }

        // Access sApplications field (ArrayMap<String, Application>)
        val apps = LoadedApk::class.java.getDeclaredField("sApplications").run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(loadedApk) as ArrayMap<String, Application>
        }

        return apps.values
            .map { it.applicationContext.classLoader }
            .mapNotNull {
                // Get DexPathList
                if (it is BaseDexClassLoader) {
                    BaseDexClassLoader::class.java.getDeclaredField("pathList").run {
                        isAccessible = true
                        get(it) // as DexPathList
                    }
                } else null
            }
            .flatMap {
                // Collect all DEX paths from dexElements
                it::class.java.getDeclaredField("dexElements").run {
                    isAccessible = true
                    get(it) as Array<*>
                }.map { element ->
                    it.javaClass.getDeclaredMethod("getDexPath").run {
                        isAccessible = true
                        invoke(element) as String
                    }
                }
            }
            .let { it + additionalDexSearchPaths }
            .toTypedArray()
            .also {
                if (it.isEmpty()) {
                    // Throw if no paths found to indicate fallback is needed
                    throw IllegalStateException("No DEX paths located from context")
                }
            }
    }

    /**
     * Locate APK/DEX paths for this process by reading /proc/self/maps.
     */
    private fun locateDexFromMemoryMap(): Array<String> {
        val result = additionalDexSearchPaths
        try {
            val mapsFile = File("/proc/self/maps")
            if (!mapsFile.exists()) return emptyArray()
            val pathRegex = Regex("""(/[^"\s]+?\.(?:apk|dex|jar|odex))""")
            mapsFile.useLines { lines ->
                lines.forEach { line ->
                    pathRegex.find(line)?.groups?.get(1)?.value?.let {
                        // Skip resource-only APKs
                        if (!it.contains("/overlay/") &&
                            !it.startsWith("/data/resource-cache/") &&
                            it != "/system/framework/framework-res.apk" &&
                            // Skip apex packages for now
                            !it.startsWith("/apex/"))
                            result.add(it)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read /proc/self/maps: ${t.message}", t)
        }
        return result.distinct().toTypedArray()
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


        paths.joinToString("\n").let {
            Log.e(TAG, "Scanning DEX/APK paths:\n$it")
        }

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

    private fun parseDexStreamForMethod(input: java.io.InputStream, klassDescriptor: String, methodName: String, paramCount: Int): Array<String>? {
        try {
            val dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input)
            for (dexClass in dex.classes) {
                if (dexClass.type != klassDescriptor) continue
                for (dm in dexClass.methods) {
                    if (dm.name != methodName) continue
                    try {
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
        return null
    }
}
