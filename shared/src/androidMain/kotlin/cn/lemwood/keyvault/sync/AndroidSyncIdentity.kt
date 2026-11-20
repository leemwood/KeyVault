package cn.lemwood.keyvault.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import javax.security.auth.x500.X500Principal

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private val identityLock = Any()

fun loadOrCreateAndroidSyncIdentity(): SyncTlsIdentity = synchronized(identityLock) {
    val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    if (!store.containsAlias(SYNC_TLS_KEY_ALIAS)) {
        val now = Instant.now()
        val spec = KeyGenParameterSpec.Builder(SYNC_TLS_KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // Conscrypt hashes the TLS transcript before delegating ECDSA signing to Keystore.
            .setDigests(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512,
            )
            .setCertificateSubject(X500Principal("CN=KeyVault Sync"))
            .setCertificateNotBefore(Date.from(now.minusSeconds(60)))
            .setCertificateNotAfter(Date.from(now.plus(3650, ChronoUnit.DAYS)))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    val entry = store.getEntry(SYNC_TLS_KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
    check(entry.privateKey.encoded == null) { "Expected a non-exportable Android Keystore key" }
    SyncTlsIdentity(entry.privateKey, entry.certificate as X509Certificate)
}
