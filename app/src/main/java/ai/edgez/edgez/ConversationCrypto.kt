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
)

fun encryptConversationText(
    identity: UserIdentity,
    recipient: HaLowUser,
    text: String,
    senderNode: Long,
): ConversationMessage {
    val plaintext = text.toByteArray(Charsets.UTF_8)
    return encryptConversationPayload(identity, recipient, plaintext, senderNode)
}

fun encryptConversationPayload(
    identity: UserIdentity,
    recipient: HaLowUser,
    plaintext: ByteArray,
    senderNode: Long,
): ConversationMessage {
    require(recipient.publicKey.size == 32) { "Remote user public key is missing" }
    val nonce = ByteArray(CONVERSATION_NONCE_SIZE)
    CONVERSATION_RANDOM.nextBytes(nonce)
    val aad = conversationAad(
        senderNode = senderNode,
        recipientNode = recipient.nodeNum,
        nonce = nonce,
    )

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.ENCRYPT_MODE,
        conversationKey(identity, senderNode, recipient.nodeNum, recipient.publicKey),
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
): String {
    return String(decryptConversationPayload(identity, sender, packet), Charsets.UTF_8)
}

fun decryptConversationPayload(
    identity: UserIdentity,
    sender: HaLowUser,
    packet: NetworkPacket,
): ByteArray {
    val message = packet.conversationMessage ?: error("Conversation payload is missing")
    require(sender.publicKey.size == 32) { "Sender public key is missing" }
    val aad = conversationAad(
        senderNode = packet.from,
        recipientNode = packet.to,
        nonce = message.nonce,
    )
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        conversationKey(identity, packet.to, sender.nodeNum, sender.publicKey),
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

data class MediaChunk(
    val groupId: Long,
    val totalChunks: Int,
    val index: Int,
    val bytes: ByteArray,
)

private val VOICE_CHUNK_MAGIC = byteArrayOf('E'.code.toByte(), 'V'.code.toByte(), '2'.code.toByte())
private val MEDIA_CHUNK_MAGIC = byteArrayOf('E'.code.toByte(), 'M'.code.toByte(), '1'.code.toByte())
const val VOICE_CHUNK_AUDIO_BYTES = 290
const val IMAGE_CHUNK_BYTES = VOICE_CHUNK_AUDIO_BYTES

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
    if (payload.size < VOICE_CHUNK_MAGIC.size + 8 + 4 + 2 + 2 + 1) return null
    if (!payload.take(VOICE_CHUNK_MAGIC.size).toByteArray().contentEquals(VOICE_CHUNK_MAGIC)) return null
    val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    buffer.position(VOICE_CHUNK_MAGIC.size)
    val groupId = buffer.long
    val durationMs = buffer.int.toLong().coerceAtLeast(0L)
    val totalChunks = buffer.short.toInt() and 0xffff
    val index = buffer.short.toInt() and 0xffff
    val codec = buffer.get().toInt() and 0xff
    val audio = ByteArray(buffer.remaining())
    buffer.get(audio)
    if (totalChunks <= 0 || index >= totalChunks || audio.isEmpty()) return null
    return VoiceChunk(groupId, durationMs, totalChunks, index, codec, audio)
}

fun encodeMediaChunk(chunk: MediaChunk): ByteArray {
    return ByteBuffer.allocate(MEDIA_CHUNK_MAGIC.size + 8 + 2 + 2 + chunk.bytes.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(MEDIA_CHUNK_MAGIC)
        .putLong(chunk.groupId)
        .putShort(chunk.totalChunks.coerceIn(0, 0xffff).toShort())
        .putShort(chunk.index.coerceIn(0, 0xffff).toShort())
        .put(chunk.bytes)
        .array()
}

fun decodeMediaChunk(payload: ByteArray): MediaChunk? {
    if (payload.size < MEDIA_CHUNK_MAGIC.size + 8 + 2 + 2) return null
    if (!payload.take(MEDIA_CHUNK_MAGIC.size).toByteArray().contentEquals(MEDIA_CHUNK_MAGIC)) return null
    val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    buffer.position(MEDIA_CHUNK_MAGIC.size)
    val groupId = buffer.long
    val totalChunks = buffer.short.toInt() and 0xffff
    val index = buffer.short.toInt() and 0xffff
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    if (totalChunks <= 0 || index >= totalChunks || bytes.isEmpty()) return null
    return MediaChunk(groupId, totalChunks, index, bytes)
}

private fun conversationKey(
    identity: UserIdentity,
    localUserId: Long,
    peerUserId: Long,
    peerPublicKey: ByteArray,
): SecretKeySpec {
    val sharedSecret = X25519KeyGenerator.sharedSecret(identity.privateKey, peerPublicKey)
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("EdgeZ conversation v1".toByteArray(Charsets.UTF_8))
    digest.update(sharedSecret)
    val firstIsLocal = compareIdentityKeys(localUserId, identity.publicKey, peerUserId, peerPublicKey) <= 0
    if (firstIsLocal) {
        digest.update(identityKeyBytes(localUserId, identity.publicKey))
        digest.update(identityKeyBytes(peerUserId, peerPublicKey))
    } else {
        digest.update(identityKeyBytes(peerUserId, peerPublicKey))
        digest.update(identityKeyBytes(localUserId, identity.publicKey))
    }
    return SecretKeySpec(digest.digest(), "AES")
}

private fun conversationAad(
    senderNode: Long,
    recipientNode: Long,
    nonce: ByteArray,
): ByteArray {
    return ByteBuffer.allocate(8 + 8 + 2 + nonce.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putLong(senderNode)
        .putLong(recipientNode)
        .putShort(nonce.size.toShort())
        .put(nonce)
        .array()
}

private fun identityKeyBytes(userId: Long, publicKey: ByteArray): ByteArray {
    return ByteBuffer.allocate(8 + publicKey.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putLong(userId)
        .put(publicKey)
        .array()
}

private fun compareIdentityKeys(
    leftUserId: Long,
    leftPublicKey: ByteArray,
    rightUserId: Long,
    rightPublicKey: ByteArray,
): Int {
    val userCompare = leftUserId.compareTo(rightUserId)
    if (userCompare != 0) return userCompare
    val maxSize = maxOf(leftPublicKey.size, rightPublicKey.size)
    for (i in 0 until maxSize) {
        val left = leftPublicKey.getOrNull(i)?.toInt()?.and(0xff) ?: -1
        val right = rightPublicKey.getOrNull(i)?.toInt()?.and(0xff) ?: -1
        if (left != right) return left.compareTo(right)
    }
    return 0
}
