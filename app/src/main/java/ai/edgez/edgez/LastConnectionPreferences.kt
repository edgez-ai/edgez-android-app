package ai.edgez.edgez

import android.content.Context
import android.util.Base64
import java.util.UUID

private const val LAST_CONNECTION_PREFS = "edgez_connection"
private const val KEY_LAST_SUCCESSFUL_CONNECTION = "last_successful_connection"
private const val KEY_LAST_HALOW_NODE_ID = "last_halow_node_id"
private const val KEY_LAST_HALOW_NODE_BLE_ADDRESS = "last_halow_node_ble_address"
private const val KEY_MESH_COUNTRY = "mesh_country"
private const val KEY_MESH_ID = "mesh_id"
private const val KEY_MESH_PASSPHRASE = "mesh_passphrase"
private const val KEY_MESH_MAX_HOP = "mesh_max_hop"
private const val KEY_MESH_BANDWIDTH_MHZ = "mesh_bandwidth_mhz"
private const val KEY_MESH_FREQUENCY_KHZ = "mesh_frequency_khz"
private const val KEY_LIBP2P_MESH_ENABLED = "libp2p_mesh_enabled"
private const val KEY_LIBP2P_BOOTSTRAP_PEERS = "libp2p_bootstrap_peers"
private const val KEY_LIBP2P_PUBLIC_DHT = "libp2p_public_dht"
private const val KEY_BEACON_INTERVAL_SECONDS = "beacon_interval_seconds"
private const val KEY_USER_UUID = "user_uuid"
private const val KEY_USER_NAME = "user_name"
private const val KEY_USER_MARKER = "user_marker"
private const val KEY_USER_PRIVATE_KEY = "user_private_key"
private const val KEY_USER_PUBLIC_KEY = "user_public_key"
private const val KEY_SHARE_LOCATION = "share_location"
private const val KEY_AUTO_REPLAY_RECEIVED_VOICE = "auto_replay_received_voice"
private const val KEY_AUTO_ANSWER_VOICE_CALLS = "auto_answer_voice_calls"
private const val KEY_SELECTED_BLE_ADDRESS = "selected_ble_address"
private const val KEY_SELECTED_BLE_LABEL = "selected_ble_label"
private const val KEY_BLE_AUTO_CONNECT = "ble_auto_connect"
private const val KEY_DEVICE_GEOFENCES = "device_geofences"
private const val KEY_SELECTED_DEVICE_GEOFENCE = "selected_device_geofence"
private const val KEY_DASHBOARD_WIDGET_ORDER = "dashboard_widget_order"
private const val DEFAULT_MESH_ID = "edgez"
private const val DEFAULT_MESH_MAX_HOP = 2
private const val DEFAULT_MESH_BANDWIDTH_MHZ = 1
private const val DEFAULT_MESH_FREQUENCY_KHZ = 915500
val EDGEZ_LIBP2P_BOOTSTRAP_PEERS: String = """
    /ip4/65.20.115.199/tcp/4001/p2p/12D3KooWSMXRqi2rd7p4UPErgVS6zFeYpYuddo8qayYxkSR2WT7Q
    /ip4/207.148.107.129/tcp/4001/p2p/12D3KooWT29aoqxDYCno7ZcGMWjW6j9R6hNrqVFcFQsCcSwih1zx
""".trimIndent()
const val EDGEZ_LIBP2P_SWARM_KEY = "dde06cad2512343bcb572f3b9a6f7f3d7165492aa9d257b80958108848cdcf22"
val DEFAULT_LIBP2P_BOOTSTRAP_PEERS = """
    /dnsaddr/bootstrap.libp2p.io/p2p/QmNnooDu7bfjPFoTZYxMNLWUQJyrVwtbZg5gBMjTezGAJN
    /dnsaddr/bootstrap.libp2p.io/p2p/QmQCU2EcMqAqQPR2i9bChDtGNJchTbq5TbXJJ16u19uLTa
    /dnsaddr/bootstrap.libp2p.io/p2p/QmbLHAnMoJPWSCR5Zhtx6BHJX9KiKNN6tpvbUcqanj75Nb
    /dnsaddr/bootstrap.libp2p.io/p2p/QmcZf59bWwK5XFi76CZX8cbJ4BhTzzA3gU1ZjYZcYW3dwt
    /ip4/104.131.131.82/tcp/4001/p2p/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ
""".trimIndent()
const val DEFAULT_BEACON_INTERVAL_SECONDS = 30
private const val DEFAULT_USER_NAME = "EdgeZ User"
private val SUPPORTED_MESH_COUNTRIES = setOf("US", "JP", "EU")

fun normalizeLibp2pBootstrapPeers(peers: String): String = parseMultiaddrList(peers)
    .joinToString("\n")

class LastConnectionPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(LAST_CONNECTION_PREFS, Context.MODE_PRIVATE)

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

    fun getLastHaLowNodeId(): Long? {
        val selectedBleAddress = normalizeBleAddress(getSelectedBleAddress())
        if (selectedBleAddress.isEmpty()) return null
        val storedBleAddress = normalizeBleAddress(
            prefs.getString(KEY_LAST_HALOW_NODE_BLE_ADDRESS, "").orEmpty(),
        )
        val nodeId = prefs.getLong(KEY_LAST_HALOW_NODE_ID, 0L)
            .and(0x0000ffffffffffffL)
            .takeIf { it > 0x00000000ffffffffL && it != 0x0000ffffffffffffL }
            ?: return null

        if (storedBleAddress.isEmpty()) {
            // One-time migration of the original global cache: associate it
            // with the BLE device already selected by the user.
            prefs.edit()
                .putString(KEY_LAST_HALOW_NODE_BLE_ADDRESS, selectedBleAddress)
                .apply()
            return nodeId
        }
        return nodeId.takeIf { storedBleAddress == selectedBleAddress }
    }

    /** Updates the node ID associated with the remembered BLE device. */
    fun setLastHaLowNodeId(nodeId: Long): Boolean {
        val normalized = nodeId and 0x0000ffffffffffffL
        if (normalized <= 0x00000000ffffffffL || normalized == 0x0000ffffffffffffL) return false
        val selectedBleAddress = normalizeBleAddress(getSelectedBleAddress())
        if (selectedBleAddress.isEmpty()) return false
        val storedNodeId = prefs.getLong(KEY_LAST_HALOW_NODE_ID, 0L)
            .and(0x0000ffffffffffffL)
        val storedBleAddress = normalizeBleAddress(
            prefs.getString(KEY_LAST_HALOW_NODE_BLE_ADDRESS, "").orEmpty(),
        )
        if (storedNodeId == normalized && storedBleAddress == selectedBleAddress) return false
        prefs.edit()
            .putLong(KEY_LAST_HALOW_NODE_ID, normalized)
            .putString(KEY_LAST_HALOW_NODE_BLE_ADDRESS, selectedBleAddress)
            .apply()
        return true
    }

    fun getMeshId(): String = prefs.getString(KEY_MESH_ID, DEFAULT_MESH_ID) ?: DEFAULT_MESH_ID

    fun getMeshPassphrase(): String = prefs.getString(KEY_MESH_PASSPHRASE, "") ?: ""

    fun getMeshCountry(): String = normalizeMeshCountry(prefs.getString(KEY_MESH_COUNTRY, "US"))

    fun getMeshMaxHop(): Int = normalizeMeshMaxHop(prefs.getInt(KEY_MESH_MAX_HOP, DEFAULT_MESH_MAX_HOP))

    fun getMeshBandwidthMHz(): Int = prefs.getInt(KEY_MESH_BANDWIDTH_MHZ, DEFAULT_MESH_BANDWIDTH_MHZ)
        .takeIf { it == 1 || it == 2 || it == 4 || it == 8 } ?: DEFAULT_MESH_BANDWIDTH_MHZ

    fun getMeshFrequencyKHz(): Int = prefs.getInt(KEY_MESH_FREQUENCY_KHZ, DEFAULT_MESH_FREQUENCY_KHZ)

    fun getLibp2pMeshEnabled(): Boolean = prefs.getBoolean(KEY_LIBP2P_MESH_ENABLED, false)

    fun getLibp2pPublicDht(): Boolean = prefs.getBoolean(KEY_LIBP2P_PUBLIC_DHT, true)

    fun getLibp2pBootstrapPeers(): List<String> =
        parseMultiaddrList(
            prefs.getString(KEY_LIBP2P_BOOTSTRAP_PEERS, null)?.trim()?.ifBlank {
                DEFAULT_LIBP2P_BOOTSTRAP_PEERS
            },
        ).distinct()

    fun setLibp2pMeshEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_LIBP2P_MESH_ENABLED, enabled)
            .apply()
    }

    fun setLibp2pPublicDht(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_LIBP2P_PUBLIC_DHT, enabled)
            .apply()
    }

    fun setLibp2pBootstrapPeers(input: String) {
        prefs.edit()
            .putString(
                KEY_LIBP2P_BOOTSTRAP_PEERS,
                input.trim().ifBlank { DEFAULT_LIBP2P_BOOTSTRAP_PEERS },
            )
            .apply()
    }

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
        bandwidthMHz: Int = DEFAULT_MESH_BANDWIDTH_MHZ,
        frequencyKHz: Int = DEFAULT_MESH_FREQUENCY_KHZ,
    ) {
        prefs.edit()
            .putString(KEY_MESH_COUNTRY, normalizeMeshCountry(country))
            .putString(KEY_MESH_ID, meshId)
            .putString(KEY_MESH_PASSPHRASE, passphrase)
            .putInt(KEY_MESH_MAX_HOP, normalizeMeshMaxHop(maxHop))
            .putInt(KEY_BEACON_INTERVAL_SECONDS, normalizeBeaconIntervalSeconds(beaconIntervalSeconds))
            .putInt(KEY_MESH_BANDWIDTH_MHZ, bandwidthMHz)
            .putInt(KEY_MESH_FREQUENCY_KHZ, frequencyKHz)
            .apply()
    }

    fun getOrCreateUserIdentity(): UserIdentity {
        val existingUserUuid = getStoredUserUuid()
        val existingPrivateKey = decodeBytes(prefs.getString(KEY_USER_PRIVATE_KEY, null), 32)
        val existingPublicKey = decodeBytes(prefs.getString(KEY_USER_PUBLIC_KEY, null), 32)
        val name = getUserName()
        val userUuid = existingUserUuid ?: newUserUuid()
        val keyPair = Libp2pIdentity.deriveX25519KeyPair(appContext, userUuid.toString())
        val identity = UserIdentity(
            userUuid = userUuid.toString(),
            userIdHigh = userUuid.mostSignificantBits,
            userIdLow = userUuid.leastSignificantBits,
            name = name,
            privateKey = keyPair.first,
            publicKey = keyPair.second,
        )
        if (
            existingUserUuid != null &&
            existingPrivateKey != null &&
            existingPublicKey != null &&
            existingPrivateKey.contentEquals(identity.privateKey) &&
            existingPublicKey.contentEquals(identity.publicKey)
        ) {
            return identity
        }
        saveUserIdentity(identity)
        return identity
    }

    fun getUserName(): String = prefs.getString(KEY_USER_NAME, DEFAULT_USER_NAME) ?: DEFAULT_USER_NAME

    fun getUserMarker(): String = NodeMapMarker.normalize(prefs.getString(KEY_USER_MARKER, NodeMapMarker.DEFAULT.id))

    fun getShareLocation(): Boolean = prefs.getBoolean(KEY_SHARE_LOCATION, false)

    fun getAutoReplayReceivedVoice(): Boolean = prefs.getBoolean(KEY_AUTO_REPLAY_RECEIVED_VOICE, false)

    fun getAutoAnswerVoiceCalls(): Boolean = prefs.getBoolean(KEY_AUTO_ANSWER_VOICE_CALLS, false)

    fun getSelectedBleAddress(): String = prefs.getString(KEY_SELECTED_BLE_ADDRESS, "") ?: ""

    fun getSelectedBleLabel(): String = prefs.getString(KEY_SELECTED_BLE_LABEL, "") ?: ""

    fun getBleAutoConnect(): Boolean = prefs.getBoolean(KEY_BLE_AUTO_CONNECT, false)

    fun setSelectedBleDevice(address: String, label: String) {
        prefs.edit()
            .putString(KEY_SELECTED_BLE_ADDRESS, normalizeBleAddress(address))
            .putString(KEY_SELECTED_BLE_LABEL, label)
            .apply()
    }

    private fun normalizeBleAddress(address: String): String = address.trim().uppercase()

    fun getDeviceGeoFences(): List<DeviceGeoFence> {
        val stored = prefs.getString(KEY_DEVICE_GEOFENCES, "") ?: ""
        return stored.lineSequence()
            .mapNotNull(::decodeGeoFence)
            .distinctBy { it.key }
            .toList()
    }

    fun saveDeviceGeoFences(geoFences: List<DeviceGeoFence>) {
        val normalized = geoFences.distinctBy { it.key }
        val selectedKey = getSelectedDeviceGeoFenceKey()
        prefs.edit()
            .putString(KEY_DEVICE_GEOFENCES, normalized.joinToString("\n", transform = ::encodeGeoFence))
            .apply()
        if (selectedKey != null && normalized.none { it.key == selectedKey }) {
            setSelectedDeviceGeoFenceKey(null)
        }
    }

    fun getSelectedDeviceGeoFenceKey(): String? {
        return prefs.getString(KEY_SELECTED_DEVICE_GEOFENCE, null)?.takeIf { it.isNotBlank() }
    }

    fun setSelectedDeviceGeoFenceKey(key: String?) {
        prefs.edit()
            .putString(KEY_SELECTED_DEVICE_GEOFENCE, key?.takeIf { it.isNotBlank() })
            .apply()
    }

    fun getDashboardWidgetOrder(): List<String> {
        return prefs.getString(KEY_DASHBOARD_WIDGET_ORDER, "")?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.toList()
            .orEmpty()
    }

    fun setDashboardWidgetOrder(order: List<String>) {
        prefs.edit()
            .putString(KEY_DASHBOARD_WIDGET_ORDER, order.filter { it.isNotBlank() }.distinct().joinToString("\n"))
            .apply()
    }

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

    fun setAutoAnswerVoiceCalls(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_AUTO_ANSWER_VOICE_CALLS, enabled)
            .apply()
    }

    fun setBleAutoConnect(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_BLE_AUTO_CONNECT, enabled)
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
        val keyPair = Libp2pIdentity.deriveX25519KeyPair(
            Libp2pIdentity.rotatePrivateKeySeed(appContext),
            currentIdentity.userUuid,
        )
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

    private fun saveUserIdentity(identity: UserIdentity) {
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

    private fun encodeGeoFence(geoFence: DeviceGeoFence): String {
        val encodedName = Base64.encodeToString(geoFence.name.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return listOf(
            geoFence.idHigh.toString(),
            geoFence.idLow.toString(),
            encodedName,
            NodeMapMarker.normalize(geoFence.marker),
            geoFence.alertCondition.name,
        ).joinToString("|")
    }

    private fun decodeGeoFence(encoded: String): DeviceGeoFence? {
        val parts = encoded.split("|", limit = 5)
        if (parts.size != 5) return null
        val idHigh = parts[0].toLongOrNull() ?: return null
        val idLow = parts[1].toLongOrNull() ?: return null
        val nameBytes = runCatching { Base64.decode(parts[2], Base64.NO_WRAP) }.getOrNull() ?: return null
        val name = runCatching { nameBytes.toString(Charsets.UTF_8) }.getOrDefault("Geo fence")
        return DeviceGeoFence(
            idHigh = idHigh,
            idLow = idLow,
            name = name.ifBlank { "Geo fence" }.take(64),
            marker = NodeMapMarker.normalize(parts[3]),
            alertCondition = GeoFenceAlertCondition.fromName(parts[4]),
        )
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

private fun parseMultiaddrList(input: String?): List<String> {
    return input.orEmpty()
        .split(',', '\n', ';')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}
