package ai.edgez.edgez

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

data class ImageSelection(
    val path: String,
    val bytes: ByteArray,
)

fun saveSelectedImageMessage(context: Context, uri: Uri, maxBytes: Int): ImageSelection {
    val resolver = context.contentResolver
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (out.size() <= maxBytes) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }
        ?: throw IllegalStateException("Unable to read selected image")
    require(bytes.isNotEmpty()) { "Selected image is empty" }
    require(bytes.size <= maxBytes) {
        "Image is too large: ${formatBytes(bytes.size)} / ${formatBytes(maxBytes)}"
    }
    val mimeType = resolver.getType(uri).orEmpty()
    val path = saveImageMessage(context, bytes, imageExtensionFromMime(mimeType))
    return ImageSelection(path = path, bytes = bytes)
}

fun saveImageMessage(context: Context, bytes: ByteArray, extension: String = "jpg"): String {
    val file = newImageFile(context, extension)
    file.writeBytes(bytes)
    return file.absolutePath
}

fun formatBytes(bytes: Int): String {
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return "%.1f MB".format(mb)
}

private fun imageExtensionFromMime(mimeType: String): String {
    return when (mimeType.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "jpg"
    }
}

private fun newImageFile(context: Context, extension: String): File {
    val dir = File(context.filesDir, "image_messages")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    val safeExtension = extension.filter { it.isLetterOrDigit() }.ifBlank { "jpg" }
    return File(dir, "image_${System.currentTimeMillis()}_${(0..9999).random()}.$safeExtension")
}
