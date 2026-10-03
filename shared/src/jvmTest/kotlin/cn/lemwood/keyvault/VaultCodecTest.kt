package cn.lemwood.keyvault

import cn.lemwood.keyvault.data.serializer.VaultCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 磁盘格式兼容性回归测试：历史上出现过三层数据结构，全部必须能读，
 * 否则老版本升级到 KMP 版本会丢数据或错乱。
 */
class VaultCodecTest {

    // 第一层：现格式，以稳定 id 为 map key，name/url 作字段
    private val currentJson = """
        {
          "svc-1": {
            "name": "deepseek",
            "items": {
              "item-1": {
                "name": "主账号",
                "url": "https://api.deepseek.com",
                "keys": [
                  {"id": "k1", "value": "sk-aaa", "note": "生产"},
                  {"id": "k2", "value": "sk-bbb", "note": ""}
                ]
              }
            }
          }
        }
    """.trimIndent()

    // 第二层：以服务名/配置项名为 map key，字段结构同上
    private val nameKeyJson = """
        {
          "openai": {
            "items": {
              "备用账号": {
                "url": "https://api.openai.com",
                "keys": [
                  {"value": "sk-test", "note": "测试"}
                ]
              }
            }
          }
        }
    """.trimIndent()

    // 第三层：最旧格式，服务名下直接是「字段名 → 字段值」
    private val legacyFlatJson = """
        {
          "moonshot": {
            "account": {
              "api": "https://api.moonshot.cn",
              "token": "mk-old-token",
              "备注": "迁移遗留"
            }
          }
        }
    """.trimIndent()

    @Test
    fun `current format round trip keeps ids names and keys`() {
        val services = VaultCodec.decode(currentJson)
        assertEquals(1, services.size)

        val service = services[0]
        assertEquals("svc-1", service.id)
        assertEquals("deepseek", service.name)

        val item = service.items[0]
        assertEquals("item-1", item.id)
        assertEquals("主账号", item.name)
        assertEquals("https://api.deepseek.com", item.apiUrl)
        assertEquals(2, item.keys.size)
        assertEquals("k1", item.keys[0].id)
        assertEquals("sk-aaa", item.keys[0].value)
        assertEquals("生产", item.keys[0].note)

        val reEncoded = VaultCodec.encode(services)
        val reparsed = VaultCodec.decode(reEncoded)
        assertEquals(services, reparsed)
    }

    @Test
    fun `name keyed format falls back to key as name`() {
        val services = VaultCodec.decode(nameKeyJson)
        assertEquals(1, services.size)

        val service = services[0]
        assertEquals("openai", service.id)
        assertEquals("openai", service.name)

        val item = service.items[0]
        assertEquals("备用账号", item.id)
        assertEquals("备用账号", item.name)
        assertEquals("https://api.openai.com", item.apiUrl)
        assertEquals(1, item.keys.size)
        assertEquals("sk-test", item.keys[0].value)
        assertEquals("测试", item.keys[0].note)
        // 旧数据没有 id，解析时应补齐而不是留空
        assertTrue(item.keys[0].id.isNotBlank())
    }

    @Test
    fun `legacy flat format extracts url and turns other fields into keys`() {
        val services = VaultCodec.decode(legacyFlatJson)
        assertEquals(1, services.size)

        val service = services[0]
        assertEquals("moonshot", service.name)
        assertEquals(1, service.items.size)

        val item = service.items[0]
        assertEquals("account", item.name)
        assertEquals("https://api.moonshot.cn", item.apiUrl)
        assertEquals(2, item.keys.size)

        val token = item.keys.first { it.note == "token" }
        assertEquals("mk-old-token", token.value)
        assertTrue(item.keys.any { it.note == "备注" && it.value == "迁移遗留" })
    }

    @Test
    fun `damaged json degrades to empty list instead of throwing`() {
        assertTrue(VaultCodec.decode("not a json").isEmpty())
        assertTrue(VaultCodec.decode("[]").isEmpty())
        assertTrue(VaultCodec.decode("null").isEmpty())
        assertFalse(VaultCodec.encode(emptyList()).isBlank())
    }

    @Test
    fun `encode output is parseable json object`() {
        val json = VaultCodec.encode(emptyList())
        assertEquals("{}", json)
    }
}
