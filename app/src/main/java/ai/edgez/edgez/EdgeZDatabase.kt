package ai.edgez.edgez

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

private const val DATABASE_NAME = "edgez_local.db"
private const val DATABASE_VERSION = 1
private const val TABLE_USERS = "halow_users"
private const val TABLE_MESSAGES = "conversation_messages"
private const val TAG_USERS = "EdgeZUsers"

class EdgeZDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_USERS (
                node_num INTEGER PRIMARY KEY,
                user_uuid TEXT NOT NULL DEFAULT '',
                short_name TEXT NOT NULL,
                long_name TEXT NOT NULL,
                route TEXT NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                public_key BLOB NOT NULL,
                latitude REAL,
                longitude REAL,
                location_timestamp_ms INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                peer_node_num INTEGER NOT NULL,
                text TEXT NOT NULL,
                mine INTEGER NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                status TEXT NOT NULL,
                FOREIGN KEY(peer_node_num) REFERENCES $TABLE_USERS(node_num) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_messages_peer_time ON $TABLE_MESSAGES(peer_node_num, timestamp_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.setVersion(newVersion)
    }

    fun getUsers(): Map<Long, HaLowUser> {
        val users = linkedMapOf<Long, HaLowUser>()
        readableDatabase.query(
            TABLE_USERS,
            arrayOf(
                "node_num",
                "user_uuid",
                "short_name",
                "long_name",
                "route",
                "last_seen_ms",
                "public_key",
                "latitude",
                "longitude",
                "location_timestamp_ms",
            ),
            null,
            null,
            null,
            null,
            "last_seen_ms DESC",
        ).use { cursor ->
            val nodeNumIndex = cursor.getColumnIndexOrThrow("node_num")
            val userUuidIndex = cursor.getColumnIndexOrThrow("user_uuid")
            val shortNameIndex = cursor.getColumnIndexOrThrow("short_name")
            val longNameIndex = cursor.getColumnIndexOrThrow("long_name")
            val routeIndex = cursor.getColumnIndexOrThrow("route")
            val lastSeenIndex = cursor.getColumnIndexOrThrow("last_seen_ms")
            val publicKeyIndex = cursor.getColumnIndexOrThrow("public_key")
            val latitudeIndex = cursor.getColumnIndexOrThrow("latitude")
            val longitudeIndex = cursor.getColumnIndexOrThrow("longitude")
            val locationTimestampIndex = cursor.getColumnIndexOrThrow("location_timestamp_ms")
            while (cursor.moveToNext()) {
                val userUuid = cursor.getString(userUuidIndex)
                val user = HaLowUser(
                    nodeNum = cursor.getLong(nodeNumIndex),
                    userId = userIdLowFromUuid(userUuid),
                    userUuid = userUuid,
                    shortName = cursor.getString(shortNameIndex),
                    longName = cursor.getString(longNameIndex),
                    route = cursor.getString(routeIndex),
                    lastSeenMs = cursor.getLong(lastSeenIndex),
                    publicKey = cursor.getBlob(publicKeyIndex) ?: ByteArray(0),
                    latitude = cursor.getNullableDouble(latitudeIndex),
                    longitude = cursor.getNullableDouble(longitudeIndex),
                    locationTimestampMs = cursor.getLong(locationTimestampIndex),
                )
                Log.d(
                    TAG_USERS,
                    "loaded user node=${user.nodeId} uuid=${user.userUuid} name=${user.displayName} " +
                        "lastSeen=${user.lastSeenMs} lat=${user.latitude} lon=${user.longitude} locTs=${user.locationTimestampMs}",
                )
                users[user.nodeNum] = user
            }
        }
        return users
    }

    fun getMessages(): Map<Long, List<ConversationEntry>> {
        val messages = linkedMapOf<Long, MutableList<ConversationEntry>>()
        readableDatabase.query(
            TABLE_MESSAGES,
            arrayOf("peer_node_num", "text", "mine", "timestamp_ms", "status"),
            null,
            null,
            null,
            null,
            "timestamp_ms ASC, id ASC",
        ).use { cursor ->
            val peerIndex = cursor.getColumnIndexOrThrow("peer_node_num")
            val textIndex = cursor.getColumnIndexOrThrow("text")
            val mineIndex = cursor.getColumnIndexOrThrow("mine")
            val timestampIndex = cursor.getColumnIndexOrThrow("timestamp_ms")
            val statusIndex = cursor.getColumnIndexOrThrow("status")
            while (cursor.moveToNext()) {
                val peerNodeNum = cursor.getLong(peerIndex)
                val entry = ConversationEntry(
                    text = cursor.getString(textIndex),
                    mine = cursor.getInt(mineIndex) != 0,
                    timestampMs = cursor.getLong(timestampIndex),
                    status = cursor.getString(statusIndex),
                )
                messages.getOrPut(peerNodeNum) { mutableListOf() }.add(entry)
            }
        }
        return messages
    }

    fun upsertUser(user: HaLowUser) {
        val rowId = writableDatabase.insertWithOnConflict(
            TABLE_USERS,
            null,
            ContentValues().apply {
                put("node_num", user.nodeNum)
                put("user_uuid", user.userUuid)
                put("short_name", user.shortName)
                put("long_name", user.longName)
                put("route", user.route)
                put("last_seen_ms", user.lastSeenMs)
                put("public_key", user.publicKey)
                putNullableDouble("latitude", user.latitude)
                putNullableDouble("longitude", user.longitude)
                put("location_timestamp_ms", user.locationTimestampMs)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        Log.d(
            TAG_USERS,
            "upsert user rowId=$rowId node=${user.nodeId} uuid=${user.userUuid} name=${user.displayName} " +
                "lastSeen=${user.lastSeenMs} lat=${user.latitude} lon=${user.longitude} locTs=${user.locationTimestampMs}",
        )
    }

    fun deleteUser(nodeNum: Long) {
        writableDatabase.delete(TABLE_MESSAGES, "peer_node_num = ?", arrayOf(nodeNum.toString()))
        writableDatabase.delete(TABLE_USERS, "node_num = ?", arrayOf(nodeNum.toString()))
    }

    fun insertMessage(peerNodeNum: Long, entry: ConversationEntry) {
        writableDatabase.insert(
            TABLE_MESSAGES,
            null,
            ContentValues().apply {
                put("peer_node_num", peerNodeNum)
                put("text", entry.text)
                put("mine", if (entry.mine) 1 else 0)
                put("timestamp_ms", entry.timestampMs)
                put("status", entry.status)
            },
        )
    }

    private fun userIdLowFromUuid(userUuid: String): Long {
        val parts = userUuid.split('-')
        if (parts.size != 5) return 0L
        val lowHex = parts[3] + parts[4]
        if (lowHex.length != 16) return 0L
        return lowHex.toULongOrNull(16)?.toLong() ?: 0L
    }

}

private fun android.database.Cursor.getNullableDouble(columnIndex: Int): Double? {
    return if (isNull(columnIndex)) null else getDouble(columnIndex)
}

private fun ContentValues.putNullableDouble(key: String, value: Double?) {
    if (value == null) {
        putNull(key)
    } else {
        put(key, value)
    }
}
