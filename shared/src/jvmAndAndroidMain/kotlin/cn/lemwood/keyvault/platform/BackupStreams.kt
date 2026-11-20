package cn.lemwood.keyvault.platform

import cn.lemwood.keyvault.data.backup.BackupException
import cn.lemwood.keyvault.data.backup.BackupProblem
import cn.lemwood.keyvault.data.backup.MAX_BACKUP_FILE_BYTES
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

suspend fun InputStream.readBackupBytes(): ByteArray {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val output = ByteArrayOutputStream()
    val context = currentCoroutineContext()
    while (true) {
        context.ensureActive()
        val remaining = MAX_BACKUP_FILE_BYTES - output.size()
        val count = read(buffer, 0, minOf(buffer.size, remaining + 1))
        if (count < 0) return output.toByteArray()
        if (count > remaining) throw BackupException(BackupProblem.TOO_LARGE)
        output.write(buffer, 0, count)
    }
}

suspend fun OutputStream.writeBackupBytes(content: ByteArray) {
    val context = currentCoroutineContext()
    for (offset in content.indices step DEFAULT_BUFFER_SIZE) {
        context.ensureActive()
        write(content, offset, minOf(DEFAULT_BUFFER_SIZE, content.size - offset))
    }
    context.ensureActive()
}

fun backupIoFailure(error: IOException): BackupException {
    val noSpace = generateSequence<Throwable>(error) { it.cause }.any { cause ->
        val message = cause.message.orEmpty()
        listOf("ENOSPC", "EDQUOT", "no space left", "not enough space", "disk full", "空间不足").any {
            message.contains(it, ignoreCase = true)
        }
    }
    return BackupException(if (noSpace) BackupProblem.NO_SPACE else BackupProblem.FILE_IO)
}
