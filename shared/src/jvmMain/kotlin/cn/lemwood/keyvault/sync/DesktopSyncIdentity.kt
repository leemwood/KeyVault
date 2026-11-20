package cn.lemwood.keyvault.sync

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyPairGenerator
import java.security.KeyPair
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

private val identityLock = Any()
private const val SIGNING_ALIAS = "keyvault-pairing-ed25519-v1"
private fun desktopKeyStore() = Path.of(System.getProperty("user.home"), ".keyvault", "sync-tls.p12")

/** The caller supplies and owns the password and clears it after use. */
fun loadOrCreateDesktopSyncIdentity(
    password: CharArray,
    file: Path = desktopKeyStore(),
): SyncTlsIdentity = accessKeyStore(password, file) { store, protection ->
    store.loadTlsIdentity(password, protection)
}

internal fun loadOrCreateDesktopPairingIdentity(password: CharArray, file: Path = desktopKeyStore()) =
    accessKeyStore(password, file) { store, protection ->
        val tls = store.loadTlsIdentity(password, protection)
        if (!store.containsAlias(SIGNING_ALIAS)) {
            val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            store.setEntry(SIGNING_ALIAS, createEntry(keys, "Ed25519"), protection)
        }
        val encoded = store.getKey(SIGNING_ALIAS, password).encoded
        try {
            tls to PairingSigner(PrivateKeyFactory.createKey(encoded) as Ed25519PrivateKeyParameters)
        } finally {
            encoded.fill(0)
        }
    }

private fun <T> accessKeyStore(
    password: CharArray,
    file: Path,
    load: (KeyStore, KeyStore.PasswordProtection) -> T,
): T = synchronized(identityLock) {
    require(password.isNotEmpty()) { "A sync identity password is required" }
    val path = file.toAbsolutePath().normalize()
    Files.createDirectories(path.parent)
    val lockFile = path.resolveSibling("${path.fileName}.lock")
    FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
        channel.lock().use {
            val store = KeyStore.getInstance("PKCS12")
            if (Files.notExists(path)) {
                store.load(null, password)
            } else {
                Files.newInputStream(path).use { store.load(it, password) }
            }
            val previousSize = store.size()
            val protection = KeyStore.PasswordProtection(password, "PBEWithHmacSHA256AndAES_256", null)
            try {
                load(store, protection).also {
                    if (store.size() != previousSize) store.writeAtomically(path, password)
                }
            } finally {
                protection.destroy()
            }
        }
    }
}

private fun KeyStore.loadTlsIdentity(password: CharArray, protection: KeyStore.PasswordProtection): SyncTlsIdentity {
    if (!containsAlias(SYNC_TLS_KEY_ALIAS)) {
        val keys = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        setEntry(SYNC_TLS_KEY_ALIAS, createEntry(keys, "SHA256withECDSA"), protection)
    }
    val key = getKey(SYNC_TLS_KEY_ALIAS, password) as? PrivateKey ?: error("Sync identity private key is missing")
    val certificate = getCertificate(SYNC_TLS_KEY_ALIAS) as? X509Certificate ?: error("Sync identity certificate is missing")
    return SyncTlsIdentity(key, certificate)
}

private fun createEntry(keyPair: KeyPair, algorithm: String): KeyStore.PrivateKeyEntry {
    val name = X500Name("CN=KeyVault Sync")
    val now = Instant.now()
    val certificate = JcaX509v3CertificateBuilder(
        name,
        BigInteger(159, SecureRandom()).setBit(158),
        Date.from(now.minusSeconds(60)),
        Date.from(now.plus(3650, ChronoUnit.DAYS)),
        name,
        keyPair.public,
    ).build(JcaContentSignerBuilder(algorithm).build(keyPair.private))
    return KeyStore.PrivateKeyEntry(
        keyPair.private,
        arrayOf(JcaX509CertificateConverter().getCertificate(certificate)),
    )
}

private fun KeyStore.writeAtomically(path: Path, password: CharArray) {
    val temporaryFile = Files.createTempFile(path.parent, ".sync-tls-", ".p12")
    try {
        FileOutputStream(temporaryFile.toFile()).use { output ->
            store(output, password)
            output.fd.sync()
        }
        Files.move(temporaryFile, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporaryFile)
    }
}
