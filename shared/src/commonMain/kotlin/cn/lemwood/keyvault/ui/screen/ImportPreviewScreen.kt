package cn.lemwood.keyvault.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemwood.keyvault.data.backup.ImportChangeKind
import cn.lemwood.keyvault.data.backup.ImportConflict
import cn.lemwood.keyvault.data.backup.ImportConflictId
import cn.lemwood.keyvault.data.backup.ImportPlan
import cn.lemwood.keyvault.data.backup.ImportResolution
import cn.lemwood.keyvault.data.backup.ImportStrategy
import cn.lemwood.keyvault.data.backup.previewImport
import cn.lemwood.keyvault.data.backup.uniqueImportName
import cn.lemwood.keyvault.data.model.VaultProfile
import cn.lemwood.keyvault.ui.components.MessageHost
import cn.lemwood.keyvault.ui.components.MessagePresenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val previewDispatcher = Dispatchers.Default.limitedParallelism(1)

@Composable
internal fun ImportPreviewScreen(
    backup: VaultProfile,
    profiles: List<VaultProfile>,
    activeId: String,
    suggestedName: String,
    importing: Boolean,
    contentPadding: PaddingValues,
    presenter: MessagePresenter,
    onBack: () -> Unit,
    onApply: (ImportPlan) -> Unit,
) {
    var strategy by remember { mutableStateOf(ImportStrategy.ADD) }
    var newName by remember { mutableStateOf(suggestedName) }
    var targetId by remember { mutableStateOf(activeId) }
    var selectingTarget by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<ImportPlan?>(null) }
    var expanded by remember { mutableStateOf(setOf<ImportChangeKind>()) }
    val target = profiles.find { it.id == targetId }.takeUnless { strategy == ImportStrategy.ADD }
    val trimmed = newName.trim()
    val actualName = remember(trimmed, profiles) { uniqueImportName(trimmed, profiles) }
    var choices by remember(target) { mutableStateOf(mapOf<ImportConflictId, ImportResolution>()) }

    val request = remember(strategy, target, actualName, choices) { Any() }
    var preview by remember { mutableStateOf<Pair<Any, ImportPlan>?>(null) }
    LaunchedEffect(request) {
        val selectedStrategy = strategy
        val selectedChoices = choices
        if (if (selectedStrategy == ImportStrategy.ADD) actualName.isBlank() else target == null) {
            preview = null
        } else {
            val plan = withContext(previewDispatcher) {
                previewImport(backup, selectedStrategy, target, actualName, selectedChoices)
            }
            preview = request to plan
        }
    }
    val plan = preview?.second?.takeIf { it.strategy == strategy && it.target == target }
    val ready = preview?.first === request && plan != null && !importing
    val changes = remember(plan) { plan?.changes.orEmpty().groupBy { it.kind } }

    Scaffold(
        topBar = {
            SmallTopAppBar(title = "导入预览", navigationIcon = {
                IconButton(onClick = onBack, enabled = !importing) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
            })
        },
        snackbarHost = { MessageHost(presenter) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.selectableGroup()) {
                            ImportStrategy.entries.forEach { option ->
                                ImportOption(option.label, option == strategy, !importing) { strategy = option }
                            }
                        }
                    }
                }
                item {
                    if (strategy == ImportStrategy.ADD) {
                        TextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = "新配置名称",
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !importing,
                        )
                        if (trimmed != actualName) {
                            Text("将保存为「$actualName」", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    } else {
                        TextButton(
                            text = target?.let { "目标配置 · ${it.name}" } ?: "选择目标配置",
                            onClick = { selectingTarget = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !importing,
                        )
                    }
                }

                if (plan != null) {
                    ImportChangeKind.entries.forEach { kind ->
                        val entries = changes[kind].orEmpty()
                        val open = kind in expanded
                        item {
                            TextButton(
                                text = "${kind.label} ${entries.size} 项${if (entries.isEmpty()) "" else if (open) " · 收起" else " · 展开"}",
                                onClick = { expanded = if (open) expanded - kind else expanded + kind },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = entries.isNotEmpty(),
                            )
                        }
                        if (open) items(entries) { change ->
                            Text(change.path, modifier = Modifier.padding(horizontal = 12.dp))
                        }
                    }
                    if (plan.conflicts.isNotEmpty()) {
                        item { Text("冲突 ${plan.conflicts.size} 项", style = MiuixTheme.textStyles.body1) }
                        items(plan.conflicts) { conflict ->
                            ConflictCard(conflict, choices[conflict.id] ?: ImportResolution.LOCAL, !importing) { resolution ->
                                choices = choices + (conflict.id to resolution)
                            }
                        }
                    }
                } else if ((actualName.isNotBlank() && strategy == ImportStrategy.ADD) || target != null) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }

            TextButton(
                text = when {
                    importing -> "正在保存…"
                    strategy == ImportStrategy.REPLACE -> "检查并确认覆盖"
                    else -> "确认导入"
                },
                onClick = {
                    if (ready && plan != null) {
                        if (plan.strategy == ImportStrategy.REPLACE) confirmation = plan else onApply(plan)
                    }
                },
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 12.dp),
                enabled = ready,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }

    if (selectingTarget) {
        SuperDialog(show = true, title = "选择目标配置", onDismissRequest = { selectingTarget = false }) {
            LazyColumn(Modifier.heightIn(max = 300.dp).selectableGroup()) {
                items(profiles, key = { it.id }) { candidate ->
                    ImportOption(candidate.name, candidate.id == targetId) {
                        targetId = candidate.id
                        selectingTarget = false
                    }
                }
            }
        }
    }
    confirmation?.let { selected ->
        SuperDialog(
            show = true,
            title = "确认覆盖",
            summary = "「${selected.result.name}」将删除 ${selected.count(ImportChangeKind.DELETED)} 项、" +
                "修改 ${selected.count(ImportChangeKind.MODIFIED)} 项、新增 ${selected.count(ImportChangeKind.ADDED)} 项。",
            onDismissRequest = { confirmation = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = "确定覆盖",
                    onClick = {
                        confirmation = null
                        if (ready && selected === plan) onApply(selected)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = ready && selected === plan,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton("返回预览", onClick = { confirmation = null }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ConflictCard(
    conflict: ImportConflict,
    selected: ImportResolution,
    enabled: Boolean,
    onSelect: (ImportResolution) -> Unit,
) {
    val id = conflict.id
    val field = id.keyIndex?.let { "Key ${it + 1}" } ?: "API URL"
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${id.service} / ${id.item} / $field")
            Text("本地 · ${conflict.local}")
            Text("备份 · ${conflict.incoming}")
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ImportResolution.entries.forEach { resolution ->
                    if (resolution != ImportResolution.BOTH || conflict.allowBoth) {
                        ImportOption(resolution.label, selected == resolution, enabled, Modifier) { onSelect(resolution) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportOption(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit,
) {
    Row(
        modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val ImportStrategy.label get() = when (this) {
    ImportStrategy.ADD -> "新增配置"
    ImportStrategy.MERGE -> "合并到现有配置"
    ImportStrategy.REPLACE -> "覆盖现有配置"
}

private val ImportChangeKind.label get() = when (this) {
    ImportChangeKind.ADDED -> "新增"
    ImportChangeKind.MODIFIED -> "修改"
    ImportChangeKind.DELETED -> "删除"
}

private val ImportResolution.label get() = when (this) {
    ImportResolution.LOCAL -> "保留本地"
    ImportResolution.INCOMING -> "用导入"
    ImportResolution.BOTH -> "都保留"
}
