package ai.edgez.edgez

import android.content.Context

private const val LAST_CONNECTION_PREFS = "edgez_connection"
private const val KEY_LAST_SUCCESSFUL_CONNECTION = "last_successful_connection"

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
}
