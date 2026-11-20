package cn.lemwood.keyvault.sync

import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

internal const val SYNC_TLS_KEY_ALIAS = "keyvault-sync-tls-p256-v1"
private const val TLS_TIMEOUT_MS = 10_000
private val supportedTlsVersions = setOf("TLSv1.3", "TLSv1.2")

/** TLS credentials bound to one device. Peer pins must come from an authenticated pairing channel. */
class SyncTlsIdentity internal constructor(
    private val privateKey: PrivateKey,
    private val certificate: X509Certificate,
) {
    val certificatePin: String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(certificate.sha256())

    fun connect(address: InetSocketAddress, peerPin: String): SSLSocket = connect(address, peerPin) {}

    internal fun connect(address: InetSocketAddress, peerPin: String, onSocket: (SSLSocket) -> Unit): SSLSocket {
        val socket = context(peerPin).socketFactory.createSocket() as SSLSocket
        return socket.closeOnFailure {
            onSocket(this)
            enabledProtocols = supportedProtocols.filter(supportedTlsVersions::contains).toTypedArray()
            soTimeout = TLS_TIMEOUT_MS
            connect(address, TLS_TIMEOUT_MS)
            startHandshake()
        }
    }

    fun listen(address: InetSocketAddress, peerPin: String): SyncTlsListener {
        return listen(address, PinnedCertificateTrustManager(peerPin))
    }

    internal fun listenForPairing(address: InetSocketAddress): SyncTlsListener =
        listen(address, PairingCertificateTrustManager)

    internal fun listenForTrustedPeers(address: InetSocketAddress, allowedPins: () -> Set<String>): SyncTlsListener =
        listen(address, object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                val leaf = chain.singleOrNull() ?: throw CertificateException("Expected a device certificate")
                if (leaf.pin() !in allowedPins()) throw CertificateException("Untrusted device certificate")
                leaf.checkValidity()
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String): Unit =
                throw CertificateException("Expected a server pin")

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })

    private fun listen(address: InetSocketAddress, trust: X509TrustManager): SyncTlsListener {
        val socket = context(trust).serverSocketFactory.createServerSocket() as SSLServerSocket
        socket.closeOnFailure {
            enabledProtocols = supportedProtocols.filter(supportedTlsVersions::contains).toTypedArray()
            needClientAuth = true
            bind(address)
        }
        return SyncTlsListener(socket)
    }

    private fun context(peerPin: String): SSLContext = context(PinnedCertificateTrustManager(peerPin))

    private fun context(trust: X509TrustManager): SSLContext = SSLContext.getInstance("TLS").apply {
        init(
            arrayOf<KeyManager>(DeviceKeyManager(privateKey, certificate)),
            arrayOf<TrustManager>(trust),
            null,
        )
    }
}

class SyncTlsListener internal constructor(private val socket: SSLServerSocket) : Closeable {
    val localPort: Int get() = socket.localPort

    fun accept(): SSLSocket {
        val peer = acceptSocket()
        return peer.closeOnFailure {
            soTimeout = TLS_TIMEOUT_MS
            startHandshake()
        }
    }

    internal fun acceptSocket(): SSLSocket = socket.accept() as SSLSocket

    internal fun setAcceptTimeout(milliseconds: Int) {
        socket.soTimeout = milliseconds
    }

    override fun close() = socket.close()
}

// Only the temporary pairing listener accepts an unknown leaf. The pairing protocol checks the
// signed invitation, actual TLS pin and user approval before adding it to the trusted registry.
private object PairingCertificateTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf = chain.singleOrNull() ?: throw CertificateException("Expected one device certificate")
        if (leaf.publicKey.algorithm != "EC" || leaf.issuerX500Principal != leaf.subjectX500Principal) {
            throw CertificateException("Expected a self-signed device certificate")
        }
        leaf.checkValidity()
        try {
            leaf.verify(leaf.publicKey)
        } catch (error: java.security.GeneralSecurityException) {
            throw CertificateException("Invalid device certificate", error)
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String): Unit =
        throw CertificateException("Pairing server must be pinned")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private class PinnedCertificateTrustManager(peerPin: String) : X509TrustManager {
    private val expected = Base64.getUrlDecoder().decode(peerPin).also {
        require(it.size == 32) { "Invalid TLS certificate pin" }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = verify(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = verify(chain)

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun verify(chain: Array<out X509Certificate>) {
        val leaf = chain.singleOrNull() ?: throw CertificateException("Expected a device certificate")
        if (!MessageDigest.isEqual(expected, leaf.sha256())) {
            throw CertificateException("Device certificate pin mismatch")
        }
        leaf.checkValidity()
    }
}

// JSSE can sign with the Android Keystore key handle without exporting the private key.
private class DeviceKeyManager(
    private val privateKey: PrivateKey,
    private val certificate: X509Certificate,
) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String, issuers: Array<out Principal>?): Array<String>? =
        aliasFor(keyType)?.let { arrayOf(it) }

    override fun getServerAliases(keyType: String, issuers: Array<out Principal>?): Array<String>? =
        getClientAliases(keyType, issuers)

    override fun chooseClientAlias(
        keyTypes: Array<out String>,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = keyTypes.firstNotNullOfOrNull(::aliasFor)

    override fun chooseServerAlias(keyType: String, issuers: Array<out Principal>?, socket: Socket?): String? =
        aliasFor(keyType)

    override fun getCertificateChain(alias: String): Array<X509Certificate>? =
        if (alias == SYNC_TLS_KEY_ALIAS) arrayOf(certificate) else null

    override fun getPrivateKey(alias: String): PrivateKey? =
        if (alias == SYNC_TLS_KEY_ALIAS) privateKey else null

    private fun aliasFor(keyType: String): String? =
        if (keyType == "EC" || keyType == "EC_EC") SYNC_TLS_KEY_ALIAS else null
}

private fun X509Certificate.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(encoded)

internal fun SSLSocket.remoteCertificatePin(): String = (session.peerCertificates.single() as X509Certificate).pin()

private fun X509Certificate.pin(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256())

private inline fun <T : Closeable> T.closeOnFailure(action: T.() -> Unit): T {
    try {
        action()
        return this
    } catch (failure: Exception) {
        try {
            close()
        } catch (closeFailure: Exception) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }
}
