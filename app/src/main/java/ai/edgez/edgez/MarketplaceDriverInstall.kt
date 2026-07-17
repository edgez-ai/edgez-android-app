package ai.edgez.edgez

import android.content.Context
import android.content.Intent
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

private const val EDGEZ_MARKETPLACE_API = "https://www.edgez.ai/api/marketplace/items"
private val marketplaceIdPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
private val marketplaceSlugPattern = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

data class MarketplaceDriverInstallRequest(
    val itemId: String,
    val slug: String,
) {
    companion object {
        fun fromIntent(intent: Intent?): MarketplaceDriverInstallRequest? {
            if (intent?.action != Intent.ACTION_VIEW) return null
            val uri = intent.data ?: return null
            if (uri.scheme != "edgez" || uri.host != "drivers" || uri.path != "/install") return null

            val itemId = uri.getQueryParameter("id")?.trim().orEmpty()
            val slug = uri.getQueryParameter("slug")?.trim().orEmpty()
            if (!marketplaceIdPattern.matches(itemId) || !marketplaceSlugPattern.matches(slug)) return null
            return MarketplaceDriverInstallRequest(itemId = itemId, slug = slug)
        }
    }
}

data class MarketplaceDriver(
    val itemId: String,
    val slug: String,
    val driverId: String,
    val key: String,
    val scriptId: Int,
    val version: Int,
    val name: String,
    val connector: DeviceSensorConnector,
    val script: String,
    val description: String,
    val imageUrl: String,
    val globalBufferSize: Int,
)

fun fetchMarketplaceDriver(request: MarketplaceDriverInstallRequest): MarketplaceDriver {
    val connection = (URL("$EDGEZ_MARKETPLACE_API/${request.slug}").openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        requestMethod = "GET"
    }
    try {
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("Driver download failed: HTTP ${connection.responseCode}")
        }
        val item = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        if (item.optString("id") != request.itemId || item.optString("slug") != request.slug || item.optString("type") != "driver") {
            throw IllegalStateException("Marketplace driver does not match the requested install link")
        }
        val bundle = item.optJSONObject("driverBundle")
            ?: throw IllegalStateException("Marketplace item does not include a driver bundle")
        if (bundle.optString("format") != "edgez-driver/v1") {
            throw IllegalStateException("Unsupported marketplace driver format")
        }
        val connector = when (bundle.optString("interface")) {
            "uart_i2c" -> DeviceSensorConnector.UART_I2C
            "rs485" -> DeviceSensorConnector.RS485
            else -> throw IllegalStateException("Unsupported driver interface")
        }
        val driverId = bundle.optString("driverId")
        val key = bundle.optString("key")
        val scriptId = bundle.optInt("scriptId", 0)
        val version = bundle.optInt("version", 0)
        val script = bundle.optString("script")
        if (driverId.isBlank() || key.isBlank() || scriptId <= 0 || version <= 0 || script.isBlank()) {
            throw IllegalStateException("Marketplace driver is incomplete")
        }
        return MarketplaceDriver(
            itemId = request.itemId,
            slug = request.slug,
            driverId = driverId,
            key = key,
            scriptId = scriptId,
            version = version,
            name = bundle.optString("name").ifBlank { item.optString("title", request.slug) },
            connector = connector,
            script = script,
            description = bundle.optString("description"),
            imageUrl = bundle.optString("imageUrl"),
            globalBufferSize = bundle.optInt("globalBufferSize", 4096).coerceAtLeast(0),
        )
    } finally {
        connection.disconnect()
    }
}

fun installMarketplaceDriver(context: Context, driver: MarketplaceDriver) {
    val directory = File(context.filesDir, "drivers/${driver.driverId}/${driver.version}")
    if (!directory.exists() && !directory.mkdirs()) {
        throw IllegalStateException("Unable to create the driver storage directory")
    }
    val target = File(directory, "manifest.json")
    val temporary = File(directory, "manifest.tmp")
    val payload = JSONObject().apply {
        put("format", "edgez-driver/v1")
        put("driverId", driver.driverId)
        put("key", driver.key)
        put("scriptId", driver.scriptId)
        put("version", driver.version)
        put("name", driver.name)
        put("interface", driver.connector.assetFolder)
        put("entrypoint", "driver.lua")
        put("description", driver.description)
        put("globalBufferSize", driver.globalBufferSize)
        put("marketplaceItemId", driver.itemId)
        put("marketplaceSlug", driver.slug)
    }
    temporary.writeText(payload.toString())
    if (target.exists() && !target.delete()) throw IllegalStateException("Unable to replace the installed driver")
    if (!temporary.renameTo(target)) throw IllegalStateException("Unable to save the installed driver")
    File(directory, "driver.lua").writeText(driver.script)
    downloadDriverImage(driver.imageUrl, File(directory, "image"))?.let { imageName ->
        target.writeText(JSONObject(target.readText()).put("image", imageName).toString())
    }
}

fun installedMarketplaceDriverDefinitions(
    context: Context,
    connector: DeviceSensorConnector,
): List<DeviceSensorDefinition> {
    val directory = File(context.filesDir, "drivers")
    return directory.listFiles(File::isDirectory).orEmpty().flatMap { driverDirectory ->
        driverDirectory.listFiles(File::isDirectory).orEmpty().mapNotNull { versionDirectory ->
            runCatching {
                parseDriverBundle(
                    manifest = JSONObject(File(versionDirectory, "manifest.json").readText()),
                    script = File(versionDirectory, "driver.lua").readText(),
                    connector = connector,
                    imagePath = File(versionDirectory, "image").takeIf { it.isFile }?.absolutePath.orEmpty(),
                )
            }.getOrNull()
        }
    }
}

fun parseDriverBundle(
    manifest: JSONObject,
    script: String,
    connector: DeviceSensorConnector,
    imagePath: String = "",
): DeviceSensorDefinition? {
    if (manifest.optString("format") != "edgez-driver/v1" || manifest.optString("interface") != connector.assetFolder) {
        return null
    }
    val scriptId = manifest.optInt("scriptId", 0)
    val version = manifest.optInt("version", 0)
    val key = manifest.optString("key").ifBlank { "$scriptId-$version" }
    if (scriptId <= 0 || version <= 0 || script.isBlank()) return null
    return DeviceSensorDefinition(
        key = key,
        id = scriptId,
        version = version,
        name = manifest.optString("name").ifBlank { key },
        script = script,
        image = manifest.optString("image"),
        imagePath = imagePath,
        description = manifest.optString("description"),
        globalBufferSize = manifest.optInt("globalBufferSize", 4096).coerceAtLeast(0),
    )
}

private fun downloadDriverImage(imageUrl: String, target: File): String? {
    if (!imageUrl.startsWith("https://")) return null
    return runCatching {
        val connection = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            "image"
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
