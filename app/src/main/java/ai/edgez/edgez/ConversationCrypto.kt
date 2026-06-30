package ai.edgez.edgez

import ai.edgez.edgez.usb.ConversationMessage
import ai.edgez.edgez.usb.NetworkPacket
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
)

fun encryptConversationText(
    identity: UserIdentity,
    recipient: HaLowUser,
    text: String,
    senderNode: Long,
): ConversationMessage {
    require(recipient.publicKey.size == 32) { "Remote user public key is missing" }
    val nonce = ByteArray(CONVERSATION_NONCE_SIZE)
    CONVERSATION_RANDOM.nextBytes(nonce)
    val plaintext = text.toByteArray(Charsets.UTF_8)
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
    return String(cipher.doFinal(message.ciphertext), Charsets.UTF_8)
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
