@file:Suppress("unused")

package me.timschneeberger.reflectionexplorer

import android.content.Context
import android.content.Intent
import java.util.Collections

/**
 * Represents an instance to be inspected.
 */
data class Instance(
    /** The instance to inspect. */
    val instance: Any,
    /** Optional name to show in the list. If null, the class name will be used. */
    val name: String? = null,
    /** Optional group to categorize instances. */
    val group: Group? = null
)

/**
 * Represents a group/category for instances.
 */
data class Group(
    /** The display name of the group. */
    val name: String,
    /** An optional subtitle for the group. */
    val subtitle: String? = null
)

/**
 * Singleton entry point for interacting with this library.
 */
object ReflectionExplorer {
    /**
     * A list of instances to available in the main menu.
     */
    val instances: MutableList<Instance> = Collections.synchronizedList(mutableListOf())

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