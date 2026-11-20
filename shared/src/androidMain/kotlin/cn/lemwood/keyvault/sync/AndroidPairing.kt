package cn.lemwood.keyvault.sync

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.PersistableBundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import cn.lemwood.keyvault.platform.createVaultDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.io.File
import java.net.Inet4Address
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val SIGNING_WRAP_ALIAS = "keyvault-pairing-wrap-v1"
private const val IV_SIZE = 12
private val identityLock = Any()

fun androidLanPairing(context: Context, scanCode: suspend (String) -> String?): LanPairing =
    androidLanPairing(context, createVaultDataStore(), scanCode)

fun androidLanPairing(
    context: Context,
    store: DataStore<Preferences>,
    scanCode: suspend (String) -> String?,
): LanPairing {
    val application = context.applicationContext
    return object : LanPairing {
        override val requiresPassword = false
        override val scanLabel = "扫描二维码"
        override suspend fun scan(prompt: String): String? = scanCode(prompt)
        override fun copyInvitation(code: String) {
            val clip = ClipData.newPlainText("KeyVault", code)
            if (Build.VERSION.SDK_INT >= 33) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
            application.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        }

        override suspend fun unlock(password: CharArray): PairingDevice = withContext(Dispatchers.IO) {
            storageOperation {
                JvmPairingDevice(
                    loadOrCreateAndroidSyncIdentity(), androidPairingSigner(application),
                    Build.MODEL, store, AndroidDiscovery(application),
                )
            }
        }
    }
}

private fun androidPairingSigner(context: Context): PairingSigner = synchronized(identityLock) {
    val file = AtomicFile(File(context.noBackupFilesDir, "lan-ed25519-v1"))
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val protected = try { file.readFully() } catch (_: java.io.FileNotFoundException) { null }
    val key = wrappingKey(allowCreate = protected == null)
    val seed = if (protected != null) {
        check(protected.size == IV_SIZE + 32 + 16)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, protected, 0, IV_SIZE))
        cipher.doFinal(protected, IV_SIZE, protected.size - IV_SIZE)
    } else {
        val generated = ByteArray(32).also(SecureRandom()::nextBytes)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key)
            check(cipher.iv.size == IV_SIZE)
            val encrypted = cipher.iv + cipher.doFinal(generated)
            val output = file.startWrite()
            try {
                output.write(encrypted)
                file.finishWrite(output)
            } catch (failure: Exception) {
                file.failWrite(output)
                throw failure
            }
            generated
        } catch (failure: Exception) {
            generated.fill(0)
            throw failure
        }
    }
    try {
        PairingSigner(Ed25519PrivateKeyParameters(seed, 0))
    } finally {
        seed.fill(0)
    }
}

private fun wrappingKey(allowCreate: Boolean): SecretKey {
    val provider = "AndroidKeyStore"
    val store = KeyStore.getInstance(provider).apply { load(null) }
    (store.getKey(SIGNING_WRAP_ALIAS, null) as? SecretKey)?.let { return it }
    check(allowCreate)
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, provider).apply {
        init(KeyGenParameterSpec.Builder(SIGNING_WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
    }.generateKey()
}

private class AndroidDiscovery(context: Context) : LanDiscovery {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun addresses(): List<LanAddress> = connectivity.allNetworks.flatMap { network ->
        val capabilities = connectivity.getNetworkCapabilities(network)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true &&
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) != true
        ) return@flatMap emptyList()
        connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull { link ->
            (link.address as? Inet4Address)?.let { LanAddress(it, link.prefixLength) }
        }
    }.distinct()

    override fun discover() = callbackFlow {
        val services = linkedSetOf<String>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { trySend(emptyList()) }
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) { close() }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                services += service.serviceName
                trySend(services.toList())
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                services -= service.serviceName
                trySend(services.toList())
            }
        }
        nsd.discoverServices(MDNS_SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    override suspend fun announce(address: Inet4Address, port: Int, name: String) {
        callbackFlow<Unit> {
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = Unit
                override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { close() }
                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
            }
            val localNetwork = connectivity.allNetworks.firstOrNull { network ->
                connectivity.getLinkProperties(network)?.linkAddresses?.any { it.address == address } == true
            }
            val info = NsdServiceInfo().apply {
                serviceName = name
                serviceType = MDNS_SERVICE
                setPort(port)
                if (Build.VERSION.SDK_INT >= 33) network = localNetwork
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            awaitClose { runCatching { nsd.unregisterService(listener) } }
        }.collect {}
    }
}
