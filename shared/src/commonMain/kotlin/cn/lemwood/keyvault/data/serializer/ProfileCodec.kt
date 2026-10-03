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
 *
 * 备份文件格式：`{"format":1,"name":"...","services":{...}}`。
 * 导入时若发现没有 `services` 字段，就把整个 JSON 当成 services 解析，
 * 这样直接把旧版 services 贴进来、或导入三层历史格式的 JSON 也能正常工作。
 */
object ProfileCodec {

    private const val BACKUP_FORMAT = 1
    private const val DEFAULT_PROFILE_NAME = "默认配置"
    private const val IMPORTED_PROFILE_NAME = "导入的配置"

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
    private fun servicesElement(services: List<Service>): JsonElement =
        json.parseToJsonElement(VaultCodec.encode(services))

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

    /** 导出：把一份配置序列化成备份文件内容。 */
    fun encodeBackup(profile: VaultProfile): String {
        val root = buildJsonObject {
            put("format", BACKUP_FORMAT)
            put("name", profile.name)
            put("services", servicesElement(profile.services))
        }
        return root.toString()
    }

    /**
     * 导入：解析备份文件。返回 null 表示内容不是能识别的 JSON。
     * 兼容两种输入：备份格式（带 services 字段），以及裸 services JSON（历史格式或手工导出）。
     */
    fun decodeBackup(source: String): VaultProfile? {
        val root = runCatching { json.parseToJsonElement(source) }.getOrNull() as? JsonObject
            ?: return null
        val servicesValue = root["services"]
        if (servicesValue != null && servicesValue !is JsonPrimitive) {
            val name = root.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: IMPORTED_PROFILE_NAME
            val services = runCatching { VaultCodec.decode(servicesValue.toString()) }
                .getOrDefault(emptyList())
            return VaultProfile(name = name, services = services)
        }
        // 没有 services 字段：整个文档就是 services（旧版导出的裸数据）
        val services = runCatching { VaultCodec.decode(root.toString()) }.getOrDefault(emptyList())
        return VaultProfile(name = IMPORTED_PROFILE_NAME, services = services)
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    /** 旧 `services` 键迁移成默认配置时用的名字。 */
    fun defaultProfileName(): String = DEFAULT_PROFILE_NAME
}
