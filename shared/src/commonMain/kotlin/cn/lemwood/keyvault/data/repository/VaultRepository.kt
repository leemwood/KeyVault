package cn.lemwood.keyvault.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cn.lemwood.keyvault.data.model.VaultProfile
import cn.lemwood.keyvault.data.serializer.ProfileCodec
import cn.lemwood.keyvault.data.serializer.VaultCodec
import cn.lemwood.keyvault.sync.TransferReceipt
import cn.lemwood.keyvault.sync.TransferStatus
import cn.lemwood.keyvault.sync.ReplicaConflict
import cn.lemwood.keyvault.sync.ReplicaIndex
import cn.lemwood.keyvault.sync.ReplicaMetadata
import cn.lemwood.keyvault.sync.ReplicaSnapshot
import cn.lemwood.keyvault.sync.ReplicaUpdate
import cn.lemwood.keyvault.sync.ResolvedReplicaConflict
import cn.lemwood.keyvault.sync.SyncBinding
import cn.lemwood.keyvault.sync.TransferException
import cn.lemwood.keyvault.sync.TransferProblem
import cn.lemwood.keyvault.sync.applyReplicaValues
import cn.lemwood.keyvault.sync.bindings
import cn.lemwood.keyvault.sync.recent
import cn.lemwood.keyvault.sync.revokedPeersKey
import cn.lemwood.keyvault.sync.recordChanges
import cn.lemwood.keyvault.sync.resolveConflict
import cn.lemwood.keyvault.sync.snapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * 配置（档案）仓储。
 *
 * 磁盘格式演进：
 * - v1.0：只有一个 `services` 键。
 * - v1.1 起：`profiles`（配置数组）+ `active_profile_id`（当前激活）。
 *   读取时若 `profiles` 不存在，就把旧的 `services` 整体迁移成名为「默认配置」的一份配置，
 *   因此老用户升级后数据原样可见，无需任何额外迁移代码。
 *
 * 保存时仍会顺带把激活配置的 services 写回旧键，万一用户降级到旧版本也不会读到空数据。
 */
class VaultRepository(private val dataStore: DataStore<Preferences>) {

    data class Snapshot(
        val profiles: List<VaultProfile>,
        val activeId: String
    )

    private val profilesKey = stringPreferencesKey("profiles")
    private val activeIdKey = stringPreferencesKey("active_profile_id")
    private val legacyServicesKey = stringPreferencesKey("services")
    private val replicaKey = stringPreferencesKey("lan_replica_index_v1")
    private val replicaJson = Json { allowStructuredMapKeys = true }

    val snapshotFlow: Flow<Snapshot> = dataStore.data.map { it.readSnapshot() }
    internal val replicaConflicts = dataStore.data.map { prefs ->
        prefs.readIndex().profiles.mapValues { it.value.conflicts }
    }
    internal val conflictHistory = dataStore.data.map { prefs -> prefs.readIndex().profiles.mapValues { it.value.history.recent() } }

    internal suspend fun syncBindings(): List<SyncBinding> = dataStore.data.first().readIndex().profiles.flatMap { (id, state) ->
        state.bindings().map { (peer, remote) -> SyncBinding(id, peer, remote) }
    }

    internal suspend fun clearConflictHistory(profileId: String) {
        dataStore.edit { prefs ->
            val prior = prefs.readIndex()
            val metadata = prior.profiles[profileId] ?: return@edit
            prefs[replicaKey] = replicaJson.encodeToString(prior.copy(
                profiles = prior.profiles + (profileId to metadata.copy(history = emptyList())),
            ))
        }
    }

    private fun Preferences.readSnapshot(): Snapshot {
        val stored = this[profilesKey]?.let { ProfileCodec.decodeProfiles(it) }
        return if (!stored.isNullOrEmpty()) {
            val activeId = this[activeIdKey]
                ?.takeIf { id -> stored.any { it.id == id } }
                ?: stored.first().id
            Snapshot(stored, activeId)
        } else {
            // 迁移分支：旧版只有 services 键；全新安装时同样落到这里，得到一个空的默认配置
            val legacy = this[legacyServicesKey]?.let { raw ->
                runCatching { VaultCodec.decode(raw) }.getOrDefault(emptyList())
            } ?: emptyList()
            val migrated = VaultProfile(
                id = LEGACY_PROFILE_ID,
                name = ProfileCodec.defaultProfileName(),
                services = legacy
            )
            Snapshot(listOf(migrated), migrated.id)
        }
    }

    internal suspend fun captureReplica(current: Snapshot, profileId: String): ReplicaSnapshot {
        val profile = current.profiles.firstOrNull { it.id == profileId }
            ?: throw TransferException(TransferProblem.STALE)
        val prefs = dataStore.edit { it.writeSnapshot(current) }
        return prefs.readIndex().snapshot(profile)
    }

    internal suspend fun applyReplica(
        expected: ReplicaSnapshot, peerProfile: String, update: ReplicaUpdate, receipt: TransferReceipt,
    ): Snapshot = dataStore.edit { prefs ->
        prefs.ensureTransferTrust(receipt)
        when (prefs.status(receipt)) {
            TransferStatus.CONFLICT -> throw TransferException(TransferProblem.REPLAY)
            TransferStatus.SAVED -> return@edit
            TransferStatus.MISSING -> Unit
        }
        val current = prefs.readSnapshot()
        val profile = current.profiles.firstOrNull { it == expected.profile }
            ?: throw TransferException(TransferProblem.STALE)
        val index = prefs.readIndex()
        if (index.profiles[profile.id] == null || index.snapshot(profile) != expected) {
            throw TransferException(TransferProblem.STALE)
        }
        val (peer, remoteId) = peerProfile.split(':', limit = 2)
        val bindings = expected.bindings()
        if (bindings[peer]?.let { it != remoteId } == true || index.profiles.any { (id, state) ->
                id != profile.id && state.bindings()[peer] == remoteId
            }
        ) {
            throw TransferException(TransferProblem.WRONG_REPLICA)
        }
        val nextCounter = update.versions.values.maxOfOrNull { it.counters[index.deviceId] ?: 0L } ?: 0L
        val metadata = index.profiles.getValue(profile.id)
        val remaining = update.conflicts.mapTo(mutableSetOf(), ReplicaConflict::path)
        val now = Clock.System.now().toEpochMilliseconds()
        val history = (metadata.history + expected.conflicts.filter { it.path !in remaining }
            .map { ResolvedReplicaConflict(it.path, "sync", now) }).recent(now)
        val next = index.copy(
            nextCounter = maxOf(index.nextCounter, nextCounter),
            profiles = index.profiles + (profile.id to ReplicaMetadata(update.versions, update.conflicts,
                peerProfiles = bindings + (peer to remoteId), history = history)),
        )
        val updated = profile.applyReplicaValues(update.values)
        prefs.writeSnapshot(current.copy(profiles = current.profiles.map { if (it.id == updated.id) updated else it }), next)
        prefs[receipt.key()] = receipt.manifestHash
    }.readSnapshot()

    internal suspend fun resolveReplicaConflict(profileId: String, conflict: ReplicaConflict, remote: Boolean): Snapshot =
        dataStore.edit { prefs ->
            val snapshot = prefs.readSnapshot()
            val profile = snapshot.profiles.firstOrNull { it.id == profileId } ?: throw TransferException(TransferProblem.STALE)
            val (index, resolved) = prefs.readIndex().resolveConflict(profile, conflict, remote)
                ?: throw TransferException(TransferProblem.STALE)
            prefs.writeSnapshot(snapshot.copy(profiles = snapshot.profiles.map { if (it.id == profileId) resolved else it }), index)
        }.readSnapshot()

    suspend fun save(snapshot: Snapshot) {
        dataStore.edit { it.writeSnapshot(snapshot) }
    }

    internal suspend fun transferStatus(receipt: TransferReceipt): TransferStatus = dataStore.data.first().status(receipt)

    /** Returns the previous receipt status. MISSING means this call added the snapshot and receipt. */
    internal suspend fun saveReceived(snapshot: Snapshot, receipt: TransferReceipt): TransferStatus {
        var previous = TransferStatus.MISSING
        dataStore.edit { prefs ->
            prefs.ensureTransferTrust(receipt)
            previous = prefs.status(receipt)
            if (previous == TransferStatus.MISSING) {
                prefs.writeSnapshot(snapshot)
                prefs[receipt.key()] = receipt.manifestHash
            }
        }
        return previous
    }

    private fun Preferences.status(receipt: TransferReceipt): TransferStatus = when (get(receipt.key())) {
        null -> TransferStatus.MISSING
        receipt.manifestHash -> TransferStatus.SAVED
        else -> TransferStatus.CONFLICT
    }

    private fun TransferReceipt.key() = stringPreferencesKey("lan_transfer_v1_${peerKey}_$transferId")

    private fun Preferences.ensureTransferTrust(receipt: TransferReceipt) {
        if (receipt.peerKey.removePrefix("incremental_") in this[revokedPeersKey].orEmpty()) {
            throw TransferException(TransferProblem.UNTRUSTED)
        }
    }

    private fun Preferences.readIndex(): ReplicaIndex = this[replicaKey]?.let { replicaJson.decodeFromString<ReplicaIndex>(it) }
        ?: ReplicaIndex()

    private fun MutablePreferences.writeSnapshot(snapshot: Snapshot, replica: ReplicaIndex? = null) {
        val next = replica ?: readIndex().recordChanges(readSnapshot().profiles, snapshot.profiles)
        val versions = replicaJson.encodeToString(next)
        if (this[replicaKey] != versions) this[replicaKey] = versions
        this[profilesKey] = ProfileCodec.encodeProfiles(snapshot.profiles)
        this[activeIdKey] = snapshot.activeId
        snapshot.profiles.find { it.id == snapshot.activeId }?.let { active ->
            this[legacyServicesKey] = VaultCodec.encode(active.services)
        }
    }

    companion object {
        /** 由旧 `services` 键迁移而来的那份配置固定用这个 id，保证多次迁移结果一致。 */
        const val LEGACY_PROFILE_ID = "default"
    }
}
