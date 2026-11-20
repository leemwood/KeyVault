package cn.lemwood.keyvault.data.backup

import cn.lemwood.keyvault.data.model.VaultProfile
import cn.lemwood.keyvault.data.serializer.VaultCodec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun decodeBackupPayload(root: JsonObject): VaultProfile {
    validateBackupPayload(root)
    return VaultProfile(name = root.backupString("name"), services = VaultCodec.decodeServices(root.backupObject("services")))
}

internal fun validateBackupPayload(root: JsonObject) {
    root.requireKeys("name", "services")
    root.backupString("name").nonBlank()
    val services = root.backupObject("services")
    for ((serviceId, value) in services) {
        serviceId.nonBlank()
        val service = value as? JsonObject ?: damagedBackup()
        service.requireKeys("name", "items")
        service.backupString("name").nonBlank()
        for ((itemId, itemValue) in service.backupObject("items")) {
            itemId.nonBlank()
            val item = itemValue as? JsonObject ?: damagedBackup()
            item.requireKeys("name", "url", "keys")
            item.backupString("name").nonBlank()
            item.backupString("url")
            val keys = item["keys"] as? JsonArray ?: damagedBackup()
            val ids = mutableSetOf<String>()
            for (keyValue in keys) {
                val key = keyValue as? JsonObject ?: damagedBackup()
                key.requireKeys("id", "value", "note")
                if (!ids.add(key.backupString("id").nonBlank())) damagedBackup()
                key.backupString("value")
                key.backupString("note")
            }
        }
    }
}

internal fun decodeLegacyBackup(root: JsonObject, hasVersion: Boolean): VaultProfile {
    val nestedServices = root["services"] as? JsonObject
    val wrapped = hasVersion || root["name"] is JsonPrimitive && "services" in root
    val services = if (wrapped) {
        val permitted = if (hasVersion) setOf("format", "name", "services") else setOf("name", "services")
        if (root.keys.any { it !in permitted }) damagedBackup()
        root.backupObject("services")
    } else {
        // Older hand-written exports sometimes wrapped services without a version or name.
        val unnamedWrapper = nestedServices?.takeIf {
            root.size == 1 && it["items"] !is JsonObject && it["name"] !is JsonPrimitive
        }
        if (unnamedWrapper != null) {
            try {
                validateLegacyServices(unnamedWrapper)
                return VaultProfile(name = "导入的配置", services = VaultCodec.decodeServices(unnamedWrapper))
            } catch (_: BackupException) {
                // The sole service can itself be named "services" in bare backups.
            }
        }
        root
    }
    val name = if (wrapped && "name" in root) root.backupString("name") else ""
    validateLegacyServices(services)
    return VaultProfile(name = name.ifBlank { "导入的配置" }, services = VaultCodec.decodeServices(services))
}

private fun validateLegacyServices(services: JsonObject) {
    for ((id, value) in services) {
        id.nonBlank()
        val service = value as? JsonObject ?: damagedBackup()
        val items = service["items"] as? JsonObject ?: service
        for ((itemName, itemValue) in items) {
            if (items === service && (itemName == "id" || itemName == "name") &&
                itemValue is JsonPrimitive && itemValue.isString
            ) {
                continue
            }
            itemName.nonBlank()
            val item = itemValue as? JsonObject ?: damagedBackup()
            val keys = item["keys"]
            if (keys is JsonArray) {
                if (item.any { (name, value) -> name != "keys" && (value as? JsonPrimitive)?.isString != true }) {
                    damagedBackup()
                }
                for (keyValue in keys) {
                    val key = keyValue as? JsonObject ?: damagedBackup()
                    key.backupString("value")
                    if (key.values.any { (it as? JsonPrimitive)?.isString != true }) damagedBackup()
                }
            } else if (item.values.any { it !is JsonPrimitive || it == JsonNull }) {
                damagedBackup()
            }
        }
    }
}

private fun String.nonBlank(): String = ifBlank { damagedBackup() }
