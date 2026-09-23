package dev.sonora.protocol

import dev.sonora.protocol.peer.DistribBranchLevel
import dev.sonora.protocol.peer.DistribBranchRoot
import dev.sonora.protocol.peer.DistribSearch
import dev.sonora.protocol.peer.FileAttributes
import dev.sonora.protocol.peer.FileSearchResponse
import dev.sonora.protocol.peer.FileTransfer
import dev.sonora.protocol.peer.PeerInit
import dev.sonora.protocol.peer.PeerListener
import dev.sonora.protocol.peer.PeerSession
import dev.sonora.protocol.peer.PierceFireWall
import dev.sonora.protocol.peer.QueueUpload
import dev.sonora.protocol.peer.SearchResponse
import dev.sonora.protocol.peer.SharedFile
import dev.sonora.protocol.peer.SharedFileListRequest
import dev.sonora.protocol.peer.SharedFileListResponse
import dev.sonora.protocol.peer.SharedFolder
import dev.sonora.protocol.peer.TransferRequest
import dev.sonora.protocol.peer.TransferResponse
import dev.sonora.protocol.peer.UploadDenied
import dev.sonora.protocol.peer.UploadFailed
import dev.sonora.protocol.server.AcceptChildren
import dev.sonora.protocol.server.BranchLevel
import dev.sonora.protocol.server.BranchRoot
import dev.sonora.protocol.server.ConnectToPeer
import dev.sonora.protocol.server.EmbeddedMessage
import dev.sonora.protocol.server.FileSearch
import dev.sonora.protocol.server.GetPeerAddress
import dev.sonora.protocol.server.HaveNoParent
import dev.sonora.protocol.server.Login
import dev.sonora.protocol.server.LoginResponse
import dev.sonora.protocol.server.PeerAddress
import dev.sonora.protocol.server.PossibleParents
import dev.sonora.protocol.server.ResetDistributed
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

    /** Uploads a peer has asked for and we have offered, awaiting its answer. */
    private val negotiatingUploads = ConcurrentHashMap<String, NegotiatingUpload>()

    /**
     * Uploads the peer accepted, keyed by username.
     *
     * Keyed by user rather than token because the peer opens the file connection without saying
     * anything first — we are the uploader, so we speak first on it. The username is the only
     * thing tying that connection back to the negotiation.
     */
    private val pendingUploads = ConcurrentHashMap<String, NegotiatingUpload>()

    /** The node we receive other users' searches from, once one adopts us. */
    @Volatile
    private var distributedParent: PeerSession? = null

    /** True while a dialer is working through a batch of candidates. */
    @Volatile
    private var distributedDialing = false

    /** Our depth in the tree: a root is 0, and each generation below it adds one. */
    @Volatile
    private var branchLevel = 0L

    /** The root of our branch, as reported by our parent. */
    @Volatile
    private var branchRoot = ""

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

        // Join the distributed tree as a leaf. Saying we have no parent makes the server offer
        // candidates; children stay refused until we can forward, so we are not a dead branch.
        // The branch root and level are reported together with the request, as the reference does.
        connection.send(AcceptChildren.CODE, AcceptChildren.request(enabled = false))
        connection.send(HaveNoParent.CODE, HaveNoParent.request(noParent = true))
        connection.send(BranchRoot.CODE, BranchRoot.request(username))
        connection.send(BranchLevel.CODE, BranchLevel.request(0L))

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

            PossibleParents.CODE -> handlePossibleParents(message.body)

            ResetDistributed.CODE -> {
                onTrace("distributed reset; dropping parent")
                distributedParent?.close()
                distributedParent = null
                server?.send(HaveNoParent.CODE, HaveNoParent.request(noParent = true))
            }

            EmbeddedMessage.CODE -> {
                // The server only embeds messages for branch roots, which a leaf should never be.
                onTrace("unexpected embedded distributed message; ignoring")
            }
        }
    }

    /**
     * Offers of a parent to attach to.
     *
     * Several candidates are tried at once, because most of them are behind NAT and simply never
     * answer: one at a time means joining only if the first few happen to be reachable. The first
     * to forward a search wins and the rest are abandoned.
     *
     * The server re-offers at intervals, which is why only one batch runs at a time.
     */
    private fun handlePossibleParents(body: ByteArray) {
        if (distributedParent != null || distributedDialing) return

        val candidates = runCatching { PossibleParents.parse(body) }.getOrNull().orEmpty()
        if (candidates.isEmpty()) return

        onTrace("distributed candidates: ${candidates.joinToString { it.username }}")
        distributedDialing = true

        thread(name = "sonora-distributed-dial", isDaemon = true) {
            try {
                candidates.take(MAX_PARENT_ATTEMPTS).map { candidate ->
                    thread(name = "sonora-distributed", isDaemon = true) {
                        dialCandidate(candidate)
                    }
                }.forEach { it.join() }
            } finally {
                distributedDialing = false
            }
        }
    }

    private fun dialCandidate(candidate: UserAddress) {
        if (closed || distributedParent != null) return

        val session = dialDirect(candidate.username, candidate, PeerInit.TYPE_DISTRIBUTED)
        if (session == null) {
            onTrace("distributed parent unreachable: ${candidate.username}")
            return
        }

        onTrace("distributed candidate connected: ${candidate.username}")
        handleDistributed(session)
    }

    /**
     * Reads a `D` connection until it ends, adopting it as our parent if it forwards a search.
     *
     * A node that advertises branch information but never forwards is not a parent worth having,
     * so an unadopted connection is dropped once the adoption window passes and its slot goes to
     * the next candidate.
     */
    private fun handleDistributed(session: PeerSession) {
        val deadline = System.currentTimeMillis() + PARENT_ADOPTION_WINDOW_MS
        var adopted = false
        var candidateLevel: Long? = null
        var candidateRoot = ""

        try {
            session.readTimeoutMillis = PEER_IDLE_TIMEOUT_MS

            while (true) {
                if (!adopted && System.currentTimeMillis() > deadline) return

                val message = session.read()

                when (message.code) {
                    DistribBranchLevel.CODE -> {
                        candidateLevel = runCatching {
                            DistribBranchLevel.parse(message.body)
                        }.getOrNull()

                        onTrace("candidate ${session.username} is at level $candidateLevel")
                    }

                    DistribBranchRoot.CODE -> {
                        candidateRoot = runCatching {
                            DistribBranchRoot.parse(message.body)
                        }.getOrDefault("")

                        onTrace("candidate ${session.username} root $candidateRoot")
                    }

                    DistribSearch.CODE -> {
                        // A search is the trigger, but only a candidate that has also declared its
                        // position is a parent: without a level and a root we would be attaching to
                        // a node that is not really in the tree.
                        val level = candidateLevel
                        if (level != null && candidateRoot.isNotEmpty()) {
                            adopted = true
                            adoptParent(session, level, candidateRoot)
                        }

                        val search = runCatching { DistribSearch.parse(message.body) }.getOrNull()
                        if (search != null) onTrace("tree search from ${search.username}: ${search.query}")
                    }
                }
            }
        } catch (_: Exception) {
            // Parent gone, or we closed the connection on shutdown.
        } finally {
            session.close()
            if (distributedParent === session) {
                distributedParent = null
                onTrace("distributed parent lost")
            } else if (!adopted) {
                onTrace("distributed candidate gave nothing: ${session.username}")
            }
        }
    }

    private fun adoptParent(session: PeerSession, parentLevel: Long, parentRoot: String) {
        if (distributedParent != null) return

        distributedParent = session
        branchLevel = parentLevel + 1
        branchRoot = parentRoot

        onTrace("distributed parent adopted: ${session.username} (level $branchLevel, root $branchRoot)")

        // Reported to the server, which is what stops it offering more candidates.
        server?.let { connection ->
            connection.send(HaveNoParent.CODE, HaveNoParent.request(noParent = false))
            connection.send(BranchRoot.CODE, BranchRoot.request(branchRoot))
            connection.send(BranchLevel.CODE, BranchLevel.request(branchLevel))
        }
    }

    private fun handleConnectToPeer(body: ByteArray) {
        // Untrusted: a malformed body must not end the session.
        val address = runCatching { ConnectToPeer.parse(body) }.getOrNull() ?: return
        onTrace("relay ${address.username} ${address.connectionType} ${address.ipAddress()}:${address.port}")

        val executor = if (address.connectionType == PeerInit.TYPE_FILE) fileDials else dials
        executor.execute {
            try {
                dialPeer(address) { session, message -> onPeerMessage(session, message) }
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
    private fun dialPeer(address: PeerAddress, onMessage: (PeerSession, Message) -> Unit) {
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

            val session = PeerSession(address.username, address.connectionType, socket)

            if (address.connectionType == PeerInit.TYPE_FILE) {
                // File connections use their own framing entirely, so they take a separate path.
                handleFileConnection(session)
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
                onMessage(session, message)
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

    private fun countShared(root: File): Pair<Long, Long> {        if (!root.isDirectory) return 0L to 0L

        var directories = 0L
        var files = 0L

        root.walkTopDown().forEach { entry ->
            if (entry.isDirectory) directories++ else files++
        }

        return directories to files
    }

    private fun resolveAddress(username: String): UserAddress? {        val connection = checkNotNull(server) { "not connected" }

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

    /**
     * Sends a file a peer asked for.
     *
     * We speak first on this connection: announce the transfer with the token from the
     * negotiation, wait for the peer to say where to resume from, then stream the rest.
     *
     * The connection is closed once everything is sent. The reference warns that the *downloader*
     * closes the connection to signal completion, but the downloader knows the size it asked for
     * and stops at it, so closing after the last byte cannot truncate anything — and leaving the
     * socket open would leak it whenever a peer never comes back.
     */
    private fun serveUpload(session: PeerSession, upload: NegotiatingUpload) {
        try {
            val out = session.outputStream()
            out.write(FileTransfer.Init.encode(upload.token))
            out.flush()

            val offset = FileTransfer.Offset.parse(
                session.inputStream().readExactly(FileTransfer.Offset.BYTES),
            )

            upload.file.inputStream().use { input ->
                FileTransfer.skipBytes(input, offset)

                val sent = FileTransfer.copyBytes(
                    input = input,
                    destination = out,
                    length = upload.file.length() - offset,
                )

                onTrace("uploaded $sent bytes of ${upload.virtualPath} to ${session.username}")
            }
        } catch (e: Exception) {
            // A peer that hangs up mid-transfer is ordinary, not an error worth surfacing.
            onTrace("upload to ${session.username} ended: ${e.javaClass.simpleName}")
        } finally {
            session.close()
        }
    }

    /** Receives a file over an established `F` connection and completes the pending transfer. */
    private fun handleFileConnection(session: PeerSession) {
        // An upload connection is recognised by who opened it, not by what it says: the peer
        // speaks only after we announce the transfer, because we are the uploader.
        //
        // Both maps are consulted because of a race: the peer's acceptance is handled on its peer
        // connection's thread while this connection arrives on the listener's, so the acceptance
        // may not have moved the upload to `pendingUploads` yet. Having offered a file is proof
        // enough that a file connection from that user is the upload.
        val upload = pendingUploads.remove(session.username)
            ?: negotiatingUploads.remove(session.username)

        if (upload != null) {
            serveUpload(session, upload)
            return
        }

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
                    onPeerMessage(session, session.read())
                }
            } catch (_: Exception) {
                // Idle timeout, peer hung up, or framing desync — all end this connection.
            } finally {
                session.close()
            }
        }
    }

    private fun onPeerMessage(session: PeerSession, message: Message) {
        when (message.code) {
            FileSearchResponse.CODE -> {
                // The body is untrusted, so a malformed one must not end the connection.
                val response = try {
                    FileSearchResponse.parse(message.body)
                } catch (_: Exception) {
                    null
                }

                if (response != null) {
                    onTrace("results ${response.files.size} from ${session.username}")
                    searches[response.token]?.invoke(response)
                }
            }

            SharedFileListRequest.CODE -> replyWithSharedFiles(session)

            QueueUpload.CODE -> handleQueueUpload(session, message.body)

            TransferResponse.CODE -> handleUploadAnswer(session, message.body)

            UploadDenied.CODE -> {
                // A peer that asked for a file and then changed its mind, or revoked a queued one.
                val denial = runCatching { UploadDenied.parse(message.body) }.getOrNull()
                if (denial != null) {
                    onTrace("upload denied by ${session.username}: ${denial.reason}")
                    pendingUploads.remove(session.username)
                    negotiatingUploads.remove(session.username)
                }
            }

            // Upload serving lands here once it exists.
            else -> Unit
        }
    }

    /**
     * A peer wants one of our files.
     *
     * Answered with a [TransferRequest] rather than by opening the connection ourselves: the peer
     * dials back, which is what stops a spoofed peer from making us connect out to it.
     */
    private fun handleQueueUpload(session: PeerSession, body: ByteArray) {
        val requested = runCatching { QueueUpload.parse(body) }.getOrNull() ?: return
        val root = shareDirectory ?: return

        val file = runCatching { findSharedFile(root, requested) }.getOrNull()

        if (file == null) {
            onTrace("upload requested but not shared: $requested")
            runCatching {
                session.send(UploadDenied.CODE, UploadDenied.deny(requested, "File not shared."))
            }
            return
        }

        // The token has to exist before the peer answers, so the reply can be matched to it.
        val token = nextToken.getAndIncrement() and 0xFFFF_FFFFL
        negotiatingUploads[session.username] = NegotiatingUpload(token, file, requested)

        onTrace("upload queued by ${session.username}: $requested (${file.length()} bytes)")

        runCatching {
            session.send(TransferRequest.CODE, TransferRequest.request(token, requested, file.length()))
        }.onFailure {
            negotiatingUploads.remove(session.username)
            onTrace("upload request failed: ${it.javaClass.simpleName}")
        }
    }

    /** The peer's answer to an upload we offered: ready to serve, or not. */
    private fun handleUploadAnswer(session: PeerSession, body: ByteArray) {
        val answer = runCatching { TransferResponse.parse(body) }.getOrNull() ?: return
        val negotiating = negotiatingUploads[session.username] ?: return
        if (negotiating.token != answer.token) return

        if (!answer.allowed) {
            onTrace("upload refused by ${session.username}: ${answer.reason}")
            negotiatingUploads.remove(session.username)
            return
        }

        // Moved rather than copied: from here the peer is expected to open the file connection,
        // and it is the username that identifies it as an upload when it arrives.
        negotiatingUploads.remove(session.username)
        pendingUploads[session.username] = negotiating
    }

    /**
     * Resolves a requested virtual path to a file inside the share.
     *
     * Matched against the paths we enumerated rather than joined onto the share root, so a peer
     * cannot reach outside it with `..` — anything it names has to be something we offered.
     */
    private fun findSharedFile(root: File, requested: String): File? {
        val wanted = requested.lowercase()

        return root.walkTopDown()
            .filter { it.isFile }
            .firstOrNull { virtualPathOf(root, it).lowercase() == wanted }
    }

    /** The path a peer sees for a file: rooted at the share folder's name, backslash separated. */
    private fun virtualPathOf(root: File, file: File): String {
        val relative = file.relativeTo(root).invariantSeparatorsPath
        return "${root.name}\\${relative.replace('/', '\\')}"
    }

    /**
     * Answers a browse request with what we share.
     *
     * Failures are swallowed: a stranger asking for our file list must not be able to end the
     * connection, and an unreadable share folder is a local problem rather than theirs.
     */
    private fun replyWithSharedFiles(session: PeerSession) {
        val root = shareDirectory ?: return

        val folders = runCatching { sharedFolders(root) }.getOrNull() ?: return
        onTrace("browsed by ${session.username}: ${folders.size} folder(s)")

        runCatching {
            session.send(SharedFileListResponse.CODE, SharedFileListResponse.encode(folders))
        }.onFailure {
            onTrace("browse reply failed: ${it.javaClass.simpleName}")
        }
    }

    /**
     * The share, grouped the way a browse response wants it: one entry per folder, each listing
     * the files directly inside it.
     *
     * Paths are virtual and use backslashes, rooted at the share folder's own name, so a peer
     * sees `Soulseek\song.flac` rather than anything about where it really lives.
     */
    private fun sharedFolders(root: File): List<SharedFolder> {
        if (!root.isDirectory) return emptyList()

        return root.walkTopDown()
            .filter { it.isDirectory }
            .map { directory ->
                val relative = directory.relativeTo(root).invariantSeparatorsPath
                val virtual = if (relative.isEmpty()) {
                    root.name
                } else {
                    "${root.name}\\${relative.replace('/', '\\')}"
                }

                val files = directory.listFiles().orEmpty()
                    .filter { it.isFile }
                    .sortedBy { it.name.lowercase() }
                    .map { SharedFile(filename = it.name, size = it.length(), attributes = FileAttributes()) }

                SharedFolder(virtual, files)
            }
            .filter { it.files.isNotEmpty() }
            .toList()
    }

    override fun close() {
        closed = true
        searches.clear()
        negotiatingUploads.clear()
        pendingUploads.clear()
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

        /** Candidates dialled per offer. The reference dials every one, and so do we. */
        private const val MAX_PARENT_ATTEMPTS = 10

        /**
         * How long a connected candidate has to declare itself and forward a search.
         *
         * Short on purpose: a candidate that also has us in its own candidate list ignores the
         * connection and says nothing, so waiting long only delays the next batch.
         */
        private const val PARENT_ADOPTION_WINDOW_MS = 60_000L
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

/**
 * An upload we have offered a peer.
 *
 * Held from the moment we send the [TransferRequest] until the file connection is served, because
 * the peer answers with a token and then connects without identifying itself.
 */
private class NegotiatingUpload(
    val token: Long,
    val file: File,
    /** The virtual path the peer asked for, echoed back so it can match the transfer. */
    val virtualPath: String,
)

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
