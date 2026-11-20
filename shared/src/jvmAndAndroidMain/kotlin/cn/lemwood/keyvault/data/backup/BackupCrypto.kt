package cn.lemwood.keyvault.data.backup

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal actual object BackupCrypto {
    private val random = SecureRandom()

    actual fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    actual fun checksum(header: ByteArray, ciphertext: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { update(header) }.digest(ciphertext)

    actual fun deriveKey(password: ByteArray, salt: ByteArray, params: BackupArgonParameters): ByteArray {
        val spec = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(params.memoryKiB)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)
            .build()
        return ByteArray(32).also { key ->
            val generator = Argon2BytesGenerator()
            generator.init(spec)
            generator.generateBytes(password, key)
        }
    }

    actual fun encrypt(key: ByteArray, nonce: ByteArray, header: ByteArray, plaintext: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce, header).doFinal(plaintext)

    actual fun decrypt(key: ByteArray, nonce: ByteArray, header: ByteArray, ciphertext: ByteArray): ByteArray = try {
        cipher(Cipher.DECRYPT_MODE, key, nonce, header).doFinal(ciphertext)
    } catch (_: AEADBadTagException) {
        throw BackupException(BackupProblem.WRONG_PASSWORD)
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, header: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(header)
        }
}
