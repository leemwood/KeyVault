package cn.lemwood.keyvault.sync

import cn.lemwood.keyvault.data.backup.BackupException
import cn.lemwood.keyvault.data.backup.BackupProblem
import cn.lemwood.keyvault.data.backup.decodeProfilePayload
import cn.lemwood.keyvault.data.backup.encodeProfilePayload
import cn.lemwood.keyvault.data.model.VaultProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

internal class JvmTransferSender private constructor(
    private val payload: ByteArray,
    internal val manifest: TransferManifest,
    private val peers: PairedDevices,
    private val connect: suspend (TransferEndpoint, (SSLSocket) -> Unit) -> SSLSocket,
) : TransferSender {
    private var active: TransferSocketSlot? = null
    private var closed = false

    override suspend fun send(code: String, receiver: TrustedDevice, onProgress: suspend (TransferProgress) -> Unit) =
        withPeerTrust(peers, receiver.publicKey) { sendTo(code, receiver, onProgress) }

    private suspend fun sendTo(code: String, receiver: TrustedDevice, onProgress: suspend (TransferProgress) -> Unit) {
        val endpoint = readTransferCode(code)
        if (endpoint.peerKey != receiver.publicKey) failTransfer(TransferProblem.UNTRUSTED)
        val sockets = synchronized(this) {
            if (closed || active != null) failTransfer(TransferProblem.RECEIVER_BUSY)
            TransferSocketSlot().also { active = it }
        }
        try {
            withTransferSockets(sockets::close) {
                var acknowledged = 0
                val total = manifest.chunkCount
                for (attempt in 0 until 5) {
                    try {
                        connect(endpoint, sockets::attach).use { socket ->
                            socket.soTimeout = TRANSFER_TIMEOUT_MS
                            sendTransfer(TransferWire(socket)) { next ->
                                acknowledged = next
                                onProgress(TransferProgress(next, total))
                            }
                        }
                        return@withTransferSockets
                    } catch (failure: IOException) {
                        currentCoroutineContext().ensureActive()
                        if (sockets.isClosed()) failTransfer(TransferProblem.CONNECTION)
                        if (generateSequence<Throwable>(failure) { it.cause }.any { it is CertificateException }) {
                            failTransfer(TransferProblem.UNTRUSTED)
                        }
                        if (attempt == 4) failTransfer(TransferProblem.CONNECTION)
                        onProgress(TransferProgress(acknowledged, total, reconnecting = true))
                        delay(500L shl attempt)
                    } finally {
                        sockets.release()
                    }
                }
            }
        } finally {
            synchronized(this) {
                active = null
                if (closed) payload.fill(0)
            }
        }
    }

    private suspend fun sendTransfer(wire: TransferWire, progress: suspend (Int) -> Unit) {
        wire.sendHello()
        wire.writeManifest(manifest)
        if (wire.readReply() == TransferReply.COMPLETE) {
            progress(manifest.chunkCount)
            return
        }
        val start = wire.input.readInt()
        if (start !in 0..manifest.chunkCount) failTransfer(TransferProblem.INTEGRITY)
        val key = wire.readKey()
        try {
            val signer = TransferChunkMac(key, checksum(manifest.bytes()))
            progress(start)
            for (index in start until manifest.chunkCount) {
                currentCoroutineContext().ensureActive()
                val offset = index * manifest.chunkBytes
                val length = manifest.lengthAt(index)
                wire.writeChunk(index, payload, offset, length, signer.tag(index, payload, offset, length))
                wire.expect(TransferReply.READY)
                progress(index + 1)
            }
            wire.requestCommit()
            wire.expect(TransferReply.COMPLETE)
        } finally {
            key.fill(0)
        }
    }

    override fun close() = synchronized(this) {
        closed = true
        val sending = active
        if (sending == null) payload.fill(0) else sending.close()
    }

    companion object {
        suspend fun prepare(
            profile: VaultProfile,
            peers: PairedDevices,
            connect: suspend (TransferEndpoint, (SSLSocket) -> Unit) -> SSLSocket,
        ): JvmTransferSender {
            var prepared: JvmTransferSender? = null
            try {
                return withContext(Dispatchers.Default) {
                    val bytes = transferPayload { encodeProfilePayload(profile) }
                    try {
                        JvmTransferSender(bytes, TransferManifest.create(profile, bytes), peers, connect).also { prepared = it }
                    } catch (failure: Exception) {
                        bytes.fill(0)
                        throw failure
                    }
                }
            } catch (failure: Exception) {
                prepared?.close()
                throw failure
            }
        }
    }
}

internal class JvmTransferReceiver(
    private val listener: SyncTlsListener,
    private val link: LanAddress,
    sender: TrustedDevice,
    receiver: TrustedDevice,
    private val peers: PairedDevices,
) : TransferReceiver, Closeable {
    private val sockets = TransferSocketSlot()
    private val started = AtomicBoolean()
    private val senderKey = sender.publicKey
    private var current: TransferManifest? = null
    private var pending: IncomingChunks? = null
    private val endpoint = TransferEndpoint(InetSocketAddress(link.ip, listener.localPort), receiver.publicKey)
    override val address = "${link.ip.hostAddress}:${listener.localPort}"
    override val code = endpoint.code()

    override suspend fun receive(store: TransferStore, onEvent: suspend (TransferEvent) -> Unit) {
        if (!started.compareAndSet(false, true)) failTransfer(TransferProblem.RECEIVER_BUSY)
        try {
            withTrustedTransferSockets(peers, senderKey, ::close) {
                listener.setAcceptTimeout(500)
                while (!sockets.isClosed()) {
                    currentCoroutineContext().ensureActive()
                    val socket = try {
                        listener.acceptSocket()
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: IOException) {
                        if (sockets.isClosed()) break
                        failTransfer(TransferProblem.CONNECTION)
                    }
                    socket.use {
                        try {
                            val remote = socket.inetAddress as? Inet4Address
                            if (remote != null && link.contains(remote)) {
                                sockets.attach(socket)
                                socket.soTimeout = TRANSFER_TIMEOUT_MS
                                socket.startHandshake()
                                receiveTransfer(TransferWire(socket), store, onEvent)
                            }
                        } catch (_: IOException) {
                            if (pending != null && !sockets.isClosed()) {
                                onEvent(TransferEvent.Interrupted(TransferProblem.CONNECTION))
                            }
                        } finally {
                            sockets.release()
                        }
                    }
                }
            }
        } finally {
            pending?.close()
            pending = null
        }
    }

    private suspend fun receiveTransfer(wire: TransferWire, store: TransferStore, onEvent: suspend (TransferEvent) -> Unit) {
        try {
            wire.acceptHello()
            val manifest = wire.readManifest()
            val hash = checksum(manifest.bytes())
            val receipt = TransferReceipt(senderKey, manifest.transferId, hash.encode())
            when (withTransferStorage { store.transferStatus(receipt) }) {
                TransferStatus.CONFLICT -> failTransfer(TransferProblem.CONFLICT)
                TransferStatus.SAVED -> {
                    wire.reply(TransferReply.COMPLETE)
                    return
                }
                TransferStatus.MISSING -> Unit
            }
            current?.let { original ->
                if (original.transferId != manifest.transferId) failTransfer(TransferProblem.RECEIVER_BUSY)
                if (original != manifest) failTransfer(TransferProblem.CONFLICT)
            }
            current = manifest
            val chunks = pending ?: IncomingChunks(manifest).also { pending = it }
            val key = randomTransferBytes()
            try {
                val signer = TransferChunkMac(key, hash)
                wire.resumeAt(chunks.next, key)
                onEvent(TransferEvent.Progress(chunks.progress()))
                while (chunks.next < manifest.chunkCount) {
                    currentCoroutineContext().ensureActive()
                    val block = wire.readChunk(chunks.next, manifest.lengthAt(chunks.next), signer)
                    try {
                        chunks.append(block)
                    } finally {
                        block.fill(0)
                    }
                    wire.reply(TransferReply.READY)
                    onEvent(TransferEvent.Progress(chunks.progress()))
                }
            } finally {
                key.fill(0)
            }
            wire.expectCommit()
            val profile = try {
                chunks.profile()
            } catch (failure: TransferException) {
                chunks.close()
                pending = null
                throw failure
            }
            if (withTransferStorage { store.commitTransfer(receipt, profile) } != TransferStatus.SAVED) {
                failTransfer(TransferProblem.CONFLICT)
            }
            chunks.close()
            pending = null
            onEvent(TransferEvent.Saved)
            wire.reply(TransferReply.COMPLETE)
        } catch (failure: TransferException) {
            onEvent(TransferEvent.Interrupted(failure.problem))
            wire.reject(failure.problem)
        }
    }

    override fun close() {
        sockets.close()
        runCatching { listener.close() }
    }
}

private class IncomingChunks(private val manifest: TransferManifest) : Closeable {
    private val bytes = ByteArray(manifest.totalBytes)
    var next = 0
        private set

    fun append(block: ByteArray) {
        block.copyInto(bytes, next * manifest.chunkBytes)
        next++
    }

    fun progress() = TransferProgress(next, manifest.chunkCount)

    fun profile(): VaultProfile {
        if (checksum(bytes).encode() != manifest.checksum) failTransfer(TransferProblem.INTEGRITY)
        val profile = transferPayload { decodeProfilePayload(bytes) }
        if (profile.transferEntryCount() != manifest.entries) failTransfer(TransferProblem.INTEGRITY)
        return profile
    }

    override fun close() = bytes.fill(0)
}

internal class TransferSocketSlot : Closeable {
    private var active: SSLSocket? = null
    private var closed = false

    @Synchronized fun attach(socket: SSLSocket) {
        if (closed) throw IOException("Transfer closed")
        active = socket
    }

    @Synchronized fun release() { active = null }

    @Synchronized fun isClosed(): Boolean = closed

    @Synchronized override fun close() {
        closed = true
        runCatching { active?.close() }
        active = null
    }
}

internal suspend fun <T> withTransferSockets(close: () -> Unit, block: suspend () -> T): T = coroutineScope {
    val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { close() }
    }
    try {
        withContext(Dispatchers.IO) { block() }
    } finally {
        canceller.cancel()
    }
}

internal suspend fun <T> withTrustedTransferSockets(
    peers: PairedDevices, publicKey: String, close: () -> Unit, block: suspend () -> T,
): T = withPeerTrust(peers, publicKey) { withTransferSockets(close, block) }

private inline fun <T> transferPayload(block: () -> T): T = try {
    block()
} catch (failure: BackupException) {
    failTransfer(if (failure.problem == BackupProblem.TOO_LARGE) TransferProblem.TOO_LARGE else TransferProblem.INTEGRITY)
}

private inline fun <T> withTransferStorage(block: () -> T): T = try {
    block()
} catch (failure: TransferException) {
    throw failure
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    failTransfer(TransferProblem.STORAGE)
}
