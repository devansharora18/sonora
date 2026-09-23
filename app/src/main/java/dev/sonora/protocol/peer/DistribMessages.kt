package dev.sonora.protocol.peer

import dev.sonora.protocol.MessageReader
import dev.sonora.protocol.MessageWriter

/**
 * The `D` (distributed) message set.
 *
 * Framed like peer init — `uint32 length | uint8 code` — rather than like a `P` message, which is
 * why these cannot share the peer framing. See [dev.sonora.protocol.Framing.DISTRIBUTED].
 *
 * A `D` connection is a link in the search tree: a parent forwards other users' searches down it,
 * and children forward their own up it. Nothing here is used until forwarding is implemented.
 */

/**
 * Distrib code 3. A search that arrived through the tree.
 *
 * The leading identifier is always the code point of the ASCII character `1`, and messages using
 * anything else are rejected — an odd guard, but the reference is explicit about it.
 */
object DistribSearch {

    const val CODE = 3L

    private const val IDENTIFIER = 49

    fun parse(body: ByteArray): TreeSearch? {
        val reader = MessageReader(body)

        val identifier = reader.readUInt32()
        if (identifier != IDENTIFIER.toLong()) return null

        return TreeSearch(
            username = reader.readString(),
            token = reader.readUInt32(),
            query = reader.readString(),
        )
    }
}

data class TreeSearch(
    /** Who asked. Results are sent to them directly, not back down the tree. */
    val username: String,
    val token: Long,
    val query: String,
)

/**
 * Distrib code 4. Where a parent sits in the tree.
 *
 * A branch root is level 0, and each generation below it adds one.
 */
object DistribBranchLevel {

    const val CODE = 4L

    fun request(level: Long): ByteArray = MessageWriter().writeUInt32(level).toByteArray()

    fun parse(body: ByteArray): Long = MessageReader(body).readUInt32()
}

/** Distrib code 5. The root of a parent's branch. */
object DistribBranchRoot {

    const val CODE = 5L

    fun request(username: String): ByteArray =
        MessageWriter().writeString(username).toByteArray()

    fun parse(body: ByteArray): String = MessageReader(body).readString()
}
