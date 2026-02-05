package me.timschneeberger.reflectionexplorer.utils

import android.app.Application
import android.app.LoadedApk
import android.content.Context
import android.content.ContextWrapper
import android.util.ArrayMap
import kotlin.jvm.java

object ClassLoaderLocator {
    fun locateFromContext(context: Context): Array<ClassLoader> {
        // Contexts may be wrapped, unwrap them to get the ContextImpl instance
        if(context is ContextWrapper) {
            val baseContext = context.baseContext
            if(baseContext != null && baseContext != context) {
                return locateFromContext(baseContext)
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
            .toTypedArray()
    }


    fun findClassInProcess(context: Context, className: String): Class<*>? {
        val classLoaders = locateFromContext(context)
        for (cl in classLoaders) {
            try {
                return Class.forName(className, true, cl)
            } catch (_: ClassNotFoundException) {
                // Try next
            }
        }
        return null
    }
}