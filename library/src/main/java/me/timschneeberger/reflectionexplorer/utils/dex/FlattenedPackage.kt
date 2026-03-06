package me.timschneeberger.reflectionexplorer.utils.dex

data class FlattenedPackage(
    val name: String,
    val packages: List<FlattenedPackage>,
    val staticFields: List<StaticField>
)