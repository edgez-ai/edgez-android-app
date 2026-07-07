package ai.edgez.edgez

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

private const val LIBP2P_IDENTITY_PREFS = "edgez_libp2p_mesh"
private const val KEY_LIBP2P_PRIVATE_KEY = "private_key"
private const val LIBP2P_PRIVATE_KEY_BYTES = 32

object Libp2pIdentity {
    fun getOrCreatePrivateKeySeed(context: Context): ByteArray {
        val prefs = context.applicationContext.getSharedPreferences(LIBP2P_IDENTITY_PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_LIBP2P_PRIVATE_KEY, null)
        val decoded = stored?.let {
            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
        }
        if (decoded?.size == LIBP2P_PRIVATE_KEY_BYTES) return decoded

        val next = randomSeed()
        prefs.edit()
            .putString(KEY_LIBP2P_PRIVATE_KEY, Base64.encodeToString(next, Base64.NO_WRAP))
            .apply()
        return next
    }

    fun rotatePrivateKeySeed(context: Context): ByteArray {
        val next = randomSeed()
        context.applicationContext.getSharedPreferences(LIBP2P_IDENTITY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIBP2P_PRIVATE_KEY, Base64.encodeToString(next, Base64.NO_WRAP))
            .apply()
        return next
    }

    fun deriveX25519KeyPair(context: Context, userUuid: String): Pair<ByteArray, ByteArray> {
        return deriveX25519KeyPair(getOrCreatePrivateKeySeed(context), userUuid)
    }

    fun deriveX25519KeyPair(seed: ByteArray, userUuid: String): Pair<ByteArray, ByteArray> {
        return X25519KeyGenerator.deriveKeyPair(seed, "user:$userUuid")
    }

    private fun randomSeed(): ByteArray {
        val seed = ByteArray(LIBP2P_PRIVATE_KEY_BYTES)
        SecureRandom().nextBytes(seed)
        return seed
    }
}
