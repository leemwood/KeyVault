package cn.lemwood.keyvault.data.model

import java.util.UUID

data class Service(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val items: List<ServiceItem> = emptyList()
)

data class ServiceItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val apiUrl: String = "",
    val keys: List<ApiKey> = emptyList()
)

data class ApiKey(
    val id: String = UUID.randomUUID().toString(),
    val value: String = "",
    val note: String = ""
)
