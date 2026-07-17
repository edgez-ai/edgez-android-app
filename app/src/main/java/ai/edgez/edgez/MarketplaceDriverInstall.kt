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
    val internalId: Int,
    val version: Int,
    val name: String,
    val connector: DeviceSensorConnector,
    val script: String,
    val description: String,
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
        val source = item.optJSONObject("source")?.optJSONObject("driver")
            ?: throw IllegalStateException("Marketplace item does not include a driver")
        val connector = when (source.optString("interface")) {
            "uart_i2c" -> DeviceSensorConnector.UART_I2C
            "rs485" -> DeviceSensorConnector.RS485
            else -> throw IllegalStateException("Unsupported driver interface")
        }
        val internalId = source.optInt("internal_id", 0)
        val version = source.optInt("version", 0)
        val script = source.optString("script")
        if (internalId <= 0 || version <= 0 || script.isBlank()) {
            throw IllegalStateException("Marketplace driver is incomplete")
        }
        return MarketplaceDriver(
            itemId = request.itemId,
            slug = request.slug,
            internalId = internalId,
            version = version,
            name = source.optString("name").ifBlank { item.optString("title", request.slug) },
            connector = connector,
            script = script,
            description = item.optString("shortDescription"),
            globalBufferSize = source.optInt("global_buffer_size", 4096).coerceAtLeast(0),
        )
    } finally {
        connection.disconnect()
    }
}

fun installMarketplaceDriver(context: Context, driver: MarketplaceDriver) {
    val directory = File(context.filesDir, "marketplace-drivers")
    if (!directory.exists() && !directory.mkdirs()) {
        throw IllegalStateException("Unable to create the driver storage directory")
    }
    val target = File(directory, "${driver.itemId}.json")
    val temporary = File(directory, "${driver.itemId}.tmp")
    val payload = JSONObject().apply {
        put("itemId", driver.itemId)
        put("slug", driver.slug)
        put("internalId", driver.internalId)
        put("version", driver.version)
        put("name", driver.name)
        put("connector", driver.connector.assetFolder)
        put("script", driver.script)
        put("description", driver.description)
        put("globalBufferSize", driver.globalBufferSize)
    }
    temporary.writeText(payload.toString())
    if (target.exists() && !target.delete()) throw IllegalStateException("Unable to replace the installed driver")
    if (!temporary.renameTo(target)) throw IllegalStateException("Unable to save the installed driver")
}

fun installedMarketplaceDriverDefinitions(
    context: Context,
    connector: DeviceSensorConnector,
): List<DeviceSensorDefinition> {
    val directory = File(context.filesDir, "marketplace-drivers")
    return directory.listFiles { file -> file.extension == "json" }.orEmpty().mapNotNull { file ->
        runCatching {
            val driver = JSONObject(file.readText())
            if (driver.optString("connector") != connector.assetFolder) return@runCatching null
            val internalId = driver.optInt("internalId", 0)
            val version = driver.optInt("version", 0)
            val script = driver.optString("script")
            if (internalId <= 0 || version <= 0 || script.isBlank()) return@runCatching null
            DeviceSensorDefinition(
                key = "$internalId-$version",
                id = internalId,
                version = version,
                name = driver.optString("name").ifBlank { driver.optString("slug") },
                script = script,
                description = driver.optString("description"),
                globalBufferSize = driver.optInt("globalBufferSize", 4096).coerceAtLeast(0),
            )
        }.getOrNull()
    }
}
