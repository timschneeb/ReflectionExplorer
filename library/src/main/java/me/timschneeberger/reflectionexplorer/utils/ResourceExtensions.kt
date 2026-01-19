package me.timschneeberger.reflectionexplorer.utils

import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import androidx.core.content.ContextCompat
import me.timschneeberger.reflectionexplorer.R
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

fun Int.dpToPx(): Int = (this * Resources.getSystem().displayMetrics.density).toInt()

private fun Drawable?.composeWithOverlays(overlays: List<Drawable?>): Drawable? {
    if (this == null) return null
    if (overlays.isEmpty()) return this

    val layers = mutableListOf<Drawable>().apply {
        add(this@composeWithOverlays)
        overlays.filterNotNull().forEach(::add)
    }.toTypedArray()
    val ld = LayerDrawable(layers)

    val baseW = this.intrinsicWidth.takeIf { it > 0 } ?: 16.dpToPx()
    val baseH = this.intrinsicHeight.takeIf { it > 0 } ?: 16.dpToPx()
    val defaultOverlay = 8.dpToPx()
    val margin = 1.dpToPx()

    // ensure base occupies full bounds
    ld.setLayerInset(0, 0, 0, 0, 0)

    var overlayIdx = 1
    for (i in 1 until layers.size) {
        val overlay = layers[i]
        val oW = overlay.intrinsicWidth.takeIf { it > 0 } ?: defaultOverlay
        val oH = overlay.intrinsicHeight.takeIf { it > 0 } ?: defaultOverlay

        val desiredLeft = baseW - oW - margin
        val desiredTop = if (overlayIdx == 1) margin else baseH - oH - margin

        val left = desiredLeft.coerceIn(0, (baseW - oW).coerceAtLeast(0))
        val top = desiredTop.coerceIn(0, (baseH - oH).coerceAtLeast(0))
        val right = (baseW - left - oW).coerceAtLeast(0)
        val bottom = (baseH - top - oH).coerceAtLeast(0)

        ld.setLayerInset(i, left, top, right, bottom)
        overlayIdx++
    }

    return ld
}

fun Field.getFieldDrawable(ctx: Context): Drawable? {
    val baseId = when {
        Modifier.isPublic(modifiers) -> R.drawable.ic_public_field
        Modifier.isPrivate(modifiers) -> R.drawable.ic_private_field
        Modifier.isProtected(modifiers) -> R.drawable.ic_protected_field
        else -> R.drawable.ic_field
    }
    val base = ContextCompat.getDrawable(ctx, baseId)
    val overlays = mutableListOf<Drawable?>().apply {
        if (Modifier.isFinal(modifiers)) add(ContextCompat.getDrawable(ctx, R.drawable.ic_final_mark))
        if (Modifier.isStatic(modifiers)) add(ContextCompat.getDrawable(ctx, R.drawable.ic_static_mark))
    }
    return base.composeWithOverlays(overlays)
}

fun Method.getMethodDrawable(ctx: Context): Drawable? {
    val baseId = when {
        Modifier.isAbstract(modifiers) -> R.drawable.ic_abstractmethod
        name == "<init>" -> R.drawable.ic_constructor_method
        Modifier.isPublic(modifiers) -> R.drawable.ic_public_method
        Modifier.isPrivate(modifiers) -> R.drawable.ic_private_method
        Modifier.isProtected(modifiers) -> R.drawable.ic_protected_method
        else -> R.drawable.ic_method
    }
    val base = ContextCompat.getDrawable(ctx, baseId)
    val overlays = mutableListOf<Drawable?>().apply {
        if (Modifier.isFinal(modifiers)) add(ContextCompat.getDrawable(ctx, R.drawable.ic_final_mark))
        if (Modifier.isStatic(modifiers)) add(ContextCompat.getDrawable(ctx, R.drawable.ic_static_mark))
    }
    return base.composeWithOverlays(overlays)
}