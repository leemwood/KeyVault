package cn.lemwood.keyvault.sync

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import cn.lemwood.keyvault.data.repository.VaultRepository
import cn.lemwood.keyvault.ui.VaultViewModel
import cn.lemwood.keyvault.KeyVaultApplication
import cn.lemwood.keyvault.MainActivity
import kotlinx.coroutines.CompletableDeferred
import cn.lemwood.keyvault.data.model.VaultProfile
import cn.lemwood.keyvault.data.model.ApiKey
import cn.lemwood.keyvault.data.model.Service
import cn.lemwood.keyvault.data.model.ServiceItem
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress

class SyncTlsSmokeInstrumentation : Instrumentation() {
    private lateinit var arguments: Bundle
    private val visibleActivity = CompletableDeferred<Activity>()

    override fun callActivityOnResume(activity: Activity) {
        super.callActivityOnResume(activity)
        if (activity is MainActivity) visibleActivity.complete(activity)
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            if (arguments.containsKey("pairingAction")) {
                runBlocking { testPairing(result) }
                finish(Activity.RESULT_OK, result)
                return
            }
            val identity = loadOrCreateAndroidSyncIdentity()
            check(loadOrCreateAndroidSyncIdentity().certificatePin == identity.certificatePin)
            val peerPin = arguments.getString("peerPin")
            if (peerPin != null) {
                val port = requireNotNull(arguments.getString("port")).toInt()
                identity.connect(InetSocketAddress("127.0.0.1", port), peerPin).use { socket ->
                    socket.outputStream.write(42)
                    check(socket.inputStream.read() == 43)
                    result.putString("tlsVersion", socket.session.protocol)
                }
            }
            result.putString("certificatePin", identity.certificatePin)
            result.putString("syncTls", "ok")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString("syncTls", failure.javaClass.simpleName)
            result.putString("tlsFailure", failure.message)
            result.putString("tlsCause", failure.cause?.message)
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private suspend fun testPairing(result: Bundle) {
        val id = requireNotNull(arguments.getString("pairingId"))
        require(id.matches(Regex("[a-f0-9]{16}")))
        val directory = targetContext.cacheDir
        val store = PreferenceDataStoreFactory.create { File(directory, "pair-$id.preferences_pb") }
        val device = androidLanPairing(targetContext, store) { null }.unlock(charArrayOf())
        val peer = requireNotNull(arguments.getString("pairingPeer"))
        when (arguments.getString("pairingAction")) {
            "host" -> {
                val address = device.addresses().first()
                val invitation = device.invite(address)
                val code = File(directory, "pair-$id.code")
                val comparison = File(directory, "pair-$id.comparison")
                try {
                    code.writeText(invitation.code)
                    val approval = invitation.awaitPeer()
                    try {
                        check(approval.peer.publicKey == peer)
                        comparison.writeText(approval.comparisonCode)
                        approval.confirm()
                    } finally {
                        approval.close()
                    }
                } finally {
                    invitation.close()
                    code.delete()
                    comparison.delete()
                }
            }
            "join" -> {
                val expectedName = requireNotNull(arguments.getString("pairingName"))
                withTimeout(12_000) { device.discover().first { expectedName in it } }
                val approval = device.join(requireNotNull(arguments.getString("pairingCode")))
                val comparison = File(directory, "pair-$id.comparison")
                try {
                    check(approval.peer.publicKey == peer)
                    comparison.writeText(approval.comparisonCode)
                    approval.confirm()
                } finally {
                    approval.close()
                    comparison.delete()
                }
            }
            "transferReceive" -> receiveTransfer(device, directory, id, peer)
            "incrementalWait" -> incrementalWait(device, VaultRepository(store), directory, id, peer)
            "backgroundWait" -> backgroundWait(device, VaultRepository(store), directory, id)
            "reload" -> Unit
            else -> error("Unknown pairing test action")
        }
        check(device.trustedDevices().single().publicKey == peer)
        result.putString("pairing", "ok")
    }

    private suspend fun backgroundWait(device: PairingDevice, repository: VaultRepository, directory: File, id: String) {
        val viewModel = VaultViewModel(repository)
        val endpoint = File(directory, "pair-$id.code")
        val done = File(directory, "pair-$id.done")
        endpoint.writeText("open-activity")
        val activity = withTimeout(8_000) { visibleActivity.await() }
        val window = (activity.application as KeyVaultApplication).syncWindow
        try {
            runOnMainSync { window.start(device, viewModel.incrementalStore) }
            val state = withTimeout(8_000) { window.state.first { it == SyncWindowState.RUNNING || it == SyncWindowState.UNAVAILABLE } }
            check(state == SyncWindowState.RUNNING)
            uiAutomation.executeShellCommand("input keyevent 3").close()
            endpoint.writeText("ready")
            withTimeout(35_000) { while (!done.exists()) delay(100) }
            val expected = arguments.getString("pairingValue")
            val value = repository.snapshotFlow.first().profiles.single().services.single().items.single()
                .keys.single { it.id == "desktop-key" }.value
            if (expected != null) check(value == expected)
        } finally {
            runOnMainSync { window.stop(); activity.finish() }
            viewModel.viewModelScope.cancel()
            endpoint.delete()
            done.delete()
        }
    }

    private suspend fun receiveTransfer(device: PairingDevice, directory: File, id: String, peer: String) = coroutineScope {
        val sender = device.trustedDevices().single { it.publicKey == peer }
        val receiver = device.listenForTransfer(device.addresses().first(), sender)
        val endpoint = File(directory, "pair-$id.code")
        val done = File(directory, "pair-$id.done")
        var saved: TransferReceipt? = null
        var commits = 0
        val store = object : TransferStore {
            override suspend fun transferStatus(receipt: TransferReceipt): TransferStatus = when (saved) {
                null -> TransferStatus.MISSING
                receipt -> TransferStatus.SAVED
                else -> TransferStatus.CONFLICT
            }

            override suspend fun commitTransfer(receipt: TransferReceipt, profile: VaultProfile): TransferStatus {
                val keys = profile.services.single().items.single().keys
                check(keys.size == 500 && keys.last().id == "key-499")
                check(keys.first().value == "test-value-0/${"z".repeat(1000)}")
                check(saved == null)
                saved = receipt
                commits++
                return TransferStatus.SAVED
            }
        }
        val receiving = launch { receiver.receive(store) }
        try {
            endpoint.writeText(receiver.code)
            withTimeout(35_000) { while (!done.exists()) delay(100) }
        } finally {
            receiver.close()
            receiving.cancelAndJoin()
            endpoint.delete()
            done.delete()
        }
        check(commits == 1)
    }

    private suspend fun incrementalWait(device: PairingDevice, repository: VaultRepository, directory: File, id: String, peer: String) = coroutineScope {
        val profile = VaultProfile(id = "android-replica", name = "Wi-Fi fixture", services = listOf(
            Service("sync-service", "Service", listOf(ServiceItem("sync-item", "Item", keys = listOf(
                ApiKey("android-key", "original"), ApiKey("desktop-key", "original"),
            )))),
        ))
        repository.save(VaultRepository.Snapshot(listOf(profile), profile.id))
        val viewModel = VaultViewModel(repository)
        viewModel.isLoading.first { !it }
        viewModel.updateKeyValue("sync-service", "sync-item", "android-key", "android-edited")
        viewModel.incrementalStore.replica(profile.id)
        val remote = device.trustedDevices().single { it.publicKey == peer }
        val receiver = device.listenForIncremental(device.addresses().first(), remote, profile.id)
        val endpoint = File(directory, "pair-$id.code")
        val done = File(directory, "pair-$id.done")
        val receiving = launch { receiver.run(viewModel.incrementalStore) }
        try {
            endpoint.writeText(receiver.code)
            withTimeout(35_000) { while (!done.exists()) delay(100) }
        } finally {
            receiver.close()
            receiving.cancelAndJoin()
            viewModel.viewModelScope.cancel()
            endpoint.delete()
            done.delete()
        }
        val keys = repository.snapshotFlow.first().profiles.single().services.single().items.single().keys.associateBy(ApiKey::id)
        check(keys.getValue("android-key").value == "android-edited")
        check(keys.getValue("desktop-key").value == "desktop-edited")
    }
}
