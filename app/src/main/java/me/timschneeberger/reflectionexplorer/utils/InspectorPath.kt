package me.timschneeberger.reflectionexplorer.utils

sealed class Accessor {
    data class Field(val fieldName: String) : Accessor()
    data class ArrayIndex(val index: Int) : Accessor()
    data class ListIndex(val index: Int) : Accessor()
    data class MapKey(val key: String) : Accessor()
}

data class ParentRef(val parentIndex: Int, val accessor: Accessor)

