package cn.lemwood.keyvault.sync

import cn.lemwood.keyvault.data.model.ApiKey
import cn.lemwood.keyvault.data.model.Service
import cn.lemwood.keyvault.data.model.ServiceItem
import cn.lemwood.keyvault.data.model.VaultProfile
import cn.lemwood.keyvault.data.model.randomId
import kotlinx.serialization.Serializable
import kotlin.time.Clock

private const val TOMBSTONE_TTL_MS = 30L * 24 * 60 * 60 * 1000

@Serializable
data class ReplicaPath(val service: String, val item: String? = null, val key: String? = null) {
    internal val level get() = if (key != null) 2 else if (item != null) 1 else 0
    internal fun parent() = when (level) {
        2 -> copy(key = null)
        1 -> copy(item = null)
        else -> null
    }
}

@Serializable
data class ReplicaValue(val name: String, val detail: String = "")

@Serializable
data class ReplicaVersion(
    val updatedAt: Long,
    val deviceId: String,
    val counters: Map<String, Long> = emptyMap(),
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
)

@Serializable
data class ReplicaConflict(
    val path: ReplicaPath,
    val local: ReplicaValue?,
    val remote: ReplicaValue?,
    val version: ReplicaVersion,
)

@Serializable
data class ResolvedReplicaConflict(val path: ReplicaPath, val event: String, val resolvedAt: Long)

@Serializable
internal data class ReplicaMetadata(
    val entries: Map<ReplicaPath, ReplicaVersion> = emptyMap(),
    val conflicts: List<ReplicaConflict> = emptyList(),
    val peerProfile: String? = null,
    val peerProfiles: Map<String, String> = emptyMap(),
    val history: List<ResolvedReplicaConflict> = emptyList(),
)

internal fun ReplicaMetadata.bindings(): Map<String, String> = legacyBinding(peerProfile) + peerProfiles

private fun legacyBinding(value: String?): Map<String, String> {
    if (value == null) return emptyMap()
    val separator = value.indexOf(':')
    if (separator !in 1 until value.lastIndex) return emptyMap()
    return mapOf(value.substring(0, separator) to value.substring(separator + 1))
}

@Serializable
internal data class ReplicaIndex(
    val deviceId: String = randomId(),
    val nextCounter: Long = 0,
    val profiles: Map<String, ReplicaMetadata> = emptyMap(),
)

data class ReplicaSnapshot(
    val profile: VaultProfile,
    val versions: Map<ReplicaPath, ReplicaVersion>,
    val conflicts: List<ReplicaConflict>,
    val peerProfile: String?,
    val peerProfiles: Map<String, String> = emptyMap(),
)

internal fun ReplicaSnapshot.bindings(): Map<String, String> = legacyBinding(peerProfile) + peerProfiles

data class ReplicaUpdate(
    val values: Map<ReplicaPath, ReplicaValue>,
    val versions: Map<ReplicaPath, ReplicaVersion>,
    val conflicts: List<ReplicaConflict>,
)

internal fun VaultProfile.replicaValues(): Map<ReplicaPath, ReplicaValue> = buildMap {
    for (service in services) {
        put(ReplicaPath(service.id), ReplicaValue(service.name))
        for (item in service.items) {
            put(ReplicaPath(service.id, item.id), ReplicaValue(item.name, item.apiUrl))
            for (key in item.keys) put(ReplicaPath(service.id, item.id, key.id), ReplicaValue(key.value, key.note))
        }
    }
}

internal fun VaultProfile.applyReplicaValues(values: Map<ReplicaPath, ReplicaValue>): VaultProfile {
    val children = values.entries.groupBy { it.key.parent() }
    return copy(services = children[null].orEmpty().map { (service, data) ->
        Service(service.service, data.name, children[service].orEmpty().map { (item, config) ->
            ServiceItem(requireNotNull(item.item), config.name, config.detail, children[item].orEmpty().map { (key, secret) ->
                ApiKey(requireNotNull(key.key), secret.name, secret.detail)
            })
        })
    })
}

/** Synthesize a baseline for old profiles and record edits against the last committed profile. */
internal fun ReplicaIndex.recordChanges(
    previous: List<VaultProfile>, current: List<VaultProfile>, now: Long = Clock.System.now().toEpochMilliseconds(),
): ReplicaIndex {
    val beforeById = previous.associateBy(VaultProfile::id)
    var counter = nextCounter
    val updated = current.associate { profile ->
        val metadata = profiles[profile.id] ?: ReplicaMetadata()
        val old = beforeById[profile.id]?.replicaValues()
        val values = profile.replicaValues()
        val versions = metadata.entries.toMutableMap()
        for (path in values.keys + old.orEmpty().keys) {
            val original = versions[path] ?: ReplicaVersion(0, deviceId)
            val deleted = path !in values
            versions[path] = if (old != null && (values[path] != old[path] || original.deleted != deleted)) {
                counter++
                original.copy(
                    updatedAt = maxOf(now, original.updatedAt + 1),
                    deviceId = deviceId,
                    counters = original.counters + (deviceId to counter),
                    deleted = deleted,
                    deletedAt = now.takeIf { deleted },
                )
            } else original
        }
        val compacted = versions.mapValues { (_, version) ->
            if (version.deletedAt?.let { it < now - TOMBSTONE_TTL_MS } == true) version.copy(deletedAt = null) else version
        }
        profile.id to metadata.copy(
            entries = compacted,
            conflicts = metadata.conflicts.mapNotNull { conflict ->
                if (versions[conflict.path] == conflict.version) conflict.copy(version = compacted.getValue(conflict.path)) else null
            },
            history = metadata.history.recent(now),
        )
    }
    return copy(nextCounter = counter, profiles = updated)
}

internal fun ReplicaIndex.snapshot(profile: VaultProfile): ReplicaSnapshot {
    val metadata = requireNotNull(profiles[profile.id])
    return ReplicaSnapshot(profile, metadata.entries, metadata.conflicts, metadata.peerProfile, metadata.peerProfiles)
}

internal fun mergeCounters(first: Map<String, Long>, second: Map<String, Long>): Map<String, Long> =
    (first.keys + second.keys).associateWith { maxOf(first[it] ?: 0, second[it] ?: 0) }.toSortedMapPortable()

private fun Map<String, Long>.toSortedMapPortable(): Map<String, Long> = entries.sortedBy { it.key }
    .associate { it.key to it.value }

internal fun ReplicaVersion.dominates(other: ReplicaVersion): Boolean =
    counters != other.counters && other.counters.all { (device, count) -> (counters[device] ?: 0L) >= count }

internal fun ReplicaIndex.resolveConflict(
    profile: VaultProfile, conflict: ReplicaConflict, useRemote: Boolean, now: Long = Clock.System.now().toEpochMilliseconds(),
): Pair<ReplicaIndex, VaultProfile>? {
    val state = profiles[profile.id] ?: return null
    if (conflict !in state.conflicts || state.entries[conflict.path] != conflict.version) return null
    val values = profile.replicaValues().toMutableMap()
    val selected = if (useRemote) conflict.remote else conflict.local
    if (selected != null && conflict.path.parent()?.let { it !in values } == true) return null
    val counter = nextCounter + 1
    val revised = conflict.version.copy(
        updatedAt = maxOf(now, conflict.version.updatedAt + 1), deviceId = deviceId,
        counters = mergeCounters(conflict.version.counters, mapOf(deviceId to counter)),
        deleted = selected == null, deletedAt = now.takeIf { selected == null },
    )
    val versions = state.entries.toMutableMap()
    versions[conflict.path] = revised
    if (selected == null) {
        val removed = values.keys.filter { it == conflict.path || it.under(conflict.path) }
        for (path in removed) {
            values.remove(path)
            val old = versions.getValue(path)
            versions[path] = revised.copy(counters = mergeCounters(old.counters, revised.counters))
        }
    } else values[conflict.path] = selected
    val metadata = state.copy(
        entries = versions,
        conflicts = state.conflicts.filterNot { it.path == conflict.path || selected == null && it.path.under(conflict.path) },
        history = (state.history + ResolvedReplicaConflict(conflict.path, if (useRemote) "remote" else "local", now)).recent(now),
    )
    return copy(nextCounter = counter, profiles = profiles + (profile.id to metadata)) to profile.applyReplicaValues(values)
}

internal fun List<ResolvedReplicaConflict>.recent(now: Long = Clock.System.now().toEpochMilliseconds()) =
    filter { it.resolvedAt >= now - TOMBSTONE_TTL_MS }.takeLast(CONFLICT_HISTORY_LIMIT)

internal fun ReplicaPath.under(parent: ReplicaPath): Boolean =
    service == parent.service && level > parent.level && (parent.item == null || item == parent.item)
