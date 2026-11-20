package cn.lemwood.keyvault.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.cert.CertificateException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

@Serializable
internal data class IncrementalOffer(val id: String, val manifest: ReplicaManifest)

@Serializable
internal data class IncrementalBundle(val size: Int, val sha256: String) {
    val blocks: Int get() = (size + TRANSFER_BLOCK_BYTES - 1) / TRANSFER_BLOCK_BYTES
    fun lengthAt(index: Int) = minOf(TRANSFER_BLOCK_BYTES, size - index * TRANSFER_BLOCK_BYTES)
}

internal suspend fun runIncrementalClient(
    profileId: String,
    peer: TrustedDevice,
    peers: PairedDevices,
    store: IncrementalStore,
    connect: ((SSLSocket) -> Unit) -> SSLSocket,
    onEvent: suspend (IncrementalEvent) -> Unit,
) {
    val sockets = TransferSocketSlot()
    withTrustedTransferSockets(peers, peer.publicKey, sockets::close) {
        val local = withReplicaStorage { store.replica(profileId) }
        val offer = IncrementalOffer(randomTransferBytes(16).encode(), local.manifest(::replicaDigest))
        validateManifest(offer.manifest)
        var round: IncrementalRound? = null
        try {
            for (attempt in 0 until 6) {
                try {
                    connect(sockets::attach).use { socket ->
                        socket.soTimeout = TRANSFER_TIMEOUT_MS
                        val wire = IncrementalWire.open(socket, client = true)
                        wire.send(IncrementalFrame.ROUND, offer)
                        val remote = wire.receive<ReplicaManifest>(IncrementalFrame.MANIFEST)
                        val session = round ?: IncrementalRound(local, offer.manifest, remote, offer.id, peer).also { round = it }
                        if (remote != session.remote) failTransfer(TransferProblem.REPLAY)
                        try {
                            session.send(wire, onEvent)
                            session.receive(wire, onEvent)
                            wire.expectFinish()
                            session.apply(store)
                            wire.write(IncrementalFrame.FINISH)
                            wire.expectFinish()
                        } catch (failure: TransferException) {
                            sendRejection(wire, failure)
                            throw failure
                        }
                        onEvent(session.result())
                    }
                    return@withTrustedTransferSockets
                } catch (failure: IOException) {
                    currentCoroutineContext().ensureActive()
                    if (generateSequence<Throwable>(failure) { it.cause }.any { it is CertificateException }) {
                        failTransfer(TransferProblem.UNTRUSTED)
                    }
                    if (attempt == 5) failTransfer(TransferProblem.CONNECTION)
                    onEvent(IncrementalEvent.Interrupted(TransferProblem.CONNECTION))
                    delay(500L shl attempt)
                } finally {
                    sockets.release()
                }
            }
        } finally {
            round?.close()
        }
    }
}

internal class JvmIncrementalReceiver(
    private val listener: SyncTlsListener,
    private val link: LanAddress,
    private val peer: TrustedDevice,
    private val profileId: String,
    localKey: String,
    private val peers: PairedDevices,
) : IncrementalReceiver, Closeable {
    private val sockets = TransferSocketSlot()
    private val running = AtomicBoolean()
    override val address = "${link.ip.hostAddress}:${listener.localPort}"
    override val code = TransferEndpoint(InetSocketAddress(link.ip, listener.localPort), localKey).code(incremental = true)

    override suspend fun run(store: IncrementalStore, onEvent: suspend (IncrementalEvent) -> Unit) {
        if (!running.compareAndSet(false, true)) failTransfer(TransferProblem.RECEIVER_BUSY)
        IncrementalRounds(store, limit = 1, singleRound = true).use { rounds ->
            withTrustedTransferSockets(peers, peer.publicKey, ::close) {
                listener.setAcceptTimeout(500)
                while (!sockets.isClosed()) {
                    currentCoroutineContext().ensureActive()
                    val socket = try {
                        listener.acceptSocket()
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: IOException) {
                        if (sockets.isClosed()) break else failTransfer(TransferProblem.CONNECTION)
                    }
                    try {
                        socket.use {
                            val remote = socket.inetAddress as? Inet4Address
                            if (remote == null || !link.contains(remote)) return@use
                            sockets.attach(socket)
                            socket.soTimeout = TRANSFER_TIMEOUT_MS
                            socket.startHandshake()
                            val wire = IncrementalWire.open(socket, client = false)
                            rounds.handle(wire, peer, { event ->
                                if (event is IncrementalEvent.Saved) peers.rememberAddress(peer.publicKey, remote)
                                onEvent(event)
                            }) { profileId }
                        }
                    } catch (_: IOException) {
                        if (rounds.started && !sockets.isClosed()) onEvent(IncrementalEvent.Interrupted(TransferProblem.CONNECTION))
                    } catch (failure: TransferException) {
                        onEvent(IncrementalEvent.Interrupted(failure.problem))
                    } finally {
                        sockets.release()
                    }
                }
            }
        }
    }

    override fun close() {
        sockets.close()
        runCatching { listener.close() }
    }
}

internal class IncrementalRounds(
    private val store: IncrementalStore,
    private val limit: Int,
    private val singleRound: Boolean = false,
) : Closeable {
    private data class Entry(val round: IncrementalRound, val profile: String, var lastSeen: Long = System.nanoTime())
    private val rounds = linkedMapOf<Pair<String, String>, Entry>()
    val started get() = rounds.isNotEmpty()

    suspend fun handle(wire: IncrementalWire, peer: TrustedDevice, onEvent: suspend (IncrementalEvent) -> Unit,
        resolve: suspend (ReplicaManifest) -> String) {
        try {
            val offer = wire.receive<IncrementalOffer>(IncrementalFrame.ROUND)
            if (!validTransferField(offer.id, 16)) failTransfer(TransferProblem.INTEGRITY)
            val key = peer.publicKey to offer.id
            val profileId = resolve(offer.manifest)
            val session = rounds[key]?.also { existing ->
                if (profileId != existing.profile || offer.manifest != existing.round.remote) failTransfer(TransferProblem.REPLAY)
                existing.lastSeen = System.nanoTime()
            }?.round ?: newRound(offer, peer, profileId, key)

            wire.send(IncrementalFrame.MANIFEST, session.ours)
            session.receive(wire, onEvent)
            session.send(wire, onEvent)
            session.apply(store)
            wire.write(IncrementalFrame.FINISH)
            wire.expectFinish()
            wire.write(IncrementalFrame.FINISH)
            onEvent(session.result())
        } catch (failure: TransferException) {
            sendRejection(wire, failure)
            onEvent(IncrementalEvent.Interrupted(failure.problem))
        }
    }

    private suspend fun newRound(offer: IncrementalOffer, peer: TrustedDevice, profileId: String,
        key: Pair<String, String>): IncrementalRound {
        if (!singleRound) {
            val now = System.nanoTime()
            val expired = rounds.filterValues { now - it.lastSeen > 120_000_000_000L }.keys
            expired.forEach { rounds.remove(it)?.round?.close() }
            if (rounds.size >= limit) {
                rounds.entries.firstOrNull { it.value.round.committed }?.key?.let { rounds.remove(it)?.round?.close() }
            }
        }
        if (rounds.size >= limit) failTransfer(TransferProblem.RECEIVER_BUSY)
        val local = withReplicaStorage { store.replica(profileId) }
        val ours = local.manifest(::replicaDigest)
        validateManifest(ours)
        val round = IncrementalRound(local, ours, offer.manifest, offer.id, peer)
        try {
            if (withReplicaStorage { store.status(round.receipt) } != TransferStatus.MISSING) failTransfer(TransferProblem.REPLAY)
            rounds[key] = Entry(round, profileId)
        } catch (failure: Exception) {
            round.close()
            throw failure
        }
        return round
    }

    override fun close() {
        rounds.values.forEach { it.round.close() }
        rounds.clear()
    }
}

private class IncrementalRound(
    private val snapshot: ReplicaSnapshot,
    val ours: ReplicaManifest,
    val remote: ReplicaManifest,
    val id: String,
    peer: TrustedDevice,
) : Closeable {
    private val peerProfile = "${peer.publicKey}:${remote.profileId}"
    init {
        validateManifest(remote)
        if (snapshot.bindings()[peer.publicKey]?.let { it != remote.profileId } == true) failTransfer(TransferProblem.WRONG_REPLICA)
    }

    private val records = snapshot.recordsFor(ours, remote)
    private val outbound = replicaJson.encodeToString(records).encodeToByteArray()
    private val bundle = IncrementalBundle(outbound.size, checksum(outbound).encode())
    private var inbound: IncrementalBundle? = null
    private var staged = byteArrayOf()
    private var next = 0
    private var applied: IncrementalEvent.Saved? = null
    val committed get() = applied != null
    val receipt = TransferReceipt(
        "incremental_${peer.publicKey}", id,
        checksum(replicaJson.encodeToString(listOf(ours, remote)).encodeToByteArray()).encode(),
    )

    suspend fun send(wire: IncrementalWire, status: suspend (IncrementalEvent) -> Unit) {
        if (bundle.size > MAX_REPLICA_BYTES) failTransfer(TransferProblem.TOO_LARGE)
        wire.send(IncrementalFrame.BUNDLE, bundle)
        val start = wire.receive<Int>(IncrementalFrame.WINDOW, 16)
        if (start !in 0..bundle.blocks) failTransfer(TransferProblem.INTEGRITY)
        status(IncrementalEvent.Progress(TransferProgress(start, bundle.blocks)))
        for (index in start until bundle.blocks) {
            currentCoroutineContext().ensureActive()
            val bytes = ByteArray(bundle.lengthAt(index) + 4)
            try {
                ByteBuffer.wrap(bytes).putInt(index)
                val offset = index * TRANSFER_BLOCK_BYTES
                outbound.copyInto(bytes, 4, offset, offset + bundle.lengthAt(index))
                wire.write(IncrementalFrame.CHUNK, bytes)
            } finally { bytes.fill(0) }
            if (wire.receive<Int>(IncrementalFrame.ACK, 16) != index + 1) failTransfer(TransferProblem.INTEGRITY)
            status(IncrementalEvent.Progress(TransferProgress(index + 1, bundle.blocks)))
        }
    }

    suspend fun receive(wire: IncrementalWire, status: suspend (IncrementalEvent) -> Unit) {
        val offer = wire.receive<IncrementalBundle>(IncrementalFrame.BUNDLE, 256)
        if (offer.size > MAX_REPLICA_BYTES) failTransfer(TransferProblem.TOO_LARGE)
        if (offer.size < 2 || !validTransferField(offer.sha256, 32)) failTransfer(TransferProblem.INTEGRITY)
        if (inbound != null && inbound != offer) failTransfer(TransferProblem.REPLAY)
        if (inbound == null) {
            inbound = offer
            staged = ByteArray(offer.size)
        }
        wire.send(IncrementalFrame.WINDOW, next)
        status(IncrementalEvent.Progress(TransferProgress(next, offer.blocks)))
        while (next < offer.blocks) {
            val length = offer.lengthAt(next)
            val bytes = wire.read(IncrementalFrame.CHUNK, length + 4)
            try {
                if (bytes.size != length + 4 || ByteBuffer.wrap(bytes).int != next) failTransfer(TransferProblem.INTEGRITY)
                bytes.copyInto(staged, next * TRANSFER_BLOCK_BYTES, 4)
            } finally { bytes.fill(0) }
            next++
            wire.send(IncrementalFrame.ACK, next)
            status(IncrementalEvent.Progress(TransferProgress(next, offer.blocks)))
        }
        if (checksum(staged).encode() != offer.sha256) failTransfer(TransferProblem.INTEGRITY)
    }

    suspend fun apply(store: IncrementalStore) {
        if (applied != null) return
        val changes = decodeIncremental<List<ReplicaRecord>>(staged)
        val update = mergeReplicas(snapshot, ours, remote, changes, ::replicaDigest)
        withReplicaStorage { store.applyReplica(snapshot, peerProfile, update, receipt) }
        applied = IncrementalEvent.Saved(records.size, changes.size, update.conflicts.size)
    }

    fun result(): IncrementalEvent.Saved = requireNotNull(applied)

    override fun close() {
        staged.fill(0)
        outbound.fill(0)
    }
}

private fun validateManifest(manifest: ReplicaManifest) {
    fun validId(text: String) = text.isNotBlank() && text.length <= 256 && text.none(Char::isISOControl)
    if (!validId(manifest.profileId) || manifest.name.isBlank() || manifest.name.length > 1024 ||
        manifest.headers.size > 100_000
    ) failTransfer(TransferProblem.INTEGRITY)
    val paths = manifest.headers.mapTo(mutableSetOf(), ReplicaHeader::path)
    if (paths.size != manifest.headers.size) failTransfer(TransferProblem.INTEGRITY)
    for ((path, version, digest) in manifest.headers) {
        if (!validId(path.service) || path.item?.let { !validId(it) } == true ||
            path.key?.let { path.item == null || !validId(it) } == true ||
            path.parent()?.let { it !in paths } == true ||
            !validTransferFieldId(version.deviceId) || version.counters.size > 64 ||
            version.counters.any { (device, count) -> !validTransferFieldId(device) || count !in 1..Long.MAX_VALUE / 2 } ||
            version.updatedAt !in 0..Long.MAX_VALUE / 2 ||
            version.deletedAt?.let { !version.deleted || it !in 1..version.updatedAt } == true ||
            if (version.deleted) digest != null else digest == null || !validTransferField(digest, 32)
        ) failTransfer(TransferProblem.INTEGRITY)
    }
}

private fun validTransferFieldId(id: String) = id.length == 32 && id.all { it in '0'..'9' || it in 'a'..'f' }

private fun IncrementalWire.expectFinish() { read(IncrementalFrame.FINISH, 0) }

private fun sendRejection(wire: IncrementalWire, failure: TransferException) {
    runCatching { wire.reject(failure.problem) }
}

private inline fun <T> withReplicaStorage(action: () -> T): T = try {
    action()
} catch (failure: TransferException) {
    throw failure
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    failTransfer(TransferProblem.STORAGE)
}
