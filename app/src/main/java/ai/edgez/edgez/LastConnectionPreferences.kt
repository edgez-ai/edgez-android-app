package ai.edgez.edgez

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

private const val LAST_CONNECTION_PREFS = "edgez_connection"
private const val KEY_LAST_SUCCESSFUL_CONNECTION = "last_successful_connection"
private const val KEY_MESH_COUNTRY = "mesh_country"
private const val KEY_MESH_ID = "mesh_id"
private const val KEY_MESH_PASSPHRASE = "mesh_passphrase"
private const val KEY_MESH_MAX_HOP = "mesh_max_hop"
private const val KEY_USER_ID = "user_id"
private const val KEY_USER_NAME = "user_name"
private const val KEY_USER_PRIVATE_KEY = "user_private_key"
private const val KEY_USER_PUBLIC_KEY = "user_public_key"
private const val DEFAULT_MESH_ID = "edgez"
private const val DEFAULT_MESH_MAX_HOP = 2
private const val DEFAULT_USER_NAME = "EdgeZ User"
private val SUPPORTED_MESH_COUNTRIES = setOf("US", "JP", "EU")
private val USER_ID_RANDOM = SecureRandom()

class LastConnectionPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(LAST_CONNECTION_PREFS, Context.MODE_PRIVATE)

    fun getLastSuccessfulConnection(): ActiveConnection {
        return runCatching {
            ActiveConnection.valueOf(
                prefs.getString(KEY_LAST_SUCCESSFUL_CONNECTION, ActiveConnection.NONE.name)
                    ?: ActiveConnection.NONE.name,
            )
        }.getOrDefault(ActiveConnection.NONE)
    }

    fun setLastSuccessfulConnection(connection: ActiveConnection) {
        if (connection == ActiveConnection.NONE) return
        prefs.edit()
            .putString(KEY_LAST_SUCCESSFUL_CONNECTION, connection.name)
            .apply()
    }

    fun getMeshId(): String = prefs.getString(KEY_MESH_ID, DEFAULT_MESH_ID) ?: DEFAULT_MESH_ID

    fun getMeshPassphrase(): String = prefs.getString(KEY_MESH_PASSPHRASE, "") ?: ""

    fun getMeshCountry(): String = normalizeMeshCountry(prefs.getString(KEY_MESH_COUNTRY, "US"))

    fun getMeshMaxHop(): Int = normalizeMeshMaxHop(prefs.getInt(KEY_MESH_MAX_HOP, DEFAULT_MESH_MAX_HOP))

    fun setMeshCredentials(
        country: String,
        meshId: String,
        passphrase: String,
        maxHop: Int = DEFAULT_MESH_MAX_HOP,
    ) {
        prefs.edit()
            .putString(KEY_MESH_COUNTRY, normalizeMeshCountry(country))
            .putString(KEY_MESH_ID, meshId)
            .putString(KEY_MESH_PASSPHRASE, passphrase)
            .putInt(KEY_MESH_MAX_HOP, normalizeMeshMaxHop(maxHop))
            .apply()
    }

    fun getOrCreateUserIdentity(): UserIdentity {
        val existingUserId = getStoredUserId()
        val existingPrivateKey = decodeBytes(prefs.getString(KEY_USER_PRIVATE_KEY, null), 32)
        val existingPublicKey = decodeBytes(prefs.getString(KEY_USER_PUBLIC_KEY, null), 32)
        val name = getUserName()
        if (existingUserId != null && existingPrivateKey != null && existingPublicKey != null) {
            return UserIdentity(existingUserId, name, existingPrivateKey, existingPublicKey)
        }

        val userId = existingUserId ?: newUserId()
        val keyPair = X25519KeyGenerator.generateKeyPair()
        val identity = UserIdentity(
            userId = userId,
            name = name,
            privateKey = keyPair.first,
            publicKey = keyPair.second,
        )
        saveUserIdentity(identity)
        return identity
    }

    fun getUserName(): String = prefs.getString(KEY_USER_NAME, DEFAULT_USER_NAME) ?: DEFAULT_USER_NAME

    fun setUserName(name: String) {
        prefs.edit()
            .putString(KEY_USER_NAME, name.ifBlank { DEFAULT_USER_NAME }.take(64))
            .apply()
    }

    fun regenerateUserKeyPair(): UserIdentity {
        val currentIdentity = getOrCreateUserIdentity()
        val keyPair = X25519KeyGenerator.generateKeyPair()
        val identity = currentIdentity.copy(
            privateKey = keyPair.first,
            publicKey = keyPair.second,
        )
        saveUserIdentity(identity)
        return identity
    }

    private fun normalizeMeshCountry(country: String?): String {
        val normalized = country?.uppercase() ?: "US"
        return if (normalized in SUPPORTED_MESH_COUNTRIES) normalized else "US"
    }

    private fun normalizeMeshMaxHop(maxHop: Int): Int = maxHop.coerceIn(0, 255)

    private fun saveUserIdentity(identity: UserIdentity) {
        prefs.edit()
            .putString(KEY_USER_ID, identity.userId.toString())
            .putString(KEY_USER_NAME, identity.name.ifBlank { DEFAULT_USER_NAME }.take(64))
            .putString(KEY_USER_PRIVATE_KEY, encodeBytes(identity.privateKey))
            .putString(KEY_USER_PUBLIC_KEY, encodeBytes(identity.publicKey))
            .apply()
    }

    private fun getStoredUserId(): Long? {
        val userId = prefs.getString(KEY_USER_ID, null)?.toLongOrNull()
        return userId?.takeIf { it > 0L }
    }

    private fun newUserId(): Long {
        var userId = USER_ID_RANDOM.nextLong() and Long.MAX_VALUE
        while (userId == 0L) {
            userId = USER_ID_RANDOM.nextLong() and Long.MAX_VALUE
        }
        return userId
    }

    private fun encodeBytes(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decodeBytes(encoded: String?, expectedSize: Int): ByteArray? {
        if (encoded.isNullOrBlank()) return null
        val decoded = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return null
        return decoded.takeIf { it.size == expectedSize }
    }
}
