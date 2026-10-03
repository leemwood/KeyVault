package cn.lemwood.keyvault.platform

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File

private val lock = Any()

@Volatile
private var cachedDataStore: DataStore<Preferences>? = null

actual fun createVaultDataStore(): DataStore<Preferences> = cachedDataStore ?: synchronized(lock) {
    cachedDataStore ?: PreferenceDataStoreFactory.create(
        produceFile = {
            File(System.getProperty("user.home"), ".keyvault")
                .apply { mkdirs() }
                .resolve("vault.preferences_pb")
        }
    ).also { cachedDataStore = it }
}

actual fun platformAppVersion(): String = "1.1.0 (Desktop)"
