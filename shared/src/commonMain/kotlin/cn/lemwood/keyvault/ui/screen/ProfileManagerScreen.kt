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
import androidx.compose.runtime.key
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
import cn.lemwood.keyvault.data.serializer.ProfileCodec
import cn.lemwood.keyvault.platform.VaultFileIo
import cn.lemwood.keyvault.ui.VaultViewModel
import cn.lemwood.keyvault.ui.components.CollectMessages
import cn.lemwood.keyvault.ui.components.MessageHost
import cn.lemwood.keyvault.ui.components.rememberMessagePresenter
import cn.lemwood.keyvault.ui.screen.components.InputDialog
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

/**
 * 配置管理页：切换、新建、重命名、删除、导出，以及从备份文件导入。
 *
 * 导入的约定是「新增一份配置」而不是覆盖现有数据 —— 这样导入别人的备份不会把本地数据冲掉，
 * 导入后可以在这里（或首页左上角）自由切换、删除。
 */
@Composable
fun ProfileManagerScreen(
    viewModel: VaultViewModel,
    fileIo: VaultFileIo,
    contentPadding: PaddingValues,
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
    var pendingImportContent by remember { mutableStateOf<String?>(null) }

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
                        onExport = {
                            scope.launch {
                                val json = viewModel.exportProfile(profile.id)
                                if (json == null) {
                                    presenter.show("配置不存在")
                                    return@launch
                                }
                                val ok = fileIo.saveFile(
                                    ProfileCodec.backupFileName(profile.name),
                                    json
                                )
                                presenter.show(if (ok) "已导出「${profile.name}」" else "导出已取消或失败")
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                text = "导入备份文件",
                onClick = {
                    scope.launch {
                        val content = fileIo.openFile()
                        if (content.isNullOrBlank()) {
                            presenter.show("未选择文件或文件为空")
                        } else {
                            pendingImportContent = content
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
            Text(
                text = "导入会新增一份配置，不会覆盖现有数据",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
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

    pendingImportContent?.let { content ->
        // key 保证每份待导入内容都用全新的输入框状态，不会串到上一次的输入
        androidx.compose.runtime.key(content) {
            InputDialog(
                title = "导入为新配置",
                hint = "配置名称",
                initialValue = viewModel.suggestedProfileName(),
                onDismiss = { pendingImportContent = null },
                onConfirm = { name ->
                    val imported = viewModel.importBackup(content, name.trim())
                    if (imported != null) {
                        presenter.show("已导入为「$imported」")
                    } else {
                        presenter.show("无法解析该文件，请确认是 KeyVault 备份")
                    }
                    pendingImportContent = null
                }
            )
        }
    }
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
