package cn.lemwood.keyvault.platform

/** Platforms select a backup file and perform bounded reads or staged writes off the UI thread. */
interface VaultFileIo {
    /** Returns false if the user closes the picker. File failures throw BackupException. */
    suspend fun saveFile(fileName: String, content: ByteArray, onWriting: () -> Unit): Boolean

    /** Returns null if the user closes the picker. File failures throw BackupException. */
    suspend fun openFile(): ByteArray?
}

object NoOpVaultFileIo : VaultFileIo {
    override suspend fun saveFile(fileName: String, content: ByteArray, onWriting: () -> Unit) = false
    override suspend fun openFile(): ByteArray? = null
}
