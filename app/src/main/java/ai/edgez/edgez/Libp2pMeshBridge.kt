package ai.edgez.edgez

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

private const val TAG_LIBP2P = "EdgeZLibp2p"

data class Libp2pMeshConfig(
    val meshId: String,
    val passphrase: String,
    val privateKey: ByteArray,
    val topic: String,
    val listen: String = "/ip4/0.0.0.0/tcp/0",
    val bootstrapPeers: List<String> = emptyList(),
)

class Libp2pMeshBridge(
    context: Context,
    private val onGossipPayload: (ByteArray) -> Unit,
) : Libp2pNative.Callback {
    private val appContext = context.applicationContext
    @Volatile
    private var running = false

    @Synchronized
    fun start(config: Libp2pMeshConfig): Result<String> {
        return runCatching {
            Libp2pNative.setCallback(this)
            val result = Libp2pNative.start(config.toJson())
            val json = JSONObject(result)
            check(json.optBoolean("ok", false)) { json.optString("error", "libp2p mesh start failed") }
            running = true
            Log.i(TAG_LIBP2P, "libp2p mesh start result=$result")
            result
        }.onFailure {
            runCatching { Libp2pNative.setCallback(null) }
        }
    }

    @Synchronized
    fun stop(): Result<String> {
        if (!running) return Result.success("""{"ok":true,"state":"stopped"}""")
        return runCatching {
            running = false
            val result = Libp2pNative.stop()
            Libp2pNative.setCallback(null)
            Log.i(TAG_LIBP2P, "libp2p mesh stop result=$result")
            result
        }
    }

    fun publish(payload: ByteArray): Result<String> {
        return runCatching {
            check(running) { "libp2p mesh is not running" }
            Log.d(TAG_LIBP2P, "libp2p publish start: ${formatPayload(payload)}")
            Libp2pNative.publish(payload)
                .also { result ->
                    Log.d(TAG_LIBP2P, "libp2p publish result: $result")
                }
        }
    }

    fun configFromPreferences(preferences: LastConnectionPreferences): Libp2pMeshConfig {
        val meshId = preferences.getMeshId().ifBlank { "edgez" }
        val listen = localListenAddr()
        Log.d(TAG_LIBP2P, "libp2p listen=$listen")
        return Libp2pMeshConfig(
            meshId = meshId,
            passphrase = preferences.getMeshPassphrase(),
            privateKey = Libp2pIdentity.getOrCreatePrivateKeySeed(appContext),
            topic = topicFor(meshId),
            listen = listen,
        )
    }

    override fun onMessage(payload: ByteArray) {
        Log.d(TAG_LIBP2P, "libp2p receive: ${formatPayload(payload)}")
        onGossipPayload(payload)
    }

    private fun formatPayload(payload: ByteArray): String {
        if (payload.isEmpty()) return "bytes=0"
        val preview = Base64.encodeToString(payload.copyOfRange(0, min(payload.size, 24)), Base64.NO_WRAP)
        return "bytes=${payload.size} preview=$preview"
    }

    private fun Libp2pMeshConfig.toJson(): String {
        return JSONObject()
            .put("mesh_id", meshId)
            .put("passphrase", passphrase)
            .put("private_key", Base64.encodeToString(privateKey, Base64.NO_WRAP))
            .put("topic", topic)
            .put("listen", listen)
            .put("bootstrap_peers", JSONArray(bootstrapPeers))
            .toString()
    }

    private fun localListenAddr(): String {
        val ipv4 = localIPv4Address() ?: return "/ip4/0.0.0.0/tcp/0"
        return "/ip4/$ipv4/tcp/0"
    }

    private fun localIPv4Address(): String? = runCatching {
        for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in Collections.list(iface.inetAddresses)) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isAnyLocalAddress && !addr.isLinkLocalAddress && !addr.isMulticastAddress) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }.getOrNull()

    private fun topicFor(meshId: String): String {
        return "/edgez/mesh/${meshId.ifBlank { "edgez" }}/gossip/1.0.0"
    }
}

object Libp2pNative {
    interface Callback {
        fun onMessage(payload: ByteArray)
    }

    init {
        runCatching { System.loadLibrary("edgezlibp2p") }
            .onFailure { Log.w(TAG_LIBP2P, "libedgezlibp2p.so unavailable", it) }
    }

    external fun start(configJson: String): String
    external fun stop(): String
    external fun publish(payload: ByteArray): String
    external fun setCallback(callback: Callback?)
}
