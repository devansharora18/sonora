package dev.sonora.protocol

import dev.sonora.protocol.peer.FileSearchResponse
import dev.sonora.protocol.peer.FileTransfer
import dev.sonora.protocol.peer.PeerInit
import dev.sonora.protocol.peer.PeerListener
import dev.sonora.protocol.peer.PeerSession
import dev.sonora.protocol.peer.PierceFireWall
import dev.sonora.protocol.peer.QueueUpload
import dev.sonora.protocol.peer.SearchResponse
import dev.sonora.protocol.peer.TransferRequest
import dev.sonora.protocol.peer.TransferResponse
import dev.sonora.protocol.peer.UploadDenied
import dev.sonora.protocol.peer.UploadFailed
import dev.sonora.protocol.server.ConnectToPeer
import dev.sonora.protocol.server.FileSearch
import dev.sonora.protocol.server.GetPeerAddress
import dev.sonora.protocol.server.Login
import dev.sonora.protocol.server.LoginResponse
import dev.sonora.protocol.server.PeerAddress
import dev.sonora.protocol.server.ServerConnection
import dev.sonora.protocol.server.SetStatus
import dev.sonora.protocol.server.SetWaitPort
import dev.sonora.protocol.server.SharedFoldersFiles
import dev.sonora.protocol.server.UserAddress
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A live session with the Soulseek network.
 *
 * Owns the server connection, the inbound peer listener, and outbound peer connections, tying
 * the message definitions together into something an application can drive: connect once,
 * then search.
 *
 * Peer connections run in both directions on purpose. An inbound `PeerInit` means a peer
 * reached us; a `ConnectToPeer` on the server connection means it could not, and expects us to
 * dial out. Devices behind CGNAT — every phone on mobile data — only ever get the second case.
 * See PRD D11.
 *
 * Callbacks and [onTrace] fire on internal threads, so they must not block.
 */
class SoulseekSession(
    private val username: String,
    private val password: String,
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    private val listenPort: Int = SetWaitPort.DEFAULT_PORT,
    /**
     * Directory reported to the network as shared.
     *
     * Advertising zero shares marks us as a leecher, which Soulseek's etiquette — and many
     * users' upload rules — treat as grounds to refuse. Counting a real directory keeps the
     * advertisement honest rather than claiming shares we do not have.
     */
    private val shareDirectory: File? = null,
    /**
     * Upper bound on peer connections dialled at once.
     *
     * A single search can produce thousands of relays, so this must be capped — one thread and
     * one socket per relay exhausts file descriptors and takes the process down. Android is
     * stricter about this than a desktop.
     */
    private val maxConcurrentPeers: Int = DEFAULT_MAX_CONCURRENT_PEERS,
    /**
     * How long [download] waits for the file connection and then for the bytes. One blunt ceiling
     * over the whole transfer — a real client wants progress-aware policy rather than a timeout.
     */
    private val transferTimeoutMillis: Long = DEFAULT_TRANSFER_TIMEOUT_MS,
    /**
     * How long [download] waits for the peer's file connection before dialling the uploader
     * itself. Some clients never open one when the downloader's port is closed, so waiting
     * forever is not an option. See [initiateFileConnection].
     */
    private val fileConnectionFallbackMillis: Long = DEFAULT_FILE_CONNECTION_FALLBACK_MS,
    /** Diagnostic sink: peer connection attempts and their outcome. Used by the live spikes. */
    private val onTrace: (String) -> Unit = {},
) : Closeable {

    private val searches = ConcurrentHashMap<Long, (SearchResponse) -> Unit>()
    private val nextToken = AtomicLong(1)
    private val nextConnectToken = AtomicLong(1)
    private val outboundPeers = ConcurrentHashMap.newKeySet<Socket>()
    private val pendingAddresses = ConcurrentHashMap<String, BlockingQueue<UserAddress>>()
    private val pendingTransfers = ConcurrentHashMap<Long, PendingTransfer>()

    private val dials = ThreadPoolExecutor(
        maxConcurrentPeers,
        maxConcurrentPeers,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(DIAL_QUEUE_CAPACITY),
        // Must return an *unstarted* thread — ThreadPoolExecutor starts it itself. Kotlin's
        // thread(...) helper starts immediately, which silently breaks the pool's accounting
        // and lets every task run concurrently.
        ThreadFactory { runnable ->
            Thread(runnable, "sonora-dial").apply { isDaemon = true }
        },
        RejectedExecutionHandler { _, _ -> onTrace("relay dropped: dial queue full") },
    )

    // File relays must not wait behind thousands of search-result P connections. A download
    // has a short-lived negotiation window, while search peers can be processed opportunistically.
    private val fileDials = ThreadPoolExecutor(
        FILE_DIAL_THREADS,
        FILE_DIAL_THREADS,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(FILE_DIAL_QUEUE_CAPACITY),
        ThreadFactory { runnable -> Thread(runnable, "sonora-file-dial").apply { isDaemon = true } },
        RejectedExecutionHandler { _, _ -> onTrace("file relay dropped: dial queue full") },
    )

    private var server: ServerConnection? = null
    private var listener: PeerListener? = null

    @Volatile
    private var closed = false

    val isConnected: Boolean get() = server != null && !closed

    /**
     * Connects, logs in, and completes the session handshake. Blocking.
     *
     * Returns the login response; a rejection (bad credentials) is a normal result rather than
     * an exception. Throws only if the connection itself cannot be established.
     */
    fun connect(): LoginResponse {
        check(server == null) { "already connected" }

        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

        val connection = ServerConnection(socket)
        val response = try {
            connection.send(
                Login.CODE,
                Login.request(username, password, MAJOR_VERSION, MINOR_VERSION),
            )
            Login.parse(connection.read().body)
        } catch (e: Exception) {
            connection.close()
            throw e
        }

        if (response !is LoginResponse.Success) {
            connection.close()
            return response
        }

        val peerListener = PeerListener(listenPort, ::acceptPeer)
        listener = peerListener

        connection.send(SetWaitPort.CODE, SetWaitPort.request(peerListener.boundPort))
        connection.send(SetStatus.CODE, SetStatus.request(SetStatus.ONLINE))

        val (directories, files) = shareDirectory?.let(::countShared) ?: (0L to 0L)
        onTrace("advertising $directories directory(ies), $files file(s)")
        connection.send(SharedFoldersFiles.CODE, SharedFoldersFiles.request(directories, files))
        connection.startReading(::onServerMessage)

        server = connection
        return response
    }

    /**
     * Sends a search and routes every matching response to [onResponse]. Returns the token the
     * results will carry.
     *
     * Results arrive asynchronously as peers answer, so [onResponse] fires on internal threads.
     * The subscription lasts until [close]; there is no per-search cancellation yet.
     */
    fun search(query: String, onResponse: (SearchResponse) -> Unit): Long {
        val connection = checkNotNull(server) { "not connected" }

        val token = nextToken.getAndIncrement() and 0xFFFF_FFFFL
        searches[token] = onResponse

        connection.send(FileSearch.CODE, FileSearch.request(token, query))
        return token
    }

    private fun onServerMessage(message: Message) {
        when (message.code) {
            ConnectToPeer.CODE -> handleConnectToPeer(message.body)
            GetPeerAddress.CODE -> handlePeerAddress(message.body)
        }
    }

    private fun handleConnectToPeer(body: ByteArray) {
        // Untrusted: a malformed body must not end the session.
        val address = runCatching { ConnectToPeer.parse(body) }.getOrNull() ?: return
        onTrace("relay ${address.username} ${address.connectionType} ${address.ipAddress()}:${address.port}")

        val executor = if (address.connectionType == PeerInit.TYPE_FILE) fileDials else dials
        executor.execute {
            try {
                dialPeer(address) { onPeerMessage(address.username, it) }
            } catch (e: Exception) {
                // Sockets closed by our own shutdown are not failures — without this guard the
                // trace is dominated by shutdown noise and says nothing about peer health.
                if (!closed) {
                    // Common and expected: many peers are behind NAT themselves and their
                    // advertised port is unreachable. Only connection *establishment* failures
                    // reach here — normal endings are handled inside dialPeer.
                    onTrace("dial failed ${address.username}: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    /**
     * Dials a peer that asked for an indirect connection and completes the handshake. Runs on
     * its own thread so the server read loop keeps draining relays.
     */
    private fun dialPeer(address: PeerAddress, onMessage: (Message) -> Unit) {
        val socket = Socket()
        outboundPeers += socket

        try {
            socket.connect(
                InetSocketAddress(address.ipAddress(), address.port.toInt()),
                PEER_CONNECT_TIMEOUT_MS,
            )
            socket.soTimeout = SEARCH_CONNECTION_IDLE_MS

            Framing.PEER_INIT.write(
                socket.getOutputStream(),
                PierceFireWall.CODE,
                PierceFireWall.request(address.token),
            )

            if (address.connectionType == PeerInit.TYPE_FILE) {
                // File connections use their own framing entirely, so they take a separate path.
                handleFileConnection(
                    PeerSession(address.username, address.connectionType, socket),
                )
                return
            }

            while (true) {
                val message = try {
                    Framing.PEER.read(socket.getInputStream())
                } catch (_: SocketTimeoutException) {
                    return // idle
                } catch (_: EOFException) {
                    return // peer finished and hung up
                } catch (_: SocketException) {
                    return // peer reset the connection, or we closed the socket on shutdown
                }
                onMessage(message)
            }
        } finally {
            outboundPeers -= socket
            socket.close()
        }
    }

    /**
     * Resolves a user's address and opens a direct peer connection to them.
     *
     * This is where a download starts: search results give a username, but asking a peer for a
     * file needs a connection to that peer first.
     *
     * Returns null when the address cannot be resolved or the peer is unreachable. Both are
     * ordinary outcomes — plenty of peers are behind NAT themselves — so neither is an exception.
     * The caller owns the returned session, and it is also closed with this session.
     */
    fun connectToUser(username: String): PeerSession? {
        val address = resolveAddress(username) ?: return null
        return dialDirect(username, address, PeerInit.TYPE_PEER)
    }

    private fun countShared(root: File): Pair<Long, Long> {
        if (!root.isDirectory) return 0L to 0L

        var directories = 0L
        var files = 0L

        root.walkTopDown().forEach { entry ->
            if (entry.isDirectory) directories++ else files++
        }

        return directories to files
    }

    private fun resolveAddress(username: String): UserAddress? {
        val connection = checkNotNull(server) { "not connected" }

        val pending = LinkedBlockingQueue<UserAddress>()
        pendingAddresses[username] = pending

        return try {
            connection.send(GetPeerAddress.CODE, GetPeerAddress.request(username))
            pending.poll(ADDRESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            pendingAddresses.remove(username)
        }
    }

    /**
     * Negotiates a download from a user: connects to them, asks for the file, and accepts the
     * transfer they offer.
     *
     * The transfer itself is not handled here — accepting a [TransferRequest] only tells the
     * peer to open a file connection, which arrives later through the normal peer paths.
     */
    fun requestDownload(username: String, filename: String): DownloadRequest =
        negotiateDownload(username, filename, expectedSize = null) { _, _ -> }

    /**
     * Downloads a file to [destination]. Blocking: returns once the transfer completes or fails.
     *
     * [size] should come from the original search result rather than the peer's offer, because
     * SoulseekQt reports 0 for files over 2 GB.
     */
    fun download(
        username: String,
        filename: String,
        destination: File,
        size: Long,
    ): DownloadOutcome {
        val transfer = PendingTransfer(destination, size)
        var token: Long? = null

        val negotiation = negotiateDownload(username, filename, expectedSize = size) { peer, accepted ->
            token = accepted
            pendingTransfers[accepted] = transfer
            watchNegotiationConnection(peer, transfer)
        }

        if (negotiation !is DownloadRequest.Accepted) {
            return DownloadOutcome.Failed("the download request was not accepted")
        }

        return try {
            if (!transfer.completion.await(transferTimeoutMillis, TimeUnit.MILLISECONDS)) {
                DownloadOutcome.Failed("the peer never opened a file connection")
            } else {
                transfer.outcome ?: DownloadOutcome.Failed("the transfer ended without a result")
            }
        } catch (_: InterruptedException) {
            DownloadOutcome.Failed("interrupted")
        } finally {
            token?.let { pendingTransfers.remove(it) }
        }
    }

    private fun negotiateDownload(
        username: String,
        filename: String,
        expectedSize: Long?,
        onAccepted: (peer: PeerSession, token: Long) -> Unit,
    ): DownloadRequest {
        val peer = connectToUser(username) ?: return DownloadRequest.Unreachable

        return try {
            peer.send(QueueUpload.CODE, QueueUpload.request(filename))
            awaitOffer(peer, filename, expectedSize, onAccepted)
        } catch (_: Exception) {
            // Peer hung up, went quiet, or answered with something unparseable.
            DownloadRequest.Unreachable
        }
    }

    /** Reads until the peer offers [filename], then accepts it. */
    private fun awaitOffer(
        peer: PeerSession,
        filename: String,
        expectedSize: Long?,
        onAccepted: (peer: PeerSession, token: Long) -> Unit,
    ): DownloadRequest {
        var outcome: DownloadRequest = DownloadRequest.Unreachable

        while (true) {
            val message = peer.read()

            // Anything other than the offer we are waiting for: keep reading until the peer
            // offers the file or goes quiet.
            if (message.code != TransferRequest.CODE) continue

            val request = TransferRequest.parse(message.body)
            if (request.direction != TransferRequest.DIRECTION_UPLOAD) continue
            if (request.filename != filename) continue

            // Registered before we accept: the uploader may open the file connection the instant
            // it receives our acceptance, so the token has to be known by then.
            onAccepted(peer, request.token)

            // Echo the size we believe in — the caller's from the search result, since the peer
            // reports 0 for files over 2 GB.
            peer.send(TransferResponse.CODE, TransferResponse.accepted(request.token))

            outcome = DownloadRequest.Accepted(request.token, request.filename, request.size)
            break
        }

        return outcome
    }

    /**
     * Keeps reading the connection the file was requested on.
     *
     * We used to stop reading the instant we accepted, which made anything the peer said next —
     * a refusal, a queue notice — completely invisible. Peers also revoke queued files here.
     */
    private fun watchNegotiationConnection(peer: PeerSession, transfer: PendingTransfer) {
        thread(name = "sonora-negotiation-${peer.username}", isDaemon = true) {
            try {
                while (true) {
                    val message = peer.read()
                    onTrace("post-accept ${peer.username} message ${message.code}")

                    if (message.code == UploadDenied.CODE) {
                        val denial = UploadDenied.parse(message.body)
                        transfer.outcome = DownloadOutcome.Failed("peer refused: ${denial.reason}")
                        transfer.completion.countDown()
                        return@thread
                    }

                    if (message.code == UploadFailed.CODE) {
                        // The peer tried and gave up — most often because it could not reach us,
                        // since a closed listening port leaves it nothing to connect to.
                        val filename = UploadFailed.parse(message.body)
                        transfer.outcome =
                            DownloadOutcome.Failed("peer gave up on the upload ($filename)")
                        transfer.completion.countDown()
                        return@thread
                    }
                }
            } catch (_: Exception) {
                // Connection ended. The file connection, if any, carries on independently.
            }
        }
    }

    /** Receives a file over an established `F` connection and completes the pending transfer. */
    private fun handleFileConnection(session: PeerSession) {
        var transfer: PendingTransfer? = null

        try {
            val token = FileTransfer.readInitToken(session.inputStream())
            val pending = pendingTransfers[token]
            transfer = pending

            if (pending == null) {
                onTrace("file connection with unknown token $token from ${session.username}")
                return
            }

            // A peer may open a connection while our own dial is in flight; only one may drive
            // the transfer, or both would write to the same file.
            if (!pending.claim()) {
                onTrace("duplicate file connection for token $token from ${session.username}")
                return
            }

            onTrace("receiving ${transfer.size} bytes from ${session.username}")
            FileTransfer.requestFrom(session.outputStream(), offset = 0)

            transfer.destination.parentFile?.mkdirs()
            val written = transfer.destination.outputStream().use { destination ->
                FileTransfer.copyBytes(session.inputStream(), destination, transfer.size)
            }

            transfer.outcome = if (written == transfer.size) {
                DownloadOutcome.Completed(written)
            } else {
                // Resumable: the caller keeps the partial file and can retry from `written`.
                DownloadOutcome.Failed("truncated: $written of ${transfer.size} bytes")
            }
        } catch (e: Exception) {
            // The originating frame matters here: a local `Socket closed` and a peer reset look
            // identical from the message alone, and they mean very different things.
            val frame = e.stackTrace.firstOrNull()
                ?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                .orEmpty()

            onTrace("file connection failed from ${session.username}: ${e.javaClass.simpleName}: ${e.message}$frame")
            transfer?.outcome = DownloadOutcome.Failed("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            session.close()
            transfer?.completion?.countDown()
        }
    }

    private fun dialDirect(
        username: String,
        address: UserAddress,
        connectionType: String,
    ): PeerSession? {
        val socket = Socket()
        outboundPeers += socket

        try {
            socket.connect(
                InetSocketAddress(address.ipAddress(), address.port.toInt()),
                PEER_CONNECT_TIMEOUT_MS,
            )
            socket.soTimeout = PEER_IDLE_TIMEOUT_MS

            Framing.PEER_INIT.write(
                socket.getOutputStream(),
                PeerInit.CODE,
                PeerInit.request(this.username, connectionType),
            )

            return PeerSession(username, connectionType, socket)
        } catch (_: Exception) {
            outboundPeers -= socket
            socket.close()
            return null
        }
    }

    private fun handlePeerAddress(body: ByteArray) {
        val address = runCatching { GetPeerAddress.parse(body) }.getOrNull() ?: return
        pendingAddresses[address.username]?.put(address)
    }

    private fun acceptPeer(session: PeerSession) {
        onTrace("inbound ${session.username} ${session.connectionType}")

        if (session.connectionType == PeerInit.TYPE_FILE) {
            thread(name = "sonora-file-${session.username}", isDaemon = true) {
                handleFileConnection(session)
            }
        } else {
            readPeerMessages(session)
        }
    }

    private fun readPeerMessages(session: PeerSession) {
        thread(name = "sonora-read-${session.username}", isDaemon = true) {
            try {
                session.readTimeoutMillis = PEER_IDLE_TIMEOUT_MS
                while (true) {
                    onPeerMessage(session.username, session.read())
                }
            } catch (_: Exception) {
                // Idle timeout, peer hung up, or framing desync — all end this connection.
            } finally {
                session.close()
            }
        }
    }

    private fun onPeerMessage(peer: String, message: Message) {
        when (message.code) {
            FileSearchResponse.CODE -> {
                // The body is untrusted, so a malformed one must not end the connection.
                val response = try {
                    FileSearchResponse.parse(message.body)
                } catch (_: Exception) {
                    null
                }

                if (response != null) {
                    onTrace("results ${response.files.size} from $peer")
                    searches[response.token]?.invoke(response)
                }
            }

            // Download and reshare messages land here once those exist.
            else -> Unit
        }
    }

    override fun close() {
        closed = true
        searches.clear()
        listener?.close()
        server?.close()
        dials.shutdownNow()
        fileDials.shutdownNow()
        outboundPeers.forEach { it.close() }
        outboundPeers.clear()
        server = null
    }

    companion object {
        const val DEFAULT_HOST = "server.slsknet.org"
        const val DEFAULT_PORT = 2242

        /**
         * Major version 177 is the value reserved for experimental development and testing
         * (docs/protocol-scope.md), so this does not impersonate an established client.
         */
        const val MAJOR_VERSION = 177
        const val MINOR_VERSION = 1

        /**
         * How many peers may be contacted at once.
         *
         * Search produces thousands of relays, so this bounds how much of a result set is ever
         * reached: too small and most peers never get dialled. The reference client allows 512
         * sockets; it uses non-blocking I/O, which is the better long-term answer for a number
         * this size, but a thread per connection is workable at this level.
         */
        const val DEFAULT_MAX_CONCURRENT_PEERS = 200

        private const val FILE_DIAL_THREADS = 4
        private const val FILE_DIAL_QUEUE_CAPACITY = 128

        /**
         * Relays are tiny, so queuing is cheap and generous — the scarce resource is sockets,
         * not queue entries. Overflow is dropped rather than letting the queue grow without
         * bound, and is reported through the trace.
         */
        private const val DIAL_QUEUE_CAPACITY = 8192

        private const val CONNECT_TIMEOUT_MS = 15_000

        /**
         * Short, because a peer that does not accept quickly is not worth holding a worker for.
         * Search produces thousands of relays and most of the work is simply reaching them.
         */
        private const val PEER_CONNECT_TIMEOUT_MS = 5_000

        /**
         * Recycle search connections quickly. Peers send their results promptly, so a long idle
         * wait just occupies a worker that another peer could be using.
         */
        private const val SEARCH_CONNECTION_IDLE_MS = 8_000

        private const val PEER_IDLE_TIMEOUT_MS = 30_000
        private const val ADDRESS_TIMEOUT_MS = 10_000L

        /**
         * Default ceiling for a whole transfer. Generous, because a peer may queue us before it
         * starts sending.
         */
        const val DEFAULT_TRANSFER_TIMEOUT_MS = 300_000L

        /**
         * How long to wait for the peer's own file connection before dialling the uploader
         * ourselves. Well under the transfer timeout, because waiting is the failure mode.
         */
        const val DEFAULT_FILE_CONNECTION_FALLBACK_MS = 5_000L

        /**
         * Pause between opening the file connection and accepting the transfer, so the peer has
         * registered the inbound connection before it decides where to send `FileTransferInit`.
         */
        private const val FILE_CONNECTION_SETTLE_MS = 250L
    }
}

/** A download waiting for its file connection, matched up by transfer token. */
private class PendingTransfer(val destination: File, val size: Long) {
    val completion = CountDownLatch(1)

    private val claimed = AtomicBoolean(false)

    @Volatile
    var outcome: DownloadOutcome? = null

    /** Only one connection may drive a transfer — a peer may open one while our own dial is in flight. */
    fun claim(): Boolean = claimed.compareAndSet(false, true)

    val isClaimed: Boolean get() = claimed.get()
}

/** The outcome of a download. */
sealed interface DownloadOutcome {

    data class Completed(val bytes: Long) : DownloadOutcome

    /**
     * Kept deliberately coarse. A short read leaves a partial file on disk, which is resumable
     * rather than lost, so the message says enough to resume without pretending to be a taxonomy.
     */
    data class Failed(val reason: String) : DownloadOutcome
}

/** The outcome of asking a peer for a file. */
sealed interface DownloadRequest {

    /** The peer accepted and will open a file connection for [filename]. */
    data class Accepted(
        val token: Long,
        val filename: String,
        /**
         * As reported by the peer. Unreliable for files over 2 GB, where SoulseekQt sends 0 and
         * clients fall back to the size from the original search result.
         */
        val size: Long?,
    ) : DownloadRequest

    /**
     * The peer could not be reached or never answered.
     *
     * Note a peer can also actively refuse (peer code 50, `QueueFailed`), which is not handled
     * yet — that currently shows up here as a timeout instead.
     */
    data object Unreachable : DownloadRequest
}
