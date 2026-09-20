package dev.sonora.protocol.peer

import dev.sonora.protocol.MessageReader

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
