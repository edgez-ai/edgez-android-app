package ai.edgez.edgez

import androidx.compose.ui.graphics.Color

fun HaLowUser.markerTintColor(): Color? {
    return markerTintColor(marker)
}

private fun markerTintColor(markerId: String?): Color? {
    return NodeMapMarker.fromId(markerId).colorArgb?.let { Color(it.toInt()) }
}
