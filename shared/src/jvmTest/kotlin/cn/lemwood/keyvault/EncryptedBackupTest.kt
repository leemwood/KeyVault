package cn.lemwood.keyvault

import cn.lemwood.keyvault.data.backup.BackupCodec
import cn.lemwood.keyvault.data.backup.BackupCrypto
import cn.lemwood.keyvault.data.backup.BackupException
import cn.lemwood.keyvault.data.backup.BackupProblem
import cn.lemwood.keyvault.data.backup.BackupSource
import cn.lemwood.keyvault.data.backup.MAX_BACKUP_FILE_BYTES
import cn.lemwood.keyvault.data.model.ApiKey
import cn.lemwood.keyvault.data.model.Service
import cn.lemwood.keyvault.data.model.ServiceItem
import cn.lemwood.keyvault.data.model.VaultProfile
import cn.lemwood.keyvault.platform.backupIoFailure
import cn.lemwood.keyvault.platform.readBackupBytes
import cn.lemwood.keyvault.platform.writeBackupAtomically
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EncryptedBackupTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `exports keep names and keys private and use fresh salt and nonce`() = runBlocking {
        val first = encryptedFile.decodeToString()
        assertFalse("演示配置" in first)
        assertFalse("fixture-key-明文" in first)
        val second = BackupCodec.encode(profile, password).decodeToString()
        val a = Json.parseToJsonElement(first).jsonObject
        val b = Json.parseToJsonElement(second).jsonObject
        assertNotEquals(a.getValue("kdf").jsonObject["salt"], b.getValue("kdf").jsonObject["salt"])
        assertNotEquals(a.getValue("cipher").jsonObject["nonce"], b.getValue("cipher").jsonObject["nonce"])
        val restored = BackupCodec.unlock(loadEnvelope(), password)
        assertEquals(profile.name, restored.name)
        assertEquals(profile.services, restored.services)
        assertNotEquals(profile.id, restored.id)
    }

    @Test
    fun `reports exact failure types before importing anything`() = runBlocking {
        val json = Json.parseToJsonElement(encryptedFile.decodeToString()).jsonObject
        val ciphertext = json.getValue("ciphertext").jsonPrimitive.content
        val changedByte = (if (ciphertext.first() == 'A') 'B' else 'A') + ciphertext.drop(1)
        val document = JsonObject(json + ("ciphertext" to JsonPrimitive(changedByte))).toString()
        expectProblem(BackupProblem.DAMAGED) { BackupCodec.inspect(document.encodeToByteArray()) }
        expectProblem(BackupProblem.DAMAGED) { BackupCodec.inspect(encryptedFile.copyOf(encryptedFile.size / 2)) }
        expectProblem(BackupProblem.UNSUPPORTED) { BackupCodec.inspect("""{"format":99}""".encodeToByteArray()) }
        val unreasonable = encryptedFile.decodeToString().replace("\"memoryKiB\":32768", "\"memoryKiB\":99999999")
        expectProblem(BackupProblem.DAMAGED) { BackupCodec.inspect(unreasonable.encodeToByteArray()) }
        expectProblem(BackupProblem.DAMAGED) {
            BackupCodec.inspect("""{"format":1,"\u0066ormat":1,"services":{}}""".encodeToByteArray())
        }
        expectProblem(BackupProblem.WRONG_PASSWORD) {
            BackupCodec.unlock(loadEnvelope(), "wrong password".encodeToByteArray())
        }
    }

    @Test
    fun `valid checksum and authentication still require a valid profile schema`() = runBlocking {
        val source = loadEnvelope()
        val oldHeader = Json.parseToJsonElement(source.header.decodeToString()).jsonObject
        val nonce = BackupCrypto.randomBytes(12)
        val header = JsonObject(oldHeader + ("cipher" to JsonObject(mapOf(
            "alg" to JsonPrimitive("AES-256-GCM"), "nonce" to JsonPrimitive(Base64.encode(nonce)),
        ))))
        val aad = header.toString().encodeToByteArray()
        val key = BackupCrypto.deriveKey(password, source.salt, source.params)
        val invalidProfile = """{"name":"fixture","services":{"svc":{"name":"a","items":[]}}}"""
        val ciphertext = BackupCrypto.encrypt(key, nonce, aad, invalidProfile.encodeToByteArray())
        val checksum = BackupCrypto.checksum(aad, ciphertext)
        val file = JsonObject(header + mapOf(
            "ciphertext" to JsonPrimitive(Base64.encode(ciphertext)),
            "checksum" to JsonPrimitive(Base64.encode(checksum)),
        )).toString().encodeToByteArray()
        val inspected = assertIs<BackupSource.Encrypted>(BackupCodec.inspect(file))
        expectProblem(BackupProblem.DAMAGED) { BackupCodec.unlock(inspected, password) }
    }

    @Test
    fun `file read and parser reject oversize backups`() = runBlocking {
        val oversize = ByteArray(MAX_BACKUP_FILE_BYTES + 1)
        expectProblem(BackupProblem.TOO_LARGE) { oversize.inputStream().use { it.readBackupBytes() } }
        expectProblem(BackupProblem.TOO_LARGE) { BackupCodec.inspect(oversize) }
    }

    @Test
    fun `desktop replaces the file and removes its staging file on failure`() = runBlocking {
        val directory = temporaryFolder.root.toPath()
        val target = directory.resolve("backup.json")
        Files.writeString(target, "previous backup")
        writeBackupAtomically(target, encryptedFile)
        assertContentEquals(encryptedFile, Files.readAllBytes(target))
        val occupied = Files.createDirectory(directory.resolve("occupied.json"))
        Files.writeString(occupied.resolve("keep"), "existing data")
        expectProblem(BackupProblem.FILE_IO) { writeBackupAtomically(occupied, encryptedFile) }
        assertEquals("existing data", Files.readString(occupied.resolve("keep")))
        assertTrue(Files.list(directory).use { paths -> paths.noneMatch { it.fileName.toString().endsWith(".tmp") } })
        assertEquals(BackupProblem.NO_SPACE, backupIoFailure(IOException("ENOSPC")).problem)
    }

    private suspend fun loadEnvelope() = assertIs<BackupSource.Encrypted>(BackupCodec.inspect(encryptedFile))

    private inline fun expectProblem(expected: BackupProblem, block: () -> Unit) {
        assertEquals(expected, assertFailsWith<BackupException>(block = block).problem)
    }

    private companion object {
        val password = "正确的备份口令 example".encodeToByteArray()
        val profile = VaultProfile(name = "演示配置", services = listOf(
            Service(id = "s1", name = "示例服务", items = listOf(
                ServiceItem(id = "i1", name = "默认", apiUrl = "https://example.com", keys = listOf(
                    ApiKey(id = "k1", value = "fixture-key-明文", note = "备注"),
                )),
            )),
        ))
        val encryptedFile by lazy { runBlocking { BackupCodec.encode(profile, password) } }
    }
}
