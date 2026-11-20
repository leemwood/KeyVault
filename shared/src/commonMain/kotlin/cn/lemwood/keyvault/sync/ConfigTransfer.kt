package cn.lemwood.keyvault.sync

import cn.lemwood.keyvault.data.model.VaultProfile

interface TransferSender {
    suspend fun send(code: String, receiver: TrustedDevice, onProgress: suspend (TransferProgress) -> Unit = {})
    fun close()
}

interface TransferReceiver {
    val code: String
    val address: String
    suspend fun receive(store: TransferStore, onEvent: suspend (TransferEvent) -> Unit = {})
    fun close()
}

data class TransferProgress(val completed: Int, val total: Int, val reconnecting: Boolean = false)

sealed interface TransferEvent {
    data class Progress(val progress: TransferProgress) : TransferEvent
    data object Saved : TransferEvent
    data class Interrupted(val problem: TransferProblem) : TransferEvent
}

data class TransferReceipt(val peerKey: String, val transferId: String, val manifestHash: String)

enum class TransferStatus { MISSING, SAVED, CONFLICT }

interface TransferStore {
    suspend fun transferStatus(receipt: TransferReceipt): TransferStatus
    suspend fun commitTransfer(receipt: TransferReceipt, profile: VaultProfile): TransferStatus
}

enum class TransferProblem {
    INVALID_CODE, UNTRUSTED, OTHER_SUBNET, CONNECTION, UNSUPPORTED, TOO_LARGE,
    INTEGRITY, RECEIVER_BUSY, CONFLICT, STORAGE, WRONG_REPLICA, STALE, REPLAY,
}

class TransferException(val problem: TransferProblem) : Exception(problem.name)
