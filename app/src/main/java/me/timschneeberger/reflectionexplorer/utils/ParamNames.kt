package me.timschneeberger.reflectionexplorer.utils

import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Small helper to obtain parameter names from DEX files using dexlib2, with caching.
 */
object ParamNames {
    private val cache: ConcurrentHashMap<Method, Array<String>> = ConcurrentHashMap()

    /**
     * Returns parameter names for [method] or null if not found.
     */
    fun lookup(method: Method): Array<String>? {
        val arr = cache.computeIfAbsent(method) {
            try {
                val dexRes = lookupFromDex(method)
                if (dexRes != null) return@computeIfAbsent dexRes
            } catch (e: Throwable) { e.printStackTrace() }

            emptyArray<String>()
        }
        return if (arr.isEmpty()) null else arr
    }

    /**
     * Locate APK/DEX paths for this process by reading /proc/self/maps.
     */
    private fun locateApkOrDexPaths(): List<String> {
        val result = mutableListOf<String>()

        try {
            val mapsFile = java.io.File("/proc/self/maps")
            if (mapsFile.exists()) {
                mapsFile.useLines { lines ->
                    val pathRegex = Regex("""(/[^"\s]+?\.(?:apk|dex|jar))""")
                    lines.forEach { line ->
                        val m = pathRegex.find(line)
                        m?.groups?.get(1)?.value?.let { result.add(it) }
                    }
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return result.distinct()
    }

    /**
     * Read DEX/APK files and extract parameter names using dexlib2.
     */
    private fun lookupFromDex(method: Method): Array<String>? {
        val cls = method.declaringClass
        val paths = locateApkOrDexPaths()
        if (paths.isEmpty()) return null

        val klassDescriptor = "L${cls.name.replace('.', '/')};"

        for (path in paths) {
            try {
                val file = java.io.File(path)
                // If path is a jar (or apk) with embedded dex entries, parse those dex entries.
                if (file.isFile && (file.name.endsWith(".jar", true) || file.name.endsWith(".apk", true))) {
                    try {
                        java.util.zip.ZipFile(file).use { zip ->
                            val entries = zip.entries()
                            while (entries.hasMoreElements()) {
                                val ze = entries.nextElement()
                                if (!ze.name.startsWith("classes") || !ze.name.endsWith(".dex")) continue
                                zip.getInputStream(ze).use { zis ->
                                    val s = if (zis.markSupported()) zis else java.io.BufferedInputStream(zis)
                                    try {
                                        val dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), s)
                                        for (dexClass in dex.classes) {
                                            if (dexClass.type != klassDescriptor) continue
                                            for (dm in dexClass.methods) {
                                                if (dm.name != method.name) continue
                                                try {
                                                    val dexParamNames = dm.parameters.mapNotNull { it.name }
                                                    if (dexParamNames.size == method.parameterCount && dexParamNames.all { it.isNotBlank() }) {
                                                        return dexParamNames.toTypedArray()
                                                    }
                                                } catch (e: Throwable) { e.printStackTrace() }
                                            }
                                        }
                                    } catch (e: Throwable) { e.printStackTrace() }
                                }
                            }
                        }
                    } catch (_: Throwable) { }
                } else if (file.isFile && file.name.endsWith(".dex", true)) {
                    java.io.FileInputStream(file).use { fis ->
                        val s = if (fis.markSupported()) fis else java.io.BufferedInputStream(fis)
                        try {
                            val dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), s)
                            for (dexClass in dex.classes) {
                                if (dexClass.type != klassDescriptor) continue
                                for (dm in dexClass.methods) {
                                    if (dm.name != method.name) continue
                                    try {
                                        val dexParamNames = dm.parameters.mapNotNull { it.name }
                                        if (dexParamNames.size == method.parameterCount && dexParamNames.all { it.isNotBlank() }) {
                                            return dexParamNames.toTypedArray()
                                        }
                                    } catch (e: Throwable) { e.printStackTrace() }
                                }
                            }
                        } catch (e: Throwable) { e.printStackTrace() }
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        return null
    }
}
