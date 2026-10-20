package com.zetarun.app.data

import com.zetarun.app.domain.Operation
import com.zetarun.app.domain.SessionRecord
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalHistoryLedgerTest {
    private class MemoryStorage : HistoryStorage {
        val values = mutableMapOf<String, String>()
        var failWrites = false
        override fun get(key: String): String? = values[key]
        override fun commit(updates: Map<String, String>): Boolean {
            if (failWrites) return false
            values.putAll(updates)
            return true
        }
    }

    private fun session(id: String) = SessionRecord(
        id, 1000, 120, 120_000, 42, 47, 1950, 12, setOf(Operation.ADD), true,
    )

    @Test fun guestRoundsMoveOnceAndCannotLeakToTheNextAccount() {
        val ledger = LocalHistoryLedger(MemoryStorage())
        val guest = session("guest-round")
        ledger.write(null, listOf(guest))
        assertEquals(listOf(guest), ledger.select("account-a"))
        assertTrue(ledger.select(null).isEmpty())
        assertTrue(ledger.select("account-b").isEmpty())
        assertEquals(listOf(guest), ledger.select("account-a"))
    }

    @Test fun switchingAccountsRetainsTheirIndependentHistories() {
        val ledger = LocalHistoryLedger(MemoryStorage())
        ledger.write("account-a", listOf(session("round-a")))
        ledger.write("account-b", listOf(session("round-b")))
        assertEquals("round-a", ledger.select("account-a").single().id)
        assertEquals("round-b", ledger.select("account-b").single().id)
        assertTrue(ledger.select(null).isEmpty())
    }

    @Test fun failedAtomicMigrationRetainsGuestAndExistingAccountData() {
        val storage = MemoryStorage()
        val ledger = LocalHistoryLedger(storage)
        ledger.write(null, listOf(session("guest")))
        ledger.write("owner", listOf(session("existing")))
        val before = storage.values.toMap()
        storage.failWrites = true
        try {
            ledger.select("owner")
            fail("A failed persistence write must be reported")
        } catch (_: IOException) {
            assertEquals(before, storage.values)
        }
        storage.failWrites = false
        assertEquals(setOf("guest", "existing"), ledger.select("owner").map { it.id }.toSet())
        assertTrue(ledger.read(null).isEmpty())
    }

    @Test fun malformedHistoryIsRetainedAndNeverOverwrittenBySelection() {
        val storage = MemoryStorage()
        storage.values["user:owner"] = "broken-json"
        val ledger = LocalHistoryLedger(storage)
        try {
            ledger.select("owner")
            fail("Malformed history must be reported")
        } catch (_: Exception) {
            assertEquals("broken-json", storage.values["user:owner"])
        }
    }

    @Test fun deletingOneBucketCannotEraseAnotherAccountOrGuestResults() {
        val ledger = LocalHistoryLedger(MemoryStorage())
        ledger.write("account-a", listOf(session("a")))
        ledger.write("account-b", listOf(session("b")))
        ledger.write(null, listOf(session("guest")))
        ledger.write("account-a", emptyList())
        assertTrue(ledger.read("account-a").isEmpty())
        assertEquals("b", ledger.read("account-b").single().id)
        assertEquals("guest", ledger.read(null).single().id)
    }

    @Test fun pendingDeletionSurvivesRepositoryRestartAndCannotBeReuploaded() {
        val storage = MemoryStorage()
        val ledger = LocalHistoryLedger(storage)
        ledger.write("owner", listOf(session("deleted"), session("keep")))
        ledger.markDeleted("owner", setOf("deleted"))
        val restarted = LocalHistoryLedger(storage)
        assertEquals(setOf("deleted"), restarted.pendingDeletes("owner"))
        assertEquals(listOf("keep"), restarted.read("owner").map { it.id })
        // A stale network response or delayed round-save callback must not resurrect the result.
        restarted.write("owner", listOf(session("deleted"), session("keep")))
        assertEquals(listOf("keep"), restarted.read("owner").map { it.id })
    }

    @Test fun acknowledgedDeletionStillRejectsAStaleRoundSave() {
        val ledger = LocalHistoryLedger(MemoryStorage())
        ledger.write("owner", listOf(session("deleted")))
        ledger.markDeleted("owner", setOf("deleted"))
        ledger.applyRemoteDeletes("owner", setOf("deleted"))
        assertTrue(ledger.pendingDeletes("owner").isEmpty())
        ledger.write("owner", listOf(session("deleted"), session("new")))
        assertEquals(listOf("new"), ledger.read("owner").map { it.id })
    }

    @Test fun remoteDeletionRemovesAnOfflineDeviceCacheWithoutAffectingNewRounds() {
        val ledger = LocalHistoryLedger(MemoryStorage())
        ledger.write("owner", listOf(session("old-device-round"), session("new-round")))
        ledger.applyRemoteDeletes("owner", setOf("old-device-round"))
        assertEquals(listOf("new-round"), ledger.read("owner").map { it.id })
        ledger.write("owner", listOf(session("old-device-round"), session("new-round")))
        assertEquals(listOf("new-round"), ledger.read("owner").map { it.id })
    }

    @Test fun failedDeletionIntentCommitCannotHideOrLoseResults() {
        val storage = MemoryStorage()
        val ledger = LocalHistoryLedger(storage)
        ledger.write("owner", listOf(session("keep")))
        storage.failWrites = true
        try {
            ledger.markDeleted("owner", setOf("keep"))
            fail("Failed deletion intent must be reported")
        } catch (_: IOException) {
            assertEquals(listOf("keep"), ledger.read("owner").map { it.id })
            assertTrue(ledger.pendingDeletes("owner").isEmpty())
        }
    }

    @Test fun accountDeletionIntentIsDurableAndScopedToItsOwner() {
        val storage = MemoryStorage()
        val ledger = LocalHistoryLedger(storage)
        ledger.markAccountDeletion("owner")
        val restarted = LocalHistoryLedger(storage)
        assertTrue(restarted.accountDeletionRequested("owner"))
        assertTrue(!restarted.accountDeletionRequested("another"))
        assertTrue(!restarted.accountDeletionRequested(null))
        restarted.removeAccount("owner")
        assertTrue(restarted.accountDeletionRequested("owner"))
        assertTrue(restarted.read("owner").isEmpty())
    }

    @Test fun queuedRoundCannotResurrectGuestHistoryAfterDeletion() {
        val ledger = LocalHistoryLedger(MemoryStorage())
        val queuedGeneration = ledger.generation(null)
        ledger.deleteGuestHistory()
        assertEquals(null, ledger.append(null, session("queued-before-delete"), queuedGeneration))
        assertTrue(ledger.read(null).isEmpty())
        val newGeneration = ledger.generation(null)
        ledger.append(null, session("played-after-delete"), newGeneration)
        assertEquals("played-after-delete", ledger.read(null).single().id)
    }

    @Test fun cloudDeletionAlsoRejectsARoundQueuedBeforeItsIdWasPersisted() {
        val ledger = LocalHistoryLedger(MemoryStorage())
        val queuedGeneration = ledger.generation("owner")
        ledger.markDeleted("owner", emptySet())
        assertEquals(null, ledger.append("owner", session("not-yet-in-history"), queuedGeneration))
        assertTrue(ledger.read("owner").isEmpty())
    }
}
