package me.timschneeberger.reflectionexplorer.model

/**
 * Wrapper that represents a request to inspect a class's static members.
 * If [target] is null this represents a placeholder entry (e.g. "Inspect class...")
 * which can be used to prompt the user for a class name.
 */
class StaticClass(
    val target: Class<*>?,
    val label: String
) {
    override fun toString(): String = target?.name ?: label
}