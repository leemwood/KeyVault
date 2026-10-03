package cn.lemwood.keyvault

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cn.lemwood.keyvault.data.repository.VaultRepository
import cn.lemwood.keyvault.data.serializer.ProfileCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 多配置文件与旧数据迁移的回归测试。
 *
 * 这里守住两条底线：
 * 1. v1.0 只有一个 `services` 键的老数据，升级后必须原样出现在「默认配置」里，一个服务都不能少；
 * 2. 导出再导入必须无损往返，且导入走的是「新增一份配置」而不是覆盖。
 */
class ProfileMigrationTest {

    private val legacyServicesJson = """
        {
          "svc-1": {
            "name": "deepseek",
            "items": {
              "item-1": {
                "name": "主账号",
                "url": "https://api.deepseek.com",
                "keys": [{"id": "k1", "value": "sk-aaa", "note": "生产"}]
              }
            }
          }
        }
    """.trimIndent()

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "keyvault-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    private fun dataStore(dir: File) = PreferenceDataStoreFactory.create(
        produceFile = { File(dir, "vault.preferences_pb").also { it.parentFile?.mkdirs() } }
    )

    @Test
    fun `legacy services key migrates into default profile`() = runBlocking {
        val dir = tempDir()
        val store = dataStore(dir)
        store.edit { it[stringPreferencesKey("services")] = legacyServicesJson }

        val snapshot = VaultRepository(store).snapshotFlow.first()

        assertEquals(1, snapshot.profiles.size)
        val profile = snapshot.profiles[0]
        assertEquals("默认配置", profile.name)
        assertEquals(1, profile.services.size)
        assertEquals("deepseek", profile.services[0].name)
        assertEquals("sk-aaa", profile.services[0].items[0].keys[0].value)
        assertEquals(profile.id, snapshot.activeId)
    }

    @Test
    fun `fresh install starts with one empty default profile`() = runBlocking {
        val snapshot = VaultRepository(dataStore(tempDir())).snapshotFlow.first()
        assertEquals(1, snapshot.profiles.size)
        assertEquals("默认配置", snapshot.profiles[0].name)
        assertTrue(snapshot.profiles[0].services.isEmpty())
    }

    @Test
    fun `profiles round trip keeps order names and services`() = runBlocking {
        // 先把旧数据迁进来，再补一份新配置，模拟真实升级路径
        val store = dataStore(tempDir())
        store.edit { it[stringPreferencesKey("services")] = legacyServicesJson }
        val repo = VaultRepository(store)
        val migrated = repo.snapshotFlow.first()
        val extra = cn.lemwood.keyvault.data.model.VaultProfile(name = "工作", services = emptyList())
        val saved = migrated.profiles + extra
        repo.save(VaultRepository.Snapshot(saved, extra.id))

        val reread = repo.snapshotFlow.first()
        assertEquals(2, reread.profiles.size)
        assertEquals("默认配置", reread.profiles[0].name)
        assertEquals("工作", reread.profiles[1].name)
        assertEquals(extra.id, reread.activeId)
        assertEquals(1, reread.profiles[0].services.size)
    }

    @Test
    fun `backup export and import round trip`() {
        val services = cn.lemwood.keyvault.data.serializer.VaultCodec.decode(legacyServicesJson)
        val profile = cn.lemwood.keyvault.data.model.VaultProfile(name = "我的备份", services = services)
        val backup = ProfileCodec.encodeBackup(profile)

        val decoded = ProfileCodec.decodeBackup(backup)
        assertNotNull(decoded)
        assertEquals("我的备份", decoded.name)
        assertEquals(services, decoded.services)
    }

    @Test
    fun `import accepts bare services json from old versions`() {
        val decoded = ProfileCodec.decodeBackup(legacyServicesJson)
        assertNotNull(decoded)
        assertEquals(1, decoded.services.size)
        assertEquals("deepseek", decoded.services[0].name)
    }

    @Test
    fun `import rejects unparseable content`() {
        assertNull(ProfileCodec.decodeBackup("not a json"))
        assertNull(ProfileCodec.decodeBackup(""))
    }

    @Test
    fun `backup file name is sanitized`() {
        assertEquals("keyvault-工作.json", ProfileCodec.backupFileName("工作"))
        assertEquals("keyvault-backup.json", ProfileCodec.backupFileName("///"))
        assertTrue(ProfileCodec.backupFileName("a/b\\c").endsWith(".json"))
    }
}
