package cn.lemwood.keyvault.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.lemwood.keyvault.sync.LanPairing
import cn.lemwood.keyvault.sync.PairingApproval
import cn.lemwood.keyvault.sync.PairingDevice
import cn.lemwood.keyvault.sync.PairingException
import cn.lemwood.keyvault.sync.PairingInvitation
import cn.lemwood.keyvault.sync.PairingProblem
import cn.lemwood.keyvault.sync.PairingQr
import cn.lemwood.keyvault.sync.TrustedDevice
import cn.lemwood.keyvault.sync.TransferStore
import cn.lemwood.keyvault.sync.IncrementalStore
import cn.lemwood.keyvault.sync.ReplicaConflict
import cn.lemwood.keyvault.sync.ResolvedReplicaConflict
import cn.lemwood.keyvault.sync.SyncWindowControl
import cn.lemwood.keyvault.sync.SyncWindowState
import cn.lemwood.keyvault.data.model.VaultProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.time.Clock

@OptIn(kotlin.time.ExperimentalTime::class)
@Composable
internal fun PairingScreen(
    pairing: LanPairing,
    insets: PaddingValues,
    profiles: List<VaultProfile>,
    activeProfileId: String,
    store: TransferStore,
    incrementalStore: IncrementalStore,
    replicaConflicts: Map<String, List<ReplicaConflict>>,
    conflictHistory: Map<String, List<ResolvedReplicaConflict>>,
    clearConflictHistory: suspend (String) -> Unit,
    syncWindow: SyncWindowControl?,
    resolveConflict: suspend (String, ReplicaConflict, Boolean) -> Unit,
    showTransfer: Boolean,
    onShowTransfer: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var device by remember { mutableStateOf<PairingDevice?>(null) }
    var invitation by remember { mutableStateOf<PairingInvitation?>(null) }
    var approval by remember { mutableStateOf<PairingApproval?>(null) }
    var trusted by remember { mutableStateOf(emptyList<TrustedDevice>()) }
    var found by remember { mutableStateOf(emptyList<String>()) }
    var working by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var inviteAddresses by remember { mutableStateOf(emptyList<String>()) }
    var revoking by remember { mutableStateOf<TrustedDevice?>(null) }
    val windowState = syncWindow?.state?.collectAsState()?.value

    fun runOperation(status: String, block: suspend () -> Unit) {
        if (working != null) return
        message = null
        working = status
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PairingException) {
                message = error.problem.message()
            } catch (_: Exception) {
                message = PairingProblem.UNREACHABLE.message()
            } finally {
                working = null
            }
        }
    }

    fun unlock() {
        val phrase = password.toCharArray()
        password = ""
        runOperation("正在打开设备身份…") {
            try {
                val open = pairing.unlock(phrase)
                trusted = open.trustedDevices()
                device = open
            } finally {
                phrase.fill('\u0000')
            }
        }
    }

    LaunchedEffect(pairing) { if (!pairing.requiresPassword) unlock() }
    val openDevice = device
    val activeInvitation = invitation
    val pending = approval
    LaunchedEffect(openDevice, activeInvitation, pending, showTransfer) {
        found = emptyList()
        if (activeInvitation == null && pending == null && !showTransfer) {
            openDevice?.discover()?.catch { emit(emptyList()) }?.collect { found = it }
        }
    }

    if (showTransfer && openDevice != null) {
        TransferScreen(pairing, openDevice, trusted, profiles, activeProfileId, store,
            incrementalStore, replicaConflicts, resolveConflict, conflictHistory, clearConflictHistory, insets) {
            onShowTransfer(false)
        }
        return
    }
    DisposableEffect(activeInvitation) { onDispose { activeInvitation?.close() } }
    DisposableEffect(pending) { onDispose { pending?.close() } }

    LaunchedEffect(pending) {
        if (pending != null) {
            delay((pending.expiresAt - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0))
            approval = null
            message = PairingProblem.EXPIRED.message()
        }
    }

    LaunchedEffect(activeInvitation) {
        if (activeInvitation != null) {
            try {
                approval = activeInvitation.awaitPeer()
                invitation = null
                message = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val problem = (failure as? PairingException)?.problem ?: PairingProblem.UNREACHABLE
                invitation = null
                message = problem.message()
            }
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(title = "局域网配对", navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
            })
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = insets.calculateBottomPadding())
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            message?.let { Text(it) }
            working?.let { Text(it) }
            when {
                openDevice == null -> {
                    if (pairing.requiresPassword) {
                        Text("首次使用时设置设备身份口令，以后用该口令解锁。")
                        TextField(
                            value = password,
                            onValueChange = { if (it.length <= 256) password = it },
                            label = "设备身份口令",
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        PairingButton("解锁", enabled = working == null && password.isNotBlank(), onClick = ::unlock)
                    } else if (working == null) {
                        PairingButton("重试", onClick = ::unlock)
                    }
                }
                pending != null -> {
                    Text(pending.peer.name, style = MiuixTheme.textStyles.title3)
                    Text("请核对两台设备上的校验码，完全一致后在两端确认。")
                    Text(pending.comparisonCode, style = MiuixTheme.textStyles.title2.copy(fontFamily = FontFamily.Monospace))
                    PairingButton("校验码一致，确认配对", enabled = working == null) {
                        runOperation("正在等待另一台设备确认…") {
                            try {
                                pending.confirm()
                                trusted = openDevice.trustedDevices()
                                message = "已与「${pending.peer.name}」配对"
                            } finally {
                                approval = null
                            }
                        }
                    }
                    TextButton("取消配对", onClick = { approval = null }, modifier = Modifier.fillMaxWidth())
                }
                activeInvitation != null -> {
                    val qr = remember(activeInvitation) { openDevice.qr(activeInvitation.code) }
                    var seconds by remember(activeInvitation) { mutableStateOf(120L) }
                    LaunchedEffect(activeInvitation) {
                        while (seconds > 0) {
                            seconds = ((activeInvitation.expiresAt - Clock.System.now().toEpochMilliseconds() + 999) / 1_000)
                                .coerceIn(0, 120)
                            delay(500)
                        }
                    }
                    Text("在另一台设备扫描二维码。剩余 $seconds 秒。")
                    QrImage(qr, Modifier.align(Alignment.CenterHorizontally))
                    TextButton("复制配对码", onClick = {
                        message = if (runCatching { pairing.copyInvitation(activeInvitation.code) }.isSuccess) {
                            "已复制配对码"
                        } else "无法使用剪贴板，可直接扫描二维码"
                    }, modifier = Modifier.fillMaxWidth())
                    TextButton("取消配对", onClick = { invitation = null }, modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    if (trusted.isNotEmpty() || replicaConflicts.values.any { it.isNotEmpty() } ||
                        conflictHistory.values.any { it.isNotEmpty() }) {
                        val label = if (trusted.isNotEmpty()) "传送配置" else "同步冲突与历史"
                        PairingButton(label, enabled = working == null) { onShowTransfer(true) }
                    }
                    if (syncWindow != null && (trusted.isNotEmpty() || windowState == SyncWindowState.RUNNING)) {
                        when (windowState) {
                            SyncWindowState.RUNNING, SyncWindowState.STARTING ->
                                TextButton("停止后台同步", onClick = syncWindow::stop, modifier = Modifier.fillMaxWidth())
                            else -> PairingButton("开启后台同步", enabled = working == null) {
                                syncWindow.start(openDevice, incrementalStore)
                            }
                        }
                        when (windowState) {
                            SyncWindowState.RUNNING -> Text("同步窗口已开启，已绑定的副本会定时同步。")
                            SyncWindowState.STARTING -> Text("正在开启同步窗口…")
                            SyncWindowState.UNAVAILABLE -> Text("无法开启同步窗口，请检查局域网连接或端口占用。")
                            else -> Unit
                        }
                    }
                    PairingButton("显示配对二维码", enabled = working == null) {
                        runOperation("正在查找本机局域网地址…") {
                            val addresses = openDevice.addresses()
                            when (addresses.size) {
                                0 -> throw PairingException(PairingProblem.NOT_SAME_SUBNET)
                                1 -> invitation = openDevice.invite(addresses.single())
                                else -> inviteAddresses = addresses
                            }
                        }
                    }
                    PairingButton(pairing.scanLabel, enabled = working == null) {
                        runOperation("正在读取配对二维码…") {
                            pairing.scan("扫描另一台设备显示的 KeyVault 配对码")?.let { approval = openDevice.join(it) }
                        }
                    }
                    TextButton("粘贴配对码", onClick = {
                        runOperation("正在连接…") {
                            val code = clipboard.getText()?.text ?: throw PairingException(PairingProblem.INVALID_CODE)
                            approval = openDevice.join(code)
                        }
                    }, enabled = working == null, modifier = Modifier.fillMaxWidth())
                    Text("附近设备", style = MiuixTheme.textStyles.title3)
                    if (found.isEmpty()) Text("未发现可配对的设备，可扫描二维码直连。")
                    found.forEach { Text(it) }
                    Text("已配对设备", style = MiuixTheme.textStyles.title3)
                    if (trusted.isEmpty()) Text("还没有已配对设备")
                    trusted.forEach { paired ->
                        TextButton("${paired.name} · ${paired.publicKey.takeLast(6)}", onClick = { revoking = paired },
                            modifier = Modifier.fillMaxWidth(), enabled = working == null)
                    }
                }
            }
        }
    }

    if (inviteAddresses.isNotEmpty() && openDevice != null) {
        SuperDialog(show = true, title = "选择本机局域网地址", onDismissRequest = { inviteAddresses = emptyList() }) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                inviteAddresses.forEach { address ->
                    TextButton(address, onClick = {
                        inviteAddresses = emptyList()
                        runOperation("正在生成配对码…") { invitation = openDevice.invite(address) }
                    }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    revoking?.let { peer ->
        if (openDevice != null) {
            SuperDialog(show = true, title = "撤销设备信任",
                summary = "「${peer.name}」正在进行的连接会中止，下次连接需要重新配对。",
                onDismissRequest = { revoking = null }) {
                Column {
                    PairingButton("撤销信任") {
                        revoking = null
                        runOperation("正在撤销信任…") {
                            openDevice.revokeDevice(peer.publicKey)
                            trusted = openDevice.trustedDevices()
                            message = "已撤销「${peer.name}」的信任"
                        }
                    }
                    TextButton("取消", onClick = { revoking = null }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
internal fun PairingButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(text, onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.textButtonColorsPrimary())
}

@Composable
internal fun QrImage(qr: PairingQr, modifier: Modifier, description: String = "配对二维码") {
    Canvas(modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f).semantics {
        contentDescription = description
    }) {
        drawRect(Color.White)
        val cell = (size.minDimension / qr.size).toInt().coerceAtLeast(1).toFloat()
        val offset = ((size.minDimension - cell * qr.size) / 2).toInt().toFloat()
        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                if (qr.modules[y * qr.size + x]) {
                    drawRect(Color.Black, Offset(offset + x * cell, offset + y * cell), Size(cell, cell))
                }
            }
        }
    }
}

private fun PairingProblem.message() = when (this) {
    PairingProblem.INVALID_CODE -> "无法读取 KeyVault 配对码"
    PairingProblem.EXPIRED -> "配对码已过期，请重新生成"
    PairingProblem.NOT_SAME_SUBNET -> "请将两台设备连接到同一局域网的 IPv4 网段"
    PairingProblem.UNREACHABLE -> "无法连接。请检查两台设备是否在同一局域网、是否允许设备互访"
    PairingProblem.REJECTED -> "配对已被拒绝，或证书校验失败"
    PairingProblem.IDENTITY_CHANGED -> "设备身份或证书已变化，无法建立信任"
    PairingProblem.STORAGE -> "无法打开或保存设备身份。桌面端请检查设备身份口令"
}
