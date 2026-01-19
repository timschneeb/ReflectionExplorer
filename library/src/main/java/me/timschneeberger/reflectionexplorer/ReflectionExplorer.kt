package me.timschneeberger.reflectionexplorer

import android.content.Context
import android.content.Intent
import java.util.Collections

/**
 * Singleton entry point for interacting with this library.
 */
object ReflectionExplorer {
    /**
     * A list of instances to available in the main menu.
     */
    val instances: MutableList<Any> = Collections.synchronizedList(mutableListOf())

    /**
     * Launches the main explorer activity with the instance selection.
     */
    fun launchMainActivity(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
        )
    }

    /**
     * Directly inspect the given instance, skipping the instance selection.
     */
    fun launchExplorerFor(context: Context, instance: Any) {
        MainActivity.pendingInspection = instance
        launchMainActivity(context)
    }
}