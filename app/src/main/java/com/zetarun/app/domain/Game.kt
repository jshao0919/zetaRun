package com.zetarun.app.domain

import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class Operation(val symbol: String) {
    ADD("+"), SUBTRACT("−"), MULTIPLY("×"), DIVIDE("÷")
}

/** Operand ranges match the original trainer. Subtraction/division invert addition/multiplication. */
data class GameConfig(
    val durationSeconds: Int = 120,
    val operations: Set<Operation> = Operation.entries.toSet(),
    val addLeftMin: Int = 2,
    val addLeftMax: Int = 100,
    val addRightMin: Int = 2,
    val addRightMax: Int = 100,
    val multiplyLeftMin: Int = 2,
    val multiplyLeftMax: Int = 12,
    val multiplyRightMin: Int = 2,
    val multiplyRightMax: Int = 100,
) {
    init {
        require(durationSeconds in 1..3600) { "Choose a duration from 1 to 3600 seconds." }
        require(operations.isNotEmpty()) { "Select at least one operation." }
        require(addLeftMin in 0..1000 && addLeftMax in addLeftMin..1000)
        require(addRightMin in 0..1000 && addRightMax in addRightMin..1000)
        require(multiplyLeftMin in 1..1000 && multiplyLeftMax in multiplyLeftMin..1000)
        require(multiplyRightMin in 1..1000 && multiplyRightMax in multiplyRightMin..1000)
    }
}

data class Problem(val left: Int, val right: Int, val operation: Operation) {
    init {
        require(operation != Operation.DIVIDE || (right != 0 && left % right == 0)) {
            "Division problems must have an exact integer answer."
        }
    }

    val answer: Int
        get() = when (operation) {
            Operation.ADD -> Math.addExact(left, right)
            Operation.SUBTRACT -> Math.subtractExact(left, right)
            Operation.MULTIPLY -> Math.multiplyExact(left, right)
            Operation.DIVIDE -> left / right
        }

    val display: String get() = "$left ${operation.symbol} $right"
}

class ProblemGenerator(private val config: GameConfig, private val random: Random = Random.Default) {
    fun next(): Problem {
        val operation = config.operations.sortedBy { it.ordinal }.random(random)
        return when (operation) {
            Operation.ADD, Operation.SUBTRACT -> {
                val a = random.nextInt(config.addLeftMin, config.addLeftMax + 1)
                val b = random.nextInt(config.addRightMin, config.addRightMax + 1)
                if (operation == Operation.ADD) Problem(a, b, operation) else Problem(a + b, a, operation)
            }
            Operation.MULTIPLY, Operation.DIVIDE -> {
                val a = random.nextInt(config.multiplyLeftMin, config.multiplyLeftMax + 1)
                val b = random.nextInt(config.multiplyRightMin, config.multiplyRightMax + 1)
                if (operation == Operation.MULTIPLY) Problem(a, b, operation) else Problem(a * b, a, operation)
            }
        }
    }
}

data class SessionRecord(
    val id: String,
    val startedAtEpochMs: Long,
    val durationSeconds: Int,
    val elapsedMs: Long,
    val score: Int,
    val attempts: Int,
    val meanResponseMs: Long,
    val bestStreak: Int,
    val operations: Set<Operation>,
    val completed: Boolean,
    val inputMode: String = "voice",
    val finishedAtEpochMs: Long = startedAtEpochMs + elapsedMs,
) {
    val accuracy: Double get() = if (attempts == 0) 0.0 else score.toDouble() / attempts
    val answersPerMinute: Double get() = if (elapsedMs == 0L) 0.0 else score * 60_000.0 / elapsedMs
}

enum class SubmissionResult { CORRECT, INCORRECT, EXPIRED, NOT_RUNNING }

/** Monotonic timestamps keep wall-clock changes from extending rounds. Call from one thread. */
class SessionEngine(val config: GameConfig, random: Random = Random.Default) {
    private val generator = ProblemGenerator(config, random)
    private var startMs = 0L
    private var questionStartMs = 0L
    private var lastSeenMs = 0L
    private var wallStartMs = 0L
    private var responseTotalMs = 0L
    private var sessionId = ""
    private var record: SessionRecord? = null
    private var pausedAtMs: Long? = null
    private var pausedTotalMs = 0L
    private var activeInputMode = "voice"

    var currentProblem: Problem? = null
        private set
    var score: Int = 0
        private set
    var attempts: Int = 0
        private set
    var streak: Int = 0
        private set
    var bestStreak: Int = 0
        private set
    var isRunning: Boolean = false
        private set
    val isPaused: Boolean get() = pausedAtMs != null

    fun start(nowMs: Long, startedAtEpochMs: Long = System.currentTimeMillis(), inputMode: String = "voice") {
        check(!isRunning) { "Finish the active session before starting another." }
        require(nowMs >= 0L)
        startMs = nowMs
        questionStartMs = nowMs
        lastSeenMs = nowMs
        wallStartMs = startedAtEpochMs
        responseTotalMs = 0
        sessionId = UUID.randomUUID().toString()
        record = null
        pausedAtMs = null
        pausedTotalMs = 0
        activeInputMode = inputMode
        score = 0
        attempts = 0
        streak = 0
        bestStreak = 0
        currentProblem = generator.next()
        isRunning = true
    }

    fun remainingMs(nowMs: Long): Long {
        if (sessionId.isEmpty()) return config.durationSeconds * 1000L
        val elapsed = record?.elapsedMs ?: elapsedMs(nowMs)
        return max(0, config.durationSeconds * 1000L - elapsed)
    }

    fun submit(answer: Int, nowMs: Long): SubmissionResult {
        if (!isRunning || isPaused) return SubmissionResult.NOT_RUNNING
        checkTime(nowMs)
        if (remainingMs(nowMs) == 0L) {
            finish(nowMs)
            return SubmissionResult.EXPIRED
        }
        attempts++
        if (answer != currentProblem?.answer) {
            streak = 0
            return SubmissionResult.INCORRECT
        }
        score++
        streak++
        bestStreak = max(bestStreak, streak)
        responseTotalMs += nowMs - questionStartMs
        questionStartMs = nowMs
        currentProblem = generator.next()
        return SubmissionResult.CORRECT
    }

    fun pause(nowMs: Long): Boolean {
        if (!isRunning || isPaused) return false
        checkTime(nowMs)
        if (remainingMs(nowMs) == 0L) {
            finish(nowMs)
            return false
        }
        pausedAtMs = nowMs
        return true
    }

    fun resume(nowMs: Long): Boolean {
        if (!isRunning) return false
        val pausedAt = pausedAtMs ?: return false
        checkTime(nowMs)
        val pausedFor = nowMs - pausedAt
        pausedTotalMs += pausedFor
        questionStartMs += pausedFor
        pausedAtMs = null
        return true
    }

    /** Skipping advances the question and counts one incorrect attempt. */
    fun skip(nowMs: Long): Boolean {
        if (!isRunning || isPaused) return false
        checkTime(nowMs)
        if (remainingMs(nowMs) == 0L) {
            finish(nowMs)
            return false
        }
        attempts++
        streak = 0
        questionStartMs = nowMs
        currentProblem = generator.next()
        return true
    }

    /** Idempotent: stopping and a timer expiring together cannot create duplicate sessions. */
    fun finish(nowMs: Long, inputMode: String = activeInputMode): SessionRecord {
        record?.let { return it }
        check(sessionId.isNotEmpty()) { "The session has not started." }
        checkTime(nowMs)
        val durationMs = config.durationSeconds * 1000L
        val elapsed = min(durationMs, elapsedMs(nowMs))
        val totalPaused = pausedTotalMs + (pausedAtMs?.let { nowMs - it } ?: 0L)
        return SessionRecord(
            id = sessionId,
            startedAtEpochMs = wallStartMs,
            durationSeconds = config.durationSeconds,
            elapsedMs = elapsed,
            score = score,
            attempts = attempts,
            meanResponseMs = if (score == 0) 0 else responseTotalMs / score,
            bestStreak = bestStreak,
            operations = config.operations.toSet(),
            completed = elapsed == durationMs,
            inputMode = inputMode,
            finishedAtEpochMs = wallStartMs + elapsed + totalPaused,
        ).also {
            record = it
            isRunning = false
            pausedAtMs = null
        }
    }

    private fun elapsedMs(nowMs: Long): Long = max(0, (pausedAtMs ?: nowMs) - startMs - pausedTotalMs)

    private fun checkTime(nowMs: Long) {
        require(nowMs >= lastSeenMs) { "Session timestamps must use the same monotonic clock." }
        lastSeenMs = nowMs
    }
}
