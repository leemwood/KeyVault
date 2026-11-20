package cn.lemwood.keyvault.data.serializer

import cn.lemwood.keyvault.data.model.Service
import cn.lemwood.keyvault.data.model.VaultProfile
import cn.lemwood.keyvault.data.model.randomId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 配置（档案）层的编解码。
 *
 * 磁盘上新增两个键：`profiles`（配置数组）与 `active_profile_id`（当前激活的配置）。
 * v1.0 只有 `services` 一个键，读取时若发现只有旧键，就把它整体迁移成一份名为
 * 「默认配置」的配置（见 [VaultRepository]），老用户升级后数据原样可见。
 */
object ProfileCodec {

    private const val DEFAULT_PROFILE_NAME = "默认配置"

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeProfiles(profiles: List<VaultProfile>): String {
        val array = buildJsonArray {
            profiles.forEach { profile ->
                add(buildJsonObject {
                    put("id", profile.id)
                    put("name", profile.name)
                    put("services", servicesElement(profile.services))
                })
            }
        }
        return array.toString()
    }

    fun decodeProfiles(source: String): List<VaultProfile> {
        val array = runCatching { json.parseToJsonElement(source) }.getOrNull() as? JsonArray
            ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val servicesValue = obj["services"]
            val services = when (servicesValue) {
                null -> emptyList()
                else -> runCatching { VaultCodec.decode(servicesValue.toString()) }.getOrDefault(emptyList())
            }
            VaultProfile(
                id = obj.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: randomId(),
                name = obj.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: DEFAULT_PROFILE_NAME,
                services = services
            )
        }
    }

    /** 把 services 编成 JsonElement，便于嵌进配置对象里。 */
    private fun servicesElement(services: List<Service>): JsonElement = VaultCodec.encodeServices(services)

    /**
     * 备份用的文件名，去掉路径分隔符与控制字符，避免不同平台写出非法文件名。
     */
    fun backupFileName(profileName: String): String {
        val safe = profileName
            .trim()
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .ifEmpty { "backup" }
        return "keyvault-$safe.json"
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    /** 旧 `services` 键迁移成默认配置时用的名字。 */
    fun defaultProfileName(): String = DEFAULT_PROFILE_NAME
}
