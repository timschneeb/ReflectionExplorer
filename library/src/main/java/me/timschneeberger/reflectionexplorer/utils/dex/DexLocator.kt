package me.timschneeberger.reflectionexplorer.utils.dex

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import dalvik.system.BaseDexClassLoader
import me.timschneeberger.reflectionexplorer.utils.ClassLoaderLocator
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

object DexLocator {
    const val TAG = "DexLocator"

    val additionalDexSearchPaths = mutableListOf<String>()

    /**
     * Returns all loaded DEX/APK/JAR paths for this process, or null if none could be located.
     */
    fun findLoadedPaths(context: Context): Array<String>? = try {
        locateDexFromContext(context)
    } catch (t: Throwable) {
        Log.w(TAG, "locateDexFromContext failed: ${t.message}", t)

        try {
            locateDexFromMemoryMap()
        } catch (t: Throwable) {
            Log.w(TAG, "locateDexFromMemoryMap failed: ${t.message}", t)
            null
        }
    }?.let {
        (it + additionalDexSearchPaths).distinct()
    }?.filter {
        // Skip resource-only APKs
        !it.contains("/overlay/") &&
                !it.startsWith("/data/resource-cache/") &&
                it != "/system/framework/framework-res.apk"
    }?.toTypedArray()

    fun openPackage(path: String): Sequence<DexBackedDexFile> = sequence {
        val f = File(path)
        // If file is .dex, parse it directly
        if (f.extension.equals("dex", ignoreCase = true)) {
            FileInputStream(f).use { fis ->
                val input = BufferedInputStream(fis)
                yield(DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input))
            }
        }
        // If file is archive (.apk/.jar/.zip), scan for classes*.dex
        else if (f.extension.equals("apk", true) || f.extension.equals("jar", true) || f.extension.equals("zip", true)) {
            val zip = try {
                ZipFile(f)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to inspect archive ${f.absolutePath}: ${t.message}", t)
                null
            }

            zip?.use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val ze = entries.nextElement()
                    val name = ze.name
                    if (!name.startsWith("classes") || !name.endsWith(".dex")) continue
                    zip.getInputStream(ze).use { fis ->
                        val input = BufferedInputStream(fis)
                        yield(DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input))
                    }
                }
            }
        }
        else {
            throw IllegalArgumentException("Unsupported file type for path: $path")
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun locateDexFromContext(context: Context): Array<String> =
        ClassLoaderLocator.locateFromContext(context)
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
                    element!!.javaClass.getDeclaredMethod("getDexPath").run {
                        isAccessible = true
                        invoke(element) as String
                    }
                }
            }
            .toTypedArray()
            .also {
                if (it.isEmpty()) {
                    // Throw if no paths found to indicate fallback is needed
                    throw IllegalStateException("No DEX paths located from context")
                }
            }

    /**
     * Locate APK/DEX paths for this process by reading /proc/self/maps.
     */
    private fun locateDexFromMemoryMap(): Array<String> {
        val result = mutableListOf<String>()
        try {
            val mapsFile = File("/proc/self/maps")
            if (!mapsFile.exists()) return emptyArray()
            val pathRegex = Regex("""(/[^"\s]+?\.(?:apk|dex|jar|odex))""")
            mapsFile.useLines { lines ->
                lines.forEach { line ->
                    pathRegex.find(line)?.groups?.get(1)?.value?.let(result::add)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read /proc/self/maps: ${t.message}", t)
        }
        return result.distinct().toTypedArray()
    }
}