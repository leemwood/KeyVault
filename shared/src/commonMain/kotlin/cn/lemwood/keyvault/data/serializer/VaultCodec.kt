package cn.lemwood.keyvault.data.serializer

import cn.lemwood.keyvault.data.model.ApiKey
import cn.lemwood.keyvault.data.model.Service
import cn.lemwood.keyvault.data.model.ServiceItem
import cn.lemwood.keyvault.data.model.randomId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 保险库的 JSON 编解码，persist 到 DataStore 的单一字符串键。
 *
 * 磁盘格式与旧版（org.json 手写）保持一致，并兼容历史上出现过的三层格式：
 * 1. 现格式：以稳定 id 为 map key，name/url 作为字段
 * 2. 中格式：以服务名/配置项名为 map key，字段结构同上
 * 3. 最旧格式：服务名下直接是「字段名 → 字段值」映射，api/url 或 http 开头的值识别为 URL
 *
 * 迁移时必须保留全部三层解析逻辑，否则老用户升级后数据会错乱。
 */
object VaultCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(services: List<Service>): String {
        val root = buildJsonObject {
            services.forEach { service ->
                put(service.id, buildJsonObject {
                    put("name", service.name)
                    put("items", buildJsonObject {
                        service.items.forEach { item ->
                            put(item.id, buildJsonObject {
                                put("name", item.name)
                                put("url", item.apiUrl)
                                put("keys", buildJsonArray {
                                    item.keys.forEach { apiKey ->
                                        add(buildJsonObject {
                                            put("id", apiKey.id)
                                            put("value", apiKey.value)
                                            put("note", apiKey.note)
                                        })
                                    }
                                })
                            })
                        }
                    })
                })
            }
        }
        return root.toString()
    }

    fun decode(source: String): List<Service> {
        val root = runCatching { json.parseToJsonElement(source) }.getOrNull() as? JsonObject
            ?: return emptyList()

        val services = mutableListOf<Service>()
        root.forEach { (serviceKey, serviceValue) ->
            val serviceObj = serviceValue as? JsonObject ?: return@forEach
            // 新格式以稳定 id 为 key、name 存为字段；旧格式以服务名为 key
            val serviceId = serviceObj.stringOrNull("id") ?: serviceKey
            val serviceName = serviceObj.stringOrNull("name") ?: serviceKey
            val itemsObj = serviceObj["items"] as? JsonObject ?: serviceObj
            val items = mutableListOf<ServiceItem>()

            itemsObj.forEach { (itemKey, itemValue) ->
                val obj = itemValue as? JsonObject ?: return@forEach
                val itemId = obj.stringOrNull("id") ?: itemKey
                val keysArray = obj["keys"] as? JsonArray

                if (keysArray != null) {
                    val itemName = obj.stringOrNull("name") ?: itemKey
                    val keys = keysArray.mapNotNull { element ->
                        val keyObj = element as? JsonObject ?: return@mapNotNull null
                        ApiKey(
                            id = keyObj.stringOrNull("id") ?: randomId(),
                            value = keyObj.stringOrNull("value").orEmpty(),
                            note = keyObj.stringOrNull("note").orEmpty()
                        )
                    }
                    items += ServiceItem(
                        id = itemId,
                        name = itemName,
                        apiUrl = obj.stringOrNull("url").orEmpty(),
                        keys = keys
                    )
                } else {
                    // 最旧格式：{字段名: 字段值}，api/url 字段或 http 开头的值作为 URL，其余转为 keys
                    var apiUrl = ""
                    val keys = mutableListOf<ApiKey>()
                    obj.forEach { (fieldKey, fieldValue) ->
                        val value = (fieldValue as? JsonPrimitive)?.content ?: return@forEach
                        val isUrlLike = fieldKey.equals("api", ignoreCase = true) ||
                            fieldKey.equals("url", ignoreCase = true) ||
                            value.startsWith("http://") ||
                            value.startsWith("https://")
                        if (apiUrl.isEmpty() && isUrlLike) {
                            apiUrl = value
                        } else {
                            keys += ApiKey(value = value, note = fieldKey)
                        }
                    }
                    items += ServiceItem(id = itemId, name = itemKey, apiUrl = apiUrl, keys = keys)
                }
            }

            services += Service(id = serviceId, name = serviceName, items = items)
        }
        return services
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.content
}
