package cn.lemwood.keyvault.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.lemwood.keyvault.data.model.ApiKey
import cn.lemwood.keyvault.data.model.Service
import cn.lemwood.keyvault.data.model.ServiceItem
import cn.lemwood.keyvault.data.store.VaultDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class VaultViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = VaultDataStore(application.applicationContext)

    private val _services = MutableStateFlow<List<Service>>(emptyList())
    val services: StateFlow<List<Service>> = _services.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _saveError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val saveError: SharedFlow<String> = _saveError.asSharedFlow()

    // 启动竞态门闩：初始数据读取完成前，update 一律等待，避免被旧数据覆盖
    private val loaded = CompletableDeferred<Unit>()

    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            // 只读取一次作为初始数据，之后由 update 驱动内存状态，
            // 避免每次 save 后 DataStore flow 回显旧值覆盖正在编辑的内容
            _services.value = dataStore.servicesFlow.first()
            _isLoading.value = false
            loaded.complete(Unit)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 兜底最后一次保存，防止防抖窗口内的修改丢失
        saveJob?.cancel()
        if (loaded.isCompleted) {
            runBlocking {
                runCatching { dataStore.save(_services.value) }
            }
        }
    }

    fun addService(name: String): Boolean {
        if (_services.value.any { it.name == name }) return false
        update { current ->
            if (current.any { it.name == name }) current
            else current + Service(name = name)
        }
        return true
    }

    fun deleteService(serviceId: String) {
        update { current -> current.filter { it.id != serviceId } }
    }

    fun updateServiceName(serviceId: String, name: String): Boolean {
        if (name.isBlank()) return false
        if (_services.value.any { it.id != serviceId && it.name == name }) return false
        update { current ->
            current.map { if (it.id == serviceId) it.copy(name = name) else it }
        }
        return true
    }

    fun addItem(serviceId: String, itemName: String): Boolean {
        val service = _services.value.find { it.id == serviceId } ?: return false
        if (service.items.any { it.name == itemName }) return false
        update { current ->
            current.map { s ->
                if (s.id == serviceId) {
                    if (s.items.any { it.name == itemName }) s
                    else s.copy(items = s.items + ServiceItem(name = itemName))
                } else s
            }
        }
        return true
    }

    fun deleteItem(serviceId: String, itemId: String) {
        update { current ->
            current.map { service ->
                if (service.id == serviceId) {
                    service.copy(items = service.items.filter { it.id != itemId })
                } else service
            }
        }
    }

    fun updateItemName(serviceId: String, itemId: String, name: String) {
        update { current ->
            current.map { service ->
                if (service.id == serviceId) {
                    service.copy(
                        items = service.items.map { item ->
                            if (item.id == itemId) item.copy(name = name) else item
                        }
                    )
                } else service
            }
        }
    }

    fun updateApiUrl(serviceId: String, itemId: String, url: String) {
        update { current ->
            current.map { service ->
                if (service.id == serviceId) {
                    service.copy(
                        items = service.items.map { item ->
                            if (item.id == itemId) item.copy(apiUrl = url) else item
                        }
                    )
                } else service
            }
        }
    }

    fun addKey(serviceId: String, itemId: String, value: String) {
        update { current ->
            current.map { service ->
                if (service.id == serviceId) {
                    service.copy(
                        items = service.items.map { item ->
                            if (item.id == itemId) {
                                item.copy(keys = item.keys + ApiKey(value = value))
                            } else item
                        }
                    )
                } else service
            }
        }
    }

    fun deleteKey(serviceId: String, itemId: String, keyId: String) {
        update { current ->
            current.map { service ->
                if (service.id == serviceId) {
                    service.copy(
                        items = service.items.map { item ->
                            if (item.id == itemId) {
                                item.copy(keys = item.keys.filter { it.id != keyId })
                            } else item
                        }
                    )
                } else service
            }
        }
    }

    fun updateKeyValue(serviceId: String, itemId: String, keyId: String, value: String) {
        update { current ->
            current.map { service ->
                if (service.id == serviceId) {
                    service.copy(
                        items = service.items.map { item ->
                            if (item.id == itemId) {
                                item.copy(
                                    keys = item.keys.map { key ->
                                        if (key.id == keyId) key.copy(value = value) else key
                                    }
                                )
                            } else item
                        }
                    )
                } else service
            }
        }
    }

    fun updateKeyNote(serviceId: String, itemId: String, keyId: String, note: String) {
        update { current ->
            current.map { service ->
                if (service.id == serviceId) {
                    service.copy(
                        items = service.items.map { item ->
                            if (item.id == itemId) {
                                item.copy(
                                    keys = item.keys.map { key ->
                                        if (key.id == keyId) key.copy(note = note) else key
                                    }
                                )
                            } else item
                        }
                    )
                } else service
            }
        }
    }

    private fun update(block: (List<Service>) -> List<Service>) {
        viewModelScope.launch {
            loaded.await()
            // 内存即时更新，磁盘写入防抖 500ms
            val newList = block(_services.value.toList())
            _services.value = newList
            saveJob?.cancel()
            saveJob = viewModelScope.launch {
                delay(500)
                try {
                    dataStore.save(newList)
                } catch (e: Exception) {
                    _saveError.emit("保存失败，请重试")
                }
            }
        }
    }
}
