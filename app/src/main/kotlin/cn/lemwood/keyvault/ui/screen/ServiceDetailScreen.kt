package cn.lemwood.keyvault.ui.screen

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.lemwood.keyvault.data.model.ApiKey
import cn.lemwood.keyvault.data.model.Service
import cn.lemwood.keyvault.data.model.ServiceItem
import cn.lemwood.keyvault.ui.VaultViewModel
import cn.lemwood.keyvault.ui.screen.components.InputDialog
import top.yukonga.miuix.kmp.basic.Button
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
fun ServiceDetailScreen(
    service: Service,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onAddItem: (String) -> Boolean,
    onDeleteItem: (String) -> Unit,
    onUpdateItemName: (String, String) -> Unit,
    onUpdateApiUrl: (String, String) -> Unit,
    onAddKey: (String, String) -> Unit,
    onDeleteKey: (String, String) -> Unit,
    onUpdateKeyValue: (String, String, String) -> Unit,
    onUpdateKeyNote: (String, String, String) -> Unit,
    viewModel: VaultViewModel = viewModel()
) {
    val context = LocalContext.current
    var showAddItemDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.saveError.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            SmallTopAppBar(
                title = service.name,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddItemDialog = true }
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "添加配置项")
            }
        }
    ) { innerPadding ->
        val top = innerPadding.calculateTopPadding() + contentPadding.calculateTopPadding()
        val bottom = innerPadding.calculateBottomPadding() + contentPadding.calculateBottomPadding()

        if (service.items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = top, bottom = bottom),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无配置项，点击右下角 + 添加",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center
                )
            }
        } else {
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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(service.items, key = { it.id }) { item ->
                    ItemCard(
                        item = item,
                        onDeleteItem = { onDeleteItem(item.id) },
                        onUpdateItemName = { onUpdateItemName(item.id, it) },
                        onUpdateApiUrl = { onUpdateApiUrl(item.id, it) },
                        onAddKey = { onAddKey(item.id, it) },
                        onDeleteKey = { keyId -> onDeleteKey(item.id, keyId) },
                        onUpdateKeyValue = { keyId, value -> onUpdateKeyValue(item.id, keyId, value) },
                        onUpdateKeyNote = { keyId, note -> onUpdateKeyNote(item.id, keyId, note) }
                    )
                }
            }
        }
    }

    if (showAddItemDialog) {
        InputDialog(
            title = "添加配置项",
            hint = "配置项名称",
            onDismiss = { showAddItemDialog = false },
            onConfirm = { name ->
                if (onAddItem(name.trim())) {
                    showAddItemDialog = false
                } else {
                    Toast.makeText(context, "配置项名称已存在", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@Composable
private fun ItemCard(
    item: ServiceItem,
    onDeleteItem: () -> Unit,
    onUpdateItemName: (String) -> Unit,
    onUpdateApiUrl: (String) -> Unit,
    onAddKey: (String) -> Unit,
    onDeleteKey: (String) -> Unit,
    onUpdateKeyValue: (String, String) -> Unit,
    onUpdateKeyNote: (String, String) -> Unit
) {
    val context = LocalContext.current
    var showAddKeyDialog by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showEditUrlDialog by remember { mutableStateOf(false) }
    var showDeleteItemDialog by remember { mutableStateOf(false) }
    var deletingKey by remember { mutableStateOf<ApiKey?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.name,
                style = MiuixTheme.textStyles.title3,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row {
                IconButton(onClick = { showEditNameDialog = true }) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = { showDeleteItemDialog = true }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "API URL",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = item.apiUrl.ifBlank { "未设置" },
                style = MiuixTheme.textStyles.body2,
                color = if (item.apiUrl.isBlank()) MiuixTheme.colorScheme.onSurfaceVariantSummary
                else MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = { showEditUrlDialog = true }) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑 URL")
            }
            if (item.apiUrl.isNotBlank()) {
                IconButton(onClick = { copyToClipboard(context, item.apiUrl) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "复制 URL")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (item.keys.isEmpty()) {
            Text(
                text = "尚未添加 Key",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item.keys.forEach { key ->
                    KeyRow(
                        key = key,
                        onValueChange = { onUpdateKeyValue(key.id, it) },
                        onNoteChange = { onUpdateKeyNote(key.id, it) },
                        onDelete = { deletingKey = key }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { showAddKeyDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColorsPrimary()
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("添加 Key")
        }
    }

    if (showAddKeyDialog) {
        InputDialog(
            title = "添加 Key",
            hint = "API Key 值",
            onDismiss = { showAddKeyDialog = false },
            onConfirm = { value ->
                if (value.isNotBlank()) {
                    onAddKey(value.trim())
                }
                showAddKeyDialog = false
            }
        )
    }

    if (showEditNameDialog) {
        InputDialog(
            title = "编辑配置项名称",
            initialValue = item.name,
            hint = "配置项名称",
            onDismiss = { showEditNameDialog = false },
            onConfirm = { name ->
                if (name.isNotBlank()) {
                    onUpdateItemName(name.trim())
                }
                showEditNameDialog = false
            }
        )
    }

    if (showEditUrlDialog) {
        InputDialog(
            title = "编辑 API URL",
            initialValue = item.apiUrl,
            hint = "https://api.example.com/v1",
            onDismiss = { showEditUrlDialog = false },
            onConfirm = { url ->
                onUpdateApiUrl(url.trim())
                showEditUrlDialog = false
            }
        )
    }

    if (showDeleteItemDialog) {
        SuperDialog(
            show = true,
            title = "删除配置项",
            summary = "将删除「${item.name}」及其 ${item.keys.size} 个 Key，且不可恢复",
            onDismissRequest = { showDeleteItemDialog = false }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "删除",
                    onClick = {
                        onDeleteItem()
                        showDeleteItemDialog = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    text = "取消",
                    onClick = { showDeleteItemDialog = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    deletingKey?.let { key ->
        SuperDialog(
            show = true,
            title = "删除 Key",
            summary = "将删除该 Key，且不可恢复",
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
private fun KeyRow(
    key: ApiKey,
    onValueChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var valueVisible by remember(key.id) { mutableStateOf(false) }
    var valueDraft by remember(key.id) { mutableStateOf(key.value) }
    var noteDraft by remember(key.id) { mutableStateOf(key.note) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = valueDraft,
                onValueChange = { valueDraft = it },
                label = "Key",
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged {
                        if (!it.isFocused && valueDraft != key.value) {
                            onValueChange(valueDraft)
                        }
                    },
                singleLine = true,
                visualTransformation = if (valueVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (valueDraft != key.value) {
                            onValueChange(valueDraft)
                        }
                    }
                )
            )
            IconButton(onClick = { valueVisible = !valueVisible }) {
                Icon(
                    if (valueVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = "显示/隐藏"
                )
            }
            IconButton(onClick = { copyToClipboard(context, valueDraft) }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "复制 Key")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除 Key")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        TextField(
            value = noteDraft,
            onValueChange = { noteDraft = it },
            label = "备注",
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    if (!it.isFocused && noteDraft != key.note) {
                        onNoteChange(noteDraft)
                    }
                },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (noteDraft != key.note) {
                        onNoteChange(noteDraft)
                    }
                }
            )
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    if (text.isBlank()) {
        Toast.makeText(context, "内容为空", Toast.LENGTH_SHORT).show()
        return
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("key", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}
