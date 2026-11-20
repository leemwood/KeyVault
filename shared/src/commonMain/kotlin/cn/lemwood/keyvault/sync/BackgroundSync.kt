package cn.lemwood.keyvault.sync

import kotlinx.coroutines.flow.StateFlow
import androidx.datastore.preferences.core.stringSetPreferencesKey

data class SyncBinding(val localProfile: String, val peerKey: String, val remoteProfile: String)

enum class SyncWindowState { STOPPED, STARTING, RUNNING, UNAVAILABLE }

interface SyncWindowControl {
    val state: StateFlow<SyncWindowState>
    fun start(device: PairingDevice, store: IncrementalStore)
    fun stop()
}

internal const val CONFLICT_HISTORY_LIMIT = 100

internal const val SYNC_WINDOW_PORT = 36541
internal val revokedPeersKey = stringSetPreferencesKey("lan_revoked_peers_v1")
