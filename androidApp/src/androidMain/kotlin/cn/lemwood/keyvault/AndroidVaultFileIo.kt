package cn.lemwood.keyvault

import android.content.Context
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.LifecycleOwner
import cn.lemwood.keyvault.platform.VaultFileIo
import kotlinx.coroutines.CompletableDeferred

/**
 * Android 端的文件读写：走系统文件选择器（SAF）。
 *
 * SAF 是异步回调式的，这里用 CompletableDeferred 把它桥接成 suspend 调用，
 * 好让 UI 层像同步一样等待结果。
 *
 * 必须在 Activity 的 onCreate（STARTED 之前）创建，否则 register 会抛异常。
 */
class AndroidVaultFileIo(
    private val context: Context,
    registry: ActivityResultRegistry,
    lifecycleOwner: LifecycleOwner
) : VaultFileIo {

    private var pendingContent: String = ""
    private var saveDeferred: CompletableDeferred<Boolean>? = null
    private var openDeferred: CompletableDeferred<String?>? = null

    private val createDocument = registry.register(
        "keyvault_export",
        lifecycleOwner,
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val deferred = saveDeferred ?: return@register
        saveDeferred = null
        if (uri == null) {
            deferred.complete(false)
        } else {
            deferred.complete(writeTo(uri, pendingContent))
        }
    }

    private val openDocument = registry.register(
        "keyvault_import",
        lifecycleOwner,
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val deferred = openDeferred ?: return@register
        openDeferred = null
        deferred.complete(if (uri == null) null else readFrom(uri))
    }

    override suspend fun saveFile(fileName: String, content: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        saveDeferred?.complete(false)
        saveDeferred = deferred
        pendingContent = content
        runCatching { createDocument.launch(fileName) }
            .onFailure {
                saveDeferred = null
                deferred.complete(false)
            }
        return deferred.await()
    }

    override suspend fun openFile(): String? {
        val deferred = CompletableDeferred<String?>()
        openDeferred?.complete(null)
        openDeferred = deferred
        // json 有时被识别成 text/plain，末尾放 */* 兜底，保证用户总能选到文件
        runCatching {
            openDocument.launch(arrayOf("application/json", "text/json", "text/plain", "*/*"))
        }.onFailure {
            openDeferred = null
            deferred.complete(null)
        }
        return deferred.await()
    }

    private fun writeTo(uri: Uri, content: String): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
        } != null
    }.getOrDefault(false)

    private fun readFrom(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
    }.getOrNull()
}
