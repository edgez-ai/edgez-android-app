package ai.edgez.edgez

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

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
            Libp2pNative.publish(payload)
        }
    }

    fun configFromPreferences(preferences: LastConnectionPreferences): Libp2pMeshConfig {
        val meshId = preferences.getMeshId().ifBlank { "edgez" }
        return Libp2pMeshConfig(
            meshId = meshId,
            passphrase = preferences.getMeshPassphrase(),
            privateKey = Libp2pIdentity.getOrCreatePrivateKeySeed(appContext),
            topic = topicFor(meshId),
        )
    }

    override fun onMessage(payload: ByteArray) {
        onGossipPayload(payload)
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
