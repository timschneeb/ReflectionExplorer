package me.timschneeberger.reflectionexplorer.utils

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

// Contain
interface CollectionMember : GettableMember {
    fun applyDelete(rootInstance: Any): Any?
    fun applyEdit(rootInstance: Any, newValue: Any?): Any?
}

sealed class MemberInfo(val name: String)

class FieldInfo(name: String, val field: Field) : MemberInfo(name), SettableMember {
    override fun setValue(rootInstance: Any, newValue: Any?) =
        ReflectionInspector.setFieldValue(rootInstance, field, newValue)

    override fun getValue(rootInstance: Any): Any? =
        ReflectionInspector.getField(rootInstance, field)

    override fun getType(rootInstance: Any): Class<*> = field.type
}


class MethodInfo(name: String, val method: Method) : MemberInfo(name) {
    operator fun invoke(instance: Any, args: Array<Any?> = emptyArray()): Any? {
        return ReflectionInspector.invokeMethod(instance, method, args)
    }
}

// ElementInfo represents a collection/array element by index
class ElementInfo(name: String, val index: Int, val value: Any?) : MemberInfo(name), CollectionMember {
    override fun applyDelete(rootInstance: Any): Any? =
        ReflectionInspector.deleteElementInternal(rootInstance, index)

    override fun applyEdit(rootInstance: Any, newValue: Any?): Any? =
        ReflectionInspector.editElementInternal(rootInstance, index, newValue)

    override fun getValue(rootInstance: Any): Any? =
        ReflectionInspector.getElementCurrentValue(rootInstance, index)

    override fun getType(rootInstance: Any): Class<*> {
        val currentValue = getValue(rootInstance)
        if (currentValue != null) return currentValue.javaClass

        // if null try to infer from array component type
        return rootInstance::class.java.componentType ?: Any::class.java
    }
}

// MapEntryInfo represents a map entry by key
class MapEntryInfo(name: String, val key: Any?, val value: Any?) : MemberInfo(name), CollectionMember {
    /**
     * Apply deletion of this map entry inside rootInstance. Returns new rootInstance (same or copy) or null if no-op.
     */
    override fun applyDelete(rootInstance: Any): Any? =
        ReflectionInspector.deleteMapInternal(rootInstance, key)

    override fun applyEdit(rootInstance: Any, newValue: Any?): Any? =
        ReflectionInspector.editMapInternal(rootInstance, key, newValue)

    override fun getValue(rootInstance: Any): Any? =
        ReflectionInspector.getMapEntryCurrentValue(rootInstance, key)

    override fun getType(rootInstance: Any): Class<*> =
        getValue(rootInstance)?.javaClass ?: Any::class.java
}

// Header that groups members declared on a particular class
class ClassHeaderInfo(val cls: Class<*>) : MemberInfo(cls.simpleName)

object ReflectionInspector {
    // Internal helpers to consolidate mutation logic for elements and maps
    @Suppress("UNCHECKED_CAST", "USELESS_CAST")
    internal fun deleteElementInternal(rootInstance: Any, index: Int): Any? {
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
    internal fun editElementInternal(rootInstance: Any, index: Int, newValue: Any?): Any? {
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
    internal fun deleteMapInternal(rootInstance: Any, key: Any?): Any? {
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
    internal fun editMapInternal(rootInstance: Any, key: Any?, newValue: Any?): Any? {
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

    // Reflection helper to set a field value, attempting to clear the final modifier if necessary (best-effort)
    fun setFieldValue(target: Any, field: Field, value: Any?) {
        try {
            field.isAccessible = true
            val modsField = Field::class.java.getDeclaredField("modifiers")
            modsField.isAccessible = true
            val origMods = modsField.getInt(field)
            // clear final bit if present
            if ((origMods and java.lang.reflect.Modifier.FINAL) != 0) {
                modsField.setInt(field, origMods and java.lang.reflect.Modifier.FINAL.inv())
                try { field.set(target, value) } catch (_: Exception) {}
                // restore
                modsField.setInt(field, origMods)
            } else {
                field.set(target, value)
            }
        } catch (_: Exception) {
            try { field.set(target, value) } catch (_: Exception) { /* ignore */ }
        }
    }

    internal fun getElementCurrentValue(rootInstance: Any, index: Int): Any? {
        return when (rootInstance) {
            is List<*> -> rootInstance.getOrNull(index)
            else -> if (rootInstance::class.java.isArray) {
                try { JArray.get(rootInstance, index) } catch (_: Exception) { null }
            } else null
        }
    }

    internal fun getMapEntryCurrentValue(rootInstance: Any, key: Any?): Any? {
        return when (rootInstance) {
            is Map<*, *> -> rootInstance[key]
            else -> null
        }
    }

    // Centralized array helpers
    fun getArrayLength(array: Any): Int = try { JArray.getLength(array) } catch (_: Exception) { 0 }
    fun getArrayElement(array: Any, index: Int): Any? = try { JArray.get(array, index) } catch (_: Exception) { null }
    fun setArrayElement(array: Any, index: Int, value: Any?) { try { JArray.set(array, index, value) } catch (_: Exception) { } }
    fun newArrayInstance(componentType: Class<*>, length: Int): Any = JArray.newInstance(componentType, length)

    fun listMembers(instance: Any): List<MemberInfo> {
        val cls = instance::class.java
        val members = mutableListOf<MemberInfo>()

        // If the instance itself is a Collection/Array/Map, expose elements as entries first
        if (instance is Collection<*>) {
            members.addAll(instance.mapIndexed { idx, v -> ElementInfo("[$idx]", idx, v) })
        } else if (cls.isArray) {
            val len = JArray.getLength(instance)
            for (i in 0 until len) {
                val v = JArray.get(instance, i)
                members.add(ElementInfo("[$i]", i, v))
            }
        } else if (instance is Map<*, *>) {
            instance.entries.forEach { e ->
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
     * Replace references to [oldInstance] inside [root].
     * Returns Pair(changed, replacementRoot) where:
     * - changed == true if any replacement occurred (in-place or by creating a replacement),
     * - replacementRoot != null if the caller should replace its reference to [root] with that value (used for immutable List/Map parents).
     * In-place mutations are applied where possible (arrays, mutable lists/maps, object fields). For immutable lists/maps a new copy is created and returned as replacementRoot.
     */
    fun replaceReferences(root: Any, oldInstance: Any, newInstance: Any): Pair<Boolean, Any?> {
        try {
            when (root) {
                // Top-level array: mutate elements in-place
                else -> {
                    // handle arrays
                    if (root::class.java.isArray) {
                        var changed = false
                        val len = getArrayLength(root)
                        for (i in 0 until len) {
                            if (getArrayElement(root, i) === oldInstance) {
                                setArrayElement(root, i, newInstance)
                                changed = true
                            }
                        }
                        return if (changed) Pair(true, null) else Pair(false, null)
                    }

                    // MutableList: mutate in-place
                    if (root is MutableList<*>) {
                        @Suppress("UNCHECKED_CAST")
                        val lst = root as MutableList<Any?>
                        var changed = false
                        for (i in 0 until lst.size) if (lst[i] === oldInstance) { lst[i] = newInstance; changed = true }
                        return Pair(changed, null)
                    }

                    // Immutable List -> create a copy and return it
                    if (root is List<*>) {
                        @Suppress("UNCHECKED_CAST")
                        val copy = ArrayList(root as List<Any?>)
                        var changed = false
                        for (i in 0 until copy.size) if (copy[i] === oldInstance) { copy[i] = newInstance; changed = true }
                        return if (changed) Pair(true, copy) else Pair(false, null)
                    }

                    // MutableMap: mutate in-place for matching values
                    if (root is MutableMap<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        val mm = root as MutableMap<Any?, Any?>
                        var changed = false
                        val keys = mm.keys.toList()
                        for (k in keys) if (mm[k] === oldInstance) { mm[k] = newInstance; changed = true }
                        return Pair(changed, null)
                    }

                    // Immutable Map -> copy and return replacement if changed
                    if (root is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        val copy = LinkedHashMap(root as Map<Any?, Any?>)
                        var changed = false
                        val keys = copy.keys.toList()
                        for (k in keys) if (copy[k] === oldInstance) { copy[k] = newInstance; changed = true }
                        return if (changed) Pair(true, copy) else Pair(false, null)
                    }

                    // Fallback: inspect object fields and mutate fields or nested containers in-place; return null replacement (object identity unchanged)
                    var anyChanged = false
                    var cur: Class<*>? = root::class.java
                    while (cur != null && cur != Any::class.java) {
                        for (f in cur.declaredFields) {
                            try {
                                f.isAccessible = true
                                val v = f.get(root)
                                when {
                                    v === oldInstance -> {
                                        setFieldValue(root, f, newInstance)
                                        anyChanged = true
                                    }
                                    v != null && v.javaClass.isArray -> {
                                        val alen = getArrayLength(v)
                                        for (ai in 0 until alen) {
                                            if (getArrayElement(v, ai) === oldInstance) {
                                                setArrayElement(v, ai, newInstance)
                                                anyChanged = true
                                            }
                                        }
                                    }
                                    v is MutableList<*> -> {
                                        @Suppress("UNCHECKED_CAST")
                                        val ml = v as MutableList<Any?>
                                        for (i in 0 until ml.size) if (ml[i] === oldInstance) { ml[i] = newInstance; anyChanged = true }
                                    }
                                    v is List<*> -> {
                                        @Suppress("UNCHECKED_CAST")
                                        val copyList = ArrayList(v as List<Any?>)
                                        var changedList = false
                                        for (i in 0 until copyList.size) if (copyList[i] === oldInstance) { copyList[i] = newInstance; changedList = true }
                                        if (changedList) { setFieldValue(root, f, copyList); anyChanged = true }
                                    }
                                    v is MutableMap<*, *> -> {
                                        @Suppress("UNCHECKED_CAST")
                                        val mm = v as MutableMap<Any?, Any?>
                                        val keys = mm.keys.toList()
                                        for (k in keys) if (mm[k] === oldInstance) { mm[k] = newInstance; anyChanged = true }
                                    }
                                    v is Map<*, *> -> {
                                        @Suppress("UNCHECKED_CAST")
                                        val copyMap = LinkedHashMap(v as Map<Any?, Any?>)
                                        var changedMap = false
                                        val keys = copyMap.keys.toList()
                                        for (k in keys) if (copyMap[k] === oldInstance) { copyMap[k] = newInstance; changedMap = true }
                                        if (changedMap) { setFieldValue(root, f, copyMap); anyChanged = true }
                                    }
                                }
                            } catch (_: Exception) { /* ignore field access exceptions */ }
                        }
                        cur = cur.superclass
                    }
                    return Pair(anyChanged, null)
                }
            }
        } catch (_: Exception) {
            return Pair(false, null)
        }
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
