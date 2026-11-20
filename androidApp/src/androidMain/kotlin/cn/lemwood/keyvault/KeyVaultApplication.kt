package cn.lemwood.keyvault

import android.app.Application
import cn.lemwood.keyvault.platform.VaultPlatformContext
import cn.lemwood.keyvault.ui.VaultViewModel

class KeyVaultApplication : Application() {
    val vault by lazy { VaultViewModel() }
    val syncWindow by lazy { AndroidSyncWindow(this) }

    override fun onCreate() {
        super.onCreate()
        // DataStore 与版本读取需要 Application 上下文
        VaultPlatformContext.init(this)
    }
}
