package me.timschneeberger.reflectionexplorer.utils.reflection

import android.util.Log
import java.lang.reflect.Array as JArray
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.Collection
import java.util.List
import java.util.Map

object ReflectionParser {
    fun canParseType(type: Class<*>): Boolean = when (type) {
        String::class.java,
        Int::class.javaObjectType, Int::class.javaPrimitiveType!!,
        Long::class.javaObjectType, Long::class.javaPrimitiveType!!,
        Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType!!,
        Float::class.javaObjectType, Float::class.javaPrimitiveType!!,
        Double::class.javaObjectType, Double::class.javaPrimitiveType!! -> true
        else -> type.isEnum
    }

    private fun parsePrimitiveType(text: String, type: Class<*>): Any? {
        return when (type) {
            String::class.java -> text
            Int::class.javaObjectType, Int::class.javaPrimitiveType!! -> text.toIntOrNull()
            Long::class.javaObjectType, Long::class.javaPrimitiveType!! -> text.toLongOrNull()
            Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType!! -> when (text.lowercase()) { "true" -> true; else -> false }
            Double::class.javaObjectType, Double::class.javaPrimitiveType!! -> text.toDoubleOrNull()
            Float::class.javaObjectType, Float::class.javaPrimitiveType!! -> text.toFloatOrNull()
            else -> null
        }
    }

    fun parseValue(text: String, type: Class<*>, genericType: Type? = null, elementClass: Class<*>? = null): Any? {
        // try primitive parse first
        try {
            parsePrimitiveType(text, type)?.let { return it }

            fun parseArrayValue(): Any? {
                val base = type.componentType ?: elementClass ?: return null
                val content = text.trim()
                if (!content.startsWith("[") || !content.endsWith("]")) return null
                val inner = content.substring(1, content.length - 1)
                val parts = if (inner.isBlank()) emptyList() else inner.split(",").map { it.trim() }
                val arr = JArray.newInstance(base, parts.size)
                for (i in parts.indices) JArray.set(arr, i, parseValue(parts[i], base, null, null))
                return arr
            }

            fun parseCollectionValue(): Any? {
                val content = text.trim()
                if (!content.startsWith("[") || !content.endsWith("]")) return null
                val inner = content.substring(1, content.length - 1)
                val parts = if (inner.isBlank()) emptyList() else inner.split(",").map { it.trim() }
                val elementCls: Class<*>? = elementClass ?: when (genericType) {
                    is ParameterizedType -> (genericType.actualTypeArguments.getOrNull(0) as? Class<*>)
                    else -> null
                }
                val list = ArrayList<Any?>()
                for (p in parts) list.add(
                    if (elementCls != null) parseValue(
                        p,
                        elementCls,
                        null,
                        null
                    ) else p
                )
                return list
            }

            fun parseMapValue(): Any? {
                val content = text.trim()
                if (!content.startsWith("{") || !content.endsWith("}")) return null
                val inner = content.substring(1, content.length - 1)
                val map = mutableMapOf<String, Any?>()
                val valueCls: Class<*>? = elementClass ?: when (genericType) {
                    is ParameterizedType -> (genericType.actualTypeArguments.getOrNull(1) as? Class<*>)
                    else -> null
                }
                if (inner.isNotBlank()) inner.split(",").map { it.trim() }.forEach { pair ->
                    val kv = pair.split(":", limit = 2).map { it.trim() }
                    if (kv.size == 2) {
                        val key = kv[0]
                        val rawVal = kv[1]
                        map[key] = if (valueCls != null) parseValue(
                            rawVal,
                            valueCls,
                            null,
                            null
                        ) else rawVal
                    }
                }
                return map
            }

            if (type.isEnum) enumConstantFor(type, text)?.let { return it }
            if (type.isArray) return parseArrayValue()
            if (List::class.java.isAssignableFrom(type) || Collection::class.java.isAssignableFrom(
                    type
                )
            ) return parseCollectionValue()
            if (Map::class.java.isAssignableFrom(type)) return parseMapValue()
        }
        catch (e: Exception) {
            Log.e("ReflectionParser", "Failed to parse value '$text' for type ${type.name}: ${e.message}", e)
            return "<error>"
        }

        return null
    }

    fun enumConstantFor(enumClass: Class<*>, name: String): Any? {
        if (!enumClass.isEnum) return null
        val consts = enumClass.enumConstants ?: return null
        for (c in consts) if ((c as Enum<*>).name == name) return c
        return null
    }
}
