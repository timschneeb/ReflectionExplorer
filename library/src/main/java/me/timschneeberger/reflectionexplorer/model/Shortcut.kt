package me.timschneeberger.reflectionexplorer.model

data class Shortcut(val value: String, val type: Type) {
    override fun toString() = value

    enum class Type {
        EnterStaticClass,
        ScanStaticFields
    }
}
