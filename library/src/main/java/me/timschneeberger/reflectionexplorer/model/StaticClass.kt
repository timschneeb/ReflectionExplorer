package me.timschneeberger.reflectionexplorer.model

/**
 * Wrapper that represents a request to inspect a class's static members.
 */
class StaticClass(val target: Class<*>) {
    override fun toString(): String = target.name
}