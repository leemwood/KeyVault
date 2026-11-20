package cn.lemwood.keyvault.sync

import cn.lemwood.keyvault.data.backup.MAX_PAYLOAD_BYTES
import cn.lemwood.keyvault.data.model.VaultProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

internal const val TRANSFER_BLOCK_BYTES = 64 * 1024
internal const val TRANSFER_VERSION = 1
internal const val TRANSFER_MAGIC = 0x4B565452
internal const val TRANSFER_TIMEOUT_MS = 15_000
private const val DIGEST_BYTES = 32
private const val MANIFEST_MAX_BYTES = 4096
private const val COMMIT = 0x43
private const val PREFIX = "keyvault-transfer:1:"
private val json = Json { encodeDefaults = true }
private val random = SecureRandom()

internal const val INCREMENTAL_PREFIX = "keyvault-sync:1:"

internal data class TransferEndpoint(val socketAddress: InetSocketAddress, val peerKey: String) {
    fun code(incremental: Boolean = false): String =
        "${if (incremental) INCREMENTAL_PREFIX else PREFIX}${socketAddress.address.hostAddress}:${socketAddress.port}:$peerKey"
}

internal fun readTransferCode(source: String, incremental: Boolean = false): TransferEndpoint {
    val prefix = if (incremental) INCREMENTAL_PREFIX else PREFIX
    val text = source.trim()
    if (!text.startsWith(prefix) || text.length > 128) failTransfer(TransferProblem.INVALID_CODE)
    val parts = text.removePrefix(prefix).split(':', limit = 4)
    if (parts.size != 3) failTransfer(TransferProblem.INVALID_CODE)
    val (ip, portText, key) = parts
    val port = portText.toIntOrNull() ?: failTransfer(TransferProblem.INVALID_CODE)
    if (port !in 1..65535 || portText != port.toString()) failTransfer(TransferProblem.INVALID_CODE)
    if (!validTransferField(key, DIGEST_BYTES)) failTransfer(TransferProblem.INVALID_CODE)
    val address = try { parseIpv4(ip) } catch (_: PairingException) { failTransfer(TransferProblem.INVALID_CODE) }
    return TransferEndpoint(InetSocketAddress(address, port), key)
}

@Serializable
internal data class TransferManifest(
    val transferId: String,
    val profileId: String,
    val entries: Int,
    val totalBytes: Int,
    val chunkBytes: Int,
    val checksum: String,
) {
    val chunkCount: Int get() = (totalBytes + chunkBytes - 1) / chunkBytes

    fun lengthAt(index: Int): Int = minOf(chunkBytes, totalBytes - index * chunkBytes)

    fun bytes(): ByteArray = json.encodeToString(this).encodeToByteArray()

    fun validate() {
        if (totalBytes > MAX_PAYLOAD_BYTES) failTransfer(TransferProblem.TOO_LARGE)
        if (totalBytes <= 0 || entries !in 0..100_001 || chunkBytes != TRANSFER_BLOCK_BYTES ||
            profileId.isBlank() || profileId.length > 256 || profileId.any(Char::isISOControl) ||
            !validTransferField(transferId, 16) || !validTransferField(checksum, DIGEST_BYTES)
        ) failTransfer(TransferProblem.INTEGRITY)
    }

    companion object {
        fun create(profile: VaultProfile, bytes: ByteArray) = TransferManifest(
            randomTransferBytes(16).encode(), profile.id, profile.transferEntryCount(),
            bytes.size, TRANSFER_BLOCK_BYTES, checksum(bytes).encode(),
        ).also(TransferManifest::validate)
    }
}

internal fun VaultProfile.transferEntryCount(): Int = services.sumOf { service ->
    1 + service.items.sumOf { item -> 1 + item.keys.size }
}

internal enum class TransferReply(val id: Int, val problem: TransferProblem? = null) {
    READY(1), COMPLETE(2),
    VERSION_ERROR(16, TransferProblem.UNSUPPORTED),
    BAD_DATA(17, TransferProblem.INTEGRITY),
    TOO_BIG(18, TransferProblem.TOO_LARGE),
    BUSY(19, TransferProblem.RECEIVER_BUSY),
    CONFLICT(20, TransferProblem.CONFLICT),
    STORAGE(21, TransferProblem.STORAGE),
    UNTRUSTED(22, TransferProblem.UNTRUSTED),
}

internal class TransferWire(socket: SSLSocket) {
    val input = DataInputStream(BufferedInputStream(socket.inputStream))
    val output = DataOutputStream(BufferedOutputStream(socket.outputStream))

    fun sendHello() {
        output.writeInt(TRANSFER_MAGIC)
        output.writeInt(TRANSFER_VERSION)
        output.flush()
        expect(TransferReply.READY)
    }

    fun acceptHello() {
        if (input.readInt() != TRANSFER_MAGIC || input.readInt() != TRANSFER_VERSION) {
            failTransfer(TransferProblem.UNSUPPORTED)
        }
        reply(TransferReply.READY)
    }

    fun writeManifest(manifest: TransferManifest) {
        val bytes = manifest.bytes()
        if (bytes.size > MANIFEST_MAX_BYTES) failTransfer(TransferProblem.INTEGRITY)
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    fun readManifest(): TransferManifest {
        val size = input.readInt()
        if (size !in 1..MANIFEST_MAX_BYTES) failTransfer(TransferProblem.INTEGRITY)
        val bytes = input.readTransferBytes(size)
        val manifest = try {
            json.decodeFromString<TransferManifest>(bytes.decodeToString(throwOnInvalidSequence = true))
        } catch (_: IllegalArgumentException) {
            failTransfer(TransferProblem.INTEGRITY)
        } catch (_: CharacterCodingException) {
            failTransfer(TransferProblem.INTEGRITY)
        }
        if (!bytes.contentEquals(manifest.bytes())) failTransfer(TransferProblem.INTEGRITY)
        manifest.validate()
        return manifest
    }

    fun resumeAt(next: Int, key: ByteArray) {
        output.writeByte(TransferReply.READY.id)
        output.writeInt(next)
        output.write(key)
        output.flush()
    }

    fun writeChunk(index: Int, data: ByteArray, offset: Int, length: Int, tag: ByteArray) {
        output.writeInt(index)
        output.writeInt(length)
        output.write(data, offset, length)
        output.write(tag)
        output.flush()
    }

    fun readChunk(index: Int, length: Int, signer: TransferChunkMac): ByteArray {
        if (input.readInt() != index || input.readInt() != length) failTransfer(TransferProblem.INTEGRITY)
        val bytes = input.readTransferBytes(length)
        try {
            val tag = input.readTransferBytes(DIGEST_BYTES)
            if (!MessageDigest.isEqual(tag, signer.tag(index, bytes, 0, length))) failTransfer(TransferProblem.INTEGRITY)
            return bytes
        } catch (error: Exception) {
            bytes.fill(0)
            throw error
        }
    }

    fun readKey(): ByteArray = input.readTransferBytes(DIGEST_BYTES)

    fun requestCommit() {
        output.writeByte(COMMIT)
        output.flush()
    }

    fun expectCommit() {
        if (input.readUnsignedByte() != COMMIT) failTransfer(TransferProblem.INTEGRITY)
    }

    fun reply(reply: TransferReply) {
        output.writeByte(reply.id)
        output.flush()
    }

    fun readReply(): TransferReply {
        val id = input.readUnsignedByte()
        val response = TransferReply.entries.firstOrNull { it.id == id } ?: failTransfer(TransferProblem.INTEGRITY)
        response.problem?.let(::failTransfer)
        return response
    }

    fun expect(expected: TransferReply) {
        if (readReply() != expected) failTransfer(TransferProblem.INTEGRITY)
    }

    fun reject(problem: TransferProblem) {
        reply(TransferReply.entries.firstOrNull { it.problem == problem } ?: TransferReply.BAD_DATA)
    }
}

internal class TransferChunkMac(key: ByteArray, private val manifestHash: ByteArray) {
    private val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }

    fun tag(index: Int, payload: ByteArray, offset: Int, length: Int): ByteArray {
        mac.update("KeyVault transfer chunk v1\u0000".encodeToByteArray())
        mac.update(manifestHash)
        for (shift in 24 downTo 0 step 8) mac.update((index ushr shift).toByte())
        mac.update(payload, offset, length)
        return mac.doFinal()
    }
}

private fun DataInputStream.readTransferBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    try {
        readFully(bytes)
        return bytes
    } catch (failure: Exception) {
        bytes.fill(0)
        throw failure
    }
}

internal fun randomTransferBytes(size: Int = DIGEST_BYTES): ByteArray = ByteArray(size).also(random::nextBytes)

internal fun checksum(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal fun validTransferField(value: String, bytes: Int): Boolean = try {
    val result = Base64.getUrlDecoder().decode(value)
    result.size == bytes && result.encode() == value
} catch (_: IllegalArgumentException) {
    false
}

internal fun failTransfer(problem: TransferProblem): Nothing = throw TransferException(problem)
