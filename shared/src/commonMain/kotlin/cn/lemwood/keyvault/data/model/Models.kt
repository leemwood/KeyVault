package cn.lemwood.keyvault.data.model

import kotlin.random.Random

private const val ID_LENGTH = 32
private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/**
 * KMP 下没有 java.util.UUID，用定长十六进制随机串代替，避免为生成 id 引入 expect/actual。
 * 与旧版 UUID 长度不同但同为 opaque id，旧数据中已存的 id 仍会被正常读出、不受影响。
 */
internal fun randomId(): String = buildString(ID_LENGTH) {
    repeat(ID_LENGTH) { append(HEX_DIGITS[Random.nextInt(HEX_DIGITS.size)]) }
}

/**
 * 一份完整的配置（档案）。v1.0 时代只有一份「所有服务」，v1.1 起支持多份并存，
 * 导入备份时不覆盖本地，而是新增一份配置，用户可在首页左上角切换。
 */
data class VaultProfile(
    val id: String = randomId(),
    val name: String,
    val services: List<Service> = emptyList()
)

data class Service(
    val id: String = randomId(),
    val name: String,
    val items: List<ServiceItem> = emptyList()
)

data class ServiceItem(
    val id: String = randomId(),
    val name: String,
    val apiUrl: String = "",
    val keys: List<ApiKey> = emptyList()
)

data class ApiKey(
    val id: String = randomId(),
    val value: String = "",
    val note: String = ""
)
