package cn.lemwood.keyvault.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemwood.keyvault.data.model.ApiKey
import cn.lemwood.keyvault.data.model.Service
import cn.lemwood.keyvault.data.model.ServiceItem
import cn.lemwood.keyvault.ui.screen.components.InputDialog
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ItemDetailScreen(
    service: Service,
    item: ServiceItem,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onUpdateApiUrl: (String) -> Unit,
    onAddKey: (String) -> Unit,
    onDeleteKey: (String) -> Unit,
    onUpdateKeyValue: (String, String) -> Unit,
    onUpdateKeyNote: (String, String) -> Unit
) {
    val context = LocalContext.current
    var showAddKeyDialog by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf<ApiKey?>(null) }
    var deletingKey by remember { mutableStateOf<ApiKey?>(null) }
    var showEditApiUrl by remember { mutableStateOf(false) }

    fun copy(text: String, label: String) {
        if (text.isBlank()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label 已复制", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            SmallTopAppBar(
                title = item.name,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddKeyDialog = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "添加 Key")
            }
        }
    ) { innerPadding ->
        val top = innerPadding.calculateTopPadding() + contentPadding.calculateTopPadding()
        val bottom = innerPadding.calculateBottomPadding() + contentPadding.calculateBottomPadding()

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = top + 12.dp,
                bottom = bottom + 92.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "接口地址",
                                style = MiuixTheme.textStyles.body1,
                                modifier = Modifier.weight(1f)
                            )
                            if (item.apiUrl.isNotBlank()) {
                                IconButton(onClick = { copy(item.apiUrl, "接口地址") }) {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = "复制")
                                }
                            }
                            IconButton(onClick = { showEditApiUrl = true }) {
                                Icon(Icons.Outlined.Edit, contentDescription = "编辑")
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        if (item.apiUrl.isBlank()) {
                            Text(
                                text = "未设置",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        } else {
                            Text(
                                text = item.apiUrl,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "API Keys（${item.keys.size}）",
                    style = MiuixTheme.textStyles.title3,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }

            if (item.keys.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无 Key，点击右下角 + 添加",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            } else {
                items(item.keys, key = { it.id }) { key ->
                    KeyCard(
                        key = key,
                        onCopy = { copy(key.value, "Key") },
                        onEdit = { editingKey = key },
                        onDelete = { deletingKey = key }
                    )
                }
            }
        }
    }

    if (showAddKeyDialog) {
        InputDialog(
            title = "添加 Key",
            hint = "Key 值",
            onDismiss = { showAddKeyDialog = false },
            onConfirm = { value ->
                if (value.isNotBlank()) {
                    onAddKey(value.trim())
                }
                showAddKeyDialog = false
            }
        )
    }

    if (showEditApiUrl) {
        InputDialog(
            title = "编辑接口地址",
            initialValue = item.apiUrl,
            hint = "API URL",
            onDismiss = { showEditApiUrl = false },
            onConfirm = { url ->
                onUpdateApiUrl(url.trim())
                showEditApiUrl = false
            }
        )
    }

    editingKey?.let { key ->
        KeyEditDialog(
            title = "编辑 Key",
            initialValue = key.value,
            initialNote = key.note,
            onDismiss = { editingKey = null },
            onConfirm = { value, note ->
                onUpdateKeyValue(key.id, value)
                onUpdateKeyNote(key.id, note)
                editingKey = null
            }
        )
    }

    deletingKey?.let { key ->
        SuperDialog(
            show = true,
            title = "删除 Key",
            summary = if (key.note.isNotBlank())
                "将删除备注「${key.note}」对应的 Key，且不可恢复"
            else "将删除该 Key，且不可恢复",
            onDismissRequest = { deletingKey = null }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "删除",
                    onClick = {
                        onDeleteKey(key.id)
                        deletingKey = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    text = "取消",
                    onClick = { deletingKey = null },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun KeyCard(
    key: ApiKey,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = key.note.ifBlank { "Key" },
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (visible) "隐藏" else "显示"
                    )
                }
                IconButton(onClick = onCopy) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "复制")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "删除",
                        tint = MiuixTheme.colorScheme.error
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (visible) key.value else "••••••••••••",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = if (visible) 3 else 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun KeyEditDialog(
    title: String,
    initialValue: String,
    initialNote: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var value by rememberSaveable { mutableStateOf(initialValue) }
    var note by rememberSaveable { mutableStateOf(initialNote) }

    SuperDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = value,
                onValueChange = { value = it },
                label = "Key 值",
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextField(
                value = note,
                onValueChange = { note = it },
                label = "备注",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(
                text = "确定",
                onClick = { onConfirm(value.trim(), note.trim()) },
                modifier = Modifier.fillMaxWidth(),
                enabled = value.isNotBlank(),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
