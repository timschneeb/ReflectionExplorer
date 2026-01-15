package me.timschneeberger.reflectionexplorer

import java.lang.reflect.Field
import java.lang.reflect.Method

sealed class MemberInfo(val name: String)
class FieldInfo(name: String, val field: Field) : MemberInfo(name)
class MethodInfo(name: String, val method: Method) : MemberInfo(name)
// ElementInfo represents a collection/array element by index
class ElementInfo(name: String, val index: Int, val value: Any?) : MemberInfo(name)
// MapEntryInfo represents a map entry by key
class MapEntryInfo(name: String, val key: String, val value: Any?) : MemberInfo(name)
// Header that groups members declared on a particular class
class ClassHeaderInfo(val cls: Class<*>) : MemberInfo(cls.simpleName)

object ReflectionInspector {
    fun listMembers(instance: Any): List<MemberInfo> {
        val cls = instance::class.java
        val members = mutableListOf<MemberInfo>()

        // If the instance itself is a Collection/Array/Map, expose elements as entries first
        if (instance is Collection<*>) {
            members.addAll(instance.mapIndexed { idx, v -> ElementInfo("[$idx]", idx, v) })
        } else if (cls.isArray) {
            val len = java.lang.reflect.Array.getLength(instance)
            for (i in 0 until len) {
                val v = java.lang.reflect.Array.get(instance, i)
                members.add(ElementInfo("[$i]", i, v))
            }
        } else if (instance is Map<*, *>) {
            instance.entries.forEach { e ->
                members.add(MapEntryInfo("{${e.key}}", e.key.toString(), e.value))
            }
        }

        // Group declared fields/methods by their declaring class along the superclass chain
        var current: Class<*>? = cls
        while (current != null) {
            // collect declared members for this declaring class
            val declaredFields = current.declaredFields.map { f -> f.apply { isAccessible = true }; FieldInfo(f.name, f) }
            val declaredMethods = current.declaredMethods.map { m -> m.apply { isAccessible = true }; MethodInfo(m.name, m) }
            if (declaredFields.isNotEmpty() || declaredMethods.isNotEmpty()) {
                members.add(ClassHeaderInfo(current))
                // add fields then methods (sorted by name)
                val combined = (declaredFields + declaredMethods).sortedBy { it.name }
                members.addAll(combined)
            }
            current = current.superclass
            if (current == Any::class.java) {
                // include java.lang.Object members optionally? skip to avoid noise
                break
            }
        }

        return members
    }

    fun getField(instance: Any, field: Field): Any? {
        field.isAccessible = true
        return field.get(instance)
    }

    fun setField(instance: Any, field: Field, value: Any?) {
        field.isAccessible = true
        field.set(instance, value)
    }

    fun invokeMethod(instance: Any, method: Method, args: Array<Any?> = emptyArray()): Any? {
        method.isAccessible = true
        return method.invoke(instance, *args)
    }
}
