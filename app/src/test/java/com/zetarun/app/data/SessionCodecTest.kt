package com.zetarun.app.data

import com.zetarun.app.domain.Operation
import com.zetarun.app.domain.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCodecTest {
    private fun round(id: String, time: Long = 1000L) = SessionRecord(
        id = id,
        startedAtEpochMs = time,
        durationSeconds = 120,
        elapsedMs = 120_000,
        score = 42,
        attempts = 47,
        meanResponseMs = 1950,
        bestStreak = 12,
        operations = setOf(Operation.ADD, Operation.DIVIDE),
        completed = true,
        inputMode = "voice",
    )

    @Test fun aggregateResultsSurviveLocalJsonAndCloudMapRoundTrips() {
        val expected = round("round-123")
        assertEquals(listOf(expected), SessionCodec.decode(SessionCodec.encode(listOf(expected))))
        // Firestore materializes all integral values as Long, unlike JSONObject.
        val firestoreMap = SessionCodec.toMap(expected).mapValues { (_, value) ->
            if (value is Int) value.toLong() else value
        }
        assertEquals(expected, SessionCodec.fromMap(firestoreMap))
    }

    @Test fun retryAndGuestMigrationCannotDuplicateAnExistingRound() {
        val older = round("older", 1000)
        val newer = round("newer", 2000)
        val existingCloud = listOf(older)
        val once = SessionCodec.merge(existingCloud, listOf(older, newer))
        val retried = SessionCodec.merge(once, listOf(older, newer))
        assertEquals(listOf(newer, older), once)
        assertEquals(once, retried)
    }

    @Test fun aStaleCloudCopyDoesNotReplaceTheLocalImmutableRecord() {
        val local = round("same")
        val incoming = local.copy(score = 1)
        assertEquals(local, SessionCodec.merge(listOf(local), listOf(incoming)).single())
    }

    @Test fun emptyAndMissingHistoryAreValidFirstLaunchState() {
        assertTrue(SessionCodec.decode(null).isEmpty())
        assertTrue(SessionCodec.decode("[]").isEmpty())
    }

    @Test(expected = Exception::class)
    fun malformedHistoryIsReportedInsteadOfSilentlyDiscarded() {
        SessionCodec.decode("[{\"id\":\"not-a-complete-record\"}]")
    }

    @Test(expected = IllegalArgumentException::class)
    fun idsCannotEscapeTheUsersSessionDocumentPath() {
        SessionCodec.fromMap(SessionCodec.toMap(round("valid")) + ("id" to "../../other-user"))
    }

    @Test fun persistedSchemaContainsNoVoicePayloadOrIdentity() {
        val keys = SessionCodec.toMap(round("valid")).keys
        assertFalse(keys.any { it.contains("audio", true) || it.contains("transcript", true) })
        assertFalse(keys.any { it in setOf("email", "name", "idToken") })
    }

    @Test fun actualKeypadInputModeSurvivesBothPersistencePaths() {
        val expected = round("keypad-round").copy(inputMode = "keypad")
        assertEquals(expected, SessionCodec.decode(SessionCodec.encode(listOf(expected))).single())
        assertEquals(expected, SessionCodec.fromMap(SessionCodec.toMap(expected)))
    }
}
