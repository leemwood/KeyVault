package cn.lemwood.keyvault.platform

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

/**
 * 创建平台各自的 DataStore 实例。
 *
 * Android 端路径必须保持 filesDir/datastore/vault.preferences_pb —— 旧版本就写在这里，
 * 换了路径等于让用户数据凭空消失。
 */
expect fun createVaultDataStore(): DataStore<Preferences>

/** 平台版本名，用于「关于」页展示。 */
expect fun platformAppVersion(): String
