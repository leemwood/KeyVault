package cn.lemwood.keyvault.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventState
import cn.lemwood.keyvault.platform.NoOpVaultFileIo
import cn.lemwood.keyvault.platform.VaultFileIo
import cn.lemwood.keyvault.ui.screen.AboutScreen
import cn.lemwood.keyvault.ui.screen.HomeScreen
import cn.lemwood.keyvault.ui.screen.ItemDetailScreen
import cn.lemwood.keyvault.ui.screen.ProfileManagerScreen
import cn.lemwood.keyvault.ui.screen.ServiceDetailScreen
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 全平台共享的应用根 composable。
 *
 * @param onSecureScreenChange Android 上用于切换 FLAG_SECURE；桌面端无需处理（默认空实现）
 * @param onSystemBarsAppearanceChange Android 上用于让状态栏/导航栏图标跟随亮暗（true = 当前是深色）
 * @param fileIo 备份文件的读写通道（导出/导入），桌面端与 Android 各有一套实现
 * @param navigationEventDispatcherOwner 返回事件源。Android 端**必须**传 Activity（ComponentActivity
 *        自 1.12.0 起实现 NavigationEventDispatcherOwner，它才是 OnBackInvokedDispatcher 的真正入口）；
 *        不传则退回 composition 内的孤立 dispatcher —— 那东西收不到系统返回事件，按返回键会直接退出 App。
 *        桌面端没有系统返回手势，用孤立 dispatcher 即可。
 */
@Composable
fun KeyVaultApp(
    onSecureScreenChange: (Boolean) -> Unit = {},
    onSystemBarsAppearanceChange: (Boolean) -> Unit = {},
    fileIo: VaultFileIo = NoOpVaultFileIo,
    navigationEventDispatcherOwner: NavigationEventDispatcherOwner? = null
) {
    val viewModel = rememberVaultViewModel()

    DisposableEffect(viewModel) {
        onDispose { viewModel.flush() }
    }

    // 跟随系统亮暗：系统切换深色模式时 uiMode 变化会触发重组，这里把结果回传给平台层，
    // 让 Android 的状态栏/导航栏图标颜色同步（否则深色界面配深色图标会看不见）
    val isDarkTheme = isSystemInDarkTheme()
    DisposableEffect(isDarkTheme) {
        onSystemBarsAppearanceChange(isDarkTheme)
        onDispose { }
    }

    // miuix 的 SuperDialog 内部依赖 NavigationEventDispatcher 处理返回关闭，必须提供。
    // Android 优先用 Activity 的 owner（能收到真实返回事件）；桌面端没有，用 composition 内的孤立实例。
    val fallbackOwner = rememberNavigationEventDispatcherOwner(parent = null)
    val dispatcherOwner = navigationEventDispatcherOwner ?: fallbackOwner
    // 显式声明跟随系统：ThemeController 在 ColorSchemeMode.System 下会读 isSystemInDarkTheme()，
    // 系统切换亮/暗时 uiMode 变化触发重组，配色自动跟随。不写死 Light/Dark，用户也没有开关。
    val themeController = remember { ThemeController(colorSchemeMode = ColorSchemeMode.System) }
    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides dispatcherOwner) {
        MiuixTheme(themeController) {
            val services by viewModel.services.collectAsState()
            val isLoading by viewModel.isLoading.collectAsState()
            val profiles by viewModel.profiles.collectAsState()
            val activeProfileId by viewModel.activeProfileId.collectAsState()
            var selectedServiceId by rememberSaveable { mutableStateOf<String?>(null) }
            var selectedItemId by rememberSaveable { mutableStateOf<String?>(null) }
            var showAbout by rememberSaveable { mutableStateOf(false) }
            var showProfileManager by rememberSaveable { mutableStateOf(false) }
            val selectedService = selectedServiceId?.let { id -> services.find { it.id == id } }
            val selectedItem = selectedItemId?.let { id -> selectedService?.items?.find { it.id == id } }

            // 只用「一个」返回处理器并按当前层级决定怎么退，不要每层各注册一个：
            // 多个 NavigationBackHandler 同时启用时事件会被分发多次，实际表现是返回键直接退出 App。
            val canGoBack = showProfileManager || showAbout ||
                selectedItemId != null || selectedServiceId != null
            NavigationBackHandler(
                state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
                isBackEnabled = canGoBack,
                onBackCompleted = {
                    when {
                        showProfileManager -> showProfileManager = false
                        showAbout -> showAbout = false
                        selectedItemId != null -> selectedItemId = null
                        selectedServiceId != null -> selectedServiceId = null
                    }
                }
            )

            // 首页允许截图；一旦进入二级/三级页面（含 key 明文）即请求平台禁止截屏
            val deepPage = selectedServiceId != null
            DisposableEffect(deepPage) {
                onSecureScreenChange(deepPage)
                onDispose { onSecureScreenChange(false) }
            }

            // 用层级 id 作转场 key，避免编辑导致对象变化触发重复转场
            val screenKey = when {
                showProfileManager -> "profiles"
                showAbout -> "about"
                selectedItem != null -> "item:${selectedItem.id}"
                selectedService != null -> "service:${selectedService.id}"
                else -> "home"
            }

            Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
                AnimatedContent(
                    targetState = screenKey,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "screen_transition"
                ) { key ->
                    when {
                        key == "profiles" -> {
                            ProfileManagerScreen(
                                viewModel = viewModel,
                                fileIo = fileIo,
                                contentPadding = paddingValues,
                                onBack = { showProfileManager = false }
                            )
                        }

                        key == "about" -> {
                            AboutScreen(
                                contentPadding = paddingValues,
                                onBack = { showAbout = false }
                            )
                        }

                        key.startsWith("item:") && selectedService != null && selectedItem != null -> {
                            val s = selectedService
                            val it = selectedItem
                            ItemDetailScreen(
                                service = s,
                                item = it,
                                contentPadding = paddingValues,
                                onBack = { selectedItemId = null },
                                onUpdateApiUrl = { url ->
                                    viewModel.updateApiUrl(s.id, it.id, url)
                                },
                                onAddKey = { value ->
                                    viewModel.addKey(s.id, it.id, value)
                                },
                                onDeleteKey = { keyId ->
                                    viewModel.deleteKey(s.id, it.id, keyId)
                                },
                                onUpdateKeyValue = { keyId, value ->
                                    viewModel.updateKeyValue(s.id, it.id, keyId, value)
                                },
                                onUpdateKeyNote = { keyId, note ->
                                    viewModel.updateKeyNote(s.id, it.id, keyId, note)
                                }
                            )
                        }

                        key.startsWith("service:") && selectedService != null -> {
                            ServiceDetailScreen(
                                service = selectedService,
                                contentPadding = paddingValues,
                                messages = viewModel.saveError,
                                onBack = { selectedServiceId = null },
                                onItemClick = { selectedItemId = it.id },
                                onAddItem = { viewModel.addItem(selectedService.id, it) },
                                onDeleteItem = { viewModel.deleteItem(selectedService.id, it) },
                                onUpdateItemName = { id, name ->
                                    viewModel.updateItemName(selectedService.id, id, name)
                                }
                            )
                        }

                        else -> {
                            HomeScreen(
                                services = services,
                                isLoading = isLoading,
                                contentPadding = paddingValues,
                                messages = viewModel.saveError,
                                profiles = profiles,
                                activeProfileId = activeProfileId,
                                onServiceClick = { selectedServiceId = it.id },
                                onAddService = { viewModel.addService(it) },
                                onDeleteService = { viewModel.deleteService(it.id) },
                                onUpdateServiceName = { id, name ->
                                    viewModel.updateServiceName(id, name)
                                },
                                onSwitchProfile = { viewModel.switchProfile(it) },
                                onManageProfiles = { showProfileManager = true },
                                onAboutClick = { showAbout = true }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 桌面端没有 ViewModelStoreOwner，退化成 composition 级别的 remember；
 * Android 端正常走 Activity 的 ViewModelStore，横竖屏切换不丢状态。
 */
@Composable
fun rememberVaultViewModel(): VaultViewModel {
    val owner = LocalViewModelStoreOwner.current
    return if (owner != null) viewModel(owner) else androidx.compose.runtime.remember { VaultViewModel() }
}
