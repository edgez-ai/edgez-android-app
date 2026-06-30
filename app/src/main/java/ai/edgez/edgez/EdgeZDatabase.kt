package ai.edgez.edgez

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

private const val DATABASE_NAME = "edgez_local.db"
private const val DATABASE_VERSION = 1
private const val TABLE_USERS = "halow_users"
private const val TABLE_MESSAGES = "conversation_messages"

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
                short_name TEXT NOT NULL,
                long_name TEXT NOT NULL,
                route TEXT NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                public_key BLOB NOT NULL
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

    fun getUsers(): Map<Long, HaLowUser> {
        val users = linkedMapOf<Long, HaLowUser>()
        readableDatabase.query(
            TABLE_USERS,
            arrayOf("node_num", "short_name", "long_name", "route", "last_seen_ms", "public_key"),
            null,
            null,
            null,
            null,
            "last_seen_ms DESC",
        ).use { cursor ->
            val nodeNumIndex = cursor.getColumnIndexOrThrow("node_num")
            val shortNameIndex = cursor.getColumnIndexOrThrow("short_name")
            val longNameIndex = cursor.getColumnIndexOrThrow("long_name")
            val routeIndex = cursor.getColumnIndexOrThrow("route")
            val lastSeenIndex = cursor.getColumnIndexOrThrow("last_seen_ms")
            val publicKeyIndex = cursor.getColumnIndexOrThrow("public_key")
            while (cursor.moveToNext()) {
                val user = HaLowUser(
                    nodeNum = cursor.getLong(nodeNumIndex),
                    shortName = cursor.getString(shortNameIndex),
                    longName = cursor.getString(longNameIndex),
                    route = cursor.getString(routeIndex),
                    lastSeenMs = cursor.getLong(lastSeenIndex),
                    publicKey = cursor.getBlob(publicKeyIndex) ?: ByteArray(0),
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
        writableDatabase.insertWithOnConflict(
            TABLE_USERS,
            null,
            ContentValues().apply {
                put("node_num", user.nodeNum)
                put("short_name", user.shortName)
                put("long_name", user.longName)
                put("route", user.route)
                put("last_seen_ms", user.lastSeenMs)
                put("public_key", user.publicKey)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
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
}
