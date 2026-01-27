@file:Suppress("unused")

package me.timschneeberger.reflectionexplorer

import android.content.Context
import android.content.Intent

/**
 * Represents an instance to be inspected.
 */
open class Instance(
    /** The instance to inspect. */
    val instance: Any,
    /** Optional name to show in the list. If null, the class name will be used. */
    val name: String? = null,
    /** Optional group to categorize instances. */
    val group: Group? = null
)

class ErrorInstance(message: String, attachment: Any? = null) :
    Instance(attachment ?: Any(), message, null)

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
     * Instance provider that returns the list of instances to show.
     * It will be called when the main activity is launched or refreshed by the user.
     */
    fun interface IInstancesProvider {
        fun provide(context: Context): List<Instance>
    }

    /**
     * Optional custom launcher that host apps can provide.
     * If set, this will be used instead of Context.startActivity so callers can control how
     * activities are started (for example by using a process-aware launcher).
     */
    fun interface IActivityLauncher {
        fun launch(context: Context, intent: Intent)
    }

    /**
     * Provides a list of instances to available in the main menu.
     */
    @JvmField
    @Volatile
    var instancesProvider: IInstancesProvider? = null


    @JvmField
    @Volatile
    var activityLauncher: IActivityLauncher? = null

    /**
     * Launches the main explorer activity with the instance selection.
     */
    @JvmStatic
    fun launch(context: Context) {
        val intent = Intent(context, ReflectionActivity::class.java)
        val launcher = activityLauncher
        if (launcher != null) launcher.launch(context, intent) else context.startActivity(intent)
    }

    /**
     * Directly inspect the given instance, skipping the instance selection.
     */
    @JvmStatic
    fun launchExplorerFor(context: Context, instance: Any) {
        ReflectionActivity.pendingInspection = instance
        launch(context)
    }
}