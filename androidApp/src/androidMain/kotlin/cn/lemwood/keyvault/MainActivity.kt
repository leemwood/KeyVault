package cn.lemwood.keyvault

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import cn.lemwood.keyvault.ui.KeyVaultApp

/**
 * 注意：返回键能正常工作依赖 ComponentActivity 实现 NavigationEventDispatcherOwner
 * （androidx-activity >= 1.12.0）。这里把 Activity 自己作为返回事件源传给 KeyVaultApp，
 * 否则 Compose 里的 NavigationBackHandler 收不到系统返回事件，按返回键会直接退出应用。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FLAG_SECURE 按导航层级动态切换（见 KeyVaultApp），首页可截图，二/三级页面禁截
        enableEdgeToEdge()
        // 必须在 STARTED 之前注册 SAF 回调，所以放在 onCreate 里、setContent 之前
        val fileIo = AndroidVaultFileIo(this, activityResultRegistry, this)
        setContent {
            KeyVaultApp(
                onSecureScreenChange = { enabled -> setSecureScreen(enabled) },
                onSystemBarsAppearanceChange = { isDark -> setSystemBarsAppearance(isDark) },
                fileIo = fileIo,
                navigationEventDispatcherOwner = this@MainActivity
            )
        }
    }

    /**
     * 让状态栏/导航栏图标颜色跟随系统亮暗：深色模式下用浅色图标，否则深色图标。
     * 只改图标颜色，不改透明/沉浸式行为（enableEdgeToEdge 已处理）。
     */
    private fun setSystemBarsAppearance(isDark: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isDark
        controller.isAppearanceLightNavigationBars = !isDark
    }

    private fun setSecureScreen(enabled: Boolean) {
        if (enabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
