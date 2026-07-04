package ai.edgez.edgez

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import ai.edgez.edgez.usb.PacketMime

private const val DATABASE_NAME = "edgez_local.db"
private const val DATABASE_VERSION = 4
private const val TABLE_USERS = "halow_users"
private const val TABLE_MESSAGES = "conversation_messages"
private const val TABLE_SENSOR_DATA = "sensor_data"
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
                user_uuid TEXT PRIMARY KEY,
                node_num INTEGER NOT NULL,
                short_name TEXT NOT NULL,
                long_name TEXT NOT NULL,
                route TEXT NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                public_key BLOB NOT NULL,
                latitude REAL,
                longitude REAL,
                location_timestamp_ms INTEGER NOT NULL DEFAULT 0,
                marker TEXT NOT NULL DEFAULT 'default',
                device_type INTEGER NOT NULL DEFAULT 0,
                geo_fence_id_high INTEGER NOT NULL DEFAULT 0,
                geo_fence_id_low INTEGER NOT NULL DEFAULT 0,
                geo_fence_name TEXT NOT NULL DEFAULT '',
                geo_fence_marker TEXT NOT NULL DEFAULT 'default',
                geo_fence_alert_condition INTEGER NOT NULL DEFAULT 0,
                sleeping INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                peer_user_uuid TEXT NOT NULL,
                text TEXT NOT NULL,
                mine INTEGER NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                status TEXT NOT NULL,
                mime INTEGER NOT NULL DEFAULT 1,
                audio_path TEXT NOT NULL DEFAULT '',
                duration_ms INTEGER NOT NULL DEFAULT 0,
                message_uuid TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(peer_user_uuid) REFERENCES $TABLE_USERS(user_uuid) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_messages_peer_time ON $TABLE_MESSAGES(peer_user_uuid, timestamp_ms)")
        createSensorDataTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN mime INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN audio_path TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE_USERS ADD COLUMN marker TEXT NOT NULL DEFAULT 'default'")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE $TABLE_USERS ADD COLUMN device_type INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_USERS ADD COLUMN geo_fence_id_high INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_USERS ADD COLUMN geo_fence_id_low INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_USERS ADD COLUMN geo_fence_name TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $TABLE_USERS ADD COLUMN geo_fence_marker TEXT NOT NULL DEFAULT 'default'")
            db.execSQL("ALTER TABLE $TABLE_USERS ADD COLUMN geo_fence_alert_condition INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_USERS ADD COLUMN sleeping INTEGER NOT NULL DEFAULT 0")
            createSensorDataTable(db)
        }
    }

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
                "marker",
                "device_type",
                "geo_fence_id_high",
                "geo_fence_id_low",
                "geo_fence_name",
                "geo_fence_marker",
                "geo_fence_alert_condition",
                "sleeping",
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
            val markerIndex = cursor.getColumnIndexOrThrow("marker")
            val deviceTypeIndex = cursor.getColumnIndexOrThrow("device_type")
            val geoFenceIdHighIndex = cursor.getColumnIndexOrThrow("geo_fence_id_high")
            val geoFenceIdLowIndex = cursor.getColumnIndexOrThrow("geo_fence_id_low")
            val geoFenceNameIndex = cursor.getColumnIndexOrThrow("geo_fence_name")
            val geoFenceMarkerIndex = cursor.getColumnIndexOrThrow("geo_fence_marker")
            val geoFenceAlertConditionIndex = cursor.getColumnIndexOrThrow("geo_fence_alert_condition")
            val sleepingIndex = cursor.getColumnIndexOrThrow("sleeping")
            while (cursor.moveToNext()) {
                val userUuid = cursor.getString(userUuidIndex)
                val geoFence = cursor.toDeviceGeoFence(
                    geoFenceIdHighIndex,
                    geoFenceIdLowIndex,
                    geoFenceNameIndex,
                    geoFenceMarkerIndex,
                    geoFenceAlertConditionIndex,
                )
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
                    marker = NodeMapMarker.normalize(cursor.getString(markerIndex)),
                    deviceType = EdgeZDeviceType.fromProtoValue(cursor.getInt(deviceTypeIndex)),
                    geoFence = geoFence,
                    sleeping = cursor.getInt(sleepingIndex) != 0,
                )
                Log.d(
                    TAG_USERS,
                    "loaded user node=${user.nodeId} uuid=${user.userUuid} name=${user.displayName} " +
                        "lastSeen=${user.lastSeenMs} lat=${user.latitude} lon=${user.longitude} locTs=${user.locationTimestampMs} marker=${user.marker} " +
                        "deviceType=${user.deviceType.label} geoFence=${user.geoFence?.name ?: "none"} sleeping=${user.sleeping}",
                )
                users[user.nodeNum] = user
            }
        }
        return users
    }

    fun getMessages(): Map<String, List<ConversationEntry>> {
        val messages = linkedMapOf<String, MutableList<ConversationEntry>>()
        readableDatabase.query(
            TABLE_MESSAGES,
            arrayOf(
                "peer_user_uuid",
                "text",
                "mine",
                "timestamp_ms",
                "status",
                "mime",
                "audio_path",
                "duration_ms",
                "message_uuid",
            ),
            null,
            null,
            null,
            null,
            "timestamp_ms ASC, id ASC",
        ).use { cursor ->
            val peerIndex = cursor.getColumnIndexOrThrow("peer_user_uuid")
            val textIndex = cursor.getColumnIndexOrThrow("text")
            val mineIndex = cursor.getColumnIndexOrThrow("mine")
            val timestampIndex = cursor.getColumnIndexOrThrow("timestamp_ms")
            val statusIndex = cursor.getColumnIndexOrThrow("status")
            val mimeIndex = cursor.getColumnIndexOrThrow("mime")
            val audioPathIndex = cursor.getColumnIndexOrThrow("audio_path")
            val durationIndex = cursor.getColumnIndexOrThrow("duration_ms")
            val messageUuidIndex = cursor.getColumnIndexOrThrow("message_uuid")
            while (cursor.moveToNext()) {
                val peerUserUuid = cursor.getString(peerIndex)
                val messageUuid = cursor.getString(messageUuidIndex)
                val entry = ConversationEntry(
                    text = cursor.getString(textIndex),
                    mine = cursor.getInt(mineIndex) != 0,
                    timestampMs = cursor.getLong(timestampIndex),
                    status = cursor.getString(statusIndex),
                    mime = PacketMime.fromWireValue(cursor.getInt(mimeIndex)),
                    audioPath = cursor.getString(audioPathIndex),
                    durationMs = cursor.getLong(durationIndex),
                    messageUuid = messageUuid,
                )
                messages.getOrPut(peerUserUuid) { mutableListOf() }.add(entry)
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
                put("marker", NodeMapMarker.normalize(user.marker))
                put("device_type", user.deviceType.protoValue)
                put("geo_fence_id_high", user.geoFence?.idHigh ?: 0L)
                put("geo_fence_id_low", user.geoFence?.idLow ?: 0L)
                put("geo_fence_name", user.geoFence?.name.orEmpty())
                put("geo_fence_marker", NodeMapMarker.normalize(user.geoFence?.marker ?: NodeMapMarker.DEFAULT.id))
                put("geo_fence_alert_condition", user.geoFence?.alertCondition?.protoValue ?: 0)
                put("sleeping", if (user.sleeping) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        Log.d(
            TAG_USERS,
            "upsert user rowId=$rowId node=${user.nodeId} uuid=${user.userUuid} name=${user.displayName} " +
                "lastSeen=${user.lastSeenMs} lat=${user.latitude} lon=${user.longitude} locTs=${user.locationTimestampMs} marker=${user.marker} " +
                "deviceType=${user.deviceType.label} geoFence=${user.geoFence?.name ?: "none"} sleeping=${user.sleeping}",
        )
    }

    fun deleteUser(userUuid: String) {
        writableDatabase.delete(TABLE_SENSOR_DATA, "peer_user_uuid = ?", arrayOf(userUuid))
        writableDatabase.delete(TABLE_MESSAGES, "peer_user_uuid = ?", arrayOf(userUuid))
        writableDatabase.delete(TABLE_USERS, "user_uuid = ?", arrayOf(userUuid))
    }

    fun insertSensorData(peerUserUuid: String, nodeNum: Long, timestampMs: Long, sensorData: EdgeZSensorData) {
        val rowId = writableDatabase.insert(
            TABLE_SENSOR_DATA,
            null,
            ContentValues().apply {
                put("peer_user_uuid", peerUserUuid)
                put("node_num", nodeNum)
                put("timestamp_ms", timestampMs)
                putNullableDouble("latitude", sensorData.latitude)
                putNullableDouble("longitude", sensorData.longitude)
                putNullableDouble("altitude", sensorData.altitude)
                putNullableDouble("temperature", sensorData.temperature)
                putNullableDouble("humidity", sensorData.humidity)
                putNullableDouble("pressure", sensorData.pressure)
            },
        )
        Log.d(
            TAG_USERS,
            "insert sensor rowId=$rowId peer=$peerUserUuid node=0x%012x ts=$timestampMs temp=${sensorData.temperature} humidity=${sensorData.humidity} pressure=${sensorData.pressure} lat=${sensorData.latitude} lon=${sensorData.longitude} alt=${sensorData.altitude}"
                .format(nodeNum),
        )
    }

    fun insertMessage(peerUserUuid: String, entry: ConversationEntry) {
        writableDatabase.insert(
            TABLE_MESSAGES,
            null,
            ContentValues().apply {
                put("peer_user_uuid", peerUserUuid)
                put("text", entry.text)
                put("mine", if (entry.mine) 1 else 0)
                put("timestamp_ms", entry.timestampMs)
                put("status", entry.status)
                put("mime", entry.mime.wireValue)
                put("audio_path", entry.audioPath)
                put("duration_ms", entry.durationMs)
                put("message_uuid", entry.messageUuid)
            },
        )
    }

    fun updateMessageStatus(
        peerUserUuid: String,
        timestampMs: Long,
        audioPath: String,
        status: String,
    ) {
        writableDatabase.update(
            TABLE_MESSAGES,
            ContentValues().apply {
                put("status", status)
            },
            "peer_user_uuid = ? AND timestamp_ms = ? AND audio_path = ?",
            arrayOf(peerUserUuid, timestampMs.toString(), audioPath),
        )
    }

    fun updateMessageStatusByUuid(peerUserUuid: String, messageUuid: String, status: String) {
        if (messageUuid.isBlank()) return
        writableDatabase.update(
            TABLE_MESSAGES,
            ContentValues().apply {
                put("status", status)
            },
            "peer_user_uuid = ? AND message_uuid = ?",
            arrayOf(peerUserUuid, messageUuid),
        )
    }

    private fun userIdLowFromUuid(userUuid: String): Long {
        val parts = userUuid.split('-')
        if (parts.size != 5) return 0L
        val lowHex = parts[3] + parts[4]
        if (lowHex.length != 16) return 0L
        return lowHex.toULongOrNull(16)?.toLong() ?: 0L
    }

    private fun createSensorDataTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SENSOR_DATA (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                peer_user_uuid TEXT NOT NULL,
                node_num INTEGER NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                latitude REAL,
                longitude REAL,
                altitude REAL,
                temperature REAL,
                humidity REAL,
                pressure REAL,
                FOREIGN KEY(peer_user_uuid) REFERENCES $TABLE_USERS(user_uuid) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sensor_peer_time ON $TABLE_SENSOR_DATA(peer_user_uuid, timestamp_ms)")
    }

}

private fun android.database.Cursor.getNullableDouble(columnIndex: Int): Double? {
    return if (isNull(columnIndex)) null else getDouble(columnIndex)
}

private fun android.database.Cursor.toDeviceGeoFence(
    idHighIndex: Int,
    idLowIndex: Int,
    nameIndex: Int,
    markerIndex: Int,
    alertConditionIndex: Int,
): DeviceGeoFence? {
    val idHigh = getLong(idHighIndex)
    val idLow = getLong(idLowIndex)
    val name = getString(nameIndex).orEmpty()
    if (idHigh == 0L && idLow == 0L && name.isBlank()) return null
    return DeviceGeoFence(
        idHigh = idHigh,
        idLow = idLow,
        name = name.ifBlank { "Geo fence" },
        marker = NodeMapMarker.normalize(getString(markerIndex)),
        alertCondition = GeoFenceAlertCondition.fromProtoValue(getInt(alertConditionIndex)),
    )
}

private fun ContentValues.putNullableDouble(key: String, value: Double?) {
    if (value == null) {
        putNull(key)
    } else {
        put(key, value)
    }
}
