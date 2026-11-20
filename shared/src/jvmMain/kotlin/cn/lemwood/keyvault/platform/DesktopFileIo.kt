package cn.lemwood.keyvault.platform

import cn.lemwood.keyvault.data.backup.BackupException
import cn.lemwood.keyvault.data.backup.BackupProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class DesktopFileIo : VaultFileIo {
    // Compose Desktop supplies the AWT event thread for the native pickers, including on macOS.
    override suspend fun saveFile(fileName: String, content: ByteArray, onWriting: () -> Unit): Boolean {
        val dialog = FileDialog(null as Frame?, "导出配置", FileDialog.SAVE).apply {
            file = withJsonSuffix(fileName)
        }
        val target = dialog.selectPath(addSuffix = true) ?: return false
        currentCoroutineContext().ensureActive()
        onWriting()
        withContext(Dispatchers.IO) { writeBackupAtomically(target, content) }
        return true
    }

    override suspend fun openFile(): ByteArray? {
        val dialog = FileDialog(null as Frame?, "导入配置", FileDialog.LOAD).apply {
            filenameFilter = { dir, name ->
                name.endsWith(".json", ignoreCase = true) || File(dir, name).isDirectory
            }
        }
        val path = dialog.selectPath() ?: return null
        return withContext(Dispatchers.IO) {
            try {
                Files.newInputStream(path).use { it.readBackupBytes() }
            } catch (error: IOException) {
                throw backupIoFailure(error)
            } catch (_: SecurityException) {
                throw BackupException(BackupProblem.FILE_IO)
            }
        }
    }

    private fun FileDialog.selectPath(addSuffix: Boolean = false): Path? = try {
        isVisible = true
        val name = file
        val folder = directory
        if (name == null || folder == null) null
        else File(folder, if (addSuffix) withJsonSuffix(name) else name).toPath()
    } finally {
        dispose()
    }

    private fun withJsonSuffix(name: String): String =
        if (name.endsWith(".json", ignoreCase = true)) name else "$name.json"
}

internal suspend fun writeBackupAtomically(target: Path, content: ByteArray) {
    val directory = target.toAbsolutePath().parent
    try {
        if (Files.getFileStore(directory).usableSpace < content.size) {
            throw BackupException(BackupProblem.NO_SPACE)
        }
        val temporary = Files.createTempFile(directory, ".keyvault-backup-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.writeBackupBytes(content)
                output.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    } catch (error: IOException) {
        throw backupIoFailure(error)
    } catch (_: SecurityException) {
        throw BackupException(BackupProblem.FILE_IO)
    }
}
