package cn.lemwood.keyvault.ui.desktop

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemwood.keyvault.data.model.ApiKey
import cn.lemwood.keyvault.data.model.Service
import cn.lemwood.keyvault.data.model.ServiceItem
import cn.lemwood.keyvault.ui.VaultViewModel
import cn.lemwood.keyvault.ui.components.MessagePresenter
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DesktopServiceDetail(
    service: Service,
    requestedItemId: String?,
    onItemSelected: (String) -> Unit,
    viewModel: VaultViewModel,
    presenter: MessagePresenter,
) {
    val colors = MiuixTheme.colorScheme
    val selectedItem = service.items.find { it.id == requestedItemId } ?: service.items.firstOrNull()
    var editingService by remember { mutableStateOf(false) }
    var deletingService by remember { mutableStateOf(false) }
    var addingItem by remember { mutableStateOf(false) }
    var createdItemName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(service.items, createdItemName) {
        val name = createdItemName ?: return@LaunchedEffect
        val created = service.items.find { it.name == name } ?: return@LaunchedEffect
        onItemSelected(created.id)
        createdItemName = null
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(colors.background).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(colors.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Dns, null, Modifier.size(23.dp), tint = colors.onPrimaryContainer)
            }
            Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(service.name, Modifier.semantics { heading() }, color = colors.onSurface, style = MiuixTheme.textStyles.title2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${service.items.size} 个配置项", color = colors.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote2)
            }
            DesktopIconButton(Icons.Outlined.Edit, "重命名服务", onClick = { editingService = true })
            DesktopIconButton(Icons.Outlined.Delete, "删除服务", onClick = { deletingService = true }, danger = true)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.dividerLine))
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("配置项", Modifier.semantics { heading() }, color = colors.onSurface, style = MiuixTheme.textStyles.title4)
            DesktopButton("添加配置项", onClick = { addingItem = true }, icon = Icons.Outlined.Add)
        }
        if (selectedItem == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DesktopEmpty(Icons.Outlined.Layers, "暂无配置项", "添加配置项后即可保存接口地址和 Key。")
            }
        } else {
            ItemTabs(service.items, selectedItem.id, onItemSelected)
            key(selectedItem.id) { DesktopItemDetail(service.id, selectedItem, viewModel, presenter) }
        }
    }

    if (addingItem) {
        DesktopEditDialog("添加配置项", "配置项名称", onDismiss = { addingItem = false }) { name, _ ->
            if (viewModel.addItem(service.id, name)) {
                createdItemName = name
                addingItem = false
                null
            } else {
                "已存在同名配置项"
            }
        }
    }
    if (editingService) {
        DesktopEditDialog("重命名服务", "服务名称", service.name, onDismiss = { editingService = false }) { name, _ ->
            if (viewModel.updateServiceName(service.id, name)) {
                editingService = false
                null
            } else {
                "已存在同名服务"
            }
        }
    }
    if (deletingService) {
        DesktopDeleteDialog("删除服务", "将删除「${service.name}」及其中的所有配置项和 Key。", { deletingService = false }) {
            viewModel.deleteService(service.id)
            deletingService = false
        }
    }
}

@Composable
private fun ItemTabs(items: List<ServiceItem>, selectedId: String, onSelect: (String) -> Unit) {
    TabRowWithContour(
        tabs = items.map { it.name },
        selectedTabIndex = items.indexOfFirst { it.id == selectedId },
        onTabSelected = { onSelect(items[it].id) },
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            .widthIn(max = (items.size.coerceAtMost(4) * 132).dp).fillMaxWidth(),
        minWidth = 72.dp,
        maxWidth = 176.dp,
        height = 38.dp,
    )
}

@Suppress("DEPRECATION")
@Composable
private fun DesktopItemDetail(serviceId: String, item: ServiceItem, viewModel: VaultViewModel, presenter: MessagePresenter) {
    val colors = MiuixTheme.colorScheme
    val state = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var editingItem by remember { mutableStateOf(false) }
    var deletingItem by remember { mutableStateOf(false) }
    var editingUrl by remember { mutableStateOf(false) }
    var addingKey by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf<ApiKey?>(null) }
    var deletingKey by remember { mutableStateOf<ApiKey?>(null) }

    fun copy(value: String, label: String) {
        val copied = runCatching { clipboard.setText(AnnotatedString(value)) }.isSuccess
        presenter.show(if (copied) "$label 已复制" else "无法使用剪贴板")
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("item-title") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, Modifier.weight(1f).semantics { heading() }, color = colors.onSurface,
                        style = MiuixTheme.textStyles.title3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    DesktopIconButton(Icons.Outlined.Edit, "重命名配置项", onClick = { editingItem = true })
                    DesktopIconButton(Icons.Outlined.Delete, "删除配置项", onClick = { deletingItem = true }, danger = true)
                }
            }
            item("url") {
                DetailRow(
                    title = "接口地址",
                    value = item.apiUrl.ifBlank { "未设置" },
                ) {
                    if (item.apiUrl.isNotBlank()) {
                        DesktopIconButton(Icons.Outlined.ContentCopy, "复制接口地址", onClick = { copy(item.apiUrl, "接口地址") })
                    }
                    DesktopIconButton(Icons.Outlined.Edit, "编辑接口地址", onClick = { editingUrl = true })
                }
            }
            item("key-title") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("API Keys · ${item.keys.size}", Modifier.semantics { heading() }, color = colors.onSurface,
                        style = MiuixTheme.textStyles.title4)
                    DesktopButton("添加 Key", onClick = { addingKey = true }, icon = Icons.Outlined.Add, primary = true)
                }
            }
            if (item.keys.isEmpty()) {
                item("empty") {
                    Text("暂无 Key", Modifier.padding(vertical = 12.dp),
                        color = colors.onSurfaceVariantSummary, style = MiuixTheme.textStyles.body2)
                }
            }
            items(item.keys, key = { "key:${it.id}" }) { apiKey ->
                var revealed by remember(apiKey.value) { mutableStateOf(false) }
                DetailRow(
                    title = apiKey.note.ifBlank { "Key" },
                    value = if (revealed) apiKey.value else "••••••••••••",
                    monospace = true,
                ) {
                    DesktopIconButton(
                        if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        if (revealed) "隐藏 Key" else "显示 Key",
                        onClick = { revealed = !revealed },
                    )
                    DesktopIconButton(Icons.Outlined.ContentCopy, "复制 Key", onClick = { copy(apiKey.value, "Key") })
                    DesktopIconButton(Icons.Outlined.Edit, "编辑 Key", onClick = { editingKey = apiKey })
                    DesktopIconButton(Icons.Outlined.Delete, "删除 Key", onClick = { deletingKey = apiKey }, danger = true)
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.fillMaxHeight().align(Alignment.CenterEnd))
    }

    if (editingItem) {
        DesktopEditDialog("重命名配置项", "配置项名称", item.name, onDismiss = { editingItem = false }) { name, _ ->
            viewModel.updateItemName(serviceId, item.id, name)
            editingItem = false
            null
        }
    }
    if (deletingItem) {
        DesktopDeleteDialog("删除配置项", "将删除「${item.name}」及其中的所有 Key。", { deletingItem = false }) {
            viewModel.deleteItem(serviceId, item.id)
            deletingItem = false
        }
    }
    if (editingUrl) {
        DesktopEditDialog("编辑接口地址", "API URL", item.apiUrl, allowBlank = true, onDismiss = { editingUrl = false }) { url, _ ->
            viewModel.updateApiUrl(serviceId, item.id, url)
            editingUrl = false
            null
        }
    }
    if (addingKey) {
        DesktopEditDialog("添加 Key", "Key 值", onDismiss = { addingKey = false }) { value, _ ->
            viewModel.addKey(serviceId, item.id, value)
            addingKey = false
            null
        }
    }
    editingKey?.let { apiKey ->
        DesktopEditDialog("编辑 Key", "Key 值", apiKey.value, note = apiKey.note, onDismiss = { editingKey = null }) { value, note ->
            viewModel.updateKeyValue(serviceId, item.id, apiKey.id, value)
            viewModel.updateKeyNote(serviceId, item.id, apiKey.id, note)
            editingKey = null
            null
        }
    }
    deletingKey?.let { apiKey ->
        DesktopDeleteDialog("删除 Key", "删除后无法恢复。", { deletingKey = null }) {
            viewModel.deleteKey(serviceId, item.id, apiKey.id)
            deletingKey = null
        }
    }
}

@Composable
private fun DetailRow(title: String, value: String, monospace: Boolean = false, actions: @Composable () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 13.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = colors.onSurface, style = MiuixTheme.textStyles.body1,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val valueStyle = MiuixTheme.textStyles.body2
                Text(
                    value,
                    color = colors.onSurfaceVariantSummary,
                    style = if (monospace) valueStyle.copy(fontFamily = FontFamily.Monospace) else valueStyle,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}

@Composable
internal fun DesktopEditDialog(
    title: String,
    label: String,
    initialValue: String = "",
    note: String? = null,
    allowBlank: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String, String) -> String?,
) {
    val colors = MiuixTheme.colorScheme
    val fieldStyle = MiuixTheme.textStyles.body1.copy(color = colors.onSurface)
    var value by remember { mutableStateOf(initialValue) }
    var keyNote by remember { mutableStateOf(note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val canSave = allowBlank || value.isNotBlank()
    fun save() {
        if (canSave) error = onSave(value.trim(), keyNote.trim())
    }
    DesktopDialog(title, onDismiss) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        TextField(
            value = value,
            onValueChange = {
                value = it
                error = null
            },
            label = label,
            labelColor = colors.onSurfaceVariantSummary,
            textStyle = fieldStyle,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                if (note == null && event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                    save()
                    true
                } else {
                    false
                }
            },
            singleLine = note == null,
            maxLines = if (note == null) 1 else 4,
        )
        if (note != null) {
            TextField(
                value = keyNote,
                onValueChange = { keyNote = it },
                label = "备注",
                labelColor = colors.onSurfaceVariantSummary,
                textStyle = fieldStyle,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        error?.let { Text(it, color = colors.error, style = MiuixTheme.textStyles.body2) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            DesktopButton("取消", onDismiss)
            DesktopButton("保存", ::save, primary = true, enabled = canSave)
        }
    }
}

@Composable
private fun DesktopDeleteDialog(title: String, message: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    DesktopDialog(title, onDismiss) {
        Text(message, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.body2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            DesktopButton("取消", onDismiss)
            DesktopButton("删除", onDelete, danger = true)
        }
    }
}
