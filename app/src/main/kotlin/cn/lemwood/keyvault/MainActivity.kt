package cn.lemwood.keyvault

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import cn.lemwood.keyvault.ui.VaultViewModel
import cn.lemwood.keyvault.ui.screen.HomeScreen
import cn.lemwood.keyvault.ui.screen.ServiceDetailScreen
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        enableEdgeToEdge()
        setContent {
            KeyVaultApp()
        }
    }
}

@Composable
fun KeyVaultApp(viewModel: VaultViewModel = viewModel()) {
    // miuix 的 SuperDialog 内部依赖 NavigationEventDispatcher 处理返回关闭，必须提供
    val dispatcherOwner = rememberNavigationEventDispatcherOwner(parent = null)
    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides dispatcherOwner) {
    MiuixTheme {
        val services by viewModel.services.collectAsState()
        var selectedServiceId by rememberSaveable { mutableStateOf<String?>(null) }
        val selectedService = selectedServiceId?.let { id -> services.find { it.id == id } }

        BackHandler(enabled = selectedServiceId != null) {
            selectedServiceId = null
        }

        Scaffold(
            modifier = Modifier.fillMaxSize()
        ) { paddingValues ->
            AnimatedContent(
                targetState = selectedService,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "screen_transition"
            ) { service ->
            if (service == null) {
                HomeScreen(
                    services = services,
                    contentPadding = paddingValues,
                    onServiceClick = { selectedServiceId = it.id },
                    onAddService = { viewModel.addService(it) },
                    onDeleteService = { viewModel.deleteService(it.id) },
                    onUpdateServiceName = { id, name ->
                        viewModel.updateServiceName(id, name)
                    }
                )
            } else {
                ServiceDetailScreen(
                    service = service,
                    contentPadding = paddingValues,
                    onBack = { selectedServiceId = null },
                    onAddItem = { viewModel.addItem(service.id, it) },
                    onDeleteItem = { itemId -> viewModel.deleteItem(service.id, itemId) },
                    onUpdateItemName = { itemId, name ->
                        viewModel.updateItemName(service.id, itemId, name)
                    },
                    onUpdateApiUrl = { itemId, url ->
                        viewModel.updateApiUrl(service.id, itemId, url)
                    },
                    onAddKey = { itemId, value ->
                        viewModel.addKey(service.id, itemId, value)
                    },
                    onDeleteKey = { itemId, keyId ->
                        viewModel.deleteKey(service.id, itemId, keyId)
                    },
                    onUpdateKeyValue = { itemId, keyId, value ->
                        viewModel.updateKeyValue(service.id, itemId, keyId, value)
                    },
                    onUpdateKeyNote = { itemId, keyId, note ->
                        viewModel.updateKeyNote(service.id, itemId, keyId, note)
                    }
                )
            }
        }
    }
    }
    }
}
