package ai.edgez.edgez

import android.content.Context

private const val LAST_CONNECTION_PREFS = "edgez_connection"
private const val KEY_LAST_SUCCESSFUL_CONNECTION = "last_successful_connection"
private const val KEY_MESH_COUNTRY = "mesh_country"
private const val KEY_MESH_ID = "mesh_id"
private const val KEY_MESH_PASSPHRASE = "mesh_passphrase"
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

    fun getMeshId(): String = prefs.getString(KEY_MESH_ID, "") ?: ""

    fun getMeshPassphrase(): String = prefs.getString(KEY_MESH_PASSPHRASE, "") ?: ""

    fun getMeshCountry(): String = normalizeMeshCountry(prefs.getString(KEY_MESH_COUNTRY, "US"))

    fun setMeshCredentials(country: String, meshId: String, passphrase: String) {
        prefs.edit()
            .putString(KEY_MESH_COUNTRY, normalizeMeshCountry(country))
            .putString(KEY_MESH_ID, meshId)
            .putString(KEY_MESH_PASSPHRASE, passphrase)
            .apply()
    }

    private fun normalizeMeshCountry(country: String?): String {
        val normalized = country?.uppercase() ?: "US"
        return if (normalized in SUPPORTED_MESH_COUNTRIES) normalized else "US"
    }
}
