package me.timschneeberger.reflectionexplorer.utils

import android.content.Context
import me.timschneeberger.reflectionexplorer.R
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Array as JArray

interface SettableMember : GettableMember {
    fun setValue(rootInstance: Any, newValue: Any?)
}

interface GettableMember {
    fun getValue(rootInstance: Any): Any?
    fun getType(rootInstance: Any): Class<*>
}

interface CollectionMember : GettableMember {
    fun applyDelete(rootInstance: Any): Any?
    fun applyEdit(rootInstance: Any, newValue: Any?): Any?
}

sealed class MemberInfo(val name: String)

class FieldInfo(name: String, val field: Field) : MemberInfo(name), SettableMember {
    override fun setValue(rootInstance: Any, newValue: Any?) =
        rootInstance.setField(field, newValue)

    override fun getValue(rootInstance: Any): Any? =
        rootInstance.getField(field)

    override fun getType(rootInstance: Any): Class<*> = field.type
}

class MethodInfo(name: String, val method: Method) : MemberInfo(name) {
    operator fun invoke(instance: Any, args: Array<Any?> = emptyArray()): Any? =
        instance.invokeMethod(method, args)
}

// ElementInfo represents a collection/array element by index
class ElementInfo(name: String, val index: Int, val value: Any?) : MemberInfo(name), CollectionMember {
    override fun applyDelete(rootInstance: Any) = deleteElementInternal(rootInstance, index)
    override fun applyEdit(rootInstance: Any, newValue: Any?) = editElementInternal(rootInstance, index, newValue)

    override fun getValue(rootInstance: Any): Any? = when {
        rootInstance is List<*> -> rootInstance.getOrNull(index)
        rootInstance::class.java.isArray -> JArray.get(rootInstance, index)
        else -> null
    }

    override fun getType(rootInstance: Any): Class<*> {
        val currentValue = getValue(rootInstance)
        if (currentValue != null) return currentValue.javaClass

        // if null try to infer from array component type
        return rootInstance::class.java.componentType ?: Any::class.java
    }
}

// MapEntryInfo represents a map entry by key
class MapEntryInfo(name: String, val key: Any?, val value: Any?) : MemberInfo(name), CollectionMember {
    override fun applyDelete(rootInstance: Any) = deleteMapInternal(rootInstance, key)
    override fun applyEdit(rootInstance: Any, newValue: Any?) = editMapInternal(rootInstance, key, newValue)

    override fun getValue(rootInstance: Any): Any? = when (rootInstance) {
        is Map<*, *> -> rootInstance[key]
        else -> null
    }

    override fun getType(rootInstance: Any): Class<*> =
        getValue(rootInstance)?.javaClass ?: Any::class.java

    fun getTypes(rootInstance: Any): Pair<Class<*>,Class<*>> =
        Pair(key?.javaClass ?: Any::class.java, getType(rootInstance))
}

// Header that groups members declared on a particular class
class ClassHeaderInfo(val cls: Class<*>) : MemberInfo(cls.simpleName)

fun Any?.formatObject(ctx: Context, additionalTypeInfo: GettableMember?, withType: Boolean = true): String = try {
    var type = this?.javaClass?.simpleName ?: additionalTypeInfo?.getType(this ?: Any())?.simpleName ?: "Unknown"
    val value = when (this) {
        null -> "null"
        is CharSequence -> this.toString().let { s -> if (s.length > 80) "\"${s.take(80)}...\" (len=${s.length})" else "\"$s\"" }
        is Collection<*> -> run {
            val elemName = this.firstOrNull { it != null }?.javaClass?.simpleName ?: "Any"
            type = "$type<$elemName>"
            ctx.getString(R.string.collection_size, this.size)
        }
        is Map<*, *> -> run {
            val keyName = this.keys.firstOrNull { it != null }?.javaClass?.simpleName ?: "Any"
            val valName = this.values.firstOrNull { it != null }?.javaClass?.simpleName ?: "Any"
            type = "$type<$keyName, $valName>"
            ctx.getString(R.string.collection_size, this.size)
        }
        else -> {
            if (this.javaClass.isArray) {
                ctx.getString(R.string.collection_size, JArray.getLength(this))
            } else {
                this.toString().let { s -> if (s.length > 120) "${s.take(120)}... (len=${s.length})" else s }
            }
        }
    }
    if (withType) "$type: $value" else value
} catch (e: Exception) {
    e.printStackTrace()
    "<error>"
}

// Reflection helper to set a field value, attempting to clear the final modifier if necessary
fun Any.setField(field: Field, value: Any?) {
    try {
        field.isAccessible = true
        val modsField = Field::class.java.getDeclaredField("modifiers")
        modsField.isAccessible = true
        val origMods = modsField.getInt(field)
        // clear final bit if present
        if ((origMods and java.lang.reflect.Modifier.FINAL) != 0) {
            modsField.setInt(field, origMods and java.lang.reflect.Modifier.FINAL.inv())
            try { field.set(this, value) } catch (_: Exception) {}
            // restore
            modsField.setInt(field, origMods)
        } else {
            field.set(this, value)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        try { field.set(this, value) } catch (e2: Exception) {
            e2.printStackTrace()
        }
    }
}

fun Any.getField(field: Field): Any? {
    field.isAccessible = true
    return field.get(this)
}

fun Any?.invokeMethod(method: Method, args: Array<Any?> = emptyArray()): Any? {
    method.isAccessible = true
    return method.invoke(this, *args)
}

fun Any.listMembers(): List<MemberInfo> {
    val cls = this::class.java
    val members = mutableListOf<MemberInfo>()

    // If the instance itself is a Collection/Array/Map, expose elements as entries first
    if (this is Collection<*>) {
        members.addAll(this.mapIndexed { idx, v -> ElementInfo("[$idx]", idx, v) })
    } else if (cls.isArray) {
        val len = JArray.getLength(this)
        for (i in 0 until len) {
            val v = JArray.get(this, i)
            members.add(ElementInfo("[$i]", i, v))
        }
    } else if (this is Map<*, *>) {
        this.entries.forEach { e ->
            members.add(MapEntryInfo("{${e.key}}", e.key, e.value))
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

/**
 * Create a new array with one additional slot and append [element] at the end.
 * Returns the new array instance or null on failure.
 */
fun appendToArray(array: Any, element: Any?): Any {
    val cls = array::class.java
    if (!cls.isArray)
        throw IllegalArgumentException("Not an array: ${cls.name}")
    val len = JArray.getLength(array)
    val newArr = JArray.newInstance(cls.componentType!!, len + 1)
    for (i in 0 until len)
        JArray.set(newArr, i, JArray.get(array, i))
    JArray.set(newArr, len, element)
    return newArr
}

// Internal helpers to consolidate mutation logic for elements and maps
@Suppress("UNCHECKED_CAST", "USELESS_CAST")
private fun deleteElementInternal(rootInstance: Any, index: Int): Any? {
    return when (rootInstance) {
        is MutableList<*> -> {
            @Suppress("UNCHECKED_CAST")
            val lst = rootInstance as MutableList<Any?>
            if (index in 0 until lst.size) {
                try {
                    // try in-place remove
                    lst.removeAt(index)
                    rootInstance
                } catch (_: UnsupportedOperationException) {
                    // e.g., Arrays.asList returns a fixed-size list; create a copy and remove there
                    val copy = ArrayList(lst)
                    if (index in 0 until copy.size) {
                        copy.removeAt(index)
                        copy
                    } else null
                } catch (_: Exception) {
                    null
                }
            } else null
        }
        else -> {
            val cls = rootInstance::class.java
            when {
                cls.isArray -> {
                    val len = JArray.getLength(rootInstance)
                    if (index !in 0 until len) return null
                    val comp = cls.componentType ?: return null
                    val newArr = JArray.newInstance(comp, (len - 1).coerceAtLeast(0))
                    var dst = 0
                    for (i in 0 until len) {
                        if (i == index) continue
                        JArray.set(newArr, dst++, JArray.get(rootInstance, i))
                    }
                    newArr
                }
                rootInstance is List<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val copy = (rootInstance as List<Any?>).toMutableList()
                    if (index in 0 until copy.size) {
                        copy.removeAt(index)
                        copy
                    } else null
                }
                else -> null
            }
        }
    }
}

@Suppress("UNCHECKED_CAST", "USELESS_CAST")
private fun editElementInternal(rootInstance: Any, index: Int, newValue: Any?): Any? {
    return when (rootInstance) {
        is MutableList<*> -> {
            @Suppress("UNCHECKED_CAST")
            val lst = rootInstance as MutableList<Any?>
            if (index in 0 until lst.size) {
                try {
                    lst[index] = newValue
                    rootInstance
                } catch (_: UnsupportedOperationException) {
                    // fixed-size list: create a copy and replace there
                    val copy = ArrayList(lst)
                    if (index in 0 until copy.size) {
                        copy[index] = newValue
                        copy
                    } else null
                }
            } else null
        }
        else -> {
            val cls = rootInstance::class.java
            when {
                cls.isArray -> {
                    val len = try { JArray.getLength(rootInstance) } catch (_: Exception) { return null }
                    if (index !in 0 until len) return null
                    try {
                        JArray.set(rootInstance, index, newValue)
                        rootInstance
                    } catch (_: Exception) {
                        // fallback: create new array copy with replaced value
                        val comp = cls.componentType ?: return null
                        val newArr = JArray.newInstance(comp, len)
                        for (i in 0 until len) JArray.set(newArr, i, JArray.get(rootInstance, i))
                        JArray.set(newArr, index, newValue)
                        newArr
                    }
                }
                rootInstance is List<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val copy = (rootInstance as List<Any?>).toMutableList()
                    if (index in 0 until copy.size) {
                        copy[index] = newValue
                        copy
                    } else null
                }
                else -> null
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun deleteMapInternal(rootInstance: Any, key: Any?): Any? {
    return when (rootInstance) {
        is MutableMap<*, *> -> {
            val m = rootInstance as MutableMap<Any?, Any?>
            if (m.containsKey(key)) {
                m.remove(key)
                rootInstance
            } else null
        }
        is Map<*, *> -> {
            val copy = LinkedHashMap(rootInstance as Map<Any?, Any?>)
            if (copy.containsKey(key)) {
                copy.remove(key)
                copy
            } else null
        }
        else -> null
    }
}

@Suppress("UNCHECKED_CAST")
private fun editMapInternal(rootInstance: Any, key: Any?, newValue: Any?): Any? {
    return when (rootInstance) {
        is MutableMap<*, *> -> {
            val m = rootInstance as MutableMap<Any?, Any?>
            if (m.containsKey(key)) {
                m[key] = newValue
                rootInstance
            } else null
        }
        is Map<*, *> -> {
            val copy = LinkedHashMap(rootInstance as Map<Any?, Any?>)
            if (copy.containsKey(key)) {
                copy[key] = newValue
                copy
            } else null
        }
        else -> null
    }
}
