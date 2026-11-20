package cn.lemwood.keyvault.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import cn.lemwood.keyvault.data.model.ApiKey
import cn.lemwood.keyvault.data.model.Service
import cn.lemwood.keyvault.data.model.ServiceItem
import cn.lemwood.keyvault.data.model.VaultProfile
import cn.lemwood.keyvault.data.repository.VaultRepository
import cn.lemwood.keyvault.ui.VaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanPairingTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `pairing fixes both identities across reopening and retires the invitation`() = runBlocking {
        val first = fixture("first", createTlsFirst = true)
        val second = fixture("second")
        val invite = first.device.invite(loopback)
        try {
            val qr = first.device.qr(invite.code)
            val size = qr.size * 4
            val pixels = IntArray(size * size) { index ->
                if (qr.modules[(index / size / 4) * qr.size + (index % size / 4)]) 0xff000000.toInt() else -1
            }
            val image = BufferedImage(size + 40, size + 80, BufferedImage.TYPE_INT_RGB)
            val background = image.createGraphics()
            try {
                background.color = java.awt.Color.WHITE
                background.fillRect(0, 0, image.width, image.height)
            } finally {
                background.dispose()
            }
            image.setRGB(20, 40, size, size, pixels, 0, size)
            val file = folder.newFile("pairing.png")
            assertTrue(ImageIO.write(image, "png", file))
            assertEquals(invite.code, decodeQrImage(file))
            val listening = async { invite.awaitPeer() }
            val joining = second.device.join(invite.code)
            val hosting = listening.await()
            try {
                assertEquals(hosting.comparisonCode, joining.comparisonCode)
                assertEquals(emptyList(), first.device.trustedDevices())
                assertEquals(emptyList(), second.device.trustedDevices())
                val confirming = async { hosting.confirm() }
                joining.confirm()
                confirming.await()
            } finally {
                hosting.close()
                joining.close()
            }
            assertEquals(PairingProblem.REJECTED, assertFailsWith<PairingException> { invite.awaitPeer() }.problem)
            assertFailsWith<PairingException> { second.device.join(invite.code) }
        } finally {
            invite.close()
        }

        val restartedHost = first.loadIdentity()
        val restartedClient = second.loadIdentity()
        assertEquals(first.tls.certificatePin, restartedHost.first.certificatePin)
        assertEquals(first.signer.publicKey, restartedHost.second.publicKey)
        val host = first.device(restartedHost)
        val client = second.device(restartedClient)
        assertEquals(first.signer.publicKey, client.trustedDevices().single().publicKey)
        assertEquals(second.signer.publicKey, host.trustedDevices().single().publicKey)
        assertEquals(PairingProblem.REJECTED, assertFailsWith<PairingException> {
            host.listenTrusted(InetSocketAddress(loopback, 0), first.signer.publicKey)
        }.problem)
        host.listenTrusted(InetSocketAddress(loopback, 0), second.signer.publicKey).use { listener ->
            val read = async(Dispatchers.IO) { listener.accept().use { it.inputStream.read() } }
            client.connectTrusted(InetSocketAddress(loopback, listener.localPort), first.signer.publicKey).use {
                it.outputStream.write(42)
            }
            assertEquals(42, read.await())
        }
    }

    @Test
    fun `a changed signature address expired invitation and foreign subnet cannot enroll`() = runBlocking {
        val host = fixture("host")
        val client = fixture("client")
        val invite = host.device.invite(loopback)
        try {
            val signed = readOffer(invite.code, System.currentTimeMillis())
            val changed = signed.copy(offer = signed.offer.copy(port = if (signed.offer.port == 1) 2 else 1)).toCode()
            assertEquals(PairingProblem.REJECTED, assertFailsWith<PairingException> { client.device.join(changed) }.problem)
            val stale = signed.offer.copy(expiresAt = System.currentTimeMillis() - 1).signed(host.signer).toCode()
            assertEquals(PairingProblem.EXPIRED, assertFailsWith<PairingException> { client.device.join(stale) }.problem)
            val otherNetwork = signed.offer.copy(address = "203.0.113.20").signed(host.signer).toCode()
            assertEquals(PairingProblem.NOT_SAME_SUBNET,
                assertFailsWith<PairingException> { client.device.join(otherNetwork) }.problem)
            assertTrue(client.device.trustedDevices().isEmpty())
        } finally {
            invite.close()
        }
    }

    @Test
    fun `pairing refuses a different TLS certificate and stored identities cannot be repinned`() = runBlocking {
        val host = fixture("host")
        val proxy = fixture("other")
        val client = fixture("client")
        proxy.tls.listenForPairing(InetSocketAddress(loopback, 0)).use { listener ->
            val legitimate = PairingOffer(
                address = loopback, port = listener.localPort, expiresAt = System.currentTimeMillis() + PAIRING_TIMEOUT_MS,
                nonce = pairingNonce(), ephemeralKey = newEphemeralKey().generatePublicKey().encoded.encode(),
                host = PeerIdentity("host", host.signer.publicKey, host.tls.certificatePin),
            )
            val responding = async(Dispatchers.IO) { runCatching { listener.accept().close() } }
            assertEquals(PairingProblem.REJECTED,
                assertFailsWith<PairingException> { client.device.join(legitimate.signed(host.signer).toCode()) }.problem)
            assertTrue(responding.await().isFailure)

            val registry = PairedDevices(client.store)
            registry.remember(legitimate.host)
            val changed = legitimate.host.copy(tlsPin = proxy.tls.certificatePin)
            assertEquals(PairingProblem.IDENTITY_CHANGED,
                assertFailsWith<PairingException> { registry.remember(changed) }.problem)
            assertEquals(listOf(legitimate.host), registry.all())
        }
    }

    @Test
    fun `declining a comparison leaves the registry empty on both devices`() = runBlocking {
        val host = fixture("host")
        val client = fixture("client")
        val invite = host.device.invite(loopback)
        try {
            val listening = async { invite.awaitPeer() }
            val joining = client.device.join(invite.code)
            val hosting = listening.await()
            try {
                joining.close()
                assertFailsWith<PairingException> { hosting.confirm() }
            } finally {
                hosting.close()
            }
            assertTrue(host.device.trustedDevices().isEmpty())
            assertTrue(client.device.trustedDevices().isEmpty())
        } finally {
            invite.close()
        }
    }

    @Test
    fun `Android advertises on LAN and pairs with desktop across process restart`() = runBlocking {
        val adb = System.getenv("KEYVAULT_TEST_ADB")
        assumeTrue(!adb.isNullOrBlank() && System.getenv("KEYVAULT_TEST_LAN") == "1")
        val desktop = fixture("desktop", discovery = DesktopDiscovery)
        val launcher = requireNotNull(adb)
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val read = { file: String -> adb(launcher, "shell", "run-as", packageName, "cat", "cache/pair-$id.$file") }
        val start = instrument(launcher, "host", id, desktop.signer.publicKey)
        try {
            val code = waitForFile { read("code") }
            val offer = readOffer(code, System.currentTimeMillis()).offer
            val discovery = withTimeoutOrNull(8_000) { desktop.device.discover().first { offer.host.name in it } }
            if (System.getenv("KEYVAULT_REQUIRE_MDNS") == "1") assertNotNull(discovery, "Android NSD announcement was not discovered")
            val joined = desktop.device.join(code)
            try {
                assertEquals(waitForFile { read("comparison") }, joined.comparisonCode)
                joined.confirm()
            } finally {
                joined.close()
            }
            assertPairingOK(start)
            assertEquals(offer.host.publicKey, desktop.device.trustedDevices().single().publicKey)
            assertPairingOK(instrument(launcher, "reload", id, desktop.signer.publicKey))
            if (System.getenv("KEYVAULT_TEST_DESKTOP_HOST") == "1") {
                val androidAddress = parseIpv4(offer.address)
                val link = DesktopDiscovery.addresses().first { it.contains(androidAddress) }
                val invite = desktop.device.invite(link.ip.hostAddress!!)
                val hosting = async { invite.awaitPeer() }
                try {
                    val connecting = instrument(launcher, "join", id, desktop.signer.publicKey,
                        "-e", "pairingName", "desktop", "-e", "pairingCode", invite.code)
                    try {
                        val approval = withTimeout(20_000) { hosting.await() }
                        try {
                            assertEquals(waitForFile { read("comparison") }, approval.comparisonCode)
                            assertEquals(offer.host.publicKey, approval.peer.publicKey)
                            approval.confirm()
                        } finally {
                            approval.close()
                        }
                        assertPairingOK(connecting)
                    } finally {
                        if (connecting.isAlive) {
                            connecting.destroyForcibly()
                            adb(launcher, "shell", "am", "force-stop", packageName)
                        }
                    }
                } finally {
                    invite.close()
                    hosting.cancel()
                }
            }
            val receiving = instrument(launcher, "transferReceive", id, desktop.signer.publicKey)
            try {
                val receiveCode = waitForFile { read("code") }
                val peer = desktop.device.trustedDevices().single()
                val sender = desktop.device.prepareTransfer(transferFixture())
                try {
                    sender.send(receiveCode, peer)
                    sender.send(receiveCode, peer)
                } finally {
                    sender.close()
                }
                adb(launcher, "shell", "run-as", packageName, "touch", "cache/pair-$id.done")
                assertPairingOK(receiving)
            } finally {
                if (receiving.isAlive) {
                    receiving.destroyForcibly()
                    adb(launcher, "shell", "am", "force-stop", packageName)
                }
            }
            val syncing = instrument(launcher, "incrementalWait", id, desktop.signer.publicKey)
            val replicaRepo = VaultRepository(PreferenceDataStoreFactory.create {
                folder.root.resolve("lan-incremental.preferences_pb")
            })
            val profile = VaultProfile(id = "desktop-replica", name = "Wi-Fi fixture", services = listOf(
                Service("sync-service", "Service", listOf(ServiceItem("sync-item", "Item", keys = listOf(
                    ApiKey("android-key", "original"), ApiKey("desktop-key", "original"),
                )))),
            ))
            replicaRepo.save(VaultRepository.Snapshot(listOf(profile), profile.id))
            val changed = profile.applyReplicaValues(profile.replicaValues() +
                (ReplicaPath("sync-service", "sync-item", "desktop-key") to ReplicaValue("desktop-edited")))
            replicaRepo.save(VaultRepository.Snapshot(listOf(changed), profile.id))
            val vm = VaultViewModel(replicaRepo)
            try {
                val syncCode = waitForFile { read("code") }
                val peer = desktop.device.trustedDevices().single()
                desktop.device.syncIncremental(syncCode, peer, profile.id, vm.incrementalStore)
                val synchronized = replicaRepo.snapshotFlow.first().profiles.single().replicaValues()
                assertEquals("android-edited", synchronized[ReplicaPath("sync-service", "sync-item", "android-key")]?.name)
                assertEquals("desktop-edited", synchronized[ReplicaPath("sync-service", "sync-item", "desktop-key")]?.name)
                adb(launcher, "shell", "run-as", packageName, "touch", "cache/pair-$id.done")
                assertPairingOK(syncing)

                adb(launcher, "shell", "am", "force-stop", packageName)
                val restored = synchronized.toMutableMap().apply {
                    this[ReplicaPath("sync-service", "sync-item", "desktop-key")] = ReplicaValue("edited-while-stopped")
                }
                vm.updateKeyValue("sync-service", "sync-item", "desktop-key", "edited-while-stopped")
                assertEquals(restored, vm.incrementalStore.replica(profile.id).profile.replicaValues())
                val reopened = instrument(launcher, "backgroundWait", id, desktop.signer.publicKey,
                    "-e", "pairingValue", "edited-while-stopped")
                try {
                    assertEquals("open-activity", waitForFile { read("code") })
                    adb(launcher, "shell", "am", "start", "-n", "$packageName/cn.lemwood.keyvault.MainActivity")
                    withTimeout(12_000) {
                        while (read("code") != "ready" && reopened.isAlive) delay(200)
                    }
                    if (!reopened.isAlive) assertPairingOK(reopened)
                    assertEquals("ready", read("code"))
                    val endpoint = TransferEndpoint(InetSocketAddress(parseIpv4(offer.address), SYNC_WINDOW_PORT), peer.publicKey)
                    desktop.device.syncIncremental(endpoint.code(incremental = true), peer, profile.id, vm.incrementalStore)
                    adb(launcher, "shell", "run-as", packageName, "touch", "cache/pair-$id.done")
                    assertPairingOK(reopened)
                } finally {
                    if (reopened.isAlive) {
                        reopened.destroyForcibly()
                        adb(launcher, "shell", "am", "force-stop", packageName)
                    }
                }
            } finally {
                vm.viewModelScope.cancel()
                if (syncing.isAlive) {
                    syncing.destroyForcibly()
                    adb(launcher, "shell", "am", "force-stop", packageName)
                }
            }
        } finally {
            if (start.isAlive) {
                start.destroyForcibly()
                adb(launcher, "shell", "am", "force-stop", packageName)
            }
            adb(launcher, "shell", "run-as", packageName, "rm", "-f", "cache/pair-$id.code",
                "cache/pair-$id.comparison", "cache/pair-$id.preferences_pb", "cache/pair-$id.done")
        }
    }

    private suspend fun waitForFile(read: () -> String): String = withTimeout(15_000) {
        var content = read()
        while (content.isBlank()) {
            delay(200)
            content = read()
        }
        content
    }

    private fun instrument(adb: String, action: String, id: String, peer: String, vararg extras: String): Process = ProcessBuilder(
        adb, "shell", "am", "instrument", "-w", "-e", "pairingAction", action,
        "-e", "pairingId", id, "-e", "pairingPeer", peer,
        *extras,
        "$packageName.test/cn.lemwood.keyvault.sync.SyncTlsSmokeInstrumentation",
    ).redirectErrorStream(true).start()

    private fun assertPairingOK(process: Process) {
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Android pairing did not finish")
            val result = process.inputStream.bufferedReader().use { it.readText() }
            assertTrue("INSTRUMENTATION_RESULT: pairing=ok" in result, result)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun adb(executable: String, vararg args: String): String {
        val process = ProcessBuilder(executable, *args).start()
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return ""
            return process.inputStream.bufferedReader().use { it.readText().trim() }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun fixture(name: String, createTlsFirst: Boolean = false, discovery: LanDiscovery = LoopbackDiscovery): Fixture {
        val keystore = folder.root.toPath().resolve("$name.p12")
        val password = "pairing test fixture".toCharArray()
        val existingPin = if (createTlsFirst) loadOrCreateDesktopSyncIdentity(password, keystore).certificatePin else null
        return Fixture(name, keystore, discovery).also {
            if (existingPin != null) assertEquals(existingPin, it.tls.certificatePin)
        }
    }

    private inner class Fixture(name: String, private val keystore: Path, private val discovery: LanDiscovery) {
        private val deviceName = name
        val store = PreferenceDataStoreFactory.create { folder.root.resolve("$name.preferences_pb") }
        private val credentials = loadIdentity()
        val tls = credentials.first
        val signer = credentials.second
        val device = device(credentials)

        fun loadIdentity() = loadOrCreateDesktopPairingIdentity("pairing test fixture".toCharArray(), keystore)
        fun device(identity: Pair<SyncTlsIdentity, PairingSigner>) =
            JvmPairingDevice(identity.first, identity.second, deviceName, store, discovery)
    }

    private object LoopbackDiscovery : LanDiscovery {
        override fun addresses() = listOf(LanAddress(parseIpv4(loopback), 8))
        override fun discover() = flowOf(emptyList<String>())
        override suspend fun announce(address: Inet4Address, port: Int, name: String): Unit = awaitCancellation()
    }

    private companion object {
        const val loopback = "127.0.0.1"
        const val packageName = "cn.lemwood.keyvault.debug"
    }
}
