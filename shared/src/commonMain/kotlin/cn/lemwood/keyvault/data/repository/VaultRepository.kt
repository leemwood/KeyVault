package cn.lemwood.keyvault.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cn.lemwood.keyvault.data.model.VaultProfile
import cn.lemwood.keyvault.data.serializer.ProfileCodec
import cn.lemwood.keyvault.data.serializer.VaultCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    val snapshotFlow: Flow<Snapshot> = dataStore.data.map { prefs ->
        val stored = prefs[profilesKey]?.let { ProfileCodec.decodeProfiles(it) }
        if (!stored.isNullOrEmpty()) {
            val activeId = prefs[activeIdKey]
                ?.takeIf { id -> stored.any { it.id == id } }
                ?: stored.first().id
            Snapshot(stored, activeId)
        } else {
            // 迁移分支：旧版只有 services 键；全新安装时同样落到这里，得到一个空的默认配置
            val legacy = prefs[legacyServicesKey]?.let { raw ->
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

    suspend fun save(snapshot: Snapshot) {
        dataStore.edit { prefs ->
            prefs[profilesKey] = ProfileCodec.encodeProfiles(snapshot.profiles)
            prefs[activeIdKey] = snapshot.activeId
            val active = snapshot.profiles.find { it.id == snapshot.activeId }
            if (active != null) {
                prefs[legacyServicesKey] = VaultCodec.encode(active.services)
            }
        }
    }

    companion object {
        /** 由旧 `services` 键迁移而来的那份配置固定用这个 id，保证多次迁移结果一致。 */
        const val LEGACY_PROFILE_ID = "default"
    }
}
