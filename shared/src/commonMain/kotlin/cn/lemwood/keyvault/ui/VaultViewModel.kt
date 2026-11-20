package cn.lemwood.keyvault.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.lemwood.keyvault.data.backup.ImportPlan
import cn.lemwood.keyvault.data.backup.ImportStrategy
import cn.lemwood.keyvault.data.backup.uniqueImportName
import cn.lemwood.keyvault.data.model.ApiKey
import cn.lemwood.keyvault.data.model.Service
import cn.lemwood.keyvault.data.model.ServiceItem
import cn.lemwood.keyvault.data.model.VaultProfile
import cn.lemwood.keyvault.data.repository.VaultRepository
import cn.lemwood.keyvault.platform.createVaultDataStore
import cn.lemwood.keyvault.sync.TransferReceipt
import cn.lemwood.keyvault.sync.TransferStatus
import cn.lemwood.keyvault.sync.TransferStore
import cn.lemwood.keyvault.sync.IncrementalStore
import cn.lemwood.keyvault.sync.ReplicaConflict
import cn.lemwood.keyvault.sync.ReplicaSnapshot
import cn.lemwood.keyvault.sync.ReplicaUpdate
import cn.lemwood.keyvault.sync.TransferException
import cn.lemwood.keyvault.sync.TransferProblem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class ImportResult { SAVED, STALE_PREVIEW, SAVE_FAILED }

class VaultViewModel constructor(private val repository: VaultRepository) : ViewModel() {
    constructor() : this(VaultRepository(createVaultDataStore()))


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
    private val importLock = Mutex()
    private val _isImporting = MutableStateFlow(false)
    internal val isImporting = _isImporting.asStateFlow()
    internal val replicaConflicts = repository.replicaConflicts
    internal val conflictHistory = repository.conflictHistory
    internal suspend fun clearConflictHistory(profileId: String) = repository.clearConflictHistory(profileId)
    val incrementalStore: IncrementalStore = object : IncrementalStore {
        override suspend fun replica(profileId: String): ReplicaSnapshot = withContext(Dispatchers.Main.immediate) {
            loaded.await()
            importLock.withLock {
                commitProfiles { repository.captureReplica(VaultRepository.Snapshot(_profiles.value, _activeProfileId.value), profileId) }
            }
        }

        override suspend fun status(receipt: TransferReceipt) = repository.transferStatus(receipt)

        override suspend fun syncBindings() = repository.syncBindings()

        override suspend fun applyReplica(expected: ReplicaSnapshot, peerProfile: String, update: ReplicaUpdate, receipt: TransferReceipt) {
            withContext(Dispatchers.Main.immediate) {
                importLock.withLock {
                    if (_profiles.value.none { it == expected.profile }) throw TransferException(TransferProblem.STALE)
                    commitProfiles { publish(repository.applyReplica(expected, peerProfile, update, receipt)) }
                }
            }
        }
    }

    internal suspend fun resolveReplicaConflict(profileId: String, conflict: ReplicaConflict, remote: Boolean) {
        withContext(Dispatchers.Main.immediate) {
            loaded.await()
            importLock.withLock {
                commitProfiles { publish(repository.resolveReplicaConflict(profileId, conflict, remote)) }
            }
        }
    }
    internal val transferStore: TransferStore = object : TransferStore {
        override suspend fun transferStatus(receipt: TransferReceipt) = repository.transferStatus(receipt)

        override suspend fun commitTransfer(receipt: TransferReceipt, profile: VaultProfile): TransferStatus =
            withContext(Dispatchers.Main.immediate) {
                loaded.await()
                importLock.withLock {
                    val current = _profiles.value
                    val added = VaultProfile(name = uniqueImportName(profile.name, current), services = profile.services)
                    val snapshot = VaultRepository.Snapshot(current + added, _activeProfileId.value)
                    commitProfiles {
                        // The receipt and the new profile are committed together, even if the socket goes away.
                        when (repository.saveReceived(snapshot, receipt)) {
                            TransferStatus.MISSING -> {
                                publish(snapshot)
                                TransferStatus.SAVED
                            }
                            TransferStatus.SAVED -> TransferStatus.SAVED
                            TransferStatus.CONFLICT -> TransferStatus.CONFLICT
                        }
                    }
                }
            }
    }

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
        if (_isImporting.value) return
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
        if (_isImporting.value) return
        if (_profiles.value.none { it.id == profileId }) return
        _activeProfileId.value = profileId
        persistNow()
    }

    /** 新建一份空配置（或带初始数据），并切换到它。同名返回 false。 */
    fun addProfile(name: String, services: List<Service> = emptyList()): Boolean {
        if (_isImporting.value) return false
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
        if (_isImporting.value) return false
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
        if (_isImporting.value) return false
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

    /** DataStore commits all three preference keys together. Publish the result only after success. */
    internal suspend fun applyImport(plan: ImportPlan): ImportResult = withContext(Dispatchers.Main.immediate) {
        loaded.await()
        importLock.withLock {
            val current = _profiles.value
            val result = plan.result
            val snapshot = if (plan.strategy == ImportStrategy.ADD) {
                if (current.any { it.name == result.name || it.id == result.id }) {
                    return@withLock ImportResult.STALE_PREVIEW
                }
                VaultRepository.Snapshot(current + result, result.id)
            } else {
                if (current.none { it == plan.target }) return@withLock ImportResult.STALE_PREVIEW
                VaultRepository.Snapshot(current.map { if (it.id == result.id) result else it }, _activeProfileId.value)
            }
            try {
                commitProfiles {
                    repository.save(snapshot)
                    publish(snapshot)
                }
                ImportResult.SAVED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ImportResult.SAVE_FAILED
            }
        }
    }

    private suspend fun <T> commitProfiles(action: suspend () -> T): T {
        _isImporting.value = true
        try {
            return withContext(NonCancellable) {
                saveJob?.join()
                action()
            }
        } finally {
            _isImporting.value = false
        }
    }

    private fun publish(snapshot: VaultRepository.Snapshot) {
        _profiles.value = snapshot.profiles
        _activeProfileId.value = snapshot.activeId
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
            importLock.withLock {
                val activeId = _activeProfileId.value
                val newList = block(currentServices())
                _profiles.value = _profiles.value.map { profile ->
                    if (profile.id == activeId) profile.copy(services = newList) else profile
                }
                scheduleSave()
            }
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

    /** 生成新配置的默认名：配置 2、配置 3……保证不重名。 */
    fun suggestedProfileName(): String {
        var index = _profiles.value.size + 1
        val names = _profiles.value.map { it.name }.toHashSet()
        while ("配置 $index" in names) index++
        return "配置 $index"
    }
}
