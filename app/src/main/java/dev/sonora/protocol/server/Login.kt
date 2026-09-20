package dev.sonora.protocol.server

import dev.sonora.protocol.MessageReader
import dev.sonora.protocol.MessageWriter
import java.security.MessageDigest

/**
 * Server code 1 (Login), sent immediately after connecting.
 *
 * The hash is an MD5 hex digest of the concatenated username and password, sent as a
 * string alongside the plaintext credentials.
 *
 * There is no password reset on the Soulseek network, so credentials must be validated and
 * stored locally — see PRD D6.
 */
object Login {

    const val CODE = 1L

    private const val REASON_INVALID_USERNAME = "INVALIDUSERNAME"

    /** Builds the message body. Frame it with [dev.sonora.protocol.Framing.SERVER]. */
    fun request(
        username: String,
        password: String,
        majorVersion: Int,
        minorVersion: Int,
    ): ByteArray = MessageWriter()
        .writeString(username)
        .writeString(password)
        .writeUInt32(majorVersion.toLong())
        .writeString(md5Hex(username + password))
        .writeUInt32(minorVersion.toLong())
        .toByteArray()

    fun parse(body: ByteArray): LoginResponse {
        val reader = MessageReader(body)

        if (!reader.readBool()) {
            val reason = reader.readString()
            // Only INVALIDUSERNAME carries a detail string. Guard the read so a terse
            // server response reports the rejection instead of throwing.
            val detail = if (reason == REASON_INVALID_USERNAME && reader.remaining > 0) {
                reader.readString()
            } else {
                null
            }
            return LoginResponse.Rejected(reason, detail)
        }

        return LoginResponse.Success(
            greeting = reader.readString(),
            ownIp = reader.readUInt32(),
            passwordHash = reader.readString(),
            isSupporter = reader.readBool(),
        )
    }

    private fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.ISO_8859_1))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

sealed interface LoginResponse {

    data class Success(
        /** Server MOTD. */
        val greeting: String,
        /** Our own IP as the server sees it, as a uint32. */
        val ownIp: Long,
        /** MD5 hex digest of the password, echoed back by the server. */
        val passwordHash: String,
        val isSupporter: Boolean,
    ) : LoginResponse

    data class Rejected(
        /** One of INVALIDUSERNAME, EMPTYPASSWORD, INVALIDPASS, INVALIDVERSION, SVRFULL, SVRPRIVATE. */
        val reason: String,
        /** Present only for INVALIDUSERNAME. */
        val detail: String?,
    ) : LoginResponse
}
