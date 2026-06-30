package ai.edgez.edgez

import android.content.Context
import android.content.Intent
import android.net.Uri

fun HaLowUser.hasLocation(): Boolean = latitude != null && longitude != null

fun Context.openUserLocationInMap(user: HaLowUser): Boolean {
    val latitude = user.latitude ?: return false
    val longitude = user.longitude ?: return false
    val label = Uri.encode(user.displayName)
    val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude($label)")
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching {
        startActivity(intent)
        true
    }.getOrDefault(false)
}
