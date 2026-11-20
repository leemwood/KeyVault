package cn.lemwood.keyvault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.OsConstants
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.LifecycleOwner
import cn.lemwood.keyvault.data.backup.BackupException
import cn.lemwood.keyvault.data.backup.BackupProblem
import cn.lemwood.keyvault.platform.VaultFileIo
import cn.lemwood.keyvault.platform.backupIoFailure
import cn.lemwood.keyvault.platform.readBackupBytes
import cn.lemwood.keyvault.platform.writeBackupBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext

/** Create before STARTED so SAF callbacks can be registered with the Activity. */
class AndroidVaultFileIo(
    context: Context,
    registry: ActivityResultRegistry,
    lifecycleOwner: LifecycleOwner,
) : VaultFileIo {
    private val resolver = context.applicationContext.contentResolver

    private class SaveRequest {
        val result = CompletableDeferred<Uri?>()
        var createdUri: Uri? = null
    }

    private var pendingSave: SaveRequest? = null
    private var pendingOpen: CompletableDeferred<Uri?>? = null

    private val createDocument = registry.register(
        "keyvault_export", lifecycleOwner, ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val request = pendingSave
        pendingSave = null
        request?.createdUri = uri
        if (request?.result?.complete(uri) != true && uri != null) {
            Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { deleteIncompleteDocument(uri) })
        }
    }

    private val openDocument = registry.register(
        "keyvault_import", lifecycleOwner, ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val result = pendingOpen
        pendingOpen = null
        result?.complete(uri)
    }

    override suspend fun saveFile(fileName: String, content: ByteArray, onWriting: () -> Unit): Boolean {
        if (pendingSave != null) throw BackupException(BackupProblem.FILE_IO)
        val request = SaveRequest()
        pendingSave = request
        try {
            createDocument.launch(fileName)
        } catch (_: RuntimeException) {
            pendingSave = null
            throw BackupException(BackupProblem.FILE_IO)
        }
        var written = false
        try {
            val uri = request.result.await() ?: return false
            onWriting()
            withContext(Dispatchers.IO) {
                documentIo {
                    val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException()
                    stream.use { it.writeBackupBytes(content) }
                }
            }
            written = true
            return true
        } finally {
            request.result.cancel()
            val uri = request.createdUri
            if (!written && uri != null) {
                withContext(NonCancellable + Dispatchers.IO) { deleteIncompleteDocument(uri) }
            }
        }
    }

    override suspend fun openFile(): ByteArray? {
        if (pendingOpen != null) throw BackupException(BackupProblem.FILE_IO)
        val result = CompletableDeferred<Uri?>()
        pendingOpen = result
        try {
            openDocument.launch(arrayOf("application/json", "text/json", "text/plain", "*/*"))
        } catch (_: RuntimeException) {
            pendingOpen = null
            throw BackupException(BackupProblem.FILE_IO)
        }
        val uri = try {
            result.await()
        } finally {
            result.cancel()
        } ?: return null
        return withContext(Dispatchers.IO) {
            documentIo {
                val stream = resolver.openInputStream(uri) ?: throw IOException()
                stream.use { it.readBackupBytes() }
            }
        }
    }

    private fun deleteIncompleteDocument(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }

    private inline fun <T> documentIo(block: () -> T): T = try {
        block()
    } catch (error: IOException) {
        val noSpace = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<ErrnoException>()
            .any { it.errno == OsConstants.ENOSPC || it.errno == OsConstants.EDQUOT }
        throw if (noSpace) BackupException(BackupProblem.NO_SPACE) else backupIoFailure(error)
    } catch (_: SecurityException) {
        throw BackupException(BackupProblem.FILE_IO)
    }
}
