package cn.lemwood.keyvault.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cn.lemwood.keyvault.data.model.VaultProfile
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

private const val ENROLLMENT_READ_MS = 5_000

internal data class LanAddress(val ip: Inet4Address, val prefixLength: Int) {
    fun contains(peer: Inet4Address): Boolean {
        if (prefixLength !in 1..32 || ip.isLoopbackAddress != peer.isLoopbackAddress ||
            peer.isAnyLocalAddress || peer.isMulticastAddress
        ) return false
        var bits = prefixLength
        return ip.address.zip(peer.address).all { (first, second) ->
            val count = bits.coerceIn(0, 8)
            bits -= count
            val mask = (0xff shl (8 - count)) and 0xff
            ((first.toInt() xor second.toInt()) and mask) == 0
        }
    }
}

internal interface LanDiscovery {
    fun addresses(): List<LanAddress>
    fun discover(): Flow<List<String>>
    suspend fun announce(address: Inet4Address, port: Int, name: String)
}

internal class JvmPairingDevice(
    private val tls: SyncTlsIdentity,
    private val signer: PairingSigner,
    name: String,
    store: DataStore<Preferences>,
    private val discovery: LanDiscovery,
) : PairingDevice {
    private val peers = PairedDevices(store)
    private val local = PeerIdentity(
        name.filterNot { it.isISOControl() || it.category == CharCategory.FORMAT }.trim().take(48).ifBlank { "KeyVault" },
        signer.publicKey,
        tls.certificatePin,
    )

    override suspend fun addresses(): List<String> = withContext(Dispatchers.IO) {
        discovery.addresses().mapNotNull { it.ip.hostAddress }.distinct()
    }

    override suspend fun trustedDevices(): List<TrustedDevice> = peers.all().map(PeerIdentity::publicDevice)

    override suspend fun revokeDevice(peerKey: String) = peers.revoke(peerKey)

    override suspend fun runSyncWindow(store: IncrementalStore, onReady: () -> Unit) =
        runBackgroundSyncWindow(tls, signer.publicKey, peers, discovery, store, onReady)

    override fun discover() = discovery.discover()

    override suspend fun invite(address: String): PairingInvitation = openResource {
        val ip = parseIpv4(address)
        val link = discovery.addresses().firstOrNull { it.ip == ip }
            ?: throw PairingException(PairingProblem.NOT_SAME_SUBNET)
        val listener = networkOperation { tls.listenForPairing(InetSocketAddress(ip, 0)) }
        try {
            val ephemeral = newEphemeralKey()
            val offer = PairingOffer(
                address = address,
                port = listener.localPort,
                expiresAt = System.currentTimeMillis() + PAIRING_TIMEOUT_MS,
                nonce = pairingNonce(),
                ephemeralKey = ephemeral.generatePublicKey().encoded.encode(),
                host = local,
            )
            Invitation(listener, offer.signed(signer), ephemeral, link, peers, discovery)
        } catch (failure: Exception) {
            listener.closeQuietly()
            throw failure
        }
    }

    override suspend fun join(code: String): PairingApproval = openResource {
        val offer = readOffer(code, System.currentTimeMillis()).offer
        if (offer.host.publicKey == local.publicKey || offer.host.tlsPin == local.tlsPin) {
            throw PairingException(PairingProblem.REJECTED)
        }
        val address = parseIpv4(offer.address)
        if (discovery.addresses().none { it.contains(address) }) throw PairingException(PairingProblem.NOT_SAME_SUBNET)
        peers.validate(offer.host)
        val deadline = PairingDeadline((offer.expiresAt - System.currentTimeMillis()).coerceAtMost(PAIRING_TIMEOUT_MS))
        networkOperation {
            val socket = tls.connect(InetSocketAddress(address, offer.port), offer.host.tlsPin)
            try {
                socket.soTimeout = deadline.remaining(ENROLLMENT_READ_MS)
                val ephemeral = newEphemeralKey()
                val reply = PairingReply(local, ephemeral.generatePublicKey().encoded.encode())
                val comparison = comparisonCode(ephemeral, offer.ephemeralKey, offer, reply)
                DataOutputStream(socket.outputStream).writeReply(reply.signed(offer, signer))
                if (DataInputStream(socket.inputStream).readUnsignedByte() != 1) throw PairingException(PairingProblem.REJECTED)
                Approval(socket, offer.host, comparison, deadline, offer.expiresAt, peers)
            } catch (failure: Exception) {
                socket.closeQuietly()
                throw failure
            }
        }
    }

    override fun qr(code: String): PairingQr {
        val matrix = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 1, 1)
        return PairingQr(matrix.width, BooleanArray(matrix.width * matrix.height) { index ->
            matrix[index % matrix.width, index / matrix.width]
        })
    }

    override suspend fun prepareTransfer(profile: VaultProfile): TransferSender =
        JvmTransferSender.prepare(profile, peers) { endpoint, attach ->
            val address = endpoint.socketAddress.address as Inet4Address
            if (discovery.addresses().none { it.contains(address) }) failTransfer(TransferProblem.OTHER_SUBNET)
            val peer = transferPeer(endpoint.peerKey)
            tls.connect(endpoint.socketAddress, peer.tlsPin, attach)
        }

    override suspend fun listenForTransfer(address: String, sender: TrustedDevice): TransferReceiver = openResource {
        val link = discovery.addresses().firstOrNull { it.ip.hostAddress == address }
            ?: failTransfer(TransferProblem.OTHER_SUBNET)
        val identity = transferPeer(sender.publicKey)
        val listener = try {
            tls.listen(InetSocketAddress(link.ip, 0), identity.tlsPin)
        } catch (_: IOException) {
            failTransfer(TransferProblem.CONNECTION)
        }
        JvmTransferReceiver(listener, link, sender, local.publicDevice(), peers)
    }

    override suspend fun listenForIncremental(address: String, peer: TrustedDevice, profileId: String): IncrementalReceiver = openResource {
        val link = discovery.addresses().firstOrNull { it.ip.hostAddress == address }
            ?: failTransfer(TransferProblem.OTHER_SUBNET)
        val identity = transferPeer(peer.publicKey)
        val listener = try {
            tls.listen(InetSocketAddress(link.ip, 0), identity.tlsPin)
        } catch (_: IOException) {
            failTransfer(TransferProblem.CONNECTION)
        }
        JvmIncrementalReceiver(listener, link, peer, profileId, local.publicKey, peers)
    }

    override suspend fun syncIncremental(code: String, peer: TrustedDevice, profileId: String, store: IncrementalStore,
        onEvent: suspend (IncrementalEvent) -> Unit) {
        val endpoint = readTransferCode(code, incremental = true)
        if (endpoint.peerKey != peer.publicKey) failTransfer(TransferProblem.UNTRUSTED)
        val identity = transferPeer(peer.publicKey)
        runIncrementalClient(profileId, peer, peers, store, { attach ->
            val address = endpoint.socketAddress.address as Inet4Address
            if (discovery.addresses().none { it.contains(address) }) failTransfer(TransferProblem.OTHER_SUBNET)
            tls.connect(endpoint.socketAddress, identity.tlsPin, attach)
        }, onEvent)
        peers.rememberAddress(peer.publicKey, endpoint.socketAddress.address)
    }

    private suspend fun transferPeer(key: String): PeerIdentity = try {
        peers.requirePeer(key)
    } catch (failure: PairingException) {
        failTransfer(if (failure.problem == PairingProblem.STORAGE) TransferProblem.STORAGE else TransferProblem.UNTRUSTED)
    }

    internal suspend fun connectTrusted(address: InetSocketAddress, deviceKey: String): SSLSocket = openResource {
        val peer = peers.requirePeer(deviceKey)
        networkOperation { tls.connect(address, peer.tlsPin) }
    }

    internal suspend fun listenTrusted(address: InetSocketAddress, deviceKey: String): SyncTlsListener = openResource {
        val peer = peers.requirePeer(deviceKey)
        networkOperation { tls.listen(address, peer.tlsPin) }
    }
}

private class Invitation(
    private val listener: SyncTlsListener,
    private val signed: SignedOffer,
    private val ephemeral: X25519PrivateKeyParameters,
    private val link: LanAddress,
    private val peers: PairedDevices,
    private val discovery: LanDiscovery,
) : PairingInvitation, Closeable {
    private val offer = signed.offer
    private val deadline = PairingDeadline(PAIRING_TIMEOUT_MS)
    private val started = AtomicBoolean()
    private val lock = Any()
    private var closed = false
    private var negotiating: SSLSocket? = null

    override val code = signed.toCode()
    override val expiresAt = offer.expiresAt

    override suspend fun awaitPeer(): PairingApproval {
        if (!started.compareAndSet(false, true)) throw PairingException(PairingProblem.REJECTED)
        var approval: Approval? = null
        try {
            return coroutineScope {
                val expiration = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        delay(deadline.remaining().toLong())
                    } finally {
                        close()
                    }
                }
                val announcement = launch {
                    try {
                        discovery.announce(link.ip, offer.port, offer.host.name)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The QR already carries the direct address.
                    }
                }
                try {
                    openResource { acceptPairing() }.also { approval = it }
                } finally {
                    close()
                    expiration.cancel()
                    announcement.cancel()
                }
            }
        } catch (failure: Exception) {
            approval?.close()
            if (failure is IOException && deadline.expired()) throw PairingException(PairingProblem.EXPIRED)
            throw failure
        }
    }

    private suspend fun acceptPairing(): Approval {
        while (true) {
            currentCoroutineContext().ensureActive()
            listener.setAcceptTimeout(deadline.remaining(500))
            val socket = try {
                listener.acceptSocket()
            } catch (_: SocketTimeoutException) {
                continue
            }
            var transferred = false
            try {
                val remote = socket.inetAddress as? Inet4Address
                if (remote == null || !link.contains(remote)) continue
                synchronized(lock) {
                    if (closed) throw PairingException(PairingProblem.REJECTED)
                    negotiating = socket
                }
                socket.soTimeout = deadline.remaining(ENROLLMENT_READ_MS)
                socket.startHandshake()
                val signedReply = DataInputStream(socket.inputStream).readReply()
                signedReply.verify(offer, socket.remoteCertificatePin())
                val reply = signedReply.reply
                if (reply.peer.tlsPin == offer.host.tlsPin) throw PairingException(PairingProblem.REJECTED)
                peers.validate(reply.peer)
                val comparison = comparisonCode(ephemeral, reply.ephemeralKey, offer, reply)
                socket.soTimeout = deadline.remaining()
                DataOutputStream(socket.outputStream).run { writeByte(1); flush() }
                synchronized(lock) {
                    if (closed) throw PairingException(PairingProblem.REJECTED)
                    negotiating = null
                    transferred = true
                }
                // This invitation grants a single approval, even if the users later reject it.
                return Approval(socket, reply.peer, comparison, deadline, expiresAt, peers)
            } catch (problem: PairingException) {
                if (problem.problem == PairingProblem.EXPIRED || problem.problem == PairingProblem.STORAGE) throw problem
            } catch (_: IOException) {
                // A connection closed or failed authentication. Keep the original invitation available.
            } finally {
                synchronized(lock) { negotiating = null }
                if (!transferred) socket.closeQuietly()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            listener.closeQuietly()
            negotiating?.closeQuietly()
        }
    }
}

private class Approval(
    private val socket: SSLSocket,
    private val identity: PeerIdentity,
    override val comparisonCode: String,
    private val deadline: PairingDeadline,
    override val expiresAt: Long,
    private val peers: PairedDevices,
) : PairingApproval, Closeable {
    private val used = AtomicBoolean()
    override val peer = identity.publicDevice()

    override suspend fun confirm() = withContext(Dispatchers.IO) {
        if (!used.compareAndSet(false, true)) throw PairingException(PairingProblem.REJECTED)
        socket.use {
            try {
                networkOperation {
                    socket.soTimeout = deadline.remaining()
                    val input = DataInputStream(socket.inputStream)
                    val output = DataOutputStream(socket.outputStream)
                    output.writeByte(1)
                    output.flush()
                    if (input.readUnsignedByte() != 1) throw PairingException(PairingProblem.REJECTED)
                    socket.soTimeout = deadline.remaining()
                    peers.remember(identity, socket.inetAddress)
                    output.writeByte(1)
                    output.flush()
                    if (input.readUnsignedByte() != 1) throw PairingException(PairingProblem.REJECTED)
                }
            } catch (failure: PairingException) {
                if (deadline.expired()) throw PairingException(PairingProblem.EXPIRED)
                throw failure
            }
        }
    }

    override fun close() = socket.closeQuietly()
}

private class PairingDeadline(duration: Long) {
    private val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(duration)

    fun expired(): Boolean = until - System.nanoTime() <= 0

    fun remaining(max: Int = PAIRING_TIMEOUT_MS.toInt()): Int {
        val nanos = until - System.nanoTime()
        if (nanos <= 0) throw PairingException(PairingProblem.EXPIRED)
        return (TimeUnit.NANOSECONDS.toMillis(nanos - 1) + 1).toInt().coerceAtMost(max)
    }
}

internal class PairedDevices(private val store: DataStore<Preferences>) {
    private val key = stringPreferencesKey("lan_paired_devices_v1")
    private val addresses = stringPreferencesKey("lan_peer_addresses_v1")

    suspend fun all(): List<PeerIdentity> = storageOperation { read(store.data.first()) }

    suspend fun requirePeer(publicKey: String): PeerIdentity = all().firstOrNull { it.publicKey == publicKey }
        ?: throw PairingException(PairingProblem.REJECTED)

    suspend fun validate(device: PeerIdentity) = checkIdentity(all(), device)

    fun observe() = store.data.map(::read)

    suspend fun pairedAddresses(): Map<String, String> = storageOperation { readAddresses(store.data.first()) }

    suspend fun revoke(publicKey: String) = storageOperation {
        store.edit { prefs ->
            val remaining = read(prefs).filterNot { it.publicKey == publicKey }
            prefs[key] = Json.encodeToString(remaining)
            prefs[addresses] = Json.encodeToString(readAddresses(prefs) - publicKey)
            prefs[revokedPeersKey] = prefs[revokedPeersKey].orEmpty() + publicKey
        }
        Unit
    }

    suspend fun rememberAddress(publicKey: String, remote: java.net.InetAddress) = storageOperation {
        if (remote is Inet4Address) {
            store.edit { prefs ->
                if (read(prefs).any { it.publicKey == publicKey }) {
                    prefs[addresses] = Json.encodeToString(readAddresses(prefs) + (publicKey to requireNotNull(remote.hostAddress)))
                }
            }
        }
        Unit
    }

    suspend fun remember(device: PeerIdentity, remote: java.net.InetAddress? = null) = storageOperation {
        store.edit { prefs ->
            val current = read(prefs)
            checkIdentity(current, device)
            prefs[key] = Json.encodeToString(current.filterNot { it.publicKey == device.publicKey } + device)
            prefs[revokedPeersKey] = prefs[revokedPeersKey].orEmpty() - device.publicKey
            if (remote is Inet4Address) {
                prefs[addresses] = Json.encodeToString(readAddresses(prefs) + (device.publicKey to requireNotNull(remote.hostAddress)))
            }
        }
        Unit
    }

    private fun read(prefs: Preferences): List<PeerIdentity> =
        prefs[key]?.let { Json.decodeFromString<List<PeerIdentity>>(it) }.orEmpty()

    private fun readAddresses(prefs: Preferences): Map<String, String> =
        prefs[addresses]?.let { Json.decodeFromString<Map<String, String>>(it) }.orEmpty()

    private fun checkIdentity(current: List<PeerIdentity>, device: PeerIdentity) {
        if (current.any { (it.publicKey == device.publicKey) != (it.tlsPin == device.tlsPin) }) {
            throw PairingException(PairingProblem.IDENTITY_CHANGED)
        }
    }
}

internal suspend fun <T> withPeerTrust(peers: PairedDevices, peerKey: String, work: suspend () -> T): T = coroutineScope {
    if (peers.all().none { it.publicKey == peerKey }) failTransfer(TransferProblem.UNTRUSTED)
    val revoked = launch {
        peers.observe().first { table -> table.none { it.publicKey == peerKey } }
        failTransfer(TransferProblem.UNTRUSTED)
    }
    try { work() } finally { revoked.cancel() }
}

internal inline fun <T> storageOperation(block: () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (problem: PairingException) {
    throw problem
} catch (_: Exception) {
    throw PairingException(PairingProblem.STORAGE)
}

private inline fun <T> networkOperation(block: () -> T): T = try {
    block()
} catch (_: SSLException) {
    throw PairingException(PairingProblem.REJECTED)
} catch (_: IOException) {
    throw PairingException(PairingProblem.UNREACHABLE)
}

private suspend fun <T : Closeable> openResource(block: suspend () -> T): T {
    var opened: T? = null
    try {
        return withContext(Dispatchers.IO) { block().also { opened = it } }
    } catch (failure: Exception) {
        opened?.closeQuietly()
        throw failure
    }
}

private fun Closeable.closeQuietly() {
    runCatching { close() }
}
