package ai.edgez.edgez

import androidx.compose.ui.graphics.Color

fun HaLowUser.markerTintColor(): Color? {
    return NodeMapMarker.fromId(marker).colorArgb?.let { Color(it) }
}
