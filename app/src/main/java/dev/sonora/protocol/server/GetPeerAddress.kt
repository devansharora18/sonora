package dev.sonora.protocol.server

import dev.sonora.protocol.MessageReader
import dev.sonora.protocol.MessageWriter

/**
 * Server code 3 (GetPeerAddress). Asks the server for a user's address so we can connect to
 * them directly.
 *
 * This is where a download starts: we need a peer connection to the uploader before we can ask
 * for the file, and search results only give us a username.
 */
object GetPeerAddress {

    const val CODE = 3L

    fun request(username: String): ByteArray =
        MessageWriter().writeString(username).toByteArray()

    fun parse(body: ByteArray): UserAddress {
        val reader = MessageReader(body)

        val username = reader.readString()
        val ip = reader.readUInt32()
        val port = reader.readUInt32()

        // Obfuscation fields follow. We do not support obfuscated connections, so these are
        // read only to stay in sync — and tolerantly, in case a server omits them.
        if (reader.remaining >= 4) reader.readUInt32() // obfuscation type
        if (reader.remaining >= 2) reader.readUInt16() // obfuscated port

        return UserAddress(username = username, ip = ip, port = port)
    }
}

/** A user's address, as returned by the server. */
data class UserAddress(
    val username: String,
    /** IPv4 address packed into a uint32. */
    val ip: Long,
    val port: Long,
) {
    fun ipAddress(): String = formatIpv4(ip)
}
