package com.wynh.appapp.grants

/** Capabilities a subapp can use only after the user grants them for that subapp. */
enum class SubappCapability(val key: String) {
    AI("ai"),
    NOTIFICATIONS("notifications"),

    /** Contacting hosts other than the subapp's own origin (remote scripts, APIs, WebSockets). */
    NETWORK("network");

    companion object {
        fun fromKey(key: String): SubappCapability? = entries.firstOrNull { it.key == key }
    }
}

/** Minimal key-value persistence so the grant logic can be tested without Android. */
interface GrantStorage {
    fun get(key: String): String?
    /** Writes every entry at once; a null value removes the key. */
    fun put(values: Map<String, String?>)
}

/**
 * Per-subapp capability grants keyed by stable subapp ID.
 *
 * The backing storage lives outside every workspace (App²'s own SharedPreferences), so subapp code,
 * ZIP exports, backups and history snapshots can neither read nor write it.
 */
class SubappGrantStore(private val storage: GrantStorage) {

    @Synchronized
    fun granted(appId: String): Set<SubappCapability> =
        storage.get(appKey(appId)).orEmpty().split(',').mapNotNull(SubappCapability::fromKey).toSet()

    fun isGranted(appId: String, capability: SubappCapability): Boolean = capability in granted(appId)

    /** Returns true when the stored value changed. */
    @Synchronized
    fun set(appId: String, capability: SubappCapability, granted: Boolean): Boolean {
        val current = granted(appId)
        val next = if (granted) current + capability else current - capability
        if (next == current) return false
        storage.put(mapOf(appKey(appId) to encode(next)))
        return true
    }

    /** Removes every grant of [appId]; used on deletion and after code changes. */
    @Synchronized
    fun clear(appId: String): Set<SubappCapability> {
        val previous = granted(appId)
        if (previous.isNotEmpty()) storage.put(mapOf(appKey(appId) to null))
        return previous
    }

    @Synchronized
    fun migrated(): Boolean = storage.get(MIGRATION_KEY) != null

    /**
     * One-time upgrade step for installs that predate grants: subapps that already have persisted
     * scheduled reminders keep notifications so those reminders do not silently stop, and every
     * existing subapp keeps network access so apps that already call web APIs keep working. AI is
     * never granted automatically. Subapps created or imported afterwards start offline.
     */
    @Synchronized
    fun migrateExisting(appIds: Collection<String>, hasScheduledAlarms: (String) -> Boolean): Boolean {
        if (migrated()) return false
        val values = linkedMapOf<String, String?>()
        appIds.distinct().forEach { id ->
            val extra = if (hasScheduledAlarms(id)) setOf(SubappCapability.NETWORK, SubappCapability.NOTIFICATIONS)
                else setOf(SubappCapability.NETWORK)
            values[appKey(id)] = encode(granted(id) + extra)
        }
        values[MIGRATION_KEY] = "1"
        storage.put(values)
        return true
    }

    private fun encode(capabilities: Set<SubappCapability>): String? =
        capabilities.sortedBy { it.ordinal }.joinToString(",") { it.key }.ifEmpty { null }

    private fun appKey(appId: String) = "grants_$appId"

    companion object {
        const val MIGRATION_KEY = "migration_v1"
    }
}
