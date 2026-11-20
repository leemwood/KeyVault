package cn.lemwood.keyvault

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import cn.lemwood.keyvault.data.backup.BackupCodec
import cn.lemwood.keyvault.data.backup.BackupSource
import cn.lemwood.keyvault.data.backup.ImportChangeKind
import cn.lemwood.keyvault.data.backup.ImportConflictId
import cn.lemwood.keyvault.data.backup.ImportResolution
import cn.lemwood.keyvault.data.backup.ImportStrategy
import cn.lemwood.keyvault.data.backup.previewImport
import cn.lemwood.keyvault.data.backup.uniqueImportName
import cn.lemwood.keyvault.data.model.ApiKey
import cn.lemwood.keyvault.data.model.Service
import cn.lemwood.keyvault.data.model.ServiceItem
import cn.lemwood.keyvault.data.model.VaultProfile
import cn.lemwood.keyvault.data.repository.VaultRepository
import cn.lemwood.keyvault.ui.ImportResult
import cn.lemwood.keyvault.ui.VaultViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ImportStrategiesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `new import previews and saves an independent empty profile`() = withVault { vm, repository ->
        val backup = readEncrypted(VaultProfile(name = "existing-name"))
        val conflictingName = previewImport(backup, ImportStrategy.ADD)
        assertEquals(ImportResult.STALE_PREVIEW, vm.applyImport(conflictingName))
        assertEquals(initial, repository.snapshotFlow.first())

        val preview = previewImport(backup, ImportStrategy.ADD, name = uniqueImportName(backup.name, initial.profiles))
        assertTrue(preview.changes.isEmpty())
        assertEquals("existing-name (2)", preview.result.name)
        assertNotEquals(backup.id, preview.result.id)
        assertEquals(ImportResult.SAVED, vm.applyImport(preview))
        val saved = repository.snapshotFlow.first()
        assertEquals(initial.profiles + preview.result, saved.profiles)
        assertEquals(preview.result.id, saved.activeId)
        assertEquals(saved.profiles, vm.profiles.value)
    }

    @Test
    fun `merge deduplicates names and values and applies all three key choices`() = withVault { vm, repository ->
        val legacy = """
            {"format":1,"name":"existing-name","services":{
              "foreign-service":{"name":"Example","items":{
                "foreign-item":{"name":"Account","url":"https://backup.example","keys":[
                  {"id":"different","value":"duplicate-value","note":"backup note"},
                  {"id":"matching-id","value":"incoming-value","note":"incoming note"},
                  {"id":"third-id","value":"backup-third-value","note":"third new note"},
                  {"id":"repeated","value":"duplicate-value","note":"backup note"}
                ]},
                "duplicate-item":{"name":"Account","url":"","keys":[{"id":"matching-id","value":"extra-value"}]}
              }},
              "duplicate-service":{"name":"Example","items":{"extra-item":{"name":"Another account","keys":[]}}}
            }}
        """.trimIndent().encodeToByteArray()
        val backup = assertIs<BackupSource.Legacy>(BackupCodec.inspect(legacy)).profile
        val unresolved = previewImport(backup, ImportStrategy.MERGE, local)
        val matches = unresolved.conflicts.associateBy { it.id.keyIndex }
        assertEquals(setOf(null, 0, 1, 2), matches.keys)
        val resolutions = mapOf(
            matches.getValue(null).id to ImportResolution.INCOMING,
            matches.getValue(0).id to ImportResolution.BOTH,
            matches.getValue(1).id to ImportResolution.INCOMING,
            matches.getValue(2).id to ImportResolution.LOCAL,
        )
        val preview = previewImport(backup, ImportStrategy.MERGE, local, resolutions = resolutions)
        assertEquals(2, preview.count(ImportChangeKind.ADDED))
        assertEquals(3, preview.count(ImportChangeKind.MODIFIED))
        assertEquals(0, preview.count(ImportChangeKind.DELETED))
        assertEquals(initial, repository.snapshotFlow.first())
        assertEquals(ImportResult.SAVED, vm.applyImport(preview))

        val saved = repository.snapshotFlow.first()
        assertEquals(unrelated, saved.profiles.last())
        assertEquals(local.id, saved.activeId)
        val merged = saved.profiles.first().services.single()
        assertEquals("local-service", merged.id)
        assertEquals(listOf("Account", "Another account"), merged.items.map { it.name })
        val item = merged.items.first()
        assertEquals("local-item", item.id)
        assertEquals("https://backup.example", item.apiUrl)
        assertEquals(4, item.keys.map { it.id }.toSet().size)
        assertEquals("local note\nbackup note", item.keys.single { it.value == "duplicate-value" }.note)
        assertEquals("incoming-value", item.keys.single { it.id == "matching-id" }.value)
        assertTrue(item.keys.any { it.value == "extra-value" })
        assertEquals("local-third-value", item.keys.single { it.id == "third-id" }.value)
    }

    @Test
    fun `overwrite previews every deletion when replacing with an empty backup`() = withVault { vm, repository ->
        val backup = readEncrypted(VaultProfile(name = "Empty backup"))
        val preview = previewImport(backup, ImportStrategy.REPLACE, local)
        assertEquals(5, preview.count(ImportChangeKind.DELETED))
        assertEquals(0, preview.count(ImportChangeKind.ADDED))
        assertTrue(preview.changes.any { it.path == "Example / Account / Key 3" })
        assertEquals(initial, repository.snapshotFlow.first())

        assertEquals(ImportResult.SAVED, vm.applyImport(preview))
        val saved = repository.snapshotFlow.first()
        assertEquals(listOf(local.copy(services = emptyList()), unrelated), saved.profiles)
        assertEquals(local.id, saved.activeId)
        assertEquals(ImportResult.STALE_PREVIEW, vm.applyImport(preview))
        assertEquals(saved, repository.snapshotFlow.first())
    }

    @Test
    fun `write failure aborts an already prepared overwrite without publishing it`() =
        withVault(simulateFailure = true) { vm, repository ->
            val backup = readEncrypted(VaultProfile(name = "Empty backup"))
            val preview = previewImport(backup, ImportStrategy.REPLACE, local)
            assertEquals(ImportResult.SAVE_FAILED, vm.applyImport(preview))
            assertEquals(initial, repository.snapshotFlow.first())
            assertEquals(initial.profiles, vm.profiles.value)
            assertEquals(initial.activeId, vm.activeProfileId.value)
        }

    @Test
    fun `keeping both values for one key ID produces two independent IDs`() {
        val backup = VaultProfile(name = "Backup", services = listOf(
            Service(name = "Example", items = listOf(ServiceItem(name = "Account", keys = listOf(
                ApiKey(id = "matching-id", value = "incoming-value"),
            )))),
        ))
        val choices = mapOf(ImportConflictId("Example", "Account", 0) to ImportResolution.BOTH)
        val preview = previewImport(backup, ImportStrategy.MERGE, local, resolutions = choices)
        val keys = preview.result.services.single().items.single().keys
        assertEquals(4, keys.map { it.id }.toSet().size)
        assertTrue(keys.any { it.value == "local-value" })
        assertTrue(keys.any { it.value == "incoming-value" })
        assertEquals(1, preview.count(ImportChangeKind.ADDED))
    }

    private suspend fun readEncrypted(profile: VaultProfile): VaultProfile {
        val password = "fixture only".encodeToByteArray()
        val bytes = BackupCodec.encode(profile, password)
        return BackupCodec.unlock(assertIs<BackupSource.Encrypted>(BackupCodec.inspect(bytes)), password)
    }

    private fun withVault(
        simulateFailure: Boolean = false,
        block: suspend (VaultViewModel, VaultRepository) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.root.resolve("test.preferences_pb")
        }
        val repository = VaultRepository(store)
        repository.save(initial)
        val input = if (simulateFailure) object : DataStore<Preferences> by store {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                store.updateData { preferences ->
                    transform(preferences)
                    throw IOException("simulated write failure")
                }
        } else store
        val vm = VaultViewModel(VaultRepository(input))
        try {
            withTimeout(10_000) { vm.isLoading.first { !it } }
            block(vm, repository)
        } finally {
            vm.viewModelScope.cancel()
            scope.cancel()
        }
    }

    private val local = VaultProfile(id = "target", name = "existing-name", services = listOf(
        Service(id = "local-service", name = "Example", items = listOf(
            ServiceItem(id = "local-item", name = "Account", apiUrl = "https://local.example", keys = listOf(
                ApiKey(id = "first-id", value = "duplicate-value", note = "local note"),
                ApiKey(id = "matching-id", value = "local-value", note = "local existing note"),
                ApiKey(id = "third-id", value = "local-third-value", note = "third note"),
            )),
        )),
    ))
    private val unrelated = VaultProfile(id = "unrelated", name = "Unrelated")
    private val initial = VaultRepository.Snapshot(listOf(local, unrelated), local.id)
}
