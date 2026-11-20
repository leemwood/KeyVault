package cn.lemwood.keyvault.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val PAIRING_TIMEOUT_MS = 120_000L
internal const val MDNS_SERVICE = "_keyvault._tcp."
private const val CODE_PREFIX = "keyvault-pair:"
internal const val MAX_PAIRING_CODE = 2_048
private const val MAX_FRAME_BYTES = 4_096
private val random = SecureRandom()
private val json = Json { encodeDefaults = true }
private val offerDomain = "KeyVault pairing offer 1\u0000".encodeToByteArray()
private val replyDomain = "KeyVault pairing reply 1\u0000".encodeToByteArray()
private val comparisonDomain = "KeyVault pairing verification 1\u0000".encodeToByteArray()

@Serializable
internal data class PeerIdentity(val name: String, val publicKey: String, val tlsPin: String) {
    fun publicDevice() = TrustedDevice(name, publicKey)

    fun validate() {
        if (name.isBlank() || name.length > 48 || name.any { it.isISOControl() || it.category == CharCategory.FORMAT }) invalid()
        publicKey.decode(32)
        tlsPin.decode(32)
    }
}

@Serializable
internal data class PairingOffer(
    val version: Int = 1,
    val address: String,
    val port: Int,
    val expiresAt: Long,
    val nonce: String,
    val ephemeralKey: String,
    val host: PeerIdentity,
)

@Serializable
internal data class SignedOffer(val offer: PairingOffer, val signature: String)

@Serializable
internal data class PairingReply(val peer: PeerIdentity, val ephemeralKey: String)

@Serializable
internal data class SignedReply(val reply: PairingReply, val signature: String)

internal class PairingSigner(private val key: Ed25519PrivateKeyParameters) {
    val publicKey: String = key.generatePublicKey().encoded.encode()

    fun sign(message: ByteArray): String = Ed25519Signer().run {
        init(true, key)
        update(message, 0, message.size)
        generateSignature().encode()
    }
}

internal fun newEphemeralKey() = X25519PrivateKeyParameters(random)

internal fun pairingNonce() = ByteArray(32).also(random::nextBytes).encode()

internal fun PairingOffer.signed(signer: PairingSigner): SignedOffer = SignedOffer(this, signer.sign(offerBytes(this)))

internal fun SignedOffer.toCode(): String = CODE_PREFIX + json.encodeToString(this).encodeToByteArray().encode()

internal fun readOffer(code: String, now: Long): SignedOffer {
    val text = code.trim()
    if (text.length > MAX_PAIRING_CODE || !text.startsWith(CODE_PREFIX)) invalid()
    val offer = decodeFrame<SignedOffer>(text.removePrefix(CODE_PREFIX).decode())
    val data = offer.offer
    if (offer.toCode() != text || data.version != 1 || data.port !in 1..65535) invalid()
    parseIpv4(data.address)
    data.host.validate()
    data.nonce.decode(32)
    data.ephemeralKey.decode(32)
    verify(data.host.publicKey, offerBytes(data), offer.signature)
    if (data.expiresAt <= now) throw PairingException(PairingProblem.EXPIRED)
    if (data.expiresAt > now + PAIRING_TIMEOUT_MS + 30_000) invalid()
    return offer
}

internal fun PairingReply.signed(offer: PairingOffer, signer: PairingSigner): SignedReply =
    SignedReply(this, signer.sign(replyBytes(offer, this)))

internal fun SignedReply.verify(offer: PairingOffer, actualPin: String) {
    reply.peer.validate()
    reply.ephemeralKey.decode(32)
    if (reply.peer.tlsPin != actualPin || reply.peer.publicKey == offer.host.publicKey) rejected()
    verify(reply.peer.publicKey, replyBytes(offer, reply), signature)
}

internal fun comparisonCode(
    local: X25519PrivateKeyParameters,
    remote: String,
    offer: PairingOffer,
    reply: PairingReply,
): String {
    val secret = ByteArray(32)
    try {
        local.generateSecret(X25519PublicKeyParameters(remote.decode(32)), secret, 0)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update(comparisonDomain)
        val code = mac.doFinal(replyBytes(offer, reply)).take(6)
        return code.joinToString("") { "%02X".format(it.toInt() and 0xff) }.chunked(4).joinToString(" ")
    } catch (_: IllegalStateException) {
        rejected()
    } finally {
        secret.fill(0)
    }
}

internal fun DataOutputStream.writeReply(reply: SignedReply) {
    val bytes = json.encodeToString(reply).encodeToByteArray()
    writeInt(bytes.size)
    write(bytes)
    flush()
}

internal fun DataInputStream.readReply(): SignedReply {
    val size = readInt()
    if (size !in 1..MAX_FRAME_BYTES) invalid()
    return decodeFrame<SignedReply>(ByteArray(size).also(::readFully))
}

internal fun parseIpv4(address: String): Inet4Address {
    val parts = address.split('.', limit = 5)
    if (parts.size != 4) invalid()
    val octets = parts.map { part ->
        val value = part.toIntOrNull() ?: invalid()
        if (value !in 0..255 || value.toString() != part) invalid()
        value.toByte()
    }
    return InetAddress.getByAddress(octets.toByteArray()) as Inet4Address
}

internal fun ByteArray.encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private fun String.decode(size: Int? = null): ByteArray {
    val bytes = try { Base64.getUrlDecoder().decode(this) } catch (_: IllegalArgumentException) { invalid() }
    if (bytes.encode() != this || (size != null && bytes.size != size)) invalid()
    return bytes
}

private inline fun <reified T> decodeFrame(bytes: ByteArray): T = try {
    val text = bytes.decodeToString(throwOnInvalidSequence = true)
    val frame = json.decodeFromString<T>(text)
    if (json.encodeToString(frame) != text) invalid()
    frame
} catch (_: IllegalArgumentException) {
    invalid()
} catch (_: CharacterCodingException) {
    invalid()
}

private fun offerBytes(offer: PairingOffer): ByteArray = offerDomain + json.encodeToString(offer).encodeToByteArray()

private fun replyBytes(offer: PairingOffer, reply: PairingReply): ByteArray =
    replyDomain + MessageDigest.getInstance("SHA-256").digest(offerBytes(offer)) + json.encodeToString(reply).encodeToByteArray()

private fun verify(publicKey: String, message: ByteArray, signature: String) {
    val public = Ed25519PublicKeyParameters(publicKey.decode(32))
    val valid = Ed25519Signer().run {
        init(false, public)
        update(message, 0, message.size)
        verifySignature(signature.decode(64))
    }
    if (!valid) rejected()
}

private fun invalid(): Nothing = throw PairingException(PairingProblem.INVALID_CODE)
private fun rejected(): Nothing = throw PairingException(PairingProblem.REJECTED)
