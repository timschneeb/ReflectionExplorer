package me.timschneeberger.reflectionexplorer.utils

import android.annotation.SuppressLint
import android.app.Application
import android.app.LoadedApk
import android.content.Context
import android.content.ContextWrapper
import android.util.ArrayMap
import android.util.Log
import dalvik.system.BaseDexClassLoader
import java.io.File
import kotlin.sequences.forEach

object DexLocator {
    private const val TAG = "DexLocator"

    /**
     * Returns all loaded DEX/APK/JAR paths for this process, or null if none could be located.
     */
    fun findLoadedPaths(context: Context, additionalSearchPaths: List<String>? = null): Array<String>? = try {
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
    }?.let {
        if (additionalSearchPaths != null) {
            it + additionalSearchPaths
        } else {
            it
        }.distinct().toTypedArray()
    }


    @SuppressLint("DiscouragedPrivateApi")
    private fun locateDexFromContext(context: Context): Array<String> {
        // Contexts may be wrapped, unwrap them to get the ContextImpl instance
        if(context is ContextWrapper) {
            val baseContext = context.baseContext
            if(baseContext != null && baseContext != context) {
                return locateDexFromContext(baseContext)
            }
            throw IllegalStateException("Unable to locate base context from ContextWrapper")
        }

        if(context::class.java.name != "android.app.ContextImpl") {
            throw IllegalArgumentException("Context is not an instance of ContextImpl: ${context::class.java.name}")
        }

        // Access mPackageInfo field (LoadedApk)
        val loadedApk = context::class.java.getDeclaredField("mPackageInfo").run {
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