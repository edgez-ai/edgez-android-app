package ai.edgez.edgez

import ai.edgez.edgez.usb.ConversationMessage
import ai.edgez.edgez.usb.NetworkPacket
import ai.edgez.edgez.usb.PacketMime
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val AES_GCM_TAG_BITS = 128
private const val CONVERSATION_NONCE_SIZE = 12
private val CONVERSATION_RANDOM = SecureRandom()

data class ConversationEntry(
    val text: String,
    val mine: Boolean,
    val timestampMs: Long,
    val status: String = "",
    val mime: PacketMime = PacketMime.TEXT,
    val audioPath: String = "",
    val durationMs: Long = 0,
    val messageUuid: String = "",
)

fun encryptConversationText(
    identity: UserIdentity,
    recipient: HaLowUser,
    text: String,
    _senderNode: Long,
): ConversationMessage {
    val plaintext = text.toByteArray(Charsets.UTF_8)
    return encryptConversationPayload(identity, recipient, plaintext, _senderNode)
}

fun encryptConversationPayload(
    identity: UserIdentity,
    recipient: HaLowUser,
    plaintext: ByteArray,
    _senderNode: Long,
    groupIdHigh: Long = 0,
    groupIdLow: Long = 0,
): ConversationMessage {
    require(recipient.publicKey.size == 32) {
        if (recipient.deviceType == EdgeZDeviceType.GROUP) "Group PSK is missing" else "Remote user public key is missing"
    }
    val nonce = ByteArray(CONVERSATION_NONCE_SIZE)
    CONVERSATION_RANDOM.nextBytes(nonce)
    val aad = conversationAad(nonce)

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.ENCRYPT_MODE,
        conversationKey(
            identity,
            recipient.publicKey,
            recipient.deviceType,
            groupIdHigh = groupIdHigh,
            groupIdLow = groupIdLow,
        ),
        GCMParameterSpec(AES_GCM_TAG_BITS, nonce),
    )
    cipher.updateAAD(aad)
    return ConversationMessage(
        nonce = nonce,
        ciphertext = cipher.doFinal(plaintext),
    )
}

fun decryptConversationText(
    identity: UserIdentity,
    sender: HaLowUser,
    packet: NetworkPacket,
    groupIdHigh: Long = 0,
    groupIdLow: Long = 0,
): String {
    return String(
        decryptConversationPayload(
            identity = identity,
            sender = sender,
            packet = packet,
            groupIdHigh = groupIdHigh,
            groupIdLow = groupIdLow,
        ),
        Charsets.UTF_8,
    )
}

fun decryptConversationPayload(
    identity: UserIdentity,
    sender: HaLowUser,
    packet: NetworkPacket,
    groupIdHigh: Long = 0,
    groupIdLow: Long = 0,
): ByteArray {
    val message = packet.conversationMessage ?: error("Conversation payload is missing")
    require(sender.publicKey.size == 32) {
        if (sender.deviceType == EdgeZDeviceType.GROUP) "Group PSK is missing" else "Sender public key is missing"
    }
    val aad = conversationAad(message.nonce)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        conversationKey(
            identity,
            sender.publicKey,
            sender.deviceType,
            groupIdHigh = groupIdHigh,
            groupIdLow = groupIdLow,
        ),
        GCMParameterSpec(AES_GCM_TAG_BITS, message.nonce),
    )
    cipher.updateAAD(aad)
    return cipher.doFinal(message.ciphertext)
}

data class VoiceChunk(
    val groupId: Long,
    val durationMs: Long,
    val totalChunks: Int,
    val index: Int,
    val codec: Int,
    val audio: ByteArray,
)

data class ConversationChunk(
    val groupId: Long,
    val durationMs: Long,
    val totalChunks: Int,
    val index: Int,
    val marker: Int,
    val bytes: ByteArray,
)

private val VOICE_CHUNK_MAGIC = byteArrayOf('E'.code.toByte(), 'V'.code.toByte(), '2'.code.toByte())
const val VOICE_CHUNK_AUDIO_BYTES = 290

fun encodeVoiceChunk(chunk: VoiceChunk): ByteArray {
    return ByteBuffer.allocate(VOICE_CHUNK_MAGIC.size + 8 + 4 + 2 + 2 + 1 + chunk.audio.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(VOICE_CHUNK_MAGIC)
        .putLong(chunk.groupId)
        .putInt(chunk.durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
        .putShort(chunk.totalChunks.coerceIn(0, 0xffff).toShort())
        .putShort(chunk.index.coerceIn(0, 0xffff).toShort())
        .put(chunk.codec.coerceIn(0, 0xff).toByte())
        .put(chunk.audio)
        .array()
}

fun decodeVoiceChunk(payload: ByteArray): VoiceChunk? {
    val chunk = decodeConversationChunk(payload) ?: return null
    return VoiceChunk(
        groupId = chunk.groupId,
        durationMs = chunk.durationMs,
        totalChunks = chunk.totalChunks,
        index = chunk.index,
        codec = chunk.marker,
        audio = chunk.bytes,
    )
}

fun decodeConversationChunk(payload: ByteArray): ConversationChunk? {
    if (payload.size < VOICE_CHUNK_MAGIC.size + 8 + 4 + 2 + 2 + 1) return null
    if (!payload.take(VOICE_CHUNK_MAGIC.size).toByteArray().contentEquals(VOICE_CHUNK_MAGIC)) return null
    val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    buffer.position(VOICE_CHUNK_MAGIC.size)
    val groupId = buffer.long
    val durationOrReserved = buffer.int.toLong().coerceAtLeast(0L)
    val totalChunks = buffer.short.toInt() and 0xffff
    val index = buffer.short.toInt() and 0xffff
    val marker = buffer.get().toInt() and 0xff
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    if (totalChunks <= 0 || index >= totalChunks || bytes.isEmpty()) return null
    return ConversationChunk(
        groupId = groupId,
        durationMs = durationOrReserved,
        totalChunks = totalChunks,
        index = index,
        marker = marker,
        bytes = bytes,
    )
}

private fun conversationKey(
    identity: UserIdentity,
    peerPublicKey: ByteArray,
    peerDeviceType: EdgeZDeviceType,
    groupIdHigh: Long = 0,
    groupIdLow: Long = 0,
): SecretKeySpec {
    if (peerDeviceType == EdgeZDeviceType.GROUP) {
        return groupConversationKey(
            groupIdHigh = if (groupIdHigh != 0L || groupIdLow != 0L) groupIdHigh else 0L,
            groupIdLow = groupIdLow,
            psk = peerPublicKey,
        )
    }
    val sharedSecret = X25519KeyGenerator.sharedSecret(identity.privateKey, peerPublicKey)
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("EdgeZ conversation v1".toByteArray(Charsets.UTF_8))
    digest.update(sharedSecret)
    val firstIsLocal = comparePublicKeys(identity.publicKey, peerPublicKey) <= 0
    if (firstIsLocal) {
        digest.update(identity.publicKey)
        digest.update(peerPublicKey)
    } else {
        digest.update(peerPublicKey)
        digest.update(identity.publicKey)
    }
    return SecretKeySpec(digest.digest(), "AES")
}

private fun groupConversationKey(
    groupIdHigh: Long,
    groupIdLow: Long,
    psk: ByteArray,
): SecretKeySpec {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("EdgeZ group conversation v1".toByteArray(Charsets.UTF_8))
    digest.update(
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(groupIdHigh)
            .putLong(groupIdLow)
            .array(),
    )
    digest.update(psk)
    return SecretKeySpec(digest.digest(), "AES")
}

private fun conversationAad(nonce: ByteArray): ByteArray {
    return ByteBuffer.allocate(2 + nonce.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(nonce.size.toShort())
        .put(nonce)
        .array()
}

private fun comparePublicKeys(
    leftPublicKey: ByteArray,
    rightPublicKey: ByteArray,
): Int {
    val maxSize = maxOf(leftPublicKey.size, rightPublicKey.size)
    for (i in 0 until maxSize) {
        val left = leftPublicKey.getOrNull(i)?.toInt()?.and(0xff) ?: -1
        val right = rightPublicKey.getOrNull(i)?.toInt()?.and(0xff) ?: -1
        if (left != right) return left.compareTo(right)
    }
    return 0
}
