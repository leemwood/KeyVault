package cn.lemwood.keyvault.data.backup

import cn.lemwood.keyvault.data.model.ApiKey
import cn.lemwood.keyvault.data.model.Service
import cn.lemwood.keyvault.data.model.ServiceItem
import cn.lemwood.keyvault.data.model.VaultProfile

internal enum class ImportStrategy { ADD, MERGE, REPLACE }

internal enum class ImportChangeKind { ADDED, MODIFIED, DELETED }

internal data class ImportChange(val kind: ImportChangeKind, val path: String)

internal enum class ImportResolution { LOCAL, INCOMING, BOTH }

internal data class ImportConflictId(val service: String, val item: String, val keyIndex: Int? = null)

internal class ImportConflict(
    val id: ImportConflictId,
    val local: String,
    val incoming: String,
    val allowBoth: Boolean,
)

internal class ImportPlan(
    val strategy: ImportStrategy,
    val target: VaultProfile?,
    val result: VaultProfile,
    val changes: List<ImportChange>,
    val conflicts: List<ImportConflict>,
) {
    fun count(kind: ImportChangeKind): Int = changes.count { it.kind == kind }
}

internal fun uniqueImportName(base: String, profiles: List<VaultProfile>): String {
    val names = profiles.mapTo(mutableSetOf()) { it.name }
    if (base !in names) return base
    var suffix = 2
    while ("$base ($suffix)" in names) suffix++
    return "$base ($suffix)"
}

internal fun previewImport(
    backup: VaultProfile,
    strategy: ImportStrategy,
    target: VaultProfile? = null,
    name: String = backup.name,
    resolutions: Map<ImportConflictId, ImportResolution> = emptyMap(),
): ImportPlan {
    if (strategy == ImportStrategy.ADD) {
        val result = VaultProfile(name = name, services = backup.services)
        return ImportPlan(strategy, null, result, compareServices(emptyList(), result.services), emptyList())
    }
    val destination = requireNotNull(target)
    val conflicts = mutableListOf<ImportConflict>()
    val services = when (strategy) {
        ImportStrategy.ADD -> error("Add creates a separate profile")
        ImportStrategy.MERGE -> ProfileMerge(resolutions, conflicts).merge(destination.services, backup.services)
        ImportStrategy.REPLACE -> backup.services
    }
    val result = destination.copy(services = services)
    return ImportPlan(strategy, destination, result, compareServices(destination.services, services), conflicts)
}

private fun compareServices(before: List<Service>, after: List<Service>): List<ImportChange> = buildList {
    compareEntries(before, after, Service::id) { oldService, newService, _ ->
        val service = requireNotNull(newService ?: oldService)
        recordEntry(oldService, newService, service.name) { old, new -> old.name != new.name }
        compareEntries(oldService?.items.orEmpty(), newService?.items.orEmpty(), ServiceItem::id) { oldItem, newItem, _ ->
            val item = requireNotNull(newItem ?: oldItem)
            val path = "${service.name} / ${item.name}"
            recordEntry(oldItem, newItem, path) { old, new -> old.name != new.name || old.apiUrl != new.apiUrl }
            compareEntries(oldItem?.keys.orEmpty(), newItem?.keys.orEmpty(), ApiKey::id) { oldKey, newKey, index ->
                recordEntry(oldKey, newKey, "$path / Key ${index + 1}") { old, new -> old != new }
            }
        }
    }
}

private inline fun <T> compareEntries(
    before: List<T>,
    after: List<T>,
    id: (T) -> String,
    visit: (T?, T?, Int) -> Unit,
) {
    val old = before.associateBy(id)
    val new = after.associateBy(id)
    before.forEachIndexed { index, value -> visit(value, new[id(value)], index) }
    after.forEachIndexed { index, value -> if (id(value) !in old) visit(null, value, index) }
}

private inline fun <T> MutableList<ImportChange>.recordEntry(
    before: T?,
    after: T?,
    path: String,
    modified: (T, T) -> Boolean,
) {
    val kind = when {
        before == null -> ImportChangeKind.ADDED
        after == null -> ImportChangeKind.DELETED
        modified(before, after) -> ImportChangeKind.MODIFIED
        else -> return
    }
    add(ImportChange(kind, path))
}
