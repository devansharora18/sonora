package dev.sonora.protocol.server

import dev.sonora.protocol.MessageReader

/**
 * Server code 18 (ConnectToPeer).
 *
 * The server relays this when a peer wants an indirect connection. Its arrival tells us the
 * peer could not reach us directly and expects us to dial *out* to it — the fallback that
 * makes peer connections work behind NAT. See PRD D11.
 */
object ConnectToPeer {

    const val CODE = 18L

    fun parse(body: ByteArray): PeerAddress {
        val reader = MessageReader(body)

        val username = reader.readString()
        val connectionType = reader.readString()
        val ip = reader.readUInt32()
        val port = reader.readUInt32()
        val token = reader.readUInt32()
        val isPrivileged = reader.readBool()

        // Obfuscation fields are part of the message but we do not support obfuscated
        // connections, so they are read only to stay in sync — and tolerantly, in case an
        // older server omits them.
        if (reader.remaining >= 8) {
            reader.readUInt32()
            reader.readUInt32()
        }

        return PeerAddress(
            username = username,
            connectionType = connectionType,
            ip = ip,
            port = port,
            token = token,
            isPrivileged = isPrivileged,
        )
    }
}

/** A peer's address, as relayed by the server. */
data class PeerAddress(
    val username: String,
    /** `P`, `F` or `D`. */
    val connectionType: String,
    /** IPv4 address packed into a uint32. */
    val ip: Long,
    val port: Long,
    /** Echo this in the [dev.sonora.protocol.peer.PeerInit] PierceFireWall reply. */
    val token: Long,
    val isPrivileged: Boolean,
) {
    /**
     * Dotted-quad form, for dialling.
     *
     * The wire stores the address little-endian (Nicotine+ reverses the four bytes before
     * `inet_ntoa`), so the uint32's most significant byte is the first octet.
     */
    fun ipAddress(): String = listOf(24, 16, 8, 0)
        .joinToString(".") { ((ip ushr it) and 0xFF).toString() }
}
