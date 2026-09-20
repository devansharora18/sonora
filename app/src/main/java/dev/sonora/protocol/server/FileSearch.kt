package dev.sonora.protocol.server

import dev.sonora.protocol.MessageWriter

/**
 * Server code 26 (FileSearch). Asks the server to propagate a query through the search
 * network. Matching peers answer directly over a peer connection with a
 * [dev.sonora.protocol.peer.FileSearchResponse] carrying the same token.
 */
object FileSearch {

    const val CODE = 26L

    fun request(token: Long, query: String): ByteArray =
        MessageWriter()
            .writeUInt32(token)
            .writeString(query)
            .toByteArray()
}
