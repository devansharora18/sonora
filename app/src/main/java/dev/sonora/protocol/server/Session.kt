package dev.sonora.protocol.server

import dev.sonora.protocol.MessageWriter

/**
 * Send-only server messages used to establish and advertise a session, sent after [Login].
 *
 * None of these elicit a response; they exist so the server knows how to route peers to us
 * and how to represent us to the rest of the network.
 */

/**
 * Server code 2 (SetWaitPort). Tells the server which port we accept peer connections on.
 *
 * The optional obfuscation fields are omitted — Nicotine+ doesn't implement obfuscated
 * connections either (docs/protocol-scope.md).
 */
object SetWaitPort {

    const val CODE = 2L

    /** The Soulseek default. */
    const val DEFAULT_PORT = 2234

    fun request(port: Int): ByteArray = MessageWriter().writeUInt32(port.toLong()).toByteArray()
}

/** Server code 28 (SetStatus). Away or online; going offline means disconnecting. */
object SetStatus {

    const val CODE = 28L

    const val AWAY = 1
    const val ONLINE = 2

    fun request(status: Int): ByteArray = MessageWriter().writeUInt32(status.toLong()).toByteArray()
}

/** Server code 35 (SharedFoldersFiles). Advertises how much we share. */
object SharedFoldersFiles {

    const val CODE = 35L

    fun request(directories: Long, files: Long): ByteArray =
        MessageWriter()
            .writeUInt32(directories)
            .writeUInt32(files)
            .toByteArray()
}
