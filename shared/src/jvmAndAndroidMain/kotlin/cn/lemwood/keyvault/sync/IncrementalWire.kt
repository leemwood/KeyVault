package cn.lemwood.keyvault.sync

import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

internal const val MAX_REPLICA_BYTES = 8 * 1024 * 1024
private const val MAGIC = 0x4B565359
private const val VERSION = 1
internal val replicaJson = Json { encodeDefaults = true }

internal enum class IncrementalFrame(val code: Int) {
    ROUND(1), MANIFEST(2), BUNDLE(3), WINDOW(4), CHUNK(5), ACK(6), FINISH(7), ERROR(8),
}

internal class IncrementalWire(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    secret: ByteArray,
    client: Boolean,
) {
    private val outgoing = authentication(secret, if (client) 1 else 2)
    private val incoming = authentication(secret, if (client) 2 else 1)
    private var sent = 0L
    private var received = 0L

    fun write(frame: IncrementalFrame, bytes: ByteArray = byteArrayOf()) {
        if (bytes.size > MAX_REPLICA_BYTES) failTransfer(TransferProblem.TOO_LARGE)
        val seq = ++sent
        val header = frameHeader(frame.code, seq, bytes.size)
        output.write(header)
        output.write(bytes)
        output.write(outgoing.tag(header, bytes))
        output.flush()
    }

    fun read(expected: IncrementalFrame, maximum: Int = MAX_REPLICA_BYTES): ByteArray {
        val kind = input.readUnsignedByte()
        val seq = input.readLong()
        val size = input.readInt()
        if (seq != received + 1) failTransfer(TransferProblem.REPLAY)
        if (kind != expected.code && kind != IncrementalFrame.ERROR.code) failTransfer(TransferProblem.INTEGRITY)
        if (size !in 0..(if (kind == IncrementalFrame.ERROR.code) 50 else maximum)) failTransfer(TransferProblem.INTEGRITY)
        val bytes = ByteArray(size)
        try {
            input.readFully(bytes)
            val tag = ByteArray(32).also(input::readFully)
            val header = frameHeader(kind, seq, size)
            if (!MessageDigest.isEqual(tag, incoming.tag(header, bytes))) failTransfer(TransferProblem.INTEGRITY)
            received = seq
            if (kind == IncrementalFrame.ERROR.code) {
                val failure = TransferProblem.entries.firstOrNull { it.name == bytes.decodeToString() }
                failTransfer(failure ?: TransferProblem.INTEGRITY)
            }
            return bytes
        } catch (failure: Exception) {
            bytes.fill(0)
            throw failure
        }
    }

    inline fun <reified T> send(frame: IncrementalFrame, value: T) {
        val bytes = replicaJson.encodeToString(value).encodeToByteArray()
        try { write(frame, bytes) } finally { bytes.fill(0) }
    }

    inline fun <reified T> receive(frame: IncrementalFrame, maximum: Int = MAX_REPLICA_BYTES): T {
        val bytes = read(frame, maximum)
        try { return decodeIncremental(bytes) } finally { bytes.fill(0) }
    }

    fun reject(problem: TransferProblem) = write(IncrementalFrame.ERROR, problem.name.encodeToByteArray())

    companion object {
        fun open(socket: SSLSocket, client: Boolean): IncrementalWire {
            val input = DataInputStream(BufferedInputStream(socket.inputStream))
            val output = DataOutputStream(BufferedOutputStream(socket.outputStream))
            val secret = if (client) {
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.flush()
                if (input.readInt() != MAGIC || input.readInt() != VERSION) failTransfer(TransferProblem.UNSUPPORTED)
                ByteArray(32).also(input::readFully)
            } else {
                if (input.readInt() != MAGIC || input.readInt() != VERSION) failTransfer(TransferProblem.UNSUPPORTED)
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                randomTransferBytes().also { output.write(it); output.flush() }
            }
            try { return IncrementalWire(input, output, secret, client) } finally { secret.fill(0) }
        }
    }
}

internal inline fun <reified T> decodeIncremental(bytes: ByteArray): T = try {
    replicaJson.decodeFromString<T>(bytes.decodeToString(throwOnInvalidSequence = true))
} catch (_: IllegalArgumentException) {
    failTransfer(TransferProblem.INTEGRITY)
} catch (_: CharacterCodingException) {
    failTransfer(TransferProblem.INTEGRITY)
}

internal fun replicaDigest(value: ReplicaValue): String {
    val bytes = replicaJson.encodeToString(value).encodeToByteArray()
    try { return checksum(bytes).encode() } finally { bytes.fill(0) }
}

private fun frameHeader(kind: Int, sequence: Long, length: Int): ByteArray =
    java.nio.ByteBuffer.allocate(13).put(kind.toByte()).putLong(sequence).putInt(length).array()

private fun authentication(secret: ByteArray, direction: Int) = Mac.getInstance("HmacSHA256").apply {
    val domain = "KeyVault incremental v1\u0000".encodeToByteArray() + direction.toByte()
    init(SecretKeySpec(secret, "HmacSHA256"))
    val key = doFinal(domain)
    try { init(SecretKeySpec(key, "HmacSHA256")) } finally { key.fill(0) }
}

private fun Mac.tag(header: ByteArray, bytes: ByteArray): ByteArray {
    update(header)
    return doFinal(bytes)
}
