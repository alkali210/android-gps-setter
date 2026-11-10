package io.github.jqssun.gpssetter.ui

import kotlin.math.log2
import kotlin.math.sqrt

// Issue #8: the location button flips the map between two scales, measured as metres across the map's width.
// Pure (no Android/map types) so it's unit-tested; works for Google Maps and MapLibre since +1 zoom halves the span on both.
object MapScale {
    const val FAR_M = 1000.0
    const val NEAR_M = 100.0

    fun zoomFor(targetM: Double, zoom: Double, widthM: Double): Double = zoom + log2(widthM / targetM)

    // nearer 100 m than 1 km (below their geometric midpoint, ~316 m) -> zoom out to 1 km, otherwise -> zoom in to 100 m
    fun toggle(zoom: Double, widthM: Double): Double =
        zoomFor(if (widthM < sqrt(FAR_M * NEAR_M)) FAR_M else NEAR_M, zoom, widthM)
}
