package cn.lemwood.keyvault.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.BindException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference

internal suspend fun runBackgroundSyncWindow(
    tls: SyncTlsIdentity,
    localKey: String,
    peers: PairedDevices,
    discovery: LanDiscovery,
    store: IncrementalStore,
    onReady: () -> Unit,
    address: InetSocketAddress = InetSocketAddress(parseIpv4("0.0.0.0"), SYNC_WINDOW_PORT),
    intervalMs: Long = 60_000,
    destinationPort: Int = address.port,
) = coroutineScope {
    val pins = AtomicReference(peers.all().associateBy(PeerIdentity::tlsPin))
    val updatePins = launch { peers.observe().collect { peers -> pins.set(peers.associateBy(PeerIdentity::tlsPin)) } }
    val schedule = launch(Dispatchers.IO) {
        while (true) {
            try {
                scheduledRound(tls, localKey, peers, discovery, store, destinationPort)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The next window tick retries after an unavailable network or store.
            }
            delay(intervalMs)
        }
    }
    val sockets = TransferSocketSlot()
    try {
        withTransferSockets(sockets::close) {
            val listener = openBackgroundListener(tls, address, pins)
            listener.use {
                onReady()
                listener.setAcceptTimeout(500)
                IncrementalRounds(store, limit = 4).use { rounds ->
                    while (!sockets.isClosed()) {
                        currentCoroutineContext().ensureActive()
                        val socket = try { listener.acceptSocket() } catch (_: SocketTimeoutException) { continue }
                        try {
                            socket.use {
                                val remote = socket.inetAddress as? Inet4Address ?: return@use
                                if (discovery.addresses().none { it.contains(remote) }) return@use
                                sockets.attach(socket)
                                socket.soTimeout = TRANSFER_TIMEOUT_MS
                                socket.startHandshake()
                                val authenticated = pins.get()[socket.remoteCertificatePin()] ?: return@use
                                withTrustedTransferSockets(peers, authenticated.publicKey, { runCatching { socket.close() } }) {
                                    val wire = IncrementalWire.open(socket, client = false)
                                    rounds.handle(wire, authenticated.publicDevice(), onEvent = { event ->
                                        if (event is IncrementalEvent.Saved) peers.rememberAddress(authenticated.publicKey, remote)
                                    }) { manifest ->
                                        store.syncBindings().singleOrNull {
                                            it.peerKey == authenticated.publicKey && it.remoteProfile == manifest.profileId
                                        }?.localProfile ?: failTransfer(TransferProblem.WRONG_REPLICA)
                                    }
                                }
                            }
                        } catch (_: IOException) {
                            // Keep the listener and authenticated partial rounds available for a retry.
                        } catch (_: TransferException) {
                            // This session ended or was revoked; other devices can still connect.
                        } finally {
                            sockets.release()
                        }
                    }
                }
            }
        }
    } finally {
        updatePins.cancel()
        schedule.cancel()
    }
}

private suspend fun openBackgroundListener(
    tls: SyncTlsIdentity, address: InetSocketAddress, pins: AtomicReference<Map<String, PeerIdentity>>,
): SyncTlsListener {
    repeat(5) { attempt ->
        try {
            return tls.listenForTrustedPeers(address) { pins.get().keys }
        } catch (_: BindException) {
            if (attempt == 4) failTransfer(TransferProblem.CONNECTION)
            delay(500)
        } catch (_: IOException) {
            failTransfer(TransferProblem.CONNECTION)
        }
    }
    error("Unreachable")
}

private suspend fun scheduledRound(
    tls: SyncTlsIdentity, localKey: String, peers: PairedDevices, discovery: LanDiscovery,
    store: IncrementalStore, port: Int,
) {
    // Each pair has one scheduled initiator. An incremental round already exchanges changes both ways.
    val partners = peers.all().filter { localKey < it.publicKey }.associateBy(PeerIdentity::publicKey)
    val addresses = peers.pairedAddresses()
    for (binding in store.syncBindings()) {
        val peer = partners[binding.peerKey] ?: continue
        val address = addresses[peer.publicKey]?.let(::parseIpv4) ?: continue
        try {
            withTimeoutOrNull(30_000) {
                runIncrementalClient(binding.localProfile, peer.publicDevice(), peers, store, { attach ->
                    if (discovery.addresses().none { it.contains(address) }) failTransfer(TransferProblem.OTHER_SUBNET)
                    tls.connect(InetSocketAddress(address, port), peer.tlsPin, attach)
                }) { }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Other profiles and peers stay eligible when this particular destination is offline.
        }
    }
}
