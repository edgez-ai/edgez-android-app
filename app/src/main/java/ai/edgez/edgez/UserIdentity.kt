package ai.edgez.edgez

data class UserIdentity(
    val userUuid: String,
    val userIdHigh: Long,
    val userIdLow: Long,
    val name: String,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UserIdentity) return false
        return userUuid == other.userUuid &&
            userIdHigh == other.userIdHigh &&
            userIdLow == other.userIdLow &&
            name == other.name &&
            privateKey.contentEquals(other.privateKey) &&
            publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int {
        var result = userUuid.hashCode()
        result = 31 * result + userIdHigh.hashCode()
        result = 31 * result + userIdLow.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + privateKey.contentHashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}
