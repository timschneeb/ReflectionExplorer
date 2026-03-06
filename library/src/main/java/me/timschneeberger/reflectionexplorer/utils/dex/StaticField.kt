package me.timschneeberger.reflectionexplorer.utils.dex

import org.jf.dexlib2.dexbacked.DexBackedField

data class StaticField(
        val declaringClass: String,
        val name: String,
        val refType: String,
        val isArray: Boolean
    ) {
        companion object {
            private fun smaliToJavaType(smaliType: String): String {
                return when {
                    // Example: [Ljava/lang/String; to java.lang.String
                    smaliType.startsWith("[L") -> smaliType.substring(2, smaliType.length - 1).replace('/', '.')
                    smaliType.startsWith("L") -> smaliType.substring(1, smaliType.length - 1).replace('/', '.')
                    else -> smaliType // Primitive types are returned as-is, though we don't handle them here anyways
                }
            }

            fun fromDexField(field: DexBackedField): StaticField {
                val isArray = field.type.startsWith("[L")
                val refType = smaliToJavaType(field.type)
                val defClassType = smaliToJavaType(field.definingClass)
                return StaticField(defClassType, field.name, refType, isArray)
            }
        }
    }
