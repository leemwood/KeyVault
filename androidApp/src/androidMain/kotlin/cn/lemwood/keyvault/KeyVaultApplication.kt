package cn.lemwood.keyvault

import android.app.Application
import cn.lemwood.keyvault.platform.VaultPlatformContext

class KeyVaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // DataStore 与版本读取需要 Application 上下文
        VaultPlatformContext.init(this)
    }
}
