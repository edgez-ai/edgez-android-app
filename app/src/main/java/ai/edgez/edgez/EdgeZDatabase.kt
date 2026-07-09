package ai.edgez.edgez

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import ai.edgez.edgez.usb.PacketMime
import java.util.UUID

private const val DATABASE_NAME = "edgez_local.db"
private const val DATABASE_VERSION = 12
private const val TABLE_USERS = "halow_users"
private const val TABLE_MESSAGES = "conversation_messages"
private const val TABLE_SENSOR_DATA = "sensor_data"
private const val TABLE_GEO_FENCES = "device_geo_fences"
private const val TAG_USERS = "EdgeZUsers"
private const val DEFAULT_MESSAGE_PAGE_SIZE = 50

data class SensorSample(
    val timestampMs: Long,
    val data: EdgeZSensorData,
)

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
                geo_fence_uuid TEXT NOT NULL DEFAULT '',
                geo_index INTEGER NOT NULL DEFAULT 0,
                geo_fence_name TEXT NOT NULL DEFAULT '',
                geo_fence_marker TEXT NOT NULL DEFAULT 'default',
                geo_fence_alert_condition INTEGER NOT NULL DEFAULT 0,
                sleeping INTEGER NOT NULL DEFAULT 0,
                dashboard_show_on INTEGER NOT NULL DEFAULT 0,
                dashboard_widget TEXT NOT NULL DEFAULT 'TEMP_HUMIDITY',
                dashboard_range TEXT NOT NULL DEFAULT 'LATEST'
            )
            """.trimIndent(),
        )
        createGeoFenceTable(db)
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
        if (oldVersion < 5) {
            createSensorDataTable(db)
        }
        if (oldVersion < 6) {
            addColumnIfMissing(db, TABLE_USERS, "geo_fence_uuid", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, TABLE_USERS, "geo_index", "INTEGER NOT NULL DEFAULT 0")
            migrateGeoFenceTableToUuid(db)
            migrateUserGeoFenceUuids(db)
            migrateUserGeoFences(db)
        }
        if (oldVersion < 7) {
            addColumnIfMissing(db, TABLE_SENSOR_DATA, "vibration_average", "REAL")
        }
        if (oldVersion < 8) {
            addColumnIfMissing(db, TABLE_USERS, "dashboard_show_on", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, TABLE_USERS, "dashboard_range", "TEXT NOT NULL DEFAULT 'LATEST'")
        }
        if (oldVersion < 9) {
            addColumnIfMissing(db, TABLE_USERS, "dashboard_widget", "TEXT NOT NULL DEFAULT 'TEMP_HUMIDITY'")
            db.execSQL(
                """
                UPDATE $TABLE_USERS
                SET dashboard_widget = CASE
                    WHEN dashboard_range = 'LATEST' THEN 'TEMP_HUMIDITY'
                    ELSE 'TIME_SERIES'
                END
                """.trimIndent(),
            )
        }
        if (oldVersion < 11) {
            addColumnIfMissing(db, TABLE_SENSOR_DATA, "sensor_data_length", "INTEGER")
        }
        if (oldVersion < 12) {
            addColumnIfMissing(db, TABLE_SENSOR_DATA, "binary_image_path", "TEXT")
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.setVersion(newVersion)
    }

    fun getUsers(): Map<Long, HaLowUser> {
        val users = linkedMapOf<Long, HaLowUser>()
        val geoFences = getGeoFenceMap()
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
                "geo_fence_uuid",
                "geo_index",
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
            val geoFenceUuidIndex = cursor.getColumnIndexOrThrow("geo_fence_uuid")
            val geoIndexIndex = cursor.getColumnIndexOrThrow("geo_index")
            val geoFenceNameIndex = cursor.getColumnIndexOrThrow("geo_fence_name")
            val geoFenceMarkerIndex = cursor.getColumnIndexOrThrow("geo_fence_marker")
            val geoFenceAlertConditionIndex = cursor.getColumnIndexOrThrow("geo_fence_alert_condition")
            val sleepingIndex = cursor.getColumnIndexOrThrow("sleeping")
            while (cursor.moveToNext()) {
                val userUuid = cursor.getString(userUuidIndex)
                val geoFenceIdHigh = cursor.getLong(geoFenceIdHighIndex)
                val geoFenceIdLow = cursor.getLong(geoFenceIdLowIndex)
                val geoFenceUuid = cursor.getString(geoFenceUuidIndex).orEmpty()
                    .ifBlank { DeviceGeoFence.keyFor(geoFenceIdHigh, geoFenceIdLow).takeIf { geoFenceIdHigh != 0L || geoFenceIdLow != 0L }.orEmpty() }
                val geoFence = geoFences[geoFenceUuid]
                    ?: cursor.toDeviceGeoFence(
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
                    geoIndex = cursor.getInt(geoIndexIndex),
                    sleeping = cursor.getInt(sleepingIndex) != 0,
                )
                Log.d(
                    TAG_USERS,
                    "loaded user node=${user.nodeId} uuid=${user.userUuid} name=${user.displayName} " +
                        "lastSeen=${user.lastSeenMs} lat=${user.latitude} lon=${user.longitude} locTs=${user.locationTimestampMs} marker=${user.marker} " +
                        "deviceType=${user.deviceType.label} geoFence=${user.geoFence?.name ?: "none"} geoIndex=${user.geoIndex} sleeping=${user.sleeping}",
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

    fun getMessages(
        peerUserUuid: String,
        beforeTimestampMs: Long? = null,
        limit: Int = DEFAULT_MESSAGE_PAGE_SIZE,
    ): List<ConversationEntry> {
        val messages = mutableListOf<ConversationEntry>()
        val selection = if (beforeTimestampMs == null) {
            "peer_user_uuid = ?"
        } else {
            "peer_user_uuid = ? AND timestamp_ms < ?"
        }
        val args = if (beforeTimestampMs == null) {
            arrayOf(peerUserUuid)
        } else {
            arrayOf(peerUserUuid, beforeTimestampMs.toString())
        }
        readableDatabase.query(
            TABLE_MESSAGES,
            arrayOf(
                "text",
                "mine",
                "timestamp_ms",
                "status",
                "mime",
                "audio_path",
                "duration_ms",
                "message_uuid",
            ),
            selection,
            args,
            null,
            null,
            "timestamp_ms DESC, id DESC",
            limit.coerceIn(1, 200).toString(),
        ).use { cursor ->
            val textIndex = cursor.getColumnIndexOrThrow("text")
            val mineIndex = cursor.getColumnIndexOrThrow("mine")
            val timestampIndex = cursor.getColumnIndexOrThrow("timestamp_ms")
            val statusIndex = cursor.getColumnIndexOrThrow("status")
            val mimeIndex = cursor.getColumnIndexOrThrow("mime")
            val audioPathIndex = cursor.getColumnIndexOrThrow("audio_path")
            val durationIndex = cursor.getColumnIndexOrThrow("duration_ms")
            val messageUuidIndex = cursor.getColumnIndexOrThrow("message_uuid")
            while (cursor.moveToNext()) {
                messages += ConversationEntry(
                    text = cursor.getString(textIndex),
                    mine = cursor.getInt(mineIndex) != 0,
                    timestampMs = cursor.getLong(timestampIndex),
                    status = cursor.getString(statusIndex),
                    mime = PacketMime.fromWireValue(cursor.getInt(mimeIndex)),
                    audioPath = cursor.getString(audioPathIndex),
                    durationMs = cursor.getLong(durationIndex),
                    messageUuid = cursor.getString(messageUuidIndex),
                )
            }
        }
        return messages.asReversed()
    }

    fun getDashboardDeviceDisplays(): Map<String, DashboardDeviceDisplay> {
        val displays = linkedMapOf<String, DashboardDeviceDisplay>()
        readableDatabase.query(
            TABLE_USERS,
            arrayOf("user_uuid", "dashboard_show_on", "dashboard_widget", "dashboard_range"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            val userUuidIndex = cursor.getColumnIndexOrThrow("user_uuid")
            val showIndex = cursor.getColumnIndexOrThrow("dashboard_show_on")
            val widgetIndex = cursor.getColumnIndexOrThrow("dashboard_widget")
            val rangeIndex = cursor.getColumnIndexOrThrow("dashboard_range")
            while (cursor.moveToNext()) {
                val deviceKey = cursor.getString(userUuidIndex)
                displays[deviceKey] = DashboardDeviceDisplay(
                    deviceKey = deviceKey,
                    showOnDashboard = cursor.getInt(showIndex) != 0,
                    widget = DashboardDeviceWidget.fromName(cursor.getString(widgetIndex)),
                    range = DashboardDeviceRange.fromName(cursor.getString(rangeIndex)),
                )
            }
        }
        return displays
    }

    fun setDashboardDeviceDisplay(display: DashboardDeviceDisplay) {
        writableDatabase.update(
            TABLE_USERS,
            ContentValues().apply {
                put("dashboard_show_on", if (display.showOnDashboard) 1 else 0)
                put("dashboard_widget", display.widget.name)
                put("dashboard_range", display.range.name)
            },
            "user_uuid = ?",
            arrayOf(display.deviceKey),
        )
    }

    fun getDashboardDeviceDisplay(deviceKey: String): DashboardDeviceDisplay {
        readableDatabase.query(
            TABLE_USERS,
            arrayOf("dashboard_show_on", "dashboard_widget", "dashboard_range"),
            "user_uuid = ?",
            arrayOf(deviceKey),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return DashboardDeviceDisplay(
                    deviceKey = deviceKey,
                    showOnDashboard = cursor.getInt(cursor.getColumnIndexOrThrow("dashboard_show_on")) != 0,
                    widget = DashboardDeviceWidget.fromName(cursor.getString(cursor.getColumnIndexOrThrow("dashboard_widget"))),
                    range = DashboardDeviceRange.fromName(cursor.getString(cursor.getColumnIndexOrThrow("dashboard_range"))),
                )
            }
        }
        return DashboardDeviceDisplay(deviceKey)
    }

    fun upsertUser(user: HaLowUser) {
        user.geoFence?.let(::insertGeoFenceIfMissing)
        val dashboardDisplay = getDashboardDeviceDisplay(user.userUuid)
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
                put("geo_fence_uuid", user.geoFence?.key.orEmpty())
                put("geo_index", user.geoIndex)
                put("geo_fence_name", "")
                put("geo_fence_marker", NodeMapMarker.DEFAULT.id)
                put("geo_fence_alert_condition", 0)
                put("sleeping", if (user.sleeping) 1 else 0)
                put("dashboard_show_on", if (dashboardDisplay.showOnDashboard) 1 else 0)
                put("dashboard_widget", dashboardDisplay.widget.name)
                put("dashboard_range", dashboardDisplay.range.name)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        Log.d(
            TAG_USERS,
            "upsert user rowId=$rowId node=${user.nodeId} uuid=${user.userUuid} name=${user.displayName} " +
                "lastSeen=${user.lastSeenMs} lat=${user.latitude} lon=${user.longitude} locTs=${user.locationTimestampMs} marker=${user.marker} " +
                "deviceType=${user.deviceType.label} geoFence=${user.geoFence?.name ?: "none"} geoIndex=${user.geoIndex} sleeping=${user.sleeping}",
        )
    }

    fun getGeoFences(): List<DeviceGeoFence> {
        return getGeoFenceMap().values
            .sortedWith(compareBy<DeviceGeoFence> { it.name.lowercase() }.thenBy { it.key })
    }

    fun upsertGeoFence(geoFence: DeviceGeoFence) {
        if (geoFence.isEmptyId) return
        writableDatabase.insertWithOnConflict(
            TABLE_GEO_FENCES,
            null,
            geoFence.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun insertGeoFenceIfMissing(geoFence: DeviceGeoFence) {
        if (geoFence.isEmptyId) return
        writableDatabase.insertWithOnConflict(
            TABLE_GEO_FENCES,
            null,
            geoFence.toContentValues(),
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun upsertGeoFences(geoFences: List<DeviceGeoFence>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            geoFences.distinctBy { it.key }.forEach { geoFence ->
                if (!geoFence.isEmptyId) {
                    db.insertWithOnConflict(
                        TABLE_GEO_FENCES,
                        null,
                        geoFence.toContentValues(),
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteGeoFence(geoFence: DeviceGeoFence) {
        writableDatabase.delete(
            TABLE_GEO_FENCES,
            "geo_fence_uuid = ?",
            arrayOf(geoFence.key),
        )
        writableDatabase.update(
            TABLE_USERS,
            ContentValues().apply {
                put("geo_fence_id_high", 0L)
                put("geo_fence_id_low", 0L)
                put("geo_fence_uuid", "")
                put("geo_index", 0)
                put("geo_fence_name", "")
                put("geo_fence_marker", NodeMapMarker.DEFAULT.id)
                put("geo_fence_alert_condition", 0)
            },
            "geo_fence_uuid = ? OR (geo_fence_id_high = ? AND geo_fence_id_low = ?)",
            arrayOf(geoFence.key, geoFence.idHigh.toString(), geoFence.idLow.toString()),
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
                putNullableDouble("vibration_average", sensorData.vibrationAverage)
                if (sensorData.binaryLengthBytes == null) {
                    putNull("sensor_data_length")
                } else {
                    put("sensor_data_length", sensorData.binaryLengthBytes)
                }
                put("binary_image_path", sensorData.binaryImagePath?.takeIf { it.isNotBlank() })
            },
        )
        Log.d(
            TAG_USERS,
            "insert sensor rowId=$rowId peer=$peerUserUuid node=0x%012x ts=$timestampMs temp=${sensorData.temperature} humidity=${sensorData.humidity} pressure=${sensorData.pressure} vibration=${sensorData.vibrationAverage} lat=${sensorData.latitude} lon=${sensorData.longitude} alt=${sensorData.altitude}"
                .format(nodeNum),
        )
    }

    fun getSensorData(peerUserUuid: String, limit: Int = 120): List<SensorSample> {
        val samples = mutableListOf<SensorSample>()
        readableDatabase.query(
            TABLE_SENSOR_DATA,
            arrayOf(
                "timestamp_ms",
                "latitude",
                "longitude",
                "altitude",
                "temperature",
                "humidity",
                "pressure",
                "vibration_average",
                "sensor_data_length",
                "binary_image_path",
            ),
            "peer_user_uuid = ?",
            arrayOf(peerUserUuid),
            null,
            null,
            "timestamp_ms DESC",
            limit.coerceIn(1, 500).toString(),
        ).use { cursor ->
            val timestampIndex = cursor.getColumnIndexOrThrow("timestamp_ms")
            val latitudeIndex = cursor.getColumnIndexOrThrow("latitude")
            val longitudeIndex = cursor.getColumnIndexOrThrow("longitude")
            val altitudeIndex = cursor.getColumnIndexOrThrow("altitude")
            val temperatureIndex = cursor.getColumnIndexOrThrow("temperature")
            val humidityIndex = cursor.getColumnIndexOrThrow("humidity")
            val pressureIndex = cursor.getColumnIndexOrThrow("pressure")
            val vibrationAverageIndex = cursor.getColumnIndexOrThrow("vibration_average")
            val sensorDataLengthIndex = cursor.getColumnIndexOrThrow("sensor_data_length")
            val binaryImagePathIndex = cursor.getColumnIndexOrThrow("binary_image_path")
            while (cursor.moveToNext()) {
                val data = EdgeZSensorData(
                    latitude = cursor.getNullableDouble(latitudeIndex),
                    longitude = cursor.getNullableDouble(longitudeIndex),
                    altitude = cursor.getNullableDouble(altitudeIndex),
                    temperature = cursor.getNullableDouble(temperatureIndex),
                    humidity = cursor.getNullableDouble(humidityIndex),
                    pressure = cursor.getNullableDouble(pressureIndex),
                    vibrationAverage = cursor.getNullableDouble(vibrationAverageIndex),
                    binaryLengthBytes = cursor.getInt(sensorDataLengthIndex).takeIf { !cursor.isNull(sensorDataLengthIndex) },
                    binaryImagePath = cursor.getString(binaryImagePathIndex).orEmpty().ifBlank { null },
                )
                samples += SensorSample(
                    timestampMs = cursor.getLong(timestampIndex),
                    data = data,
                )
            }
        }
        return samples.asReversed()
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
                vibration_average REAL,
                sensor_data_length INTEGER,
                binary_image_path TEXT,
                FOREIGN KEY(peer_user_uuid) REFERENCES $TABLE_USERS(user_uuid) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sensor_peer_time ON $TABLE_SENSOR_DATA(peer_user_uuid, timestamp_ms)")
    }

    private fun createGeoFenceTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_GEO_FENCES (
                geo_fence_uuid TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                marker TEXT NOT NULL DEFAULT 'default',
                alert_condition INTEGER NOT NULL DEFAULT 0,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun migrateUserGeoFences(db: SQLiteDatabase) {
        db.query(
            TABLE_USERS,
            arrayOf(
                "geo_fence_id_high",
                "geo_fence_id_low",
                "geo_fence_name",
                "geo_fence_marker",
                "geo_fence_alert_condition",
            ),
            "geo_fence_id_high != 0 OR geo_fence_id_low != 0",
            null,
            null,
            null,
            null,
        ).use { cursor ->
            val idHighIndex = cursor.getColumnIndexOrThrow("geo_fence_id_high")
            val idLowIndex = cursor.getColumnIndexOrThrow("geo_fence_id_low")
            val nameIndex = cursor.getColumnIndexOrThrow("geo_fence_name")
            val markerIndex = cursor.getColumnIndexOrThrow("geo_fence_marker")
            val alertConditionIndex = cursor.getColumnIndexOrThrow("geo_fence_alert_condition")
            while (cursor.moveToNext()) {
                val geoFence = DeviceGeoFence(
                    idHigh = cursor.getLong(idHighIndex),
                    idLow = cursor.getLong(idLowIndex),
                    name = cursor.getString(nameIndex).orEmpty().ifBlank { "Geo fence" }.take(64),
                    marker = NodeMapMarker.normalize(cursor.getString(markerIndex)),
                    alertCondition = GeoFenceAlertCondition.fromProtoValue(cursor.getInt(alertConditionIndex)),
                )
                if (!geoFence.isEmptyId) {
                    db.insertWithOnConflict(TABLE_GEO_FENCES, null, geoFence.toContentValues(), SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
        }
    }

    private fun getGeoFenceMap(): Map<String, DeviceGeoFence> {
        val geoFences = linkedMapOf<String, DeviceGeoFence>()
        readableDatabase.query(
            TABLE_GEO_FENCES,
            arrayOf("geo_fence_uuid", "name", "marker", "alert_condition"),
            null,
            null,
            null,
            null,
            "name COLLATE NOCASE ASC",
        ).use { cursor ->
            val uuidIndex = cursor.getColumnIndexOrThrow("geo_fence_uuid")
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val markerIndex = cursor.getColumnIndexOrThrow("marker")
            val alertConditionIndex = cursor.getColumnIndexOrThrow("alert_condition")
            while (cursor.moveToNext()) {
                val uuid = runCatching { UUID.fromString(cursor.getString(uuidIndex)) }.getOrNull() ?: continue
                val geoFence = DeviceGeoFence(
                    idHigh = uuid.mostSignificantBits,
                    idLow = uuid.leastSignificantBits,
                    name = cursor.getString(nameIndex).orEmpty().ifBlank { "Geo fence" }.take(64),
                    marker = NodeMapMarker.normalize(cursor.getString(markerIndex)),
                    alertCondition = GeoFenceAlertCondition.fromProtoValue(cursor.getInt(alertConditionIndex)),
                )
                geoFences[geoFence.key] = geoFence
            }
        }
        return geoFences
    }

    private fun migrateGeoFenceTableToUuid(db: SQLiteDatabase) {
        if (tableHasColumn(db, TABLE_GEO_FENCES, "geo_fence_uuid")) return
        val existing = mutableListOf<DeviceGeoFence>()
        if (tableExists(db, TABLE_GEO_FENCES) &&
            tableHasColumn(db, TABLE_GEO_FENCES, "id_high") &&
            tableHasColumn(db, TABLE_GEO_FENCES, "id_low")
        ) {
            db.query(
                TABLE_GEO_FENCES,
                arrayOf("id_high", "id_low", "name", "marker", "alert_condition"),
                null,
                null,
                null,
                null,
                null,
            ).use { cursor ->
                val idHighIndex = cursor.getColumnIndexOrThrow("id_high")
                val idLowIndex = cursor.getColumnIndexOrThrow("id_low")
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val markerIndex = cursor.getColumnIndexOrThrow("marker")
                val alertConditionIndex = cursor.getColumnIndexOrThrow("alert_condition")
                while (cursor.moveToNext()) {
                    existing += DeviceGeoFence(
                        idHigh = cursor.getLong(idHighIndex),
                        idLow = cursor.getLong(idLowIndex),
                        name = cursor.getString(nameIndex).orEmpty().ifBlank { "Geo fence" }.take(64),
                        marker = NodeMapMarker.normalize(cursor.getString(markerIndex)),
                        alertCondition = GeoFenceAlertCondition.fromProtoValue(cursor.getInt(alertConditionIndex)),
                    )
                }
            }
            db.execSQL("DROP TABLE $TABLE_GEO_FENCES")
        }
        createGeoFenceTable(db)
        existing.distinctBy { it.key }.forEach { geoFence ->
            if (!geoFence.isEmptyId) {
                db.insertWithOnConflict(TABLE_GEO_FENCES, null, geoFence.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    private fun migrateUserGeoFenceUuids(db: SQLiteDatabase) {
        db.query(
            TABLE_USERS,
            arrayOf("user_uuid", "geo_fence_id_high", "geo_fence_id_low"),
            "(geo_fence_uuid = '' OR geo_fence_uuid IS NULL) AND (geo_fence_id_high != 0 OR geo_fence_id_low != 0)",
            null,
            null,
            null,
            null,
        ).use { cursor ->
            val userUuidIndex = cursor.getColumnIndexOrThrow("user_uuid")
            val idHighIndex = cursor.getColumnIndexOrThrow("geo_fence_id_high")
            val idLowIndex = cursor.getColumnIndexOrThrow("geo_fence_id_low")
            while (cursor.moveToNext()) {
                db.update(
                    TABLE_USERS,
                    ContentValues().apply {
                        put("geo_fence_uuid", DeviceGeoFence.keyFor(cursor.getLong(idHighIndex), cursor.getLong(idLowIndex)))
                    },
                    "user_uuid = ?",
                    arrayOf(cursor.getString(userUuidIndex)),
                )
            }
        }
    }

    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, definition: String) {
        if (!tableHasColumn(db, table, column)) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean {
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun tableHasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return true
            }
        }
        return false
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

private fun DeviceGeoFence.toContentValues(): ContentValues {
    return ContentValues().apply {
        put("geo_fence_uuid", key)
        put("name", name.ifBlank { "Geo fence" }.take(64))
        put("marker", NodeMapMarker.normalize(marker))
        put("alert_condition", alertCondition.protoValue)
        put("updated_at_ms", System.currentTimeMillis())
    }
}
