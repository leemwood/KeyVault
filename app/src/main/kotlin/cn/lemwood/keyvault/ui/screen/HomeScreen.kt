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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HomeScreen(
    services: List<Service>,
    contentPadding: PaddingValues,
    onServiceClick: (Service) -> Unit,
    onAddService: (String) -> Boolean,
    onDeleteService: (Service) -> Unit,
    onUpdateServiceName: (String, String) -> Boolean,
    viewModel: VaultViewModel = viewModel()
) {
    val context = LocalContext.current
    val isLoading by viewModel.isLoading.collectAsState()

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editingServiceId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingService by remember { mutableStateOf<Service?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.saveError.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            SmallTopAppBar(title = "KeyVault")
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true }
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "添加服务")
            }
        }
    ) { innerPadding ->
        val top = contentPadding.calculateTopPadding()
        val bottom = innerPadding.calculateBottomPadding() + contentPadding.calculateBottomPadding()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = "搜索服务或配置项",
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "清除")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                singleLine = true
            )

            val searchResults = remember(services, query) { searchItems(services, query) }

            if (query.isBlank()) {
                if (services.isEmpty()) {
                    if (!isLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "还没有服务\n点击右下角 + 添加",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = top + 12.dp,
                            bottom = bottom + 12.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(services, key = { it.id }) { service ->
                            ServiceCard(
                                service = service,
                                onClick = { onServiceClick(service) },
                                onEdit = { editingServiceId = service.id },
                                onDelete = { deletingService = service }
                            )
                        }
                    }
                }
            } else if (searchResults.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "未找到匹配的配置项",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = top + 12.dp,
                        bottom = bottom + 12.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(searchResults, key = { it.second.id }) { (service, item) ->
                        SearchResultCard(
                            service = service,
                            item = item,
                            onClick = { onServiceClick(service) }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        InputDialog(
            title = "添加服务",
            hint = "服务名称",
            onDismiss = { showAddDialog = false },
            onConfirm = { name ->
                if (onAddService(name.trim())) {
                    showAddDialog = false
                } else {
                    Toast.makeText(context, "服务名称已存在", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    editingServiceId?.let { id ->
        services.find { it.id == id }?.let { service ->
            InputDialog(
                title = "编辑服务",
                initialValue = service.name,
                hint = "服务名称",
                onDismiss = { editingServiceId = null },
                onConfirm = { name ->
                    if (onUpdateServiceName(service.id, name.trim())) {
                        editingServiceId = null
                    } else {
                        Toast.makeText(context, "服务名称已存在", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    deletingService?.let { service ->
        SuperDialog(
            show = true,
            title = "删除服务",
            summary = "将删除「${service.name}」及其 ${service.items.size} 个配置项，且不可恢复",
            onDismissRequest = { deletingService = null }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "删除",
                    onClick = {
                        onDeleteService(service)
                        deletingService = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    text = "取消",
                    onClick = { deletingService = null },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun searchItems(
    services: List<Service>,
    query: String
): List<Pair<Service, ServiceItem>> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return services.flatMap { service ->
        service.items.mapNotNull { item ->
            val matched = service.name.contains(q, ignoreCase = true) ||
                item.name.contains(q, ignoreCase = true) ||
                item.apiUrl.contains(q, ignoreCase = true) ||
                item.keys.any {
                    it.value.contains(q, ignoreCase = true) || it.note.contains(q, ignoreCase = true)
                }
            if (matched) service to item else null
        }
    }
}

@Composable
private fun ServiceCard(
    service: Service,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
        showIndication = true,
        onClick = onClick
    ) {
        Text(
            text = service.name,
            style = MiuixTheme.textStyles.title2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${service.items.size} 个配置项",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
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
    }
}

@Composable
private fun SearchResultCard(
    service: Service,
    item: ServiceItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
        showIndication = true,
        onClick = onClick
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
            Text(
                text = service.name,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (item.apiUrl.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.apiUrl,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${item.keys.size} 个 Key",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}
