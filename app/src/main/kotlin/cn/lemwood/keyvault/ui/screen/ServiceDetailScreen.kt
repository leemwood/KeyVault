package cn.lemwood.keyvault.ui.screen

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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.lemwood.keyvault.data.model.Service
import cn.lemwood.keyvault.data.model.ServiceItem
import cn.lemwood.keyvault.ui.VaultViewModel
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
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ServiceDetailScreen(
    service: Service,
    contentPadding: PaddingValues,
    onBack: () -> Unit = {},
    onItemClick: (ServiceItem) -> Unit = {},
    onAddItem: (String) -> Boolean = { false },
    onDeleteItem: (String) -> Unit = {},
    onUpdateItemName: (String, String) -> Unit = { _, _ -> },
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
                        onClick = { onItemClick(item) },
                        onDeleteItem = { onDeleteItem(item.id) },
                        onUpdateItemName = { onUpdateItemName(item.id, it) }
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
    onClick: () -> Unit,
    onDeleteItem: () -> Unit,
    onUpdateItemName: (String) -> Unit
) {
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showDeleteItemDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        showIndication = true,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.name,
                style = MiuixTheme.textStyles.body1,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${item.keys.size} 个 Key",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1
            )
            IconButton(onClick = { showEditNameDialog = true }) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑")
            }
            IconButton(onClick = { showDeleteItemDialog = true }) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除",
                    tint = MiuixTheme.colorScheme.error
                )
            }
        }
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
}
