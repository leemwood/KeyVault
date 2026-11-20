package cn.lemwood.keyvault.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import cn.lemwood.keyvault.data.model.VaultProfile
import cn.lemwood.keyvault.platform.VaultFileIo
import cn.lemwood.keyvault.sync.LanPairing
import cn.lemwood.keyvault.sync.SyncWindowControl
import cn.lemwood.keyvault.sync.SyncWindowState
import cn.lemwood.keyvault.ui.VaultViewModel
import cn.lemwood.keyvault.ui.screen.AboutScreen
import cn.lemwood.keyvault.ui.screen.PairingScreen
import cn.lemwood.keyvault.ui.screen.ProfileManagerScreen
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class DesktopPage(val label: String, val icon: ImageVector) {
    Vault("密钥库", Icons.Outlined.Key),
    Profiles("配置与备份", Icons.Outlined.FolderOpen),
    Sync("局域网同步", Icons.Outlined.Sync),
    About("关于", Icons.Outlined.Info),
}

@Composable
fun DesktopVaultApp(
    viewModel: VaultViewModel,
    fileIo: VaultFileIo,
    pairing: LanPairing,
    syncWindow: SyncWindowControl,
) {
    DisposableEffect(viewModel) { onDispose { viewModel.flush() } }
    val isDark = isSystemInDarkTheme()
    val colors = remember(isDark) { DesktopColors(isDark) }
    val dispatcher = rememberNavigationEventDispatcherOwner(parent = null)
    CompositionLocalProvider(
        LocalDesktopColors provides colors,
        LocalNavigationEventDispatcherOwner provides dispatcher,
    ) {
        MiuixTheme(colors.miuix) {
            val profiles by viewModel.profiles.collectAsState()
            val activeId by viewModel.activeProfileId.collectAsState()
            val isLoading by viewModel.isLoading.collectAsState()
            val syncStatus by syncWindow.state.collectAsState()
            val isImporting by viewModel.isImporting.collectAsState()
            var page by remember { mutableStateOf(DesktopPage.Vault) }
            var chooseProfile by remember { mutableStateOf(false) }
            var pendingImport by remember { mutableStateOf<VaultProfile?>(null) }
            var showTransfer by remember { mutableStateOf(false) }
            val selection = remember(activeId) { DesktopSelection() }
            val windowFocus = remember { FocusRequester() }
            val searchFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { windowFocus.requestFocus() }
            // Keep the active profile and pane data from the same snapshot when profiles switch.
            val profile = profiles.find { it.id == activeId }
            fun navigate(destination: DesktopPage) {
                if (destination == page || isImporting) return
                pendingImport = null
                showTransfer = false
                page = destination
            }
            fun switchProfile(id: String) {
                viewModel.switchProfile(id)
                chooseProfile = false
                navigate(DesktopPage.Vault)
            }
            val returnToVault = { navigate(DesktopPage.Vault) }

            BoxWithConstraints(
                Modifier.fillMaxSize().background(colors.canvas).onPreviewKeyEvent { event ->
                    if (page == DesktopPage.Vault && event.type == KeyEventType.KeyDown &&
                        event.key == Key.F && (event.isCtrlPressed || event.isMetaPressed)
                    ) {
                        searchFocus.requestFocus()
                        true
                    } else {
                        false
                    }
                }.focusRequester(windowFocus).focusable(),
            ) {
                val compact = maxWidth < 1000.dp
                Row(Modifier.fillMaxSize()) {
                    DesktopSidebar(
                        compact = compact,
                        page = page,
                        profiles = profiles,
                        activeId = activeId,
                        syncStatus = syncStatus,
                        enabled = !isImporting,
                        onPage = ::navigate,
                        onProfile = ::switchProfile,
                        onChooseProfile = { chooseProfile = true },
                    )
                    Box(Modifier.fillMaxHeight().width(1.dp).background(colors.line))
                    if (page == DesktopPage.Vault) {
                        key(activeId) {
                            DesktopWorkspace(
                                services = profile?.services.orEmpty(),
                                loading = isLoading,
                                compact = compact,
                                profileName = profile?.name,
                                selection = selection,
                                searchFocus = searchFocus,
                                viewModel = viewModel,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        Box(
                            Modifier.weight(1f).fillMaxHeight().background(MiuixTheme.colorScheme.background),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Surface(
                                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                                color = MiuixTheme.colorScheme.background,
                                contentColor = MiuixTheme.colorScheme.onBackground,
                            ) {
                                when (page) {
                                    DesktopPage.Vault -> Unit
                                    DesktopPage.Profiles -> ProfileManagerScreen(
                                        viewModel = viewModel,
                                        fileIo = fileIo,
                                        contentPadding = PaddingValues(),
                                        pendingImport = pendingImport,
                                        onPreview = { pendingImport = it },
                                        onPairing = null,
                                        onBack = returnToVault,
                                    )
                                    DesktopPage.Sync -> {
                                        val conflicts by viewModel.replicaConflicts.collectAsState(initial = emptyMap())
                                        val history by viewModel.conflictHistory.collectAsState(initial = emptyMap())
                                        PairingScreen(
                                            pairing = pairing,
                                            insets = PaddingValues(),
                                            profiles = profiles,
                                            activeProfileId = activeId,
                                            store = viewModel.transferStore,
                                            incrementalStore = viewModel.incrementalStore,
                                            replicaConflicts = conflicts,
                                            conflictHistory = history,
                                            clearConflictHistory = viewModel::clearConflictHistory,
                                            syncWindow = syncWindow,
                                            resolveConflict = viewModel::resolveReplicaConflict,
                                            showTransfer = showTransfer,
                                            onShowTransfer = { showTransfer = it },
                                            onBack = returnToVault,
                                        )
                                    }
                                    DesktopPage.About -> AboutScreen(PaddingValues(), returnToVault)
                                }
                            }
                        }
                    }
                }
            }

            if (chooseProfile) {
                DesktopDialog("切换配置", onDismiss = { chooseProfile = false }) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                        items(profiles, key = { it.id }) { entry ->
                            SidebarRow(
                                label = entry.name,
                                icon = Icons.Outlined.Layers,
                                selected = entry.id == activeId,
                                onClick = { switchProfile(entry.id) },
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        DesktopButton("取消", onClick = { chooseProfile = false })
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopSidebar(
    compact: Boolean,
    page: DesktopPage,
    profiles: List<VaultProfile>,
    activeId: String,
    syncStatus: SyncWindowState,
    enabled: Boolean,
    onPage: (DesktopPage) -> Unit,
    onProfile: (String) -> Unit,
    onChooseProfile: () -> Unit,
) {
    val colors = LocalDesktopColors.current
    val syncing = syncStatus == SyncWindowState.STARTING || syncStatus == SyncWindowState.RUNNING
    Column(
        Modifier.width(if (compact) 62.dp else 208.dp).fillMaxHeight().background(colors.sidebar)
            .padding(horizontal = if (compact) 8.dp else 14.dp, vertical = 16.dp),
        horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Row(
            modifier = Modifier.padding(start = if (compact) 0.dp else 6.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Key, contentDescription = if (compact) "KeyVault" else null,
                    modifier = Modifier.size(20.dp), tint = colors.onAccent)
            }
            if (!compact) Text("KeyVault", color = colors.text, style = MiuixTheme.textStyles.title3)
        }

        for (destination in DesktopPage.entries) {
            val syncActive = destination == DesktopPage.Sync && syncing
            val title = if (syncActive) "局域网同步（运行中）" else destination.label
            if (compact) {
                Box {
                    DesktopIconButton(
                        destination.icon, title, onClick = { onPage(destination) },
                        selected = page == destination, enabled = enabled,
                    )
                    if (syncActive) SyncDot(Modifier.align(Alignment.TopEnd).padding(2.dp))
                }
            } else {
                SidebarRow(destination.label, destination.icon, page == destination, enabled, syncActive) {
                    onPage(destination)
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Spacer(Modifier.height(14.dp))
        if (compact) {
            DesktopIconButton(
                Icons.Outlined.Layers, "切换配置", onChooseProfile, enabled = enabled && profiles.isNotEmpty(),
            )
        } else {
            Text("配置", color = colors.muted, style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier.padding(start = 9.dp, bottom = 6.dp).semantics { heading() })
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    SidebarRow(
                        label = profile.name,
                        icon = Icons.Outlined.Layers,
                        selected = profile.id == activeId,
                        enabled = enabled,
                        onClick = { onProfile(profile.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SidebarRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean = true,
    syncActive: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalDesktopColors.current
    val tint = when {
        !enabled -> colors.muted
        selected -> colors.accent
        else -> colors.text
    }
    Row(
        Modifier.fillMaxWidth().desktopAction(onClick, selected = selected, enabled = enabled)
            .height(38.dp).padding(horizontal = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(17.dp), tint = tint)
        Text(label, Modifier.weight(1f), color = tint, style = MiuixTheme.textStyles.body2,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (syncActive) SyncDot(Modifier.semantics { contentDescription = "后台同步运行中" })
    }
}

@Composable
private fun SyncDot(modifier: Modifier) {
    Box(modifier.size(7.dp).clip(CircleShape).background(LocalDesktopColors.current.active))
}
