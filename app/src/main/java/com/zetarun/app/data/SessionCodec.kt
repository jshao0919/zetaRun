package com.zetarun.app.data

import com.zetarun.app.domain.Operation
import com.zetarun.app.domain.SessionRecord
import org.json.JSONArray
import org.json.JSONObject

/** Only aggregate training results are persisted. Speech and audio never enter this codec. */
internal object SessionCodec {
    fun encode(records: List<SessionRecord>): String = JSONArray().apply {
        records.forEach { put(JSONObject(toMap(it))) }
    }.toString()

    fun decode(json: String?): List<SessionRecord> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            fromMap(item.keys().asSequence().associateWith { item.get(it) })
        }.sortedByDescending { it.startedAtEpochMs }
    }

    fun toMap(record: SessionRecord): Map<String, Any> = linkedMapOf(
        "id" to record.id,
        "startedAtEpochMs" to record.startedAtEpochMs,
        "durationSeconds" to record.durationSeconds,
        "elapsedMs" to record.elapsedMs,
        "score" to record.score,
        "attempts" to record.attempts,
        "meanResponseMs" to record.meanResponseMs,
        "bestStreak" to record.bestStreak,
        "operations" to record.operations.map { it.name }.sorted(),
        "completed" to record.completed,
        "inputMode" to record.inputMode,
        "finishedAtEpochMs" to record.finishedAtEpochMs,
    )

    fun fromMap(fields: Map<String, Any?>): SessionRecord {
        fun number(key: String) = (fields[key] as? Number)?.toLong()
            ?: error("Missing session field: $key")
        val rawOperations = when (val value = fields["operations"]) {
            is JSONArray -> (0 until value.length()).map { value.getString(it) }
            is List<*> -> value.filterIsInstance<String>()
            else -> error("Missing session operations")
        }
        val id = fields["id"] as? String ?: error("Missing session id")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid session id" }
        val record = SessionRecord(
            id = id,
            startedAtEpochMs = number("startedAtEpochMs"),
            durationSeconds = number("durationSeconds").toInt(),
            elapsedMs = number("elapsedMs"),
            score = number("score").toInt(),
            attempts = number("attempts").toInt(),
            meanResponseMs = number("meanResponseMs"),
            bestStreak = number("bestStreak").toInt(),
            operations = rawOperations.map { Operation.valueOf(it) }.toSet(),
            completed = fields["completed"] as? Boolean ?: error("Missing completed flag"),
            inputMode = fields["inputMode"] as? String ?: "voice",
            finishedAtEpochMs = (fields["finishedAtEpochMs"] as? Number)?.toLong()
                ?: (number("startedAtEpochMs") + number("elapsedMs")),
        )
        require(record.startedAtEpochMs >= 0 && record.durationSeconds > 0 && record.elapsedMs >= 0)
        require(record.score >= 0 && record.attempts >= record.score && record.bestStreak >= 0)
        require(record.meanResponseMs >= 0 && record.operations.isNotEmpty())
        require(record.finishedAtEpochMs >= record.startedAtEpochMs)
        return record
    }

    /** Immutable IDs make retries and guest migration idempotent. Account buckets stay separate. */
    fun merge(local: List<SessionRecord>, incoming: List<SessionRecord>): List<SessionRecord> =
        (incoming + local).associateBy { it.id }.values.sortedByDescending { it.startedAtEpochMs }
}
