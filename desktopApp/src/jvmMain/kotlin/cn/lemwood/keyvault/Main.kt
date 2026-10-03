package cn.lemwood.keyvault

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cn.lemwood.keyvault.platform.DesktopFileIo
import cn.lemwood.keyvault.ui.KeyVaultApp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "KeyVault"
    ) {
        KeyVaultApp(fileIo = remember { DesktopFileIo() })
    }
}
