package cn.lemwood.keyvault

import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cn.lemwood.keyvault.platform.DesktopFileIo
import cn.lemwood.keyvault.sync.DesktopLanPairing
import cn.lemwood.keyvault.sync.IncrementalStore
import cn.lemwood.keyvault.sync.PairingDevice
import cn.lemwood.keyvault.sync.SyncWindowControl
import cn.lemwood.keyvault.sync.SyncWindowState
import cn.lemwood.keyvault.ui.KeyVaultApp
import cn.lemwood.keyvault.ui.VaultViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.awt.SystemTray

fun main() = application {
    val scope = rememberCoroutineScope()
    val vault = remember { VaultViewModel() }
    val syncWindow = remember { DesktopSyncWindow(scope) }
    val syncStatus by syncWindow.state.collectAsState()
    val canHide = remember { SystemTray.isSupported() }
    val windowsTray = remember { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
    var visible by remember { mutableStateOf(true) }
    val active = syncStatus == SyncWindowState.STARTING || syncStatus == SyncWindowState.RUNNING

    if (canHide && (active || !visible)) {
        Tray(icon = KeyVaultTrayIcon, tooltip = "KeyVault", onAction = { visible = true }, menu = {
            // The native AWT tray menu on Windows may display missing glyphs for Chinese text.
            Item(if (windowsTray) "Open KeyVault" else "打开 KeyVault", onClick = { visible = true })
            Item(if (windowsTray) "Stop sync" else "停止后台同步", enabled = active, onClick = syncWindow::stop)
            Item(if (windowsTray) "Quit KeyVault" else "退出 KeyVault", onClick = ::exitApplication)
        })
    }
    Window(
        onCloseRequest = { if (canHide && active) visible = false else exitApplication() },
        title = "KeyVault",
        visible = visible,
    ) {
        KeyVaultApp(fileIo = remember { DesktopFileIo() }, pairing = remember { DesktopLanPairing() },
            syncWindow = syncWindow, vaultViewModel = vault)
    }
}

private class DesktopSyncWindow(private val scope: CoroutineScope) : SyncWindowControl {
    private val status = MutableStateFlow(SyncWindowState.STOPPED)
    override val state = status.asStateFlow()
    private var session: Job? = null

    override fun start(device: PairingDevice, store: IncrementalStore) {
        if (session?.isCompleted == false) return
        status.value = SyncWindowState.STARTING
        session = scope.launch {
            try {
                device.runSyncWindow(store) { status.value = SyncWindowState.RUNNING }
                status.value = SyncWindowState.STOPPED
            } catch (cancelled: CancellationException) {
                status.value = SyncWindowState.STOPPED
                throw cancelled
            } catch (_: Exception) {
                status.value = SyncWindowState.UNAVAILABLE
            }
        }
    }

    override fun stop() {
        session?.cancel()
        status.value = SyncWindowState.STOPPED
    }
}

private object KeyVaultTrayIcon : Painter() {
    override val intrinsicSize = Size(24f, 24f)

    override fun DrawScope.onDraw() {
        val unit = size.minDimension / 24f
        drawCircle(Color(0xFF0780FF), 11f * unit)
        drawCircle(Color.White, 4f * unit, Offset(8f * unit, 10f * unit))
        drawCircle(Color(0xFF0780FF), 1.6f * unit, Offset(8f * unit, 10f * unit))
        drawLine(Color.White, Offset(10f * unit, 12f * unit), Offset(17f * unit, 17f * unit), 2.5f * unit)
        drawLine(Color.White, Offset(14.5f * unit, 15.3f * unit), Offset(17f * unit, 12.5f * unit), 2f * unit)
    }
}
