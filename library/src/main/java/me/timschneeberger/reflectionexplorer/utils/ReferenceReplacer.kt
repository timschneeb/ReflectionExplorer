package me.timschneeberger.reflectionexplorer.utils

import java.lang.reflect.Array


/**
 * Replace references to [oldInstance] inside [root].
 * Returns Pair(changed, replacementRoot) where:
 * - changed == true if any replacement occurred (in-place or by creating a replacement),
 * - replacementRoot != null if the caller should replace its reference to [root] with that value (used for immutable List/Map parents).
 * In-place mutations are applied where possible (arrays, mutable lists/maps, object fields). For immutable lists/maps a new copy is created and returned as replacementRoot.
 */
@Suppress("UNCHECKED_CAST")
fun replaceReferences(root: Any, oldInstance: Any, newInstance: Any): Pair<Boolean, Any?> {
    try {
        // Top-level quick paths: arrays, lists, maps
        when {
            root::class.java.isArray -> {
                val changed = replaceInArrayInPlace(root, oldInstance, newInstance)
                return Pair(changed, null)
            }
            root is MutableList<*> -> {
                val changed = replaceInMutableListInPlace(root as MutableList<Any?>, oldInstance, newInstance)
                return Pair(changed, null)
            }
            root is List<*> -> replaceInListCopy(root, oldInstance, newInstance)
            root is MutableMap<*, *> -> {
                val mm = root as MutableMap<Any?, Any?>
                val changed = replaceInMutableMapInPlace(mm, oldInstance, newInstance)
                return Pair(changed, null)
            }
            root is Map<*, *> -> replaceInMapCopy(root as Map<Any?, Any?>, oldInstance, newInstance)
        }

        // Fallback: scan object fields and replace inside fields/containers
        var anyChanged = false
        var cur: Class<*>? = root::class.java
        while (cur != null && cur != Any::class.java) {
            for (f in cur.declaredFields) {
                try {
                    f.isAccessible = true
                    val v = f.get(root)
                    when {
                        v === oldInstance -> {
                            root.setField(f, newInstance)
                            anyChanged = true
                        }
                        v != null && v.javaClass.isArray -> {
                            if (replaceInArrayInPlace(v, oldInstance, newInstance)) anyChanged = true
                        }
                        v is MutableList<*> -> {
                            val ml = v as MutableList<Any?>
                            if (replaceInMutableListInPlace(ml, oldInstance, newInstance)) anyChanged = true
                        }
                        v is List<*> -> {
                            val (changed, replacement) = replaceInListCopy(v, oldInstance, newInstance)
                            if (changed && replacement != null) {
                                root.setField(f, replacement)
                                anyChanged = true
                            }
                        }
                        v is MutableMap<*, *> -> {
                            val mm = v as MutableMap<Any?, Any?>
                            if (replaceInMutableMapInPlace(mm, oldInstance, newInstance)) anyChanged = true
                        }
                        v is Map<*, *> -> {
                            val (changed, replacement) = replaceInMapCopy(v as Map<Any?, Any?>, oldInstance, newInstance)
                            if (changed && replacement != null) {
                                root.setField(f, replacement)
                                anyChanged = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    // ignore problematic field access
                    e.printStackTrace()
                }
            }
            cur = cur.superclass
        }
        return Pair(anyChanged, null)
    } catch (e: Exception) {
        e.printStackTrace()
        return Pair(false, null)
    }
}


private fun replaceInArrayInPlace(arr: Any, oldInstance: Any, newInstance: Any): Boolean {
    return try {
        val len = Array.getLength(arr)
        var changed = false
        for (i in 0 until len) {
            val v = Array.get(arr, i)
            if (v === oldInstance) {
                try { Array.set(arr, i, newInstance) } catch (_: Exception) { /* ignore */ }
                changed = true
            }
        }
        changed
    } catch (_: Exception) {
        false
    }
}

private fun replaceInMutableListInPlace(lst: MutableList<Any?>, oldInstance: Any, newInstance: Any): Boolean {
    var changed = false
    for (i in 0 until lst.size) {
        if (lst[i] === oldInstance) {
            try { lst[i] = newInstance } catch (_: Exception) { /* ignore */ }
            changed = true
        }
    }
    return changed
}

private fun replaceInListCopy(list: List<Any?>, oldInstance: Any, newInstance: Any): Pair<Boolean, List<Any?>?> {
    val copy = ArrayList(list)
    var changed = false
    for (i in 0 until copy.size) {
        if (copy[i] === oldInstance) {
            copy[i] = newInstance
            changed = true
        }
    }
    return if (changed) Pair(true, copy) else Pair(false, null)
}

private fun replaceInMutableMapInPlace(m: MutableMap<Any?, Any?>, oldInstance: Any, newInstance: Any): Boolean {
    var changed = false
    val keys = m.keys.toList()
    for (k in keys) {
        if (m[k] === oldInstance) {
            try { m[k] = newInstance } catch (_: Exception) { /* ignore */ }
            changed = true
        }
    }
    return changed
}

private fun replaceInMapCopy(map: Map<Any?, Any?>, oldInstance: Any, newInstance: Any): Pair<Boolean, Map<Any?, Any?>?> {
    val copy = LinkedHashMap(map)
    var changed = false
    val keys = copy.keys.toList()
    for (k in keys) {
        if (copy[k] === oldInstance) {
            copy[k] = newInstance
            changed = true
        }
    }
    return if (changed) Pair(true, copy) else Pair(false, null)
}
