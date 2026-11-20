package cn.lemwood.keyvault.data.backup

import cn.lemwood.keyvault.data.model.ApiKey
import cn.lemwood.keyvault.data.model.Service
import cn.lemwood.keyvault.data.model.ServiceItem

internal class ProfileMerge(
    private val resolutions: Map<ImportConflictId, ImportResolution>,
    private val conflicts: MutableList<ImportConflict>,
) {
    fun merge(local: List<Service>, incoming: List<Service>): List<Service> {
        val ids = MergeIds()
        return groupByName(local, incoming, Service::name) { name, old, imported ->
            val service = (old.firstOrNull() ?: imported.first())
            service.copy(
                id = ids.claim(service.id),
                items = mergeItems(name, old.flatMap { it.items }, imported.flatMap { it.items }),
            )
        }
    }

    private fun mergeItems(service: String, local: List<ServiceItem>, incoming: List<ServiceItem>): List<ServiceItem> {
        val ids = MergeIds()
        return groupByName(local, incoming, ServiceItem::name) { item, old, imported ->
            val base = old.firstOrNull() ?: imported.first()
            val oldUrl = old.firstNotNullOfOrNull { it.apiUrl.takeIf(String::isNotBlank) }.orEmpty()
            val importedUrl = imported.firstNotNullOfOrNull { it.apiUrl.takeIf(String::isNotBlank) }.orEmpty()
            val id = ImportConflictId(service, item)
            val url = when {
                oldUrl.isBlank() -> importedUrl
                importedUrl.isBlank() || oldUrl == importedUrl -> oldUrl
                resolve(id, oldUrl, importedUrl, allowBoth = false) == ImportResolution.INCOMING -> importedUrl
                else -> oldUrl
            }
            base.copy(
                id = ids.claim(base.id),
                apiUrl = url,
                keys = mergeKeys(id, old.flatMap { it.keys }, imported.flatMap { it.keys }),
            )
        }
    }

    private fun mergeKeys(item: ImportConflictId, local: List<ApiKey>, incoming: List<ApiKey>): List<ApiKey> {
        val ids = MergeIds()
        val oldByValue = keysByValue(local).mapValues { (_, key) -> key.copy(id = ids.claim(key.id)) }
        val oldById = oldByValue.values.associateBy(ApiKey::id)
        val result = oldById.toMutableMap()
        val imported = keysByValue(incoming).values
        val matchedIds = mutableSetOf<String>()

        // Match equal values first so an ID collision cannot replace an already matched local Key.
        imported.forEach { key -> oldByValue[key.value]?.let { matchedIds += it.id } }
        imported.forEachIndexed { index, key ->
            val localKey = oldByValue[key.value] ?: oldById[key.id]?.takeIf { matchedIds.add(it.id) }
            if (localKey == null) {
                val newId = ids.claim(key.id)
                result[newId] = key.copy(id = newId)
                return@forEachIndexed
            }

            val choice = resolveKey(item.copy(keyIndex = index), localKey, key)
            when {
                choice == ImportResolution.LOCAL -> Unit
                localKey.value == key.value -> {
                    val note = if (choice == ImportResolution.BOTH) combineNotes(localKey.note, key.note) else key.note
                    result[localKey.id] = localKey.copy(note = note)
                }
                choice == ImportResolution.INCOMING -> {
                    result[localKey.id] = key.copy(id = localKey.id)
                }
                else -> {
                    val newId = ids.claim(key.id)
                    result[newId] = key.copy(id = newId)
                }
            }
        }

        return result.values.toList()
    }

    private fun resolveKey(id: ImportConflictId, local: ApiKey, incoming: ApiKey): ImportResolution {
        val sameNote = incoming.note.isBlank() || containsNote(local.note, incoming.note)
        if (local.value != incoming.value || (local.note.isNotBlank() && !sameNote)) {
            return resolve(id, local.preview(), incoming.preview(), allowBoth = true)
        }
        return if (local.note.isBlank() && incoming.note.isNotBlank()) ImportResolution.INCOMING else ImportResolution.LOCAL
    }

    private fun resolve(id: ImportConflictId, local: String, incoming: String, allowBoth: Boolean): ImportResolution {
        conflicts += ImportConflict(id, local, incoming, allowBoth)
        return resolutions[id]?.takeIf { allowBoth || it != ImportResolution.BOTH } ?: ImportResolution.LOCAL
    }

    private fun ApiKey.preview(): String {
        val masked = if (value.length >= 8) "••••${value.takeLast(4)}" else "••••"
        return if (note.isBlank()) masked else "$masked · $note"
    }
}

private fun keysByValue(keys: List<ApiKey>): Map<String, ApiKey> = keys.groupBy(ApiKey::value).mapValues { (_, group) ->
    val first = group.first()
    if (group.size == 1) first else {
        val notes = group.map(ApiKey::note).filter(String::isNotBlank).distinct()
        first.copy(note = notes.joinToString("\n"))
    }
}

private fun combineNotes(local: String, incoming: String): String = when {
    local.isBlank() -> incoming
    incoming.isBlank() || containsNote(local, incoming) -> local
    else -> "$local\n$incoming"
}

private fun containsNote(notes: String, note: String): Boolean =
    notes == note || notes.startsWith("$note\n") || notes.endsWith("\n$note") || "\n$note\n" in notes

private inline fun <T, R> groupByName(
    local: List<T>,
    incoming: List<T>,
    name: (T) -> String,
    merge: (String, List<T>, List<T>) -> R,
): List<R> {
    val existing = local.groupBy(name)
    val imported = incoming.groupBy(name)
    return (existing.keys + imported.keys).map { merge(it, existing[it].orEmpty(), imported[it].orEmpty()) }
}

private class MergeIds {
    private val used = mutableSetOf<String>()
    private val nextSuffix = mutableMapOf<String, Int>()

    fun claim(preferred: String): String {
        if (used.add(preferred)) return preferred
        var suffix = nextSuffix[preferred] ?: 2
        var candidate: String
        do {
            candidate = "$preferred-${suffix++}"
        } while (!used.add(candidate))
        nextSuffix[preferred] = suffix
        return candidate
    }
}
