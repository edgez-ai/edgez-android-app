package ai.edgez.edgez

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

private const val TAG_LIBP2P = "EdgeZLibp2p"
private const val LIBP2P_PRIVATE_KEY_BYTES = 32

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
    private val prefs = appContext.getSharedPreferences("edgez_libp2p_mesh", Context.MODE_PRIVATE)
    private var running = false

    fun start(config: Libp2pMeshConfig): Result<String> {
        return runCatching {
            Libp2pNative.setCallback(this)
            val result = Libp2pNative.start(config.toJson())
            running = true
            Log.i(TAG_LIBP2P, "libp2p mesh start result=$result")
            result
        }
    }

    fun stop(): Result<String> {
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
            privateKey = getOrCreatePrivateKey(),
            topic = topicFor(meshId),
        )
    }

    override fun onMessage(payload: ByteArray) {
        onGossipPayload(payload)
    }

    private fun getOrCreatePrivateKey(): ByteArray {
        val stored = prefs.getString("private_key", null)
        val decoded = stored?.let {
            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
        }
        if (decoded?.size == LIBP2P_PRIVATE_KEY_BYTES) return decoded
        val next = ByteArray(LIBP2P_PRIVATE_KEY_BYTES)
        SecureRandom().nextBytes(next)
        prefs.edit()
            .putString("private_key", Base64.encodeToString(next, Base64.NO_WRAP))
            .apply()
        return next
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
