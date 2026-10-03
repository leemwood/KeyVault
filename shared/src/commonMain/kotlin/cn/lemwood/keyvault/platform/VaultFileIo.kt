package cn.lemwood.keyvault.platform

/**
 * 备份文件的读写通道。
 *
 * 各平台打开文件选择器的方式完全不同：Android 必须走系统文件选择器（SAF，异步回调），
 * 桌面端则直接弹原生对话框。因此这里只抽象成 suspend 接口，由平台各自实现，
 * UI 层只关心「把这段字符串存成文件」和「让用户挑一个文件读进来」。
 */
interface VaultFileIo {

    /**
     * 让用户选择保存位置并写入内容。
     *
     * @param fileName 建议的文件名，平台可能改写（Android 上用户可自行改名）
     * @return 是否成功写入；用户取消或写入失败返回 false
     */
    suspend fun saveFile(fileName: String, content: String): Boolean

    /**
     * 让用户挑一个文件读进来。
     *
     * @return 文件内容；用户取消、读不到或不是文本文件返回 null
     */
    suspend fun openFile(): String?
}

/** 平台没有注入实现时用的空实现，避免 UI 层到处判空。 */
object NoOpVaultFileIo : VaultFileIo {
    override suspend fun saveFile(fileName: String, content: String): Boolean = false
    override suspend fun openFile(): String? = null
}
