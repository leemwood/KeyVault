package cn.lemwood.keyvault.platform

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File

/** Android 侧持有 Application 上下文，由 cn.lemwood.keyvault.KeyVaultApplication 初始化。 */
object VaultPlatformContext {

    @Volatile
    private var applicationContext: Context? = null

    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    fun requireContext(): Context = applicationContext
        ?: error("KeyVault: 上下文未初始化，请确认 Application.onCreate 中调用了 VaultPlatformContext.init()")
}

private val lock = Any()

@Volatile
private var cachedDataStore: DataStore<Preferences>? = null

actual fun createVaultDataStore(): DataStore<Preferences> = cachedDataStore ?: synchronized(lock) {
    cachedDataStore ?: PreferenceDataStoreFactory.create(
        produceFile = {
            // 与 preferencesDataStore(name = "vault") 生成的路径完全一致，保证旧数据可读
            val datastoreDir = File(VaultPlatformContext.requireContext().filesDir, "datastore")
            datastoreDir.mkdirs()
            File(datastoreDir, "vault.preferences_pb")
        }
    ).also { cachedDataStore = it }
}

actual fun platformAppVersion(): String = runCatching {
    val context = VaultPlatformContext.requireContext()
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "unknown"
