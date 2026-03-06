package me.timschneeberger.reflectionexplorer.utils.dex

import android.content.Context
import android.util.Log
import org.jf.dexlib2.AccessFlags

/**
 * Small helper to static field info from DEX files using dexlib2.
 */
object StaticFields {
    private const val TAG = "StaticFields"

    private val hiddenFieldTypePrefixes = setOf(
        "java.",
        "kotlin.",
        "androidx.",
        "android.os.Parcel", // includes Parcelable, $Creator, etc.
    )

    private val hiddenFieldTypeContains = setOf(
        ".proto.",
        ".protobuf."
    )

    fun lookup(context: Context): List<StaticField>? {
        return try {
             DexLocator.findLoadedPaths(context)?.flatMap { path ->
                DexLocator.openPackage(path).flatMap { dex ->
                    dex.classes
                        .flatMap { cls ->
                            cls.staticFields
                                .asSequence()
                                // Only interested in static reference fields or arrays of references
                                .filter { it.type.startsWith("L") || it.type.startsWith("[L") }
                                // Skip enum classes
                                .filterNot { it.classDef.accessFlags and AccessFlags.ENUM.value != 0 }
                                .map { StaticField.fromDexField(it) }
                                .filterNot {
                                    hiddenFieldTypePrefixes.any { prefix ->
                                        it.refType.startsWith(prefix)
                                    }
                                }
                                .filterNot { hiddenFieldTypeContains.contains(it.refType) }
                        }
                }
            }.also {
                Log.e(TAG, "Found ${it?.size} static reference fields across # loaded DEX files")

                makeAndPrintStaticInstanceTree(
                    it!!
                )
             }
        } catch (t: Throwable) {
            Log.e(TAG, "lookup failed: ${t.message}", t)
            return emptyList()
        }
    }

    fun makeStaticInstanceTree(staticFields: List<StaticField>): List<FlattenedPackage> {
        val root = mutableMapOf<String, MutableList<StaticField>>()

        // Group static fields by their top-level package
        for (field in staticFields) {
            val topLevelPackage = field.refType.substringBefore('.')
            root.getOrPut(topLevelPackage) { mutableListOf() }.add(field)
        }

        // Recursively build the package tree. We keep both the full package path
        // (used for grouping/filtering) and the simple/local name used for display
        // so subpackages don't contain the absolute full path.
        fun buildPackageTree(fullPackageName: String, localName: String, fields: List<StaticField>): FlattenedPackage? {
            val subPackages = fields
                .groupBy { it.refType.substringAfter("$fullPackageName.", "").substringBefore('.') }
                .filterKeys { it.isNotEmpty() }

            val pkg = FlattenedPackage(
                name = localName,
                packages = subPackages.mapNotNull { (subPackageName, subFields) ->
                    buildPackageTree("$fullPackageName.$subPackageName", subPackageName, subFields)
                },
                staticFields = fields.filter { it.refType.substringBeforeLast(".") == fullPackageName }
            )

            // If this package contains nothing, drop it
            if (pkg.staticFields.isEmpty() && pkg.packages.isEmpty()) {
                return null
            }

            // If this package has no static fields and exactly one subpackage,
            // collapse/flatten it by returning a new package whose name is the
            // combined dotted path of this package and the single child. Keep
            // the child's packages and static fields intact.
            if (pkg.staticFields.isEmpty() && pkg.packages.size == 1) {
                val child = pkg.packages[0]
                return FlattenedPackage(
                    name = "${pkg.name}.${child.name}",
                    packages = child.packages,
                    staticFields = child.staticFields
                )
            }

            return pkg
        }

        return root.mapNotNull { (packageName, fields) ->
            buildPackageTree(packageName, packageName, fields)
        }
    }

    fun makeAndPrintStaticInstanceTree(staticFields: List<StaticField>) {
        val tree = makeStaticInstanceTree(staticFields)

        fun printPackage(pkg: FlattenedPackage, indent: String = "") {
            Log.e(TAG, "$indent- ${pkg.name} (${pkg.staticFields.size} static fields)")
            for (field in pkg.staticFields.take(3)) {
                Log.e(TAG, "$indent  - ${field.name}: ${field.refType}${if (field.isArray) "[]" else ""}")
            }
            for (subPkg in pkg.packages) {
                printPackage(subPkg, "$indent  ")
            }
        }

        for (pkg in tree) {
            printPackage(pkg)
        }
    }
}