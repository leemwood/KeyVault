package cn.lemwood.keyvault.sync

import kotlinx.coroutines.flow.Flow
import cn.lemwood.keyvault.data.model.VaultProfile

interface LanPairing {
    val requiresPassword: Boolean
    val scanLabel: String
    suspend fun scan(prompt: String): String?
    fun copyInvitation(code: String)
    suspend fun unlock(password: CharArray): PairingDevice
}

interface PairingDevice {
    suspend fun addresses(): List<String>
    suspend fun trustedDevices(): List<TrustedDevice>
    suspend fun revokeDevice(peerKey: String)
    suspend fun runSyncWindow(store: IncrementalStore, onReady: () -> Unit)
    fun discover(): Flow<List<String>>
    suspend fun invite(address: String): PairingInvitation
    suspend fun join(code: String): PairingApproval
    suspend fun prepareTransfer(profile: VaultProfile): TransferSender
    suspend fun listenForTransfer(address: String, sender: TrustedDevice): TransferReceiver
    suspend fun listenForIncremental(address: String, peer: TrustedDevice, profileId: String): IncrementalReceiver
    suspend fun syncIncremental(code: String, peer: TrustedDevice, profileId: String, store: IncrementalStore,
        onEvent: suspend (IncrementalEvent) -> Unit = {})
    fun qr(code: String): PairingQr
}

data class TrustedDevice(val name: String, val publicKey: String)

class PairingQr(val size: Int, val modules: BooleanArray)

interface PairingInvitation {
    val code: String
    val expiresAt: Long
    suspend fun awaitPeer(): PairingApproval
    fun close()
}

interface PairingApproval {
    val peer: TrustedDevice
    val comparisonCode: String
    val expiresAt: Long
    suspend fun confirm()
    fun close()
}

enum class PairingProblem { INVALID_CODE, EXPIRED, NOT_SAME_SUBNET, UNREACHABLE, REJECTED, IDENTITY_CHANGED, STORAGE }

class PairingException(val problem: PairingProblem) : Exception(problem.name)
