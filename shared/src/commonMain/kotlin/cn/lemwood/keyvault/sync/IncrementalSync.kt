package cn.lemwood.keyvault.sync

import kotlinx.serialization.Serializable

interface IncrementalReceiver {
    val code: String
    val address: String
    suspend fun run(store: IncrementalStore, onEvent: suspend (IncrementalEvent) -> Unit = {})
    fun close()
}

interface IncrementalStore {
    suspend fun replica(profileId: String): ReplicaSnapshot
    suspend fun status(receipt: TransferReceipt): TransferStatus
    suspend fun applyReplica(expected: ReplicaSnapshot, peerProfile: String, update: ReplicaUpdate, receipt: TransferReceipt)
    suspend fun syncBindings(): List<SyncBinding> = emptyList()
}

sealed interface IncrementalEvent {
    data class Progress(val progress: TransferProgress) : IncrementalEvent
    data class Saved(val sent: Int, val received: Int, val conflicts: Int) : IncrementalEvent
    data class Interrupted(val problem: TransferProblem) : IncrementalEvent
}

@Serializable
internal data class ReplicaHeader(val path: ReplicaPath, val version: ReplicaVersion, val digest: String? = null)

@Serializable
internal data class ReplicaManifest(val profileId: String, val name: String, val headers: List<ReplicaHeader>)

@Serializable
internal data class ReplicaRecord(val path: ReplicaPath, val value: ReplicaValue)

internal fun ReplicaSnapshot.manifest(digest: (ReplicaValue) -> String): ReplicaManifest {
    val values = profile.replicaValues()
    return ReplicaManifest(profile.id, profile.name, versions.map { (path, version) ->
        ReplicaHeader(path, version, values[path]?.let(digest))
    }.sortedWith(compareBy({ it.path.service }, { it.path.item }, { it.path.key })))
}

internal fun requestedRecords(from: ReplicaManifest, target: ReplicaManifest): Set<ReplicaPath> {
    val known = target.headers.associateBy(ReplicaHeader::path)
    return from.headers.filter { candidate ->
        val previous = known[candidate.path]
        candidate.digest != null && candidate.digest != previous?.digest &&
            (previous == null || !previous.version.dominates(candidate.version))
    }.mapTo(linkedSetOf(), ReplicaHeader::path)
}

internal fun ReplicaSnapshot.recordsFor(local: ReplicaManifest, remote: ReplicaManifest): List<ReplicaRecord> {
    val values = profile.replicaValues()
    return requestedRecords(local, remote).map { ReplicaRecord(it, values.getValue(it)) }
}

internal fun mergeReplicas(
    local: ReplicaSnapshot,
    ours: ReplicaManifest,
    theirs: ReplicaManifest,
    received: List<ReplicaRecord>,
    digest: (ReplicaValue) -> String,
): ReplicaUpdate {
    val known = ours.headers.associateBy(ReplicaHeader::path)
    val incoming = theirs.headers.associateBy(ReplicaHeader::path)
    val payloads = received.associateBy(ReplicaRecord::path)
    if (payloads.size != received.size || payloads.keys != requestedRecords(theirs, ours) ||
        received.any { digest(it.value) != incoming[it.path]?.digest }
    ) throw TransferException(TransferProblem.INTEGRITY)

    val values = local.profile.replicaValues().toMutableMap()
    val versions = local.versions.toMutableMap()
    val conflicts = local.conflicts.associateBy(ReplicaConflict::path).toMutableMap()
    for ((path, remote) in incoming) {
        val current = known[path]
        val remoteValue = payloads[path]?.value ?: values[path].takeIf { current?.digest == remote.digest }
        if (current == null) {
            versions[path] = remote.version
            remoteValue?.let { values[path] = it }
            continue
        }
        val previous = current.version
        val next = remote.version
        if (previous.dominates(next)) continue
        val remoteDominates = next.dominates(previous)
        val differ = current.digest != remote.digest
        val winner = when {
            remoteDominates -> remote
            previous.deleted != next.deleted -> if (next.deleted) remote else current
            compareValuesBy(current, remote, { it.version.updatedAt }, { it.version.deviceId }, { it.digest.orEmpty() }) <= 0 -> remote
            else -> current
        }
        val accepted = winner.version.copy(counters = mergeCounters(previous.counters, next.counters))
        if (differ && !remoteDominates) {
            conflicts[path] = ReplicaConflict(path, values[path], remoteValue, accepted)
        } else if (conflicts[path]?.version != accepted) {
            conflicts.remove(path)
        }
        versions[path] = accepted
        if (winner === remote) {
            if (remote.version.deleted) values.remove(path) else values[path] = requireNotNull(remoteValue)
        }
    }

    // A newer child cannot recreate a removed parent. Carry the deletion barrier through siblings and descendants.
    for (path in versions.keys.sortedBy(ReplicaPath::level)) {
        val parent = path.parent() ?: continue
        val parentVersion = versions[parent] ?: throw TransferException(TransferProblem.INTEGRITY)
        if (!parentVersion.deleted) continue
        val child = versions.getValue(path)
        if (!child.deleted) values.remove(path)
        val barrier = parentVersion.copy(counters = mergeCounters(child.counters, parentVersion.counters))
        versions[path] = barrier
        if (barrier != conflicts[path]?.version) conflicts.remove(path)
    }
    return ReplicaUpdate(values, versions, conflicts.values.toList())
}
