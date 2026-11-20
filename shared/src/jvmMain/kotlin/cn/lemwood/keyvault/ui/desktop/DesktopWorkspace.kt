package cn.lemwood.keyvault.ui.desktop

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemwood.keyvault.data.model.Service
import cn.lemwood.keyvault.data.model.ServiceItem
import cn.lemwood.keyvault.ui.VaultViewModel
import cn.lemwood.keyvault.ui.components.CollectMessages
import cn.lemwood.keyvault.ui.components.MessageHost
import cn.lemwood.keyvault.ui.components.rememberMessagePresenter
import cn.lemwood.keyvault.ui.screen.searchItems
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal class DesktopSelection {
    var serviceId by mutableStateOf<String?>(null)
    var itemId by mutableStateOf<String?>(null)
}

@Composable
internal fun DesktopWorkspace(
    services: List<Service>,
    loading: Boolean,
    compact: Boolean,
    profileName: String?,
    selection: DesktopSelection,
    searchFocus: FocusRequester,
    viewModel: VaultViewModel,
    modifier: Modifier,
) {
    val colors = LocalDesktopColors.current
    val presenter = rememberMessagePresenter()
    CollectMessages(viewModel.saveError, presenter)
    var query by remember { mutableStateOf("") }
    var addingService by remember { mutableStateOf(false) }
    var createdServiceName by remember { mutableStateOf<String?>(null) }
    val selectedService = services.find { it.id == selection.serviceId } ?: services.firstOrNull()
    val requestedItemId = selection.itemId.takeIf { selectedService?.id == selection.serviceId }

    LaunchedEffect(services, createdServiceName) {
        val name = createdServiceName ?: return@LaunchedEffect
        val created = services.find { it.name == name } ?: return@LaunchedEffect
        selection.serviceId = created.id
        selection.itemId = null
        query = ""
        createdServiceName = null
    }

    fun addService() {
        addingService = true
    }

    Box(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            DesktopServiceList(
                services = services,
                loading = loading,
                query = query,
                onQuery = { query = it },
                selectedServiceId = selectedService?.id,
                selectedItemId = requestedItemId,
                profileName = profileName.takeIf { compact },
                searchFocus = searchFocus,
                onService = { service, item ->
                    selection.serviceId = service.id
                    selection.itemId = item?.id
                },
                onAdd = ::addService,
                modifier = Modifier.width(if (compact) 202.dp else 269.dp).fillMaxHeight(),
            )
            Box(Modifier.fillMaxHeight().width(1.dp).background(colors.line))
            Box(Modifier.weight(1f).fillMaxHeight().background(colors.canvas)) {
                if (selectedService != null) {
                    key(selectedService.id) {
                        DesktopServiceDetail(
                            service = selectedService,
                            requestedItemId = requestedItemId,
                            onItemSelected = {
                                selection.serviceId = selectedService.id
                                selection.itemId = it
                            },
                            viewModel = viewModel,
                            presenter = presenter,
                        )
                    }
                } else {
                    DesktopEmpty(
                        if (loading) Icons.Outlined.Layers else Icons.Outlined.Dns,
                        if (loading) "正在加载" else "添加第一个服务",
                        if (loading) "正在读取当前配置。" else "在服务下管理接口地址和 API Keys。",
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        if (!loading) DesktopButton("添加服务", ::addService, icon = Icons.Outlined.Add, primary = true)
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter)) { MessageHost(presenter) }
    }

    if (addingService) {
        DesktopEditDialog("添加服务", "服务名称", onDismiss = { addingService = false }) { name, _ ->
            if (viewModel.addService(name)) {
                createdServiceName = name
                addingService = false
                null
            } else {
                "已存在同名服务"
            }
        }
    }
}

@Composable
private fun DesktopServiceList(
    services: List<Service>,
    loading: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    selectedServiceId: String?,
    selectedItemId: String?,
    profileName: String?,
    searchFocus: FocusRequester,
    onService: (Service, ServiceItem?) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier,
) {
    val colors = LocalDesktopColors.current
    val searching = query.isNotBlank()
    val matches = remember(services, query) { searchItems(services, query) }
    val matchingServices = remember(services, query) {
        if (searching) services.filter { it.name.contains(query.trim(), ignoreCase = true) } else services
    }
    val state = rememberLazyListState()
    LaunchedEffect(query) {
        if (searching) state.scrollToItem(0)
    }
    LaunchedEffect(searching, selectedServiceId) {
        if (!searching) {
            val index = matchingServices.indexOfFirst { it.id == selectedServiceId }
            if (index >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                state.scrollToItem(index)
            }
        }
    }
    Column(modifier.background(colors.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("服务", Modifier.semantics { heading() }, color = colors.text, style = MiuixTheme.textStyles.title3)
                profileName?.let {
                    Text(it, color = colors.muted, style = MiuixTheme.textStyles.footnote2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            DesktopButton("添加服务", onAdd, icon = Icons.Outlined.Add, enabled = !loading)
        }
        DesktopSearchField(query, onQuery, searchFocus, Modifier.padding(horizontal = 12.dp))
        Text(
            if (searching) "搜索结果" else "全部服务 · ${services.size}",
            Modifier.padding(start = 16.dp, top = 19.dp, bottom = 7.dp),
            color = colors.muted,
            style = MiuixTheme.textStyles.footnote2,
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = state,
                contentPadding = PaddingValues(start = 8.dp, end = 12.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(matchingServices, key = { "service:${it.id}" }) { service ->
                    ServiceListRow(
                        service.name,
                        "${service.items.size} 个配置项",
                        selected = service.id == selectedServiceId && (!searching || selectedItemId == null),
                        itemResult = false,
                        onClick = { onService(service, null) },
                    )
                }
                items(matches, key = { (service, item) -> service.id to item.id }) { (service, item) ->
                    ServiceListRow(
                        item.name,
                        service.name,
                        selected = service.id == selectedServiceId && item.id == selectedItemId,
                        itemResult = true,
                        onClick = { onService(service, item) },
                    )
                }
                if (searching && matchingServices.isEmpty() && matches.isEmpty()) {
                    item {
                        Text("没有匹配的服务或配置项", Modifier.padding(8.dp), color = colors.muted,
                            style = MiuixTheme.textStyles.body2)
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.fillMaxHeight().align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun DesktopSearchField(
    query: String,
    onQuery: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier,
) {
    val colors = LocalDesktopColors.current
    val shape = RoundedCornerShape(8.dp)
    var focused by remember { mutableStateOf(false) }
    val shortcut = remember { if (System.getProperty("os.name").startsWith("Mac")) "⌘F" else "Ctrl+F" }
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        modifier = modifier.fillMaxWidth().height(38.dp).focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = "搜索服务、配置项、接口地址和 Key" }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && query.isNotEmpty()) {
                    onQuery("")
                    true
                } else {
                    false
                }
            },
        textStyle = MiuixTheme.textStyles.body2.copy(color = colors.text),
        singleLine = true,
        cursorBrush = SolidColor(colors.accent),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxSize().clip(shape).background(colors.field)
                    .border(1.dp, if (focused) colors.accent else colors.line, shape).padding(start = 9.dp, end = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(Icons.Outlined.Search, null, Modifier.size(17.dp), tint = colors.muted)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("搜索", color = colors.muted, style = MiuixTheme.textStyles.body2)
                    }
                    field()
                }
                if (query.isNotEmpty()) {
                    DesktopIconButton(Icons.Outlined.Close, "清除搜索", onClick = { onQuery("") })
                } else {
                    Text(shortcut, Modifier.padding(end = 6.dp), color = colors.muted, style = MiuixTheme.textStyles.footnote2)
                }
            }
        },
    )
}

@Composable
private fun ServiceListRow(
    title: String,
    summary: String,
    selected: Boolean,
    itemResult: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalDesktopColors.current
    Row(
        Modifier.fillMaxWidth().desktopAction(onClick, selected = selected).height(58.dp).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) colors.surface else colors.field),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (itemResult) Icons.Outlined.Layers else Icons.Outlined.Dns, null, Modifier.size(18.dp),
                tint = if (selected) colors.accent else colors.muted,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = if (selected) colors.accent else colors.text, style = MiuixTheme.textStyles.body1,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(summary, color = colors.muted, style = MiuixTheme.textStyles.footnote2,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
