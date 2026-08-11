package cn.lemwood.keyvault

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import cn.lemwood.keyvault.ui.VaultViewModel
import cn.lemwood.keyvault.ui.screen.AboutScreen
import cn.lemwood.keyvault.ui.screen.HomeScreen
import cn.lemwood.keyvault.ui.screen.ItemDetailScreen
import cn.lemwood.keyvault.ui.screen.ServiceDetailScreen
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FLAG_SECURE 改为按导航层级动态切换（见 KeyVaultApp），首页可截图，二/三级页面禁截
        enableEdgeToEdge()
        setContent {
            KeyVaultApp()
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
fun KeyVaultApp(viewModel: VaultViewModel = viewModel()) {
    // miuix 的 SuperDialog 内部依赖 NavigationEventDispatcher 处理返回关闭，必须提供
    val dispatcherOwner = rememberNavigationEventDispatcherOwner(parent = null)
    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides dispatcherOwner) {
        MiuixTheme {
            val services by viewModel.services.collectAsState()
            var selectedServiceId by rememberSaveable { mutableStateOf<String?>(null) }
            var selectedItemId by rememberSaveable { mutableStateOf<String?>(null) }
            var showAbout by rememberSaveable { mutableStateOf(false) }
            val selectedService = selectedServiceId?.let { id -> services.find { it.id == id } }
            val selectedItem = selectedItemId?.let { id -> selectedService?.items?.find { it.id == id } }

            BackHandler(enabled = showAbout) { showAbout = false }
            BackHandler(enabled = selectedItemId != null) {
                selectedItemId = null
            }
            BackHandler(enabled = selectedServiceId != null && selectedItemId == null) {
                selectedServiceId = null
            }

            // 首页可截图；一旦进入二级/三级页面（含 key 明文）即启用 FLAG_SECURE
            val context = LocalContext.current
            LaunchedEffect(selectedServiceId != null) {
                val window = context.activity()?.window ?: return@LaunchedEffect
                if (selectedServiceId == null) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                }
            }

            // 用层级 id 作转场 key，避免编辑导致对象变化触发重复转场
            val screenKey = when {
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
                                contentPadding = paddingValues,
                                onServiceClick = { selectedServiceId = it.id },
                                onAddService = { viewModel.addService(it) },
                                onDeleteService = { viewModel.deleteService(it.id) },
                                onUpdateServiceName = { id, name ->
                                    viewModel.updateServiceName(id, name)
                                },
                                onAboutClick = { showAbout = true }
                            )
                        }
                    }
                }
            }
        }
    }
}
