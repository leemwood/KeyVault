package cn.lemwood.keyvault.platform

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * 桌面端的文件读写：直接用 AWT 原生对话框。
 *
 * 这里刻意不切到 IO 线程 —— macOS 的 AWT 组件必须在 AWT 事件线程（也就是 Compose 的主线程）上创建，
 * 跨线程弹窗在 macOS 上会卡死或抛异常；模态对话框自己会泵事件循环，阻塞主线程是安全的。
 */
class DesktopFileIo : VaultFileIo {

    override suspend fun saveFile(fileName: String, content: String): Boolean {
        val dialog = FileDialog(
            null as Frame?,
            "导出配置",
            FileDialog.SAVE
        ).apply {
            file = withJsonSuffix(fileName)
            isVisible = true
        }
        val directory = dialog.directory ?: return false
        val file = dialog.file ?: return false
        val target = File(directory, withJsonSuffix(file))
        return runCatching {
            target.writeText(content, Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    override suspend fun openFile(): String? {
        val dialog = FileDialog(
            null as Frame?,
            "导入配置",
            FileDialog.LOAD
        ).apply {
            // 只显示 json，但不限制用户切到「所有文件」
            filenameFilter = { dir, name ->
                name.endsWith(".json", ignoreCase = true) || File(dir, name).isDirectory
            }
            isVisible = true
        }
        val directory = dialog.directory ?: return null
        val file = dialog.file ?: return null
        return runCatching {
            File(directory, file).readText(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun withJsonSuffix(name: String): String =
        if (name.endsWith(".json", ignoreCase = true)) name else "$name.json"
}
