package cn.lemwood.keyvault.data.backup

import cn.lemwood.keyvault.data.model.VaultProfile
import cn.lemwood.keyvault.data.serializer.VaultCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

const val MAX_BACKUP_FILE_BYTES = 6 * 1024 * 1024
internal const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024
private const val SALT_BYTES = 16
private const val NONCE_BYTES = 12
private const val TAG_BYTES = 16
private const val CHECKSUM_BYTES = 32
private const val FORMAT = 2
private const val KDF = "Argon2id"
private const val CIPHER = "AES-256-GCM"
internal const val MAX_BACKUP_PASSWORD_CHARS = 256

enum class BackupProblem {
    WRONG_PASSWORD, DAMAGED, UNSUPPORTED, TOO_LARGE, NO_SPACE, FILE_IO, INVALID_PASSWORD,
}

class BackupException(val problem: BackupProblem) : Exception(problem.name)

internal data class BackupArgonParameters(val memoryKiB: Int, val iterations: Int, val parallelism: Int)

internal expect object BackupCrypto {
    fun randomBytes(size: Int): ByteArray
    fun checksum(header: ByteArray, ciphertext: ByteArray): ByteArray
    fun deriveKey(password: ByteArray, salt: ByteArray, params: BackupArgonParameters): ByteArray
    fun encrypt(key: ByteArray, nonce: ByteArray, header: ByteArray, plaintext: ByteArray): ByteArray
    fun decrypt(key: ByteArray, nonce: ByteArray, header: ByteArray, ciphertext: ByteArray): ByteArray
}

internal sealed interface BackupSource {
    class Legacy(val profile: VaultProfile) : BackupSource
    class Encrypted(
        val salt: ByteArray,
        val nonce: ByteArray,
        val params: BackupArgonParameters,
        val header: ByteArray,
        val ciphertext: ByteArray,
    ) : BackupSource
}

internal object BackupCodec {
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val defaultParams = BackupArgonParameters(memoryKiB = 32 * 1024, iterations = 3, parallelism = 1)

    suspend fun encode(profile: VaultProfile, password: ByteArray): ByteArray = withContext(worker) {
        checkPassword(password)
        val salt = BackupCrypto.randomBytes(SALT_BYTES)
        val nonce = BackupCrypto.randomBytes(NONCE_BYTES)
        val header = header(salt, nonce, defaultParams)
        val aad = header.toString().encodeToByteArray()
        val payload = encodeProfilePayload(profile)
        val ciphertext = try {
            val key = BackupCrypto.deriveKey(password, salt, defaultParams)
            try {
                ensureActive()
                BackupCrypto.encrypt(key, nonce, aad, payload)
            } finally {
                key.fill(0)
            }
        } finally {
            payload.fill(0)
        }
        buildJsonObject {
            header.forEach { (name, value) -> put(name, value) }
            put("ciphertext", Base64.encode(ciphertext))
            put("checksum", Base64.encode(BackupCrypto.checksum(aad, ciphertext)))
        }.toString().encodeToByteArray()
    }

    suspend fun inspect(bytes: ByteArray): BackupSource = withContext(worker) {
        checkSize(bytes.size, MAX_BACKUP_FILE_BYTES)
        val root = parseBackupObject(bytes)
        val version = root["format"]
        // A bare legacy document can contain a service called "format".
        if (version == null || version is JsonObject) {
            return@withContext BackupSource.Legacy(decodeLegacyBackup(root, hasVersion = false))
        }
        when ((version as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: damagedBackup()) {
            1 -> BackupSource.Legacy(decodeLegacyBackup(root, hasVersion = true))
            FORMAT -> readEnvelope(root)
            else -> throw BackupException(BackupProblem.UNSUPPORTED)
        }
    }

    suspend fun unlock(source: BackupSource.Encrypted, password: ByteArray): VaultProfile = withContext(worker) {
        checkPassword(password)
        val key = BackupCrypto.deriveKey(password, source.salt, source.params)
        val plaintext = try {
            ensureActive()
            BackupCrypto.decrypt(key, source.nonce, source.header, source.ciphertext)
        } finally {
            key.fill(0)
        }
        try {
            decodeProfilePayload(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun readEnvelope(root: JsonObject): BackupSource.Encrypted {
        root.requireKeys("format", "kdf", "cipher", "ciphertext", "checksum")
        val kdf = root.backupObject("kdf")
        kdf.requireKeys("alg", "salt", "params")
        val cipher = root.backupObject("cipher")
        cipher.requireKeys("alg", "nonce")
        if (kdf.backupString("alg") != KDF || cipher.backupString("alg") != CIPHER) {
            throw BackupException(BackupProblem.UNSUPPORTED)
        }
        val values = kdf.backupObject("params")
        values.requireKeys("memoryKiB", "iterations", "parallelism")
        val params = BackupArgonParameters(
            values.backupInt("memoryKiB"), values.backupInt("iterations"), values.backupInt("parallelism"),
        )
        if (params.memoryKiB !in 19 * 1024..64 * 1024 || params.iterations !in 2..4 || params.parallelism !in 1..2) {
            damagedBackup()
        }
        val salt = kdf.decodeBase64("salt", SALT_BYTES..SALT_BYTES)
        val nonce = cipher.decodeBase64("nonce", NONCE_BYTES..NONCE_BYTES)
        val checksum = root.decodeBase64("checksum", CHECKSUM_BYTES..CHECKSUM_BYTES)
        val ciphertext = root.decodeBase64("ciphertext", (TAG_BYTES + 1)..(MAX_PAYLOAD_BYTES + TAG_BYTES))
        val aad = header(salt, nonce, params).toString().encodeToByteArray()
        if (!BackupCrypto.checksum(aad, ciphertext).contentEquals(checksum)) damagedBackup()
        return BackupSource.Encrypted(salt, nonce, params, aad, ciphertext)
    }

    private fun header(salt: ByteArray, nonce: ByteArray, params: BackupArgonParameters) = buildJsonObject {
        put("format", FORMAT)
        put("kdf", buildJsonObject {
            put("alg", KDF)
            put("salt", Base64.encode(salt))
            put("params", buildJsonObject {
                put("memoryKiB", params.memoryKiB)
                put("iterations", params.iterations)
                put("parallelism", params.parallelism)
            })
        })
        put("cipher", buildJsonObject {
            put("alg", CIPHER)
            put("nonce", Base64.encode(nonce))
        })
    }

    private fun JsonObject.backupInt(name: String): Int =
        (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: damagedBackup()

    private fun JsonObject.decodeBase64(name: String, size: IntRange): ByteArray {
        val encoded = backupString(name)
        if (encoded.length > (size.last + 2) / 3 * 4) damagedBackup()
        val decoded = try {
            Base64.decode(encoded)
        } catch (_: IllegalArgumentException) {
            damagedBackup()
        }
        if (decoded.size !in size || Base64.encode(decoded) != encoded) damagedBackup()
        return decoded
    }

    private fun checkPassword(password: ByteArray) {
        if (password.isEmpty() || password.size > 4 * MAX_BACKUP_PASSWORD_CHARS) {
            throw BackupException(BackupProblem.INVALID_PASSWORD)
        }
    }
}

internal fun encodeProfilePayload(profile: VaultProfile): ByteArray {
    val document = buildJsonObject {
        put("name", profile.name)
        put("services", VaultCodec.encodeServices(profile.services))
    }
    validateBackupPayload(document)
    val text = document.toString()
    checkSize(text.length, MAX_PAYLOAD_BYTES)
    checkJsonLimits(text)
    return text.encodeToByteArray().also { bytes ->
        if (bytes.size > MAX_PAYLOAD_BYTES) {
            bytes.fill(0)
            throw BackupException(BackupProblem.TOO_LARGE)
        }
    }
}

internal fun decodeProfilePayload(bytes: ByteArray): VaultProfile {
    checkSize(bytes.size, MAX_PAYLOAD_BYTES)
    return decodeBackupPayload(parseBackupObject(bytes))
}

private fun checkSize(size: Int, max: Int) {
    if (size > max) throw BackupException(BackupProblem.TOO_LARGE)
}

internal fun damagedBackup(): Nothing = throw BackupException(BackupProblem.DAMAGED)

internal fun JsonObject.requireKeys(vararg names: String) {
    if (size != names.size || names.any { it !in this }) damagedBackup()
}

internal fun JsonObject.backupObject(name: String): JsonObject = get(name) as? JsonObject ?: damagedBackup()

internal fun JsonObject.backupString(name: String): String =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: damagedBackup()

private fun parseBackupObject(bytes: ByteArray): JsonObject = try {
    val text = bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF")
    checkJsonLimits(text)
    Json.parseToJsonElement(text) as? JsonObject ?: damagedBackup()
} catch (_: IllegalArgumentException) {
    damagedBackup()
} catch (_: CharacterCodingException) {
    damagedBackup()
}

// Bound work before materializing the JSON tree and reject duplicate member names.
private fun checkJsonLimits(text: String) {
    val members = mutableListOf<MutableSet<String>?>()
    var index = 0
    var tokens = 0
    while (index < text.length) {
        when (text[index++]) {
            '{' -> members.add(mutableSetOf())
            '[' -> members.add(null)
            '}', ']' -> if (members.isEmpty()) damagedBackup() else members.removeAt(members.lastIndex)
            ',' -> if (++tokens > 100_000) throw BackupException(BackupProblem.TOO_LARGE)
            '"' -> {
                val start = index
                var escaped = false
                while (index < text.length && text[index] != '"') {
                    if (text[index++] == '\\') {
                        escaped = true
                        index++
                    }
                }
                if (index >= text.length) damagedBackup()
                val end = index++
                val objectMembers = members.lastOrNull() ?: continue
                while (index < text.length && text[index].isWhitespace()) index++
                if (text.getOrNull(index) == ':') {
                    val key = if (escaped) {
                        (Json.parseToJsonElement(text.substring(start - 1, end + 1)) as JsonPrimitive).content
                    } else {
                        text.substring(start, end)
                    }
                    if (!objectMembers.add(key)) damagedBackup()
                }
            }
        }
        if (members.size > 16) damagedBackup()
    }
}
