package com.zetarun.app.data

import com.zetarun.app.domain.SessionRecord
import java.io.IOException
import org.json.JSONArray

/** Implementations must commit the entire update atomically (SharedPreferences.Editor does). */
internal interface HistoryStorage {
    fun get(key: String): String?
    fun commit(updates: Map<String, String>): Boolean
}

/** Account ownership and guest migration are independent of Android and network state. */
internal class LocalHistoryLedger(private val storage: HistoryStorage) {
    fun read(owner: String?): List<SessionRecord> {
        val deleted = pendingDeletes(owner) + confirmedDeletes(owner)
        return SessionCodec.decode(storage.get(key(owner))).filterNot { it.id in deleted }
    }

    fun select(owner: String?): List<SessionRecord> {
        val account = read(owner)
        val guest = if (owner != null) read(null) else emptyList()
        if (guest.isEmpty()) return account
        val deleted = pendingDeletes(owner) + confirmedDeletes(owner)
        val merged = SessionCodec.merge(account, guest).filterNot { it.id in deleted }
        commit(mapOf(key(owner) to SessionCodec.encode(merged), key(null) to "[]"))
        return merged
    }

    fun write(owner: String?, records: List<SessionRecord>) {
        val deleted = pendingDeletes(owner) + confirmedDeletes(owner)
        commit(mapOf(key(owner) to SessionCodec.encode(records.filterNot { it.id in deleted })))
    }

    fun generation(owner: String?): Long = storage.get(generationKey(owner))?.toLong() ?: 0L

    /** A round queued before a destructive action belongs to the old history generation. */
    fun append(owner: String?, record: SessionRecord, expectedGeneration: Long): List<SessionRecord>? {
        if (generation(owner) != expectedGeneration || accountDeletionRequested(owner)) return null
        write(owner, SessionCodec.merge(read(owner), listOf(record)))
        return read(owner)
    }

    fun deleteGuestHistory() = commit(mapOf(
        key(null) to "[]", generationKey(null) to (generation(null) + 1).toString(),
    ))

    /** Removing visible results and recording deletion intent is one durable operation. */
    fun markDeleted(owner: String, ids: Set<String>) {
        val pending = pendingDeletes(owner) + ids
        commit(mapOf(
            key(owner) to SessionCodec.encode(read(owner).filterNot { it.id in pending }),
            deletedKey(owner) to JSONArray(pending.sorted()).toString(),
            generationKey(owner) to (generation(owner) + 1).toString(),
        ))
    }

    fun pendingDeletes(owner: String?): Set<String> {
        if (owner == null) return emptySet()
        return readIds(deletedKey(owner))
    }

    private fun confirmedDeletes(owner: String?): Set<String> =
        if (owner == null) emptySet() else readIds(confirmedKey(owner))

    private fun readIds(key: String): Set<String> {
        val json = storage.get(key) ?: return emptySet()
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }

    /** Confirmed remote tombstones remove old caches and acknowledge any matching outbox entries. */
    fun applyRemoteDeletes(owner: String, deletedIds: Set<String>) {
        val pending = pendingDeletes(owner) - deletedIds
        commit(mapOf(
            key(owner) to SessionCodec.encode(read(owner).filterNot { it.id in deletedIds }),
            deletedKey(owner) to JSONArray(pending.sorted()).toString(),
            confirmedKey(owner) to JSONArray((confirmedDeletes(owner) + deletedIds).sorted()).toString(),
        ))
    }

    /** Keep the deletion guard so a round-save callback queued before deletion cannot recreate data. */
    fun removeAccount(owner: String) = commit(mapOf(
        key(owner) to "[]", deletedKey(owner) to "[]", confirmedKey(owner) to "[]",
        accountDeleteKey(owner) to "true",
    ))

    fun markAccountDeletion(owner: String) = commit(mapOf(accountDeleteKey(owner) to "true"))

    fun accountDeletionRequested(owner: String?): Boolean =
        owner != null && storage.get(accountDeleteKey(owner)) == "true"

    private fun commit(updates: Map<String, String>) {
        if (!storage.commit(updates)) throw IOException("Cannot persist training history")
    }

    private fun key(owner: String?) = if (owner == null) "guest" else "user:$owner"
    private fun deletedKey(owner: String) = "pending-delete:$owner"
    private fun confirmedKey(owner: String) = "confirmed-delete:$owner"
    private fun accountDeleteKey(owner: String) = "delete-account:$owner"
    private fun generationKey(owner: String?) = "generation:${owner ?: "guest"}"
}
