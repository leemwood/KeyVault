package cn.lemwood.keyvault.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import cn.lemwood.keyvault.data.backup.encodeProfilePayload
import cn.lemwood.keyvault.data.model.ApiKey
import cn.lemwood.keyvault.data.model.Service
import cn.lemwood.keyvault.data.model.ServiceItem
import cn.lemwood.keyvault.data.model.VaultProfile
import cn.lemwood.keyvault.data.repository.VaultRepository
import cn.lemwood.keyvault.ui.VaultViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConfigTransferTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `large configuration resumes on a slow network and a lost final reply does not duplicate it`() = exchange { pair ->
        val profile = transferFixture()
        val sender = pair.sender.device.prepareTransfer(profile)
        val receiver = pair.listen()
        val delivered = mutableListOf<Int>()
        val reconnecting = mutableListOf<Int>()
        val interrupted = AtomicBoolean()
        val lostAcknowledgement = AtomicBoolean()
        val destination = readTransferCode(receiver.code)
        SlowRelay(destination.socketAddress).use { relay ->
            val receiving = launch {
                receiver.receive(pair.viewModel.transferStore) { event ->
                    when (event) {
                        is TransferEvent.Progress -> {
                            delivered += event.progress.completed
                            if (event.progress.completed == 4 && interrupted.compareAndSet(false, true)) relay.drop()
                        }
                        TransferEvent.Saved -> if (lostAcknowledgement.compareAndSet(false, true)) relay.drop()
                        is TransferEvent.Interrupted -> Unit
                    }
                }
            }
            try {
                val code = destination.copy(socketAddress = relay.address).code()
                sender.send(code, pair.receiver.peer) { progress ->
                    if (progress.reconnecting) reconnecting += progress.completed
                }
            } finally {
                receiver.close()
                receiving.cancelAndJoin()
            }
        }
        assertTrue(interrupted.get() && lostAcknowledgement.get())
        assertTrue(reconnecting.size >= 2)
        val blocks = (sender as JvmTransferSender).manifest.chunkCount
        assertEquals((0..4).toList() + (4..blocks).toList(), delivered)
        val contents = pair.repository.snapshotFlow.first()
        val saved = contents.profiles.last()
        assertEquals(pair.initial.profiles + saved, contents.profiles)
        assertEquals(pair.initial.activeId, contents.activeId)
        assertEquals("Fixture (2)", saved.name)
        assertNotEquals(profile.id, saved.id)
        assertEquals(profile.services, saved.services)

        // A fresh receiver session consults the same durable receipt before accepting any chunks.
        val fresh = pair.listen()
        val repeated = mutableListOf<TransferEvent>()
        val receiving = launch { fresh.receive(pair.viewModel.transferStore) { repeated += it } }
        try {
            sender.send(fresh.code, pair.receiver.peer)
        } finally {
            sender.close()
            fresh.close()
            receiving.cancelAndJoin()
        }
        assertTrue(repeated.isEmpty())
        assertEquals(contents, pair.repository.snapshotFlow.first())

        val storedPreferences = folder.root.resolve("vault.preferences_pb")
            .copyTo(folder.root.resolve("reopened.preferences_pb"))
        val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reopened = VaultRepository(PreferenceDataStoreFactory.create(scope = reopenedScope) { storedPreferences })
            val manifest = sender.manifest
            val receipt = TransferReceipt(pair.sender.peer.publicKey, manifest.transferId, checksum(manifest.bytes()).encode())
            assertEquals(TransferStatus.SAVED, reopened.transferStatus(receipt))
            assertEquals(contents, reopened.snapshotFlow.first())
        } finally {
            reopenedScope.cancel()
        }
    }

    @Test
    fun `invalid chunk authentication version checksum and manifest reuse are rejected`() = exchange { pair ->
        val profile = transferFixture()
        val bytes = encodeProfilePayload(profile)
        val transfer = TransferManifest.create(profile, bytes)
        val receiver = pair.listen()
        val receiving = launch { receiver.receive(pair.viewModel.transferStore) }
        try {
            pair.wire(receiver) { wire ->
                wire.output.writeInt(TRANSFER_MAGIC)
                wire.output.writeInt(TRANSFER_VERSION + 1)
                wire.output.flush()
                assertEquals(TransferProblem.UNSUPPORTED, assertFailsWith<TransferException> { wire.readReply() }.problem)
            }
            pair.wire(receiver) { wire ->
                wire.sendHello()
                wire.writeManifest(transfer.copy(totalBytes = 5 * 1024 * 1024))
                assertEquals(TransferProblem.TOO_LARGE, assertFailsWith<TransferException> { wire.readReply() }.problem)
            }
            pair.wire(receiver) { wire ->
                val (next, signer) = wire.start(transfer)
                assertEquals(0, next)
                val length = transfer.lengthAt(0)
                val tag = signer.tag(0, bytes, 0, length)
                val changed = bytes.copyOfRange(0, length).also { it[0] = 0 }
                wire.writeChunk(0, changed, 0, length, tag)
                assertEquals(TransferProblem.INTEGRITY, assertFailsWith<TransferException> { wire.readReply() }.problem)
            }
            assertEquals(pair.initial, pair.repository.snapshotFlow.first())
            pair.wire(receiver) { wire ->
                wire.sendHello()
                wire.writeManifest(transfer.copy(entries = transfer.entries + 1))
                assertEquals(TransferProblem.CONFLICT, assertFailsWith<TransferException> { wire.readReply() }.problem)
            }
            pair.wire(receiver) { wire ->
                val (next, signer) = wire.start(transfer)
                assertEquals(0, next)
                val changed = bytes.copyOf().also { it[0] = 0 }
                wire.writeRemaining(transfer, changed, signer)
                wire.requestCommit()
                assertEquals(TransferProblem.INTEGRITY, assertFailsWith<TransferException> { wire.readReply() }.problem)
            }
            assertEquals(pair.initial, pair.repository.snapshotFlow.first())
        } finally {
            bytes.fill(0)
            receiver.close()
            receiving.cancelAndJoin()
        }
    }

    @Test
    fun `failed commit has no receipt and can be retried without sending blocks again`() = exchange { pair ->
        pair.failWrite.set(true)
        val receiver = pair.listen()
        val receiving = launch { receiver.receive(pair.viewModel.transferStore) }
        val sender = pair.sender.device.prepareTransfer(transferFixture())
        val manifest = (sender as JvmTransferSender).manifest
        val receipt = TransferReceipt(pair.sender.peer.publicKey, manifest.transferId, checksum(manifest.bytes()).encode())
        try {
            val error = assertFailsWith<TransferException> { sender.send(receiver.code, pair.receiver.peer) }
            assertEquals(TransferProblem.STORAGE, error.problem)
            assertEquals(pair.initial, pair.repository.snapshotFlow.first())
            assertEquals(TransferStatus.MISSING, pair.viewModel.transferStore.transferStatus(receipt))
            pair.failWrite.set(false)
            val progress = mutableListOf<Int>()
            sender.send(receiver.code, pair.receiver.peer) { progress += it.completed }
            assertEquals(listOf(manifest.chunkCount), progress)
            assertEquals(TransferStatus.SAVED, pair.viewModel.transferStore.transferStatus(receipt))
            assertEquals(pair.initial.profiles.size + 1, pair.repository.snapshotFlow.first().profiles.size)
        } finally {
            receiver.close()
            sender.close()
            receiving.cancelAndJoin()
        }
    }

    @Test
    fun `bidirectional incremental exchange preserves independent edits and presents resolvable conflicts`() = replicas { left, right ->
        sync(left, right)
        withContext(Dispatchers.Main.immediate) {
            left.vm.updateKeyValue("service", "item", "key-0", "edit only on left")
            right.vm.updateKeyNote("service", "item", "key-1", "edit only on right")
            left.vm.updateKeyValue("service", "item", "key-2", "left conflict value")
            right.vm.updateKeyValue("service", "item", "key-2", "right conflict value")
            left.vm.deleteKey("service", "item", "key-3")
        }
        val round = sync(left, right)
        assertEquals(2, round.sent)
        assertEquals(2, round.received)
        assertEquals(1, round.conflicts)
        val merged = left.snapshot()
        assertEquals(merged.profile.services, right.snapshot().profile.services)
        val keys = merged.profile.services.single().items.single().keys.associateBy(ApiKey::id)
        assertEquals("edit only on left", keys.getValue("key-0").value)
        assertEquals("edit only on right", keys.getValue("key-1").note)
        assertTrue("key-3" !in keys)
        assertTrue(merged.versions.getValue(ReplicaPath("service", "item", "key-3")).deleted)
        val conflict = merged.conflicts.single()
        val rightVersion = right.snapshot().versions.getValue(conflict.path)
        assertEquals(conflict.version, rightVersion)
        assertEquals("left conflict value", conflict.local?.name)
        assertEquals("right conflict value", conflict.remote?.name)
        assertEquals(
            if (conflict.version.deviceId == left.snapshot().versions.getValue(ReplicaPath("service", "item", "key-0")).deviceId)
                "left conflict value" else "right conflict value",
            keys.getValue("key-2").value,
        )

        left.vm.resolveReplicaConflict(left.profileId, conflict, remote = false)
        val event = left.vm.conflictHistory.first().getValue(left.profileId).single()
        assertEquals(conflict.path, event.path)
        assertEquals("local", event.event)
        val resolved = sync(left, right)
        assertEquals(0, resolved.conflicts)
        assertTrue(right.snapshot().conflicts.isEmpty())
        assertEquals("left conflict value", right.snapshot().profile.services.single().items.single().keys[2].value)
        val unchanged = sync(left, right)
        assertEquals(0, unchanged.sent)
        assertEquals(0, unchanged.received)
        left.vm.clearConflictHistory(left.profileId)
        assertTrue(left.vm.conflictHistory.first().getValue(left.profileId).isEmpty())
    }

    @Test
    fun `incremental chunks resume after retained data and replay frames fail authentication or sequence checks`() =
        replicas(changeBeforeStart = true) { left, right ->
            val receiver = right.peer.device.listenForIncremental(LOOPBACK, left.peer.peer, right.profileId)
            val endpoint = readTransferCode(receiver.code, incremental = true)
            val dropped = AtomicBoolean()
            val saved = CompletableDeferred<IncrementalEvent.Saved>()
            val progress = mutableListOf<Int>()
            SlowRelay(endpoint.socketAddress).use { relay ->
                val listening = launch {
                    receiver.run(right.vm.incrementalStore) { event ->
                        if (event is IncrementalEvent.Progress) {
                            progress += event.progress.completed
                            if (event.progress.completed == 3 && dropped.compareAndSet(false, true)) relay.drop()
                        }
                        if (event is IncrementalEvent.Saved) saved.complete(event)
                    }
                }
                try {
                    val code = endpoint.copy(socketAddress = relay.address).code(incremental = true)
                    left.peer.device.syncIncremental(code, right.peer.peer, left.profileId, left.vm.incrementalStore)
                    assertEquals(500, saved.await().received)
                } finally {
                    receiver.close()
                    listening.cancelAndJoin()
                }
            }
            assertTrue(dropped.get())
            assertEquals(listOf(0, 1, 2, 3, 3, 4), progress.take(6))
            assertEquals(left.snapshot().profile.services, right.snapshot().profile.services)

            val secret = randomTransferBytes()
            val stream = ByteArrayOutputStream()
            IncrementalWire(DataInputStream(ByteArrayInputStream(byteArrayOf())), DataOutputStream(stream), secret, client = true)
                .send(IncrementalFrame.WINDOW, 3)
            val frame = stream.toByteArray()
            fun read(data: ByteArray, key: ByteArray) = IncrementalWire(
                DataInputStream(ByteArrayInputStream(data)), DataOutputStream(ByteArrayOutputStream()), key, client = false,
            )
            val reused = read(frame + frame, secret)
            assertEquals(3, reused.receive<Int>(IncrementalFrame.WINDOW))
            assertEquals(TransferProblem.REPLAY, assertFailsWith<TransferException> {
                reused.receive<Int>(IncrementalFrame.WINDOW)
            }.problem)
            assertEquals(TransferProblem.INTEGRITY, assertFailsWith<TransferException> {
                read(frame, randomTransferBytes()).receive<Int>(IncrementalFrame.WINDOW)
            }.problem)
            assertEquals(TransferProblem.INTEGRITY, assertFailsWith<TransferException> {
                read(frame.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }, secret)
                    .receive<Int>(IncrementalFrame.WINDOW)
            }.problem)

            // The round receipt also survives opening a completely new authenticated connection.
            val local = right.snapshot()
            val retiredId = randomTransferBytes(16).encode()
            right.vm.incrementalStore.applyReplica(
                local, "${left.peer.peer.publicKey}:${local.bindings().getValue(left.peer.peer.publicKey)}",
                ReplicaUpdate(local.profile.replicaValues(), local.versions, local.conflicts),
                TransferReceipt("incremental_${left.peer.peer.publicKey}", retiredId, "previous receipt"),
            )
            val newReceiver = right.peer.device.listenForIncremental(LOOPBACK, left.peer.peer, right.profileId)
            val listening = launch { newReceiver.run(right.vm.incrementalStore) }
            try {
                val manifest = left.snapshot().manifest(::replicaDigest)
                withContext(Dispatchers.IO) {
                    val endpoint = readTransferCode(newReceiver.code, incremental = true)
                    left.peer.tls.connect(endpoint.socketAddress, right.peer.tls.certificatePin).use { socket ->
                        val wire = IncrementalWire.open(socket, client = true)
                        wire.send(IncrementalFrame.ROUND, IncrementalOffer(retiredId, manifest))
                        assertEquals(TransferProblem.REPLAY, assertFailsWith<TransferException> {
                            wire.receive<ReplicaManifest>(IncrementalFrame.MANIFEST)
                        }.problem)
                    }
                }
            } finally {
                newReceiver.close()
                listening.cancelAndJoin()
            }
            assertEquals(local, right.snapshot())
        }

    @Test
    fun `compacted tombstone still wins over a stale value from another device`() {
        val profile = transferFixture()
        val old = ReplicaIndex().recordChanges(listOf(profile), listOf(profile), now = 1).snapshot(profile)
        val empty = profile.copy(services = emptyList())
        val revision = ReplicaIndex().recordChanges(listOf(profile), listOf(empty), now = 2)
            .recordChanges(listOf(empty), listOf(empty), now = 31L * 24 * 60 * 60 * 1000)
        val current = revision.snapshot(empty)
        val live = old.manifest(::replicaDigest)
        val deleted = current.manifest(::replicaDigest)
        val barrier = current.versions.getValue(ReplicaPath("service"))
        assertTrue(barrier.deleted && barrier.deletedAt == null)
        assertTrue(requestedRecords(live, deleted).isEmpty())
        val applied = mergeReplicas(old, live, deleted, emptyList(), ::replicaDigest)
        assertTrue(applied.values.isEmpty())
        assertTrue(applied.versions.values.all { it.deleted })
    }

    @Test
    fun `scheduled windows catch up after reopening and a revoked device cannot reconnect or write`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = Fixture("window-first", scope)
        val second = Fixture("window-second", scope)
        val (client, server) = listOf(first, second).sortedBy { it.peer.publicKey }
        val address = parseIpv4(LOOPBACK)
        val port = ServerSocket(0, 1, address).use { it.localPort }
        val registry = PairedDevices(server.dataStore)
        registry.remember(client.identity, address)
        PairedDevices(client.dataStore).remember(server.identity, address)
        val clientRepo = VaultRepository(client.dataStore)
        val serverRepo = VaultRepository(server.dataStore)
        val clientProfile = transferFixture().copy(id = "initiator-profile")
        val serverProfile = clientProfile.copy(id = "waiting-profile")
        clientRepo.save(VaultRepository.Snapshot(listOf(clientProfile), clientProfile.id))
        serverRepo.save(VaultRepository.Snapshot(listOf(serverProfile), serverProfile.id))
        val left = ReplicaSide(client, VaultViewModel(clientRepo), clientProfile.id)
        var right = ReplicaSide(server, VaultViewModel(serverRepo), serverProfile.id)
        var initiatorWindow: kotlinx.coroutines.Job? = null
        var waitingWindow: kotlinx.coroutines.Job? = null

        suspend fun launchWindow(side: ReplicaSide, listenPort: Int, targetPort: Int): kotlinx.coroutines.Job {
            val ready = CompletableDeferred<Unit>()
            val task = launch {
                runBackgroundSyncWindow(side.peer.tls, side.peer.peer.publicKey, PairedDevices(side.peer.dataStore),
                    LoopbackDiscovery, side.vm.incrementalStore, { ready.complete(Unit) },
                    InetSocketAddress(address, listenPort), intervalMs = 300, destinationPort = targetPort)
            }
            withTimeout(5_000) { ready.await() }
            return task
        }

        try {
            withTimeout(35_000) {
                left.vm.isLoading.first { !it }
                right.vm.isLoading.first { !it }
                sync(left, right)
                waitingWindow = launchWindow(right, port, port)
                initiatorWindow = launchWindow(left, 0, port)
                val path = ReplicaPath("service", "item", "key-0")

                withContext(Dispatchers.Main.immediate) { left.vm.updateKeyValue("service", "item", "key-0", "background first") }
                serverRepo.snapshotFlow.first { it.profiles.single().replicaValues()[path]?.name == "background first" }
                waitingWindow?.cancelAndJoin()
                right.vm.viewModelScope.cancel()
                withContext(Dispatchers.Main.immediate) { left.vm.updateKeyValue("service", "item", "key-0", "edited while offline") }
                delay(1_000)
                assertEquals("background first", serverRepo.snapshotFlow.first().profiles.single().replicaValues()[path]?.name)
                right = ReplicaSide(server, VaultViewModel(serverRepo), serverProfile.id)
                waitingWindow = launchWindow(right, port, port)
                serverRepo.snapshotFlow.first { it.profiles.single().replicaValues()[path]?.name == "edited while offline" }
                initiatorWindow?.cancelAndJoin()

                withContext(Dispatchers.IO) {
                    client.tls.connect(InetSocketAddress(address, port), server.tls.certificatePin).use { socket ->
                        IncrementalWire.open(socket, client = true)
                        right.peer.device.revokeDevice(client.peer.publicKey)
                        socket.soTimeout = 2_000
                        val read = runCatching { socket.inputStream.read() }
                        assertTrue(read.getOrNull() == -1 || read.exceptionOrNull() is IOException && read.exceptionOrNull() !is java.net.SocketTimeoutException)
                    }
                }
                assertTrue(registry.all().isEmpty())
                val latest = right.snapshot()
                val staleReceipt = TransferReceipt("incremental_${client.peer.publicKey}", "revoked-fixture", "hash")
                assertEquals(TransferProblem.UNTRUSTED, assertFailsWith<TransferException> {
                    right.vm.incrementalStore.applyReplica(latest, "${client.peer.publicKey}:${clientProfile.id}",
                        ReplicaUpdate(latest.profile.replicaValues(), latest.versions, latest.conflicts), staleReceipt)
                }.problem)
                withContext(Dispatchers.IO) {
                    val attempted = runCatching {
                        client.tls.connect(InetSocketAddress(address, port), server.tls.certificatePin).use { socket ->
                            IncrementalWire.open(socket, client = true)
                        }
                    }
                    assertTrue(attempted.isFailure)
                }
            }
        } finally {
            initiatorWindow?.cancelAndJoin()
            waitingWindow?.cancelAndJoin()
            left.vm.viewModelScope.cancel()
            right.vm.viewModelScope.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `one profile synchronizes with two peers without losing either binding`() = replicas { left, right ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val third = Fixture("third-device", scope)
        PairedDevices(left.peer.dataStore).remember(third.identity)
        PairedDevices(third.dataStore).remember(left.peer.identity)
        val profile = transferFixture().copy(id = "third-copy")
        val repository = VaultRepository(third.dataStore)
        repository.save(VaultRepository.Snapshot(listOf(profile), profile.id))
        val other = ReplicaSide(third, VaultViewModel(repository), profile.id)
        try {
            sync(left, right)
            sync(left, other)
            withContext(Dispatchers.Main.immediate) {
                right.vm.updateKeyValue("service", "item", "key-1", "second-device-edit")
                other.vm.updateKeyValue("service", "item", "key-2", "third-device-edit")
            }
            sync(left, right)
            sync(left, other)
            sync(left, right)
            val result = left.snapshot()
            assertEquals(mapOf(right.peer.peer.publicKey to right.profileId, other.peer.peer.publicKey to other.profileId), result.bindings())
            assertEquals(result.profile.services, right.snapshot().profile.services)
            assertEquals(result.profile.services, other.snapshot().profile.services)
        } finally {
            other.vm.viewModelScope.cancel()
            scope.cancel()
        }
    }

    private fun replicas(
        changeBeforeStart: Boolean = false,
        test: suspend CoroutineScope.(ReplicaSide, ReplicaSide) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = Fixture("incremental-left", scope)
        val second = Fixture("incremental-right", scope)
        PairedDevices(first.dataStore).remember(second.identity)
        PairedDevices(second.dataStore).remember(first.identity)

        suspend fun side(fixture: Fixture, id: String, changed: Boolean): ReplicaSide {
            val profile = transferFixture().copy(id = id)
            val repo = VaultRepository(PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("$id.preferences_pb") })
            repo.save(VaultRepository.Snapshot(listOf(profile), profile.id))
            if (changed) {
                val updated = profile.applyReplicaValues(profile.replicaValues().mapValues { (path, value) ->
                    if (path.key != null) value.copy(detail = "bulk edit") else value
                })
                repo.save(VaultRepository.Snapshot(listOf(updated), profile.id))
            }
            return ReplicaSide(fixture, VaultViewModel(repo), profile.id)
        }

        val left = side(first, "left-profile", changeBeforeStart)
        val right = side(second, "right-profile", false)
        try {
            withTimeout(45_000) {
                left.vm.isLoading.first { !it }
                right.vm.isLoading.first { !it }
                test(left, right)
            }
        } finally {
            left.vm.viewModelScope.cancel()
            right.vm.viewModelScope.cancel()
            scope.cancel()
        }
    }

    private suspend fun CoroutineScope.sync(left: ReplicaSide, right: ReplicaSide): IncrementalEvent.Saved {
        val receiver = right.peer.device.listenForIncremental(LOOPBACK, left.peer.peer, right.profileId)
        val saved = CompletableDeferred<IncrementalEvent.Saved>()
        val listening = launch {
            receiver.run(right.vm.incrementalStore) { if (it is IncrementalEvent.Saved) saved.complete(it) }
        }
        var result: IncrementalEvent.Saved? = null
        try {
            left.peer.device.syncIncremental(receiver.code, right.peer.peer, left.profileId, left.vm.incrementalStore) {
                if (it is IncrementalEvent.Saved) result = it
            }
            saved.await()
            return requireNotNull(result)
        } finally {
            receiver.close()
            listening.cancelAndJoin()
        }
    }

    private class ReplicaSide(val peer: Fixture, val vm: VaultViewModel, val profileId: String) {
        suspend fun snapshot() = vm.incrementalStore.replica(profileId)
    }

    private fun exchange(test: suspend CoroutineScope.(Peers) -> Unit) = runBlocking {
        val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sender = Fixture("sender", dataScope)
        val receiver = Fixture("receiver", dataScope)
        PairedDevices(sender.dataStore).remember(receiver.identity)
        PairedDevices(receiver.dataStore).remember(sender.identity)
        val vaultStore = PreferenceDataStoreFactory.create(scope = dataScope) { folder.root.resolve("vault.preferences_pb") }
        val repository = VaultRepository(vaultStore)
        val local = VaultProfile(id = "existing", name = "Fixture")
        val initial = VaultRepository.Snapshot(listOf(local), local.id)
        repository.save(initial)
        val failing = AtomicBoolean()
        val wrapped = object : DataStore<Preferences> by vaultStore {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                vaultStore.updateData { preferences ->
                    val updated = transform(preferences)
                    if (failing.get()) throw IOException("fixture disk write failure")
                    updated
                }
        }
        val vm = VaultViewModel(VaultRepository(wrapped))
        try {
            withTimeout(35_000) {
                vm.isLoading.first { !it }
                test(Peers(sender, receiver, repository, vm, initial, failing))
            }
        } finally {
            vm.viewModelScope.cancel()
            dataScope.cancel()
        }
    }

    private inner class Fixture(name: String, scope: CoroutineScope) {
        private val credentials = loadOrCreateDesktopPairingIdentity(
            "fixture identity".toCharArray(), folder.root.toPath().resolve("$name.p12"),
        )
        val tls = credentials.first
        val identity = PeerIdentity(name, credentials.second.publicKey, tls.certificatePin)
        val peer = identity.publicDevice()
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("$name.preferences_pb") }
        val device = JvmPairingDevice(tls, credentials.second, name, dataStore, LoopbackDiscovery)
    }

    private class Peers(
        val sender: Fixture,
        val receiver: Fixture,
        val repository: VaultRepository,
        val viewModel: VaultViewModel,
        val initial: VaultRepository.Snapshot,
        val failWrite: AtomicBoolean,
    ) {
        suspend fun listen() = receiver.device.listenForTransfer(LOOPBACK, sender.peer)

        suspend fun wire(receiverSession: TransferReceiver, block: (TransferWire) -> Unit) = withContext(Dispatchers.IO) {
            val address = readTransferCode(receiverSession.code).socketAddress
            sender.tls.connect(address, receiver.tls.certificatePin).use { block(TransferWire(it)) }
        }
    }

    private object LoopbackDiscovery : LanDiscovery {
        override fun addresses() = listOf(LanAddress(parseIpv4(LOOPBACK), 8))
        override fun discover() = flowOf(emptyList<String>())
        override suspend fun announce(address: Inet4Address, port: Int, name: String): Unit = awaitCancellation()
    }

    private class SlowRelay(private val backend: InetSocketAddress) : Closeable {
        private val server = ServerSocket(0, 10, parseIpv4(LOOPBACK))
        private var active: Pair<Socket, Socket>? = null
        val address = InetSocketAddress(LOOPBACK, server.localPort)
        private val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val incoming = try { server.accept() } catch (_: IOException) { break }
                incoming.use { client ->
                    Socket().use { target ->
                        try {
                            target.connect(backend, 1_000)
                            synchronized(this) { active = client to target }
                            val forward = thread(isDaemon = true) { pump(client, target) }
                            pump(target, client)
                            forward.join(1_000)
                        } catch (_: IOException) {
                            // The test deliberately disconnects two connections.
                        } finally {
                            drop()
                        }
                    }
                }
            }
        }

        @Synchronized fun drop() {
            active?.let { (a, b) ->
                runCatching { a.close() }
                runCatching { b.close() }
            }
            active = null
        }

        private fun pump(from: Socket, to: Socket) {
            val buffer = ByteArray(4096)
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                var count = input.read(buffer)
                while (count != -1) {
                    Thread.sleep(2)
                    output.write(buffer, 0, count)
                    count = input.read(buffer)
                }
            } catch (_: IOException) {
                // Closing either relay endpoint ends both pumps.
            } finally {
                runCatching { from.close() }
                runCatching { to.close() }
            }
        }

        override fun close() {
            server.close()
            drop()
            worker.join(2_000)
        }
    }

    private fun TransferWire.start(manifest: TransferManifest): Pair<Int, TransferChunkMac> {
        sendHello()
        writeManifest(manifest)
        expect(TransferReply.READY)
        val index = input.readInt()
        return index to TransferChunkMac(readKey(), checksum(manifest.bytes()))
    }

    private fun TransferWire.writeRemaining(manifest: TransferManifest, bytes: ByteArray, signer: TransferChunkMac) {
        for (index in 0 until manifest.chunkCount) {
            val offset = index * manifest.chunkBytes
            val length = manifest.lengthAt(index)
            writeChunk(index, bytes, offset, length, signer.tag(index, bytes, offset, length))
            expect(TransferReply.READY)
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}

internal fun transferFixture() = VaultProfile(id = "source-profile", name = "Fixture", services = listOf(
    Service(id = "service", name = "Sample", items = listOf(ServiceItem(id = "item", name = "Account", keys =
        List(500) { ApiKey(id = "key-$it", value = "test-value-$it/${"z".repeat(1000)}", note = "fixture") }
    ))),
))
