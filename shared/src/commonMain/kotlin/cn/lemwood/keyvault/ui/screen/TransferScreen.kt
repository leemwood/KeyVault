package cn.lemwood.keyvault.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import cn.lemwood.keyvault.data.model.VaultProfile
import cn.lemwood.keyvault.sync.LanPairing
import cn.lemwood.keyvault.sync.IncrementalEvent
import cn.lemwood.keyvault.sync.IncrementalReceiver
import cn.lemwood.keyvault.sync.IncrementalStore
import cn.lemwood.keyvault.sync.PairingDevice
import cn.lemwood.keyvault.sync.ReplicaConflict
import cn.lemwood.keyvault.sync.ReplicaValue
import cn.lemwood.keyvault.sync.ResolvedReplicaConflict
import cn.lemwood.keyvault.sync.TransferEvent
import cn.lemwood.keyvault.sync.TransferException
import cn.lemwood.keyvault.sync.TransferProblem
import cn.lemwood.keyvault.sync.TransferProgress
import cn.lemwood.keyvault.sync.TransferReceiver
import cn.lemwood.keyvault.sync.TransferSender
import cn.lemwood.keyvault.sync.TransferStore
import cn.lemwood.keyvault.sync.TrustedDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperDialog
import kotlin.time.Instant

private enum class TransferMode { SEND, RECEIVE, SYNC, WAIT_SYNC, CONFLICTS }

@Composable
internal fun TransferScreen(
    pairing: LanPairing,
    device: PairingDevice,
    peers: List<TrustedDevice>,
    profiles: List<VaultProfile>,
    activeProfileId: String,
    store: TransferStore,
    incrementalStore: IncrementalStore,
    replicaConflicts: Map<String, List<ReplicaConflict>>,
    resolveConflict: suspend (String, ReplicaConflict, Boolean) -> Unit,
    history: Map<String, List<ResolvedReplicaConflict>>,
    clearHistory: suspend (String) -> Unit,
    insets: PaddingValues,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var mode by remember { mutableStateOf<TransferMode?>(null) }
    var peer by remember { mutableStateOf(peers.singleOrNull()) }
    var profileId by remember { mutableStateOf(activeProfileId) }
    val profile = profiles.find { it.id == profileId }
    var choosingPeer by remember { mutableStateOf(false) }
    var choosingProfile by remember { mutableStateOf(false) }
    var addresses by remember { mutableStateOf(emptyList<String>()) }
    var sending by remember { mutableStateOf<TransferSender?>(null) }
    var listening by remember { mutableStateOf<TransferReceiver?>(null) }
    var syncing by remember { mutableStateOf<IncrementalReceiver?>(null) }
    var destination by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<TransferProgress?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var working by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var confirmingClear by remember { mutableStateOf(false) }

    fun runTransfer(action: suspend () -> Unit) {
        if (working) return
        working = true
        message = null
        operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: TransferException) {
                message = failure.problem.message()
            } catch (_: Exception) {
                message = "无法读取传送码或连接设备"
            } finally {
                working = false
            }
        }
    }

    suspend fun send(code: String, to: TrustedDevice, selected: VaultProfile) {
        destination = code
        if (mode == TransferMode.SYNC) {
            device.syncIncremental(code, to, selected.id, incrementalStore) { event ->
                withContext(Dispatchers.Main.immediate) {
                    when (event) {
                        is IncrementalEvent.Progress -> { progress = event.progress; message = null }
                        is IncrementalEvent.Saved -> { finished = true; message = event.message() }
                        is IncrementalEvent.Interrupted -> message = "连接中断，正在重连"
                    }
                }
            }
            return
        }
        val transfer = sending ?: device.prepareTransfer(selected).also { sending = it }
        transfer.send(code, to) { status -> withContext(Dispatchers.Main.immediate) { progress = status } }
        finished = true
        message = "对方已保存配置"
    }

    val receiver = listening
    DisposableEffect(receiver) { onDispose { receiver?.close() } }
    val sender = sending
    val syncReceiver = syncing
    val receiveCode = receiver?.code ?: syncReceiver?.code
    val receiveAddress = receiver?.address ?: syncReceiver?.address
    DisposableEffect(sender) { onDispose { sender?.close() } }
    DisposableEffect(syncReceiver) { onDispose { syncReceiver?.close() } }
    LaunchedEffect(receiver) {
        if (receiver == null) return@LaunchedEffect
        try {
            receiver.receive(store) { event ->
                withContext(Dispatchers.Main.immediate) {
                    when (event) {
                        is TransferEvent.Progress -> {
                            progress = event.progress
                            message = null
                        }
                        TransferEvent.Saved -> {
                            finished = true
                            message = "已接收并保存为新配置"
                        }
                        is TransferEvent.Interrupted -> if (!finished) {
                            message = if (event.problem == TransferProblem.CONNECTION) {
                                "连接中断，等待发送端重连"
                            } else event.problem.message()
                        }
                    }
                }
            }
        } catch (failure: TransferException) {
            message = failure.problem.message()
            listening = null
        }
    }

    LaunchedEffect(syncReceiver) {
        if (syncReceiver == null) return@LaunchedEffect
        try {
            syncReceiver.run(incrementalStore) { event ->
                withContext(Dispatchers.Main.immediate) {
                    when (event) {
                        is IncrementalEvent.Progress -> { progress = event.progress; message = null }
                        is IncrementalEvent.Saved -> { finished = true; message = event.message() }
                        is IncrementalEvent.Interrupted -> if (!finished) {
                            message = if (event.problem == TransferProblem.CONNECTION) "连接中断，等待对方重连" else event.problem.message()
                        }
                    }
                }
            }
        } catch (failure: TransferException) {
            message = failure.problem.message()
            syncing = null
        }
    }

    suspend fun startListening(address: String, remote: TrustedDevice) {
        if (mode == TransferMode.WAIT_SYNC) {
            syncing = device.listenForIncremental(address, remote, profileId)
        } else listening = device.listenForTransfer(address, remote)
    }

    Scaffold(topBar = {
        SmallTopAppBar(title = "传送配置", navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
        })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = insets.calculateBottomPadding())
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            message?.let { Text(it) }
            progress?.let { value ->
                val status = if (value.reconnecting && working) "正在重连" else "已确认"
                Text("$status · ${value.completed} / ${value.total} 块")
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), progress = value.completed.toFloat() / value.total)
            }
            when {
                receiveCode != null -> {
                    val qr = remember(receiveCode) { device.qr(receiveCode) }
                    Text("${peer?.name.orEmpty()} · $receiveAddress")
                    if (!finished) {
                        val label = if (syncReceiver != null) "同步码" else "接收码"
                        Text("请让对方扫码。断线时保持此页面打开。")
                        QrImage(qr, Modifier.align(Alignment.CenterHorizontally), label)
                        TextButton("复制$label", onClick = {
                            message = if (runCatching { pairing.copyInvitation(receiveCode) }.isSuccess) {
                                "已复制$label"
                            } else "无法使用剪贴板，可直接扫描二维码"
                        }, modifier = Modifier.fillMaxWidth())
                    }
                    PairingButton(if (finished) "完成" else "停止接收") {
                        receiver?.close()
                        syncReceiver?.close()
                        listening = null
                        syncing = null
                        progress = null
                        if (finished) onBack() else message = null
                    }
                }
                finished -> PairingButton("完成", onClick = onBack)
                mode == null -> {
                    if (peers.isNotEmpty()) {
                        PairingButton("发送配置") { mode = TransferMode.SEND }
                        PairingButton("接收配置") { mode = TransferMode.RECEIVE }
                        PairingButton("发起增量同步") { mode = TransferMode.SYNC }
                        PairingButton("等待增量同步") { mode = TransferMode.WAIT_SYNC }
                    }
                    if (replicaConflicts.values.any { it.isNotEmpty() } || history.values.any { it.isNotEmpty() }) {
                        PairingButton("同步冲突与历史") { mode = TransferMode.CONFLICTS }
                    }
                }
                mode == TransferMode.CONFLICTS -> {
                    TextButton(profile?.name ?: "选择配置", onClick = { choosingProfile = true }, modifier = Modifier.fillMaxWidth())
                    if (replicaConflicts[profileId].isNullOrEmpty()) Text("此配置没有待处理的同步冲突")
                }
                else -> {
                    TextButton(
                        text = peer?.let { "对端设备 · ${it.name}" } ?: "选择已配对设备",
                        enabled = !working && sender == null,
                        onClick = { choosingPeer = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val selectedPeer = peer
                    if (mode != TransferMode.RECEIVE) {
                        TextButton(
                            text = profile?.let { "本机配置 · ${it.name}" } ?: "选择本机配置",
                            enabled = !working && sender == null,
                            onClick = { choosingProfile = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (mode == TransferMode.SYNC || mode == TransferMode.WAIT_SYNC) {
                        Text("两端请选择同一配置的副本。首次可用完整传送建立副本。")
                    }
                    if (mode == TransferMode.SEND || mode == TransferMode.SYNC) {
                        val incremental = mode == TransferMode.SYNC
                        val label = if (incremental) "同步码" else "接收码"
                        val canSend = !working && selectedPeer != null && profile != null
                        PairingButton(pairing.scanLabel, enabled = canSend) {
                            if (selectedPeer != null && profile != null) runTransfer {
                                pairing.scan("扫描另一台设备显示的 KeyVault $label")?.let { send(it, selectedPeer, profile) }
                            }
                        }
                        PairingButton("粘贴$label${if (incremental) "并同步" else "并发送"}", enabled = canSend) {
                            if (selectedPeer != null && profile != null) runTransfer {
                                val code = clipboard.getText()?.text ?: throw TransferException(TransferProblem.INVALID_CODE)
                                send(code, selectedPeer, profile)
                            }
                        }
                        val previous = destination
                        if (previous != null && !incremental) {
                            PairingButton("重试传送", enabled = canSend) {
                                if (selectedPeer != null && profile != null) runTransfer { send(previous, selectedPeer, profile) }
                            }
                        }
                        if (working) {
                            TextButton("取消", onClick = { operation?.cancel() }, modifier = Modifier.fillMaxWidth())
                        } else if (sender != null) {
                            TextButton("放弃这次传送", onClick = {
                                sending = null
                                destination = null
                                progress = null
                                message = null
                            }, modifier = Modifier.fillMaxWidth())
                        }
                    } else {
                        val waiting = mode == TransferMode.WAIT_SYNC
                        PairingButton(if (waiting) "开始等待同步" else "开始接收",
                            enabled = !working && selectedPeer != null && (!waiting || profile != null)) {
                            if (selectedPeer != null) runTransfer {
                                val networks = device.addresses()
                                when (networks.size) {
                                    0 -> throw TransferException(TransferProblem.OTHER_SUBNET)
                                    1 -> startListening(networks.single(), selectedPeer)
                                    else -> addresses = networks
                                }
                            }
                        }
                    }
                }
            }
            if (mode == TransferMode.CONFLICTS || finished && (mode == TransferMode.SYNC || mode == TransferMode.WAIT_SYNC)) {
                replicaConflicts[profileId].orEmpty().forEach { conflict ->
                    SyncConflictCard(profile, conflict, enabled = !working) { useRemote ->
                        runTransfer {
                            resolveConflict(profileId, conflict, useRemote)
                            message = "已保存选择，下次同步时会传给对端"
                        }
                    }
                }
                val events = history[profileId].orEmpty()
                if (events.isNotEmpty()) {
                    Text("最近处理的冲突")
                    events.asReversed().forEach { event ->
                        val label = when (event.event) {
                            "local" -> "保留本机"
                            "remote" -> "采用对端"
                            else -> "已随同步解决"
                        }
                        val day = Instant.fromEpochMilliseconds(event.resolvedAt).toString().take(10)
                        val item = event.path.key ?: event.path.item ?: event.path.service
                        Text("$day · $label · ${item.takeLast(8)}")
                    }
                    TextButton("清除冲突历史", onClick = { confirmingClear = true }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (choosingPeer) TransferChoices("选择已配对设备", peers, { "${it.name} · ${it.publicKey.takeLast(6)}" },
        { choosingPeer = false }) { peer = it; choosingPeer = false }
    if (choosingProfile) TransferChoices("选择配置", profiles, VaultProfile::name,
        { choosingProfile = false }) { profileId = it.id; choosingProfile = false }
    if (addresses.isNotEmpty()) TransferChoices("选择本机局域网地址", addresses, { it },
        { addresses = emptyList() }) { value ->
        addresses = emptyList()
        peer?.let { selected -> runTransfer { startListening(value, selected) } }
    }
    if (confirmingClear) {
        val selected = profileId
        SuperDialog(show = true, title = "清除冲突历史", summary = "清除当前配置已处理的冲突记录。",
            onDismissRequest = { confirmingClear = false }) {
            Column {
                PairingButton("清除") {
                    confirmingClear = false
                    runTransfer { clearHistory(selected) }
                }
                TextButton("取消", onClick = { confirmingClear = false }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun <T> TransferChoices(
    title: String,
    choices: List<T>,
    label: (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    SuperDialog(show = true, title = title, onDismissRequest = onDismiss) {
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(choices) { item ->
                TextButton(label(item), onClick = { onSelect(item) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun TransferProblem.message(): String = when (this) {
    TransferProblem.INVALID_CODE -> "无法读取接收码"
    TransferProblem.UNTRUSTED -> "接收码与所选设备不符，或设备证书校验失败"
    TransferProblem.OTHER_SUBNET -> "请将两台设备接入同一局域网的 IPv4 网段"
    TransferProblem.CONNECTION -> "连接中断。请确认对方仍在接收，再重试传送"
    TransferProblem.UNSUPPORTED -> "两台设备的传送协议版本不兼容"
    TransferProblem.TOO_LARGE -> "配置大于 4 MiB，无法传送"
    TransferProblem.INTEGRITY -> "配置数据未通过完整性校验"
    TransferProblem.RECEIVER_BUSY -> "对方已有一份传送。请让对方完成或重新开始接收"
    TransferProblem.CONFLICT -> "此传送编号已被不同内容使用"
    TransferProblem.STORAGE -> "无法保存收到的配置，可重试传送"
    TransferProblem.WRONG_REPLICA -> "此配置已与另一份副本绑定，请在两端选择原先同步的配置"
    TransferProblem.STALE -> "配置在同步期间已修改，请重新开始同步"
    TransferProblem.REPLAY -> "已拒绝重复或顺序错误的同步请求"
}

private fun IncrementalEvent.Saved.message(): String =
    "同步完成 · 发送 $sent 条，接收 $received 条${if (conflicts == 0) "" else "，$conflicts 项冲突"}"

@Composable
private fun SyncConflictCard(profile: VaultProfile?, conflict: ReplicaConflict, enabled: Boolean, select: (Boolean) -> Unit) {
    val path = conflict.path
    val service = profile?.services?.firstOrNull { it.id == path.service }
    val item = service?.items?.firstOrNull { it.id == path.item }
    val key = item?.keys?.firstOrNull { it.id == path.key }
    val keyNumber = item?.keys?.indexOfFirst { it.id == path.key }?.takeIf { it >= 0 }?.plus(1)
    val title = listOfNotNull(service?.name ?: "服务", item?.name, path.key?.let { keyNumber?.let { "Key $it" } ?: "Key" })
        .joinToString(" / ")
    fun label(data: ReplicaValue?) = when {
        data == null -> "已删除"
        path.key != null -> "${if (data.name.length >= 8) "••••${data.name.takeLast(4)}" else "••••"} ${data.detail}".trim()
        path.item != null && data.detail.isNotBlank() -> "${data.name} · URL …${data.detail.takeLast(4)}"
        else -> data.name
    }
    val adopted = when {
        path.key != null -> key?.let { ReplicaValue(it.value, it.note) }
        path.item != null -> item?.let { ReplicaValue(it.name, it.apiUrl) }
        else -> service?.let { ReplicaValue(it.name) }
    }
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$title · 两边都改过")
            Text("本机 · ${label(conflict.local)}")
            Text("对端 · ${label(conflict.remote)}")
            Text(if (adopted == conflict.local) "当前采用本机版本" else "当前采用对端版本")
            TextButton("保留本机版本", onClick = { select(false) }, enabled = enabled, modifier = Modifier.fillMaxWidth())
            TextButton("采用对端版本", onClick = { select(true) }, enabled = enabled, modifier = Modifier.fillMaxWidth())
        }
    }
}
