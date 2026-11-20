package cn.lemwood.keyvault.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemwood.keyvault.data.model.VaultProfile
import cn.lemwood.keyvault.data.backup.BackupCodec
import cn.lemwood.keyvault.data.backup.BackupException
import cn.lemwood.keyvault.data.backup.BackupProblem
import cn.lemwood.keyvault.data.backup.BackupSource
import cn.lemwood.keyvault.data.serializer.ProfileCodec
import cn.lemwood.keyvault.platform.VaultFileIo
import cn.lemwood.keyvault.ui.VaultViewModel
import cn.lemwood.keyvault.ui.ImportResult
import cn.lemwood.keyvault.ui.components.CollectMessages
import cn.lemwood.keyvault.ui.components.MessageHost
import cn.lemwood.keyvault.ui.components.rememberMessagePresenter
import cn.lemwood.keyvault.ui.screen.components.BackupPasswordDialog
import cn.lemwood.keyvault.ui.screen.components.BackupProgressDialog
import cn.lemwood.keyvault.ui.screen.components.InputDialog
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ProfileManagerScreen(
    viewModel: VaultViewModel,
    fileIo: VaultFileIo,
    contentPadding: PaddingValues,
    pendingImport: VaultProfile?,
    onPreview: (VaultProfile?) -> Unit,
    onPairing: (() -> Unit)?,
    onBack: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()
    val activeId by viewModel.activeProfileId.collectAsState()
    val presenter = rememberMessagePresenter()
    CollectMessages(viewModel.saveError, presenter)
    val scope = rememberCoroutineScope()

    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<VaultProfile?>(null) }
    var deleting by remember { mutableStateOf<VaultProfile?>(null) }
    var exporting by remember { mutableStateOf<VaultProfile?>(null) }
    var encryptedImport by remember { mutableStateOf<BackupSource.Encrypted?>(null) }
    var incorrectPassword by remember { mutableStateOf(false) }
    var backupStage by remember { mutableStateOf<String?>(null) }
    var backupJob by remember { mutableStateOf<Job?>(null) }

    fun runBackup(stage: String, action: suspend () -> Unit) {
        if (backupStage != null) return
        backupStage = stage
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                action()
            } catch (failure: BackupException) {
                if (failure.problem == BackupProblem.WRONG_PASSWORD) {
                    incorrectPassword = true
                } else {
                    presenter.show(failure.problem.message())
                    encryptedImport = null
                }
            } finally {
                backupStage = null
                backupJob = null
            }
        }
        backupJob = job
        job.start()
    }

    if (pendingImport != null) {
        val importing by viewModel.isImporting.collectAsState()
        ImportPreviewScreen(
            backup = pendingImport,
            profiles = profiles,
            activeId = activeId,
            suggestedName = viewModel.suggestedProfileName(),
            importing = importing,
            contentPadding = contentPadding,
            presenter = presenter,
            onBack = { onPreview(null) },
            onApply = { plan ->
                scope.launch {
                    when (viewModel.applyImport(plan)) {
                        ImportResult.SAVED -> {
                            onPreview(null)
                            presenter.show("已导入到「${plan.result.name}」")
                        }
                        ImportResult.STALE_PREVIEW -> presenter.show("配置已变化，请重新检查预览")
                        ImportResult.SAVE_FAILED -> presenter.show("导入保存失败，请重试")
                    }
                }
            },
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            SmallTopAppBar(
                title = "配置管理",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (onPairing != null) TextButton("局域网配对", onClick = onPairing)
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "新建配置")
                    }
                }
            )
        },
        snackbarHost = { MessageHost(presenter) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .padding(horizontal = 16.dp)
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    top = 12.dp,
                    bottom = contentPadding.calculateBottomPadding() + 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileRow(
                        profile = profile,
                        isActive = profile.id == activeId,
                        canDelete = profiles.size > 1,
                        onSwitch = { viewModel.switchProfile(profile.id) },
                        onRename = { renaming = profile },
                        onDelete = { deleting = profile },
                        onExport = { exporting = profile }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                text = "导入备份文件",
                onClick = {
                    runBackup("正在读取备份…") {
                        val content = fileIo.openFile() ?: return@runBackup
                        try {
                            when (val source = BackupCodec.inspect(content)) {
                                is BackupSource.Legacy -> onPreview(source.profile)
                                is BackupSource.Encrypted -> encryptedImport = source
                            }
                        } finally {
                            content.fill(0)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
            Spacer(modifier = Modifier.height(contentPadding.calculateBottomPadding()))
        }
    }

    if (showCreateDialog) {
        InputDialog(
            title = "新建配置",
            hint = "配置名称",
            initialValue = viewModel.suggestedProfileName(),
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                if (viewModel.addProfile(name.trim())) {
                    showCreateDialog = false
                    presenter.show("已新建并切换到「${name.trim()}」")
                } else {
                    presenter.show("配置名称已存在")
                }
            }
        )
    }

    renaming?.let { profile ->
        InputDialog(
            title = "重命名配置",
            hint = "配置名称",
            initialValue = profile.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                if (viewModel.renameProfile(profile.id, name.trim())) {
                    renaming = null
                } else {
                    presenter.show("配置名称已存在")
                }
            }
        )
    }

    deleting?.let { profile ->
        SuperDialog(
            show = true,
            title = "删除配置",
            summary = "将删除「${profile.name}」及其 ${profile.services.size} 个服务，且不可恢复",
            onDismissRequest = { deleting = null }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "删除",
                    onClick = {
                        if (viewModel.deleteProfile(profile.id)) {
                            presenter.show("已删除「${profile.name}」")
                        } else {
                            presenter.show("至少要保留一份配置")
                        }
                        deleting = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    text = "取消",
                    onClick = { deleting = null },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    backupStage?.let { stage ->
        BackupProgressDialog(stage, cancelling = backupJob?.isCancelled == true) {
            backupStage = "正在取消…"
            backupJob?.cancel()
        }
        return
    }

    exporting?.let { profile ->
        BackupPasswordDialog(
            exporting = true,
            onDismiss = { exporting = null },
            onConfirm = { phrase ->
                exporting = null
                runBackup("正在加密备份…") {
                    val password = phrase.encodeToByteArray()
                    val content = try {
                        BackupCodec.encode(profile, password)
                    } finally {
                        password.fill(0)
                    }
                    backupStage = "请选择保存位置"
                    val saved = fileIo.saveFile(ProfileCodec.backupFileName(profile.name), content) {
                        backupStage = "正在写入备份…"
                    }
                    if (saved) presenter.show("已导出「${profile.name}」")
                }
            },
        )
    }

    encryptedImport?.let { source ->
        BackupPasswordDialog(
            exporting = false,
            error = BackupProblem.WRONG_PASSWORD.message().takeIf { incorrectPassword },
            onDismiss = {
                encryptedImport = null
                incorrectPassword = false
            },
            onConfirm = { phrase ->
                incorrectPassword = false
                runBackup("正在解密备份…") {
                    val password = phrase.encodeToByteArray()
                    try {
                        onPreview(BackupCodec.unlock(source, password))
                        encryptedImport = null
                    } finally {
                        password.fill(0)
                    }
                }
            },
        )
    }
}

private fun BackupProblem.message() = when (this) {
    BackupProblem.WRONG_PASSWORD -> "备份口令错误，或文件认证失败"
    BackupProblem.DAMAGED -> "备份文件已损坏或格式无效"
    BackupProblem.UNSUPPORTED -> "此备份的版本或加密算法不受支持"
    BackupProblem.TOO_LARGE -> "备份内容过大"
    BackupProblem.NO_SPACE -> "存储空间不足"
    BackupProblem.FILE_IO -> "无法读写备份文件"
    BackupProblem.INVALID_PASSWORD -> "请输入有效的备份口令"
}

@Composable
private fun ProfileRow(
    profile: VaultProfile,
    isActive: Boolean,
    canDelete: Boolean,
    onSwitch: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        showIndication = true,
        onClick = onSwitch
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        style = MiuixTheme.textStyles.body1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(profile.services.size)
                            append(" 个服务")
                            if (isActive) append(" · 当前使用中")
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                IconButton(onClick = onExport) {
                    Icon(Icons.Outlined.Download, contentDescription = "导出")
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Outlined.Edit, contentDescription = "重命名")
                }
                if (canDelete) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "删除",
                            tint = MiuixTheme.colorScheme.error
                        )
                    }
                }
            }
            if (isActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Upload,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = "点卡片可切换当前使用的配置",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}
