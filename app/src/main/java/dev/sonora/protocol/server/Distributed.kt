package dev.sonora.protocol.server

import dev.sonora.protocol.MessageReader
import dev.sonora.protocol.MessageWriter

/**
 * Server code 71. Whether we have a distributed parent.
 *
 * Saying we do not makes the server offer candidates; saying we do stops it. Until we are
 * adopted the server keeps offering them.
 */
object HaveNoParent {

    const val CODE = 71L

    fun request(noParent: Boolean): ByteArray =
        MessageWriter().writeBool(noParent).toByteArray()
}

/**
 * Server code 100. Whether we accept child nodes.
 *
 * A child sends us searches to forward, so this stays false until forwarding exists: accepting
 * children we cannot serve would make us a dead branch in the tree, which is worse than not
 * being in it.
 */
object AcceptChildren {

    const val CODE = 100L

    fun request(enabled: Boolean): ByteArray =
        MessageWriter().writeBool(enabled).toByteArray()
}

/** Server code 102. Candidate parents, offered repeatedly until we are adopted. */
object PossibleParents {

    const val CODE = 102L

    /** The server sends at most ten; the bound stops a corrupt count driving an allocation. */
    private const val MAX_CANDIDATES = 100

    fun parse(body: ByteArray): List<UserAddress> {
        val reader = MessageReader(body)
        val count = reader.readUInt32().toInt().coerceIn(0, MAX_CANDIDATES)

        return buildList {
            repeat(count) {
                add(
                    UserAddress(
                        username = reader.readString(),
                        ip = reader.readUInt32(),
                        port = reader.readUInt32(),
                    ),
                )
            }
        }
    }
}

/**
 * Server code 130. Asks us to drop our distributed parent and children.
 *
 * Sent when the network is reshaped, so the tree is re-formed from scratch.
 */
object ResetDistributed {
    const val CODE = 130L
}

/**
 * Server code 126. Our depth in the distributed tree, as the server sees it.
 *
 * Reported alongside [BranchRoot] whenever our position changes: on joining, and again once a
 * parent is adopted with the position that parent gave us.
 */
object BranchLevel {

    const val CODE = 126L

    fun request(level: Long): ByteArray = MessageWriter().writeUInt32(level).toByteArray()
}

/** Server code 127. The root of our branch, as the server sees it. */
object BranchRoot {

    const val CODE = 127L

    fun request(username: String): ByteArray =
        MessageWriter().writeString(username).toByteArray()
}

/**
 * Server code 93. A distributed message the server embedded for us.
 *
 * Only sent to branch roots, and only ever wraps a `DistribSearch`. Receiving one means the
 * server considers us a root, which is not a state a leaf should be in.
 */
object EmbeddedMessage {

    const val CODE = 93L

    fun parse(body: ByteArray): Embedded {
        val reader = MessageReader(body)
        return Embedded(distribCode = reader.readByte().toLong(), body = reader.readRemaining())
    }
}

data class Embedded(val distribCode: Long, val body: ByteArray)
