package dev.sonora.protocol.peer

import dev.sonora.protocol.MessageReader
import dev.sonora.protocol.MessageWriter

/**
 * Peer init code 1 (PeerInit). Opens a `P`, `F` or `D` connection and identifies the peer.
 *
 * Carried by [dev.sonora.protocol.Framing.PEER_INIT], which uses a single-byte code.
 */
object PeerInit {

    const val CODE = 1L

    const val TYPE_PEER = "P"
    const val TYPE_FILE = "F"
    const val TYPE_DISTRIBUTED = "D"

    /**
     * Builds an outbound handshake. Sent when dialling a peer directly — the token is legacy
     * and ignored today.
     */
    fun request(username: String, connectionType: String, token: Long = 0L): ByteArray =
        MessageWriter()
            .writeString(username)
            .writeString(connectionType)
            .writeUInt32(token)
            .toByteArray()

    fun parse(body: ByteArray): PeerHandshake {
        val reader = MessageReader(body)
        return PeerHandshake(
            username = reader.readString(),
            connectionType = reader.readString(),
            token = reader.readUInt32(),
        )
    }
}

/** The identifying fields of an inbound [PeerInit]. */
data class PeerHandshake(
    val username: String,
    /** `P`, `F` or `D` — see [PeerInit.TYPE_PEER] and friends. */
    val connectionType: String,
    /** Legacy and always 0 in practice; the field is parsed but not relied upon. */
    val token: Long,
)

/**
 * Peer init code 0 (PierceFireWall).
 *
 * Sent when dialling *out* to a peer that asked the server for an indirect connection,
 * echoing the token from the [dev.sonora.protocol.server.ConnectToPeer] that triggered it.
 * This is what makes peer connections work from behind NAT — see PRD D11.
 */
object PierceFireWall {

    const val CODE = 0L

    fun request(token: Long): ByteArray = MessageWriter().writeUInt32(token).toByteArray()
}
