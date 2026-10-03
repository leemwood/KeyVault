package cn.lemwood.keyvault.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.lemwood.keyvault.data.model.ApiKey
import cn.lemwood.keyvault.data.model.Service
import cn.lemwood.keyvault.data.model.ServiceItem
import cn.lemwood.keyvault.data.model.VaultProfile
import cn.lemwood.keyvault.data.repository.VaultRepository
import cn.lemwood.keyvault.data.serializer.ProfileCodec
import cn.lemwood.keyvault.platform.createVaultDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class VaultViewModel : ViewModel() {

    private val repository = VaultRepository(createVaultDataStore())

    private val _profiles = MutableStateFlow<List<VaultProfile>>(emptyList())
    val profiles: StateFlow<List<VaultProfile>> = _profiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow("")
    val activeProfileId: StateFlow<String> = _activeProfileId.asStateFlow()

    val activeProfile: StateFlow<VaultProfile?> =
        combine(_profiles, _activeProfileId) { list, id -> list.find { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 当前激活配置里的服务列表 —— 现有各页面都读这个，切换配置后自动跟着变。 */
    val services: StateFlow<List<Service>> =
        combine(_profiles, _activeProfileId) { list, id -> list.find { it.id == id }?.services.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
            val snapshot = repository.snapshotFlow.first()
            _profiles.value = snapshot.profiles
            _activeProfileId.value = snapshot.activeId
            _isLoading.value = false
            loaded.complete(Unit)
        }
    }

    override fun onCleared() {
        flush()
        super.onCleared()
    }

    /**
     * 兜底落盘：取消防抖窗口内的待写入任务并立即同步写一次，防止最后 500ms 的修改丢失。
     * 桌面端 composition 销毁时也会走到这里（见 KeyVaultApp 的 DisposableEffect）。
     */
    fun flush() {
        saveJob?.cancel()
        if (!loaded.isCompleted) return
        runCatching {
            runBlocking {
                withContext(NonCancellable) {
                    repository.save(VaultRepository.Snapshot(_profiles.value, _activeProfileId.value))
                }
            }
        }
    }

    // ---------- 配置（档案）管理 ----------

    /** 切换当前配置。立即落盘，避免下次启动回到旧配置。 */
    fun switchProfile(profileId: String) {
        if (_profiles.value.none { it.id == profileId }) return
        _activeProfileId.value = profileId
        persistNow()
    }

    /** 新建一份空配置（或带初始数据），并切换到它。同名返回 false。 */
    fun addProfile(name: String, services: List<Service> = emptyList()): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false
        if (_profiles.value.any { it.name == trimmed }) return false
        val profile = VaultProfile(name = trimmed, services = services)
        _profiles.value = _profiles.value + profile
        _activeProfileId.value = profile.id
        persistNow()
        return true
    }

    fun renameProfile(profileId: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false
        if (_profiles.value.any { it.id != profileId && it.name == trimmed }) return false
        _profiles.value = _profiles.value.map {
            if (it.id == profileId) it.copy(name = trimmed) else it
        }
        persistNow()
        return true
    }

    /** 删除配置；不允许删掉最后一份，删的是当前配置时自动切到第一份。 */
    fun deleteProfile(profileId: String): Boolean {
        val current = _profiles.value
        if (current.size <= 1) return false
        val remaining = current.filter { it.id != profileId }
        if (remaining.size == current.size) return false
        _profiles.value = remaining
        if (_activeProfileId.value == profileId) {
            _activeProfileId.value = remaining.first().id
        }
        persistNow()
        return true
    }

    /**
     * 从备份内容导入。约定是「新增一份配置」而不是覆盖现有数据，
     * 这样导入别人的备份不会把本地数据冲掉，用户可自行切换或删除。
     *
     * @return 导入后的配置名，null 表示内容无法解析
     */
    fun importBackup(source: String, preferredName: String? = null): String? {
        val decoded = ProfileCodec.decodeBackup(source) ?: return null
        val name = uniqueName(preferredName?.trim()?.takeIf { it.isNotBlank() } ?: decoded.name)
        val profile = VaultProfile(name = name, services = decoded.services)
        _profiles.value = _profiles.value + profile
        _activeProfileId.value = profile.id
        persistNow()
        return name
    }

    /** 导出当前配置成备份文件内容。 */
    fun exportActiveProfile(): String {
        val active = _profiles.value.find { it.id == _activeProfileId.value }
            ?: return ProfileCodec.encodeBackup(VaultProfile(name = ProfileCodec.defaultProfileName()))
        return ProfileCodec.encodeBackup(active)
    }

    fun exportProfile(profileId: String): String? {
        val profile = _profiles.value.find { it.id == profileId } ?: return null
        return ProfileCodec.encodeBackup(profile)
    }

    private fun uniqueName(base: String): String {
        val names = _profiles.value.map { it.name }.toHashSet()
        if (base !in names) return base
        var index = 2
        while ("$base ($index)" in names) index++
        return "$base ($index)"
    }

    private fun persistNow() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            try {
                repository.save(VaultRepository.Snapshot(_profiles.value, _activeProfileId.value))
            } catch (e: Exception) {
                _saveError.emit("保存失败，请重试")
            }
        }
    }

    // ---------- 服务 / 配置项 / Key ----------

    fun addService(name: String): Boolean {
        if (currentServices().any { it.name == name }) return false
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
        if (currentServices().any { it.id != serviceId && it.name == name }) return false
        update { current ->
            current.map { if (it.id == serviceId) it.copy(name = name) else it }
        }
        return true
    }

    fun addItem(serviceId: String, itemName: String): Boolean {
        val service = currentServices().find { it.id == serviceId } ?: return false
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

    /** 当前激活配置下的服务列表（同步读取，供重名校验等即时判断使用）。 */
    private fun currentServices(): List<Service> =
        _profiles.value.find { it.id == _activeProfileId.value }?.services.orEmpty()

    /**
     * 对「当前配置的服务列表」应用变换：内存即时更新，磁盘写入防抖 500ms。
     * 切换配置时因为改的是 activeId，这里的操作自然落在新配置上。
     */
    private fun update(block: (List<Service>) -> List<Service>) {
        viewModelScope.launch {
            loaded.await()
            val activeId = _activeProfileId.value
            val newList = block(currentServices())
            _profiles.value = _profiles.value.map { profile ->
                if (profile.id == activeId) profile.copy(services = newList) else profile
            }
            scheduleSave()
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            try {
                repository.save(VaultRepository.Snapshot(_profiles.value, _activeProfileId.value))
            } catch (e: Exception) {
                _saveError.emit("保存失败，请重试")
            }
        }
    }

    /** 备份文件默认名（供导出时预填文件名）。 */
    fun activeBackupFileName(): String {
        val active = _profiles.value.find { it.id == _activeProfileId.value }
        return ProfileCodec.backupFileName(active?.name ?: ProfileCodec.defaultProfileName())
    }

    /** 生成新配置的默认名：配置 2、配置 3……保证不重名。 */
    fun suggestedProfileName(): String {
        var index = _profiles.value.size + 1
        val names = _profiles.value.map { it.name }.toHashSet()
        while ("配置 $index" in names) index++
        return "配置 $index"
    }
}
