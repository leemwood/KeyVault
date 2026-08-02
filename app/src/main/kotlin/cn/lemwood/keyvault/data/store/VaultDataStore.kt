package cn.lemwood.keyvault.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cn.lemwood.keyvault.data.model.ApiKey
import cn.lemwood.keyvault.data.model.Service
import cn.lemwood.keyvault.data.model.ServiceItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vault")

class VaultDataStore(context: Context) {

    private val dataStore = context.dataStore
    private val key = stringPreferencesKey("services")

    val servicesFlow: Flow<List<Service>> = dataStore.data.map { prefs ->
        prefs[key]?.let { json ->
            // 解析失败回退空列表，避免损坏数据导致崩溃
            runCatching { parse(json) }.getOrDefault(emptyList())
        } ?: defaultServices()
    }

    suspend fun save(services: List<Service>) {
        dataStore.edit { prefs ->
            prefs[key] = serialize(services)
        }
    }

    private fun defaultServices(): List<Service> = emptyList()

    private fun serialize(services: List<Service>): String {
        val root = JSONObject()
        services.forEach { service ->
            val serviceObj = JSONObject()
            serviceObj.put("name", service.name)
            val itemsObj = JSONObject()
            service.items.forEach { item ->
                val itemObj = JSONObject()
                itemObj.put("name", item.name)
                itemObj.put("url", item.apiUrl)
                val keysArr = JSONArray()
                item.keys.forEach { apiKey ->
                    val keyObj = JSONObject()
                    keyObj.put("id", apiKey.id)
                    keyObj.put("value", apiKey.value)
                    keyObj.put("note", apiKey.note)
                    keysArr.put(keyObj)
                }
                itemObj.put("keys", keysArr)
                itemsObj.put(item.id, itemObj)
            }
            serviceObj.put("items", itemsObj)
            root.put(service.id, serviceObj)
        }
        return root.toString()
    }

    private fun parse(json: String): List<Service> {
        val root = JSONObject(json)
        val services = mutableListOf<Service>()
        root.keys().forEach { serviceKey ->
            val serviceObj = root.getJSONObject(serviceKey)
            // 新格式以稳定 id 为 key、name 存为字段；旧格式以服务名为 key
            val serviceId = serviceObj.optString("id", serviceKey)
            val serviceName = serviceObj.optString("name", serviceKey)
            val itemsObj = if (serviceObj.opt("items") is JSONObject) {
                serviceObj.getJSONObject("items")
            } else {
                // 旧格式：服务名直接对应配置项映射
                serviceObj
            }
            val items = mutableListOf<ServiceItem>()
            itemsObj.keys().forEach { itemKey ->
                val obj = itemsObj.getJSONObject(itemKey)
                val itemId = obj.optString("id", itemKey)
                if (obj.opt("keys") is JSONArray) {
                    // 新格式：{id, name, url, keys:[{id, value, note}]}（name 缺失时以 key 为名，兼容旧格式）
                    val itemName = obj.optString("name", itemKey)
                    val keys = mutableListOf<ApiKey>()
                    val arr = obj.getJSONArray("keys")
                    for (i in 0 until arr.length()) {
                        val k = arr.getJSONObject(i)
                        val keyId = k.optString("id", UUID.randomUUID().toString())
                        keys.add(ApiKey(id = keyId, value = k.optString("value", ""), note = k.optString("note", "")))
                    }
                    items.add(ServiceItem(id = itemId, name = itemName, apiUrl = obj.optString("url", ""), keys = keys))
                } else {
                    // 最旧格式：{字段名: 字段值}，api/url 字段或 http 开头的值作为 URL，其余转为 keys
                    var apiUrl = ""
                    val keys = mutableListOf<ApiKey>()
                    obj.keys().forEach { fieldKey ->
                        val value = obj.optString(fieldKey, "")
                        val isUrlLike = fieldKey.equals("api", true) || fieldKey.equals("url", true) ||
                            value.startsWith("http://") || value.startsWith("https://")
                        if (apiUrl.isEmpty() && isUrlLike) {
                            apiUrl = value
                        } else {
                            keys.add(ApiKey(value = value, note = fieldKey))
                        }
                    }
                    items.add(ServiceItem(id = itemId, name = itemKey, apiUrl = apiUrl, keys = keys))
                }
            }
            services.add(Service(id = serviceId, name = serviceName, items = items))
        }
        return services
    }
}
