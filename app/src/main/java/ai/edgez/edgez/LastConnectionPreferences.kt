package ai.edgez.edgez

import android.content.Context
import android.util.Base64
import java.util.UUID

private const val LAST_CONNECTION_PREFS = "edgez_connection"
private const val KEY_LAST_SUCCESSFUL_CONNECTION = "last_successful_connection"
private const val KEY_MESH_COUNTRY = "mesh_country"
private const val KEY_MESH_ID = "mesh_id"
private const val KEY_MESH_PASSPHRASE = "mesh_passphrase"
private const val KEY_MESH_MAX_HOP = "mesh_max_hop"
private const val KEY_BEACON_INTERVAL_SECONDS = "beacon_interval_seconds"
private const val KEY_USER_UUID = "user_uuid"
private const val KEY_USER_NAME = "user_name"
private const val KEY_USER_MARKER = "user_marker"
private const val KEY_USER_PRIVATE_KEY = "user_private_key"
private const val KEY_USER_PUBLIC_KEY = "user_public_key"
private const val KEY_SHARE_LOCATION = "share_location"
private const val KEY_AUTO_REPLAY_RECEIVED_VOICE = "auto_replay_received_voice"
private const val KEY_DEVICE_MODE_ENABLED = "device_mode_enabled"
private const val DEFAULT_MESH_ID = "edgez"
private const val DEFAULT_MESH_MAX_HOP = 2
const val DEFAULT_BEACON_INTERVAL_SECONDS = 30
private const val DEFAULT_USER_NAME = "EdgeZ User"
private val SUPPORTED_MESH_COUNTRIES = setOf("US", "JP", "EU")

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

    fun getBeaconIntervalSeconds(): Int {
        return normalizeBeaconIntervalSeconds(
            prefs.getInt(KEY_BEACON_INTERVAL_SECONDS, DEFAULT_BEACON_INTERVAL_SECONDS),
        )
    }

    fun setMeshCredentials(
        country: String,
        meshId: String,
        passphrase: String,
        maxHop: Int = DEFAULT_MESH_MAX_HOP,
        beaconIntervalSeconds: Int = DEFAULT_BEACON_INTERVAL_SECONDS,
    ) {
        prefs.edit()
            .putString(KEY_MESH_COUNTRY, normalizeMeshCountry(country))
            .putString(KEY_MESH_ID, meshId)
            .putString(KEY_MESH_PASSPHRASE, passphrase)
            .putInt(KEY_MESH_MAX_HOP, normalizeMeshMaxHop(maxHop))
            .putInt(KEY_BEACON_INTERVAL_SECONDS, normalizeBeaconIntervalSeconds(beaconIntervalSeconds))
            .apply()
    }

    fun getOrCreateUserIdentity(): UserIdentity {
        val existingUserUuid = getStoredUserUuid()
        val existingPrivateKey = decodeBytes(prefs.getString(KEY_USER_PRIVATE_KEY, null), 32)
        val existingPublicKey = decodeBytes(prefs.getString(KEY_USER_PUBLIC_KEY, null), 32)
        val name = getUserName()
        if (existingUserUuid != null && existingPrivateKey != null && existingPublicKey != null) {
            return UserIdentity(
                userUuid = existingUserUuid.toString(),
                userIdHigh = existingUserUuid.mostSignificantBits,
                userIdLow = existingUserUuid.leastSignificantBits,
                name = name,
                privateKey = existingPrivateKey,
                publicKey = existingPublicKey,
            )
        }

        val userUuid = existingUserUuid ?: newUserUuid()
        val keyPair = X25519KeyGenerator.generateKeyPair()
        val identity = UserIdentity(
            userUuid = userUuid.toString(),
            userIdHigh = userUuid.mostSignificantBits,
            userIdLow = userUuid.leastSignificantBits,
            name = name,
            privateKey = keyPair.first,
            publicKey = keyPair.second,
        )
        saveUserIdentity(identity)
        return identity
    }

    fun getUserName(): String = prefs.getString(KEY_USER_NAME, DEFAULT_USER_NAME) ?: DEFAULT_USER_NAME

    fun getUserMarker(): String = NodeMapMarker.normalize(prefs.getString(KEY_USER_MARKER, NodeMapMarker.DEFAULT.id))

    fun getShareLocation(): Boolean = prefs.getBoolean(KEY_SHARE_LOCATION, false)

    fun getAutoReplayReceivedVoice(): Boolean = prefs.getBoolean(KEY_AUTO_REPLAY_RECEIVED_VOICE, false)

    fun getDeviceModeEnabled(): Boolean = prefs.getBoolean(KEY_DEVICE_MODE_ENABLED, false)

    fun setShareLocation(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_SHARE_LOCATION, enabled)
            .apply()
    }

    fun setAutoReplayReceivedVoice(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_AUTO_REPLAY_RECEIVED_VOICE, enabled)
            .apply()
    }

    fun setDeviceModeEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_DEVICE_MODE_ENABLED, enabled)
            .apply()
    }

    fun setUserName(name: String) {
        prefs.edit()
            .putString(KEY_USER_NAME, name.ifBlank { DEFAULT_USER_NAME }.take(64))
            .apply()
    }

    fun setUserMarker(marker: String) {
        prefs.edit()
            .putString(KEY_USER_MARKER, NodeMapMarker.normalize(marker))
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

    private fun normalizeBeaconIntervalSeconds(seconds: Int): Int = seconds.coerceIn(5, 3600)

    fun saveUserIdentity(identity: UserIdentity) {
        prefs.edit()
            .putString(KEY_USER_UUID, identity.userUuid)
            .putString(KEY_USER_NAME, identity.name.ifBlank { DEFAULT_USER_NAME }.take(64))
            .putString(KEY_USER_PRIVATE_KEY, encodeBytes(identity.privateKey))
            .putString(KEY_USER_PUBLIC_KEY, encodeBytes(identity.publicKey))
            .apply()
    }

    private fun getStoredUserUuid(): UUID? {
        return runCatching { UUID.fromString(prefs.getString(KEY_USER_UUID, null)) }
            .getOrNull()
            ?.takeIf { it.mostSignificantBits != 0L || it.leastSignificantBits != 0L }
    }

    private fun newUserUuid(): UUID {
        var uuid = UUID.randomUUID()
        while (uuid.mostSignificantBits == 0L && uuid.leastSignificantBits == 0L) {
            uuid = UUID.randomUUID()
        }
        return uuid
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
