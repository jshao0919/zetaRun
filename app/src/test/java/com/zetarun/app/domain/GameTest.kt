package com.zetarun.app.domain

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class GameTest {
    @Test fun defaultRangesMatchTrainer() {
        val config = GameConfig()
        assertEquals(120, config.durationSeconds)
        assertEquals(Operation.entries.toSet(), config.operations)
        assertEquals(2, config.addLeftMin)
        assertEquals(100, config.addLeftMax)
        assertEquals(12, config.multiplyLeftMax)
        assertEquals(100, config.multiplyRightMax)
    }

    @Test fun additionAndMultiplicationRespectEveryConfiguredBound() {
        for (op in listOf(Operation.ADD, Operation.MULTIPLY)) {
            val config = GameConfig(
                operations = setOf(op), addLeftMin = 8, addLeftMax = 9,
                addRightMin = 17, addRightMax = 19, multiplyLeftMin = 3, multiplyLeftMax = 4,
                multiplyRightMin = 12, multiplyRightMax = 13,
            )
            val generator = ProblemGenerator(config, Random(41))
            val problems = List(500) { generator.next() }
            assertTrue(problems.all { it.operation == op })
            if (op == Operation.ADD) {
                assertEquals(setOf(8, 9), problems.map { it.left }.toSet())
                assertEquals(setOf(17, 18, 19), problems.map { it.right }.toSet())
                assertTrue(problems.all { it.answer == it.left + it.right })
            } else {
                assertEquals(setOf(3, 4), problems.map { it.left }.toSet())
                assertEquals(setOf(12, 13), problems.map { it.right }.toSet())
                assertTrue(problems.all { it.answer == it.left * it.right })
            }
        }
    }

    @Test fun subtractionAndDivisionAreExactPositiveInverses() {
        for (op in listOf(Operation.SUBTRACT, Operation.DIVIDE)) {
            val generator = ProblemGenerator(GameConfig(operations = setOf(op)), Random(7))
            repeat(1000) {
                val problem = generator.next()
                assertEquals(op, problem.operation)
                assertTrue(problem.answer in 2..100)
                if (op == Operation.SUBTRACT) {
                    assertTrue(problem.right in 2..100)
                    assertEquals(problem.left, problem.answer + problem.right)
                } else {
                    assertTrue(problem.right in 2..12)
                    assertEquals(0, problem.left % problem.right)
                    assertEquals(problem.left, problem.answer * problem.right)
                }
            }
        }
    }

    @Test fun maximumSupportedOperandsDoNotOverflow() {
        val generator = ProblemGenerator(GameConfig(
            operations = setOf(Operation.MULTIPLY), multiplyLeftMin = 1000, multiplyLeftMax = 1000,
            multiplyRightMin = 1000, multiplyRightMax = 1000,
        ))
        assertEquals(1_000_000, generator.next().answer)
    }

    @Test fun invalidConfigurationFailsBeforeStarting() {
        assertInvalid { GameConfig(operations = emptySet()) }
        assertInvalid { GameConfig(durationSeconds = 0) }
        assertInvalid { GameConfig(addLeftMin = 4, addLeftMax = 3) }
        assertInvalid { GameConfig(addRightMin = -1) }
        assertInvalid { GameConfig(multiplyLeftMin = 0) }
        assertInvalid { GameConfig(multiplyRightMax = 1001) }
        assertInvalid { Problem(5, 2, Operation.DIVIDE) }
        assertInvalid { Problem(2, 0, Operation.DIVIDE) }
    }

    @Test fun incorrectAnswerKeepsProblemAndTracksAttempts() {
        val engine = SessionEngine(GameConfig(), Random(10))
        engine.start(1000, 1_000_000)
        val problem = engine.currentProblem!!
        assertEquals(SubmissionResult.INCORRECT, engine.submit(problem.answer + 1, 1250))
        assertEquals(problem, engine.currentProblem)
        assertEquals(0, engine.score)
        assertEquals(1, engine.attempts)
        assertEquals(SubmissionResult.CORRECT, engine.submit(problem.answer, 1500))
        assertEquals(1, engine.score)
        assertEquals(2, engine.attempts)
    }

    @Test fun repeatedAnswersAreDistinctSubmissionsForDistinctQuestions() {
        val config = GameConfig(operations = setOf(Operation.ADD), addLeftMin = 2, addLeftMax = 2,
            addRightMin = 2, addRightMax = 2)
        val engine = SessionEngine(config)
        engine.start(0, 100_000)
        assertEquals(SubmissionResult.CORRECT, engine.submit(4, 500))
        assertEquals(SubmissionResult.CORRECT, engine.submit(4, 1000))
        assertEquals(2, engine.score)
        assertEquals(2, engine.streak)
        val record = engine.finish(1500)
        assertEquals(500L, record.meanResponseMs)
        assertEquals(2, record.bestStreak)
    }

    @Test fun deadlineIsExclusiveAndLateAnswersNeverCount() {
        val engine = SessionEngine(GameConfig(durationSeconds = 1), Random(1))
        assertEquals(SubmissionResult.NOT_RUNNING, engine.submit(0, 0))
        engine.start(100, 1_000_000)
        assertEquals(1L, engine.remainingMs(1099))
        val answer = engine.currentProblem!!.answer
        assertEquals(SubmissionResult.EXPIRED, engine.submit(answer, 1100))
        assertFalse(engine.isRunning)
        val record = engine.finish(1200)
        assertEquals(0, record.score)
        assertEquals(0, record.attempts)
        assertEquals(1000L, record.elapsedMs)
        assertTrue(record.completed)
        assertEquals(1_001_000L, record.finishedAtEpochMs)
        assertEquals(SubmissionResult.NOT_RUNNING, engine.submit(answer, 1200))
    }

    @Test fun completionStatisticsAndStreakIncludeWrongAnswers() {
        val engine = SessionEngine(GameConfig(durationSeconds = 10), Random(21))
        engine.start(100, 2_000_000)
        engine.submit(engine.currentProblem!!.answer, 500)
        engine.submit(engine.currentProblem!!.answer, 1000)
        engine.submit(engine.currentProblem!!.answer + 1, 1100)
        engine.submit(engine.currentProblem!!.answer, 1600)
        val record = engine.finish(10_100, "touch")
        assertEquals(3, record.score)
        assertEquals(4, record.attempts)
        assertEquals(2, record.bestStreak)
        assertEquals(500L, record.meanResponseMs)
        assertEquals(0.75, record.accuracy, 0.00001)
        assertEquals(18.0, record.answersPerMinute, 0.00001)
        assertEquals("touch", record.inputMode)
        assertTrue(record.completed)
        assertSame(record, engine.finish(10_500))
    }

    @Test fun earlyStopIsSavedSeparatelyFromCompletedRounds() {
        val engine = SessionEngine(GameConfig(), Random(2))
        engine.start(50, 500_000)
        val record = engine.finish(450)
        assertFalse(record.completed)
        assertEquals(400L, record.elapsedMs)
        assertEquals(0.0, record.accuracy, 0.0)
        assertEquals(0.0, record.answersPerMinute, 0.0)
    }

    @Test fun restartingCreatesIndependentSessionAndResetsStats() {
        val engine = SessionEngine(GameConfig(), Random(1))
        engine.start(0)
        engine.submit(engine.currentProblem!!.answer, 100)
        val first = engine.finish(200)
        engine.start(300)
        val second = engine.finish(400)
        assertNotEquals(first.id, second.id)
        assertEquals(0, second.score)
        assertEquals(0, second.attempts)
        assertEquals(0, second.bestStreak)
        assertEquals(0L, second.meanResponseMs)
    }

    @Test fun backwardsClockFailsRatherThanInflatingStatistics() {
        val engine = SessionEngine(GameConfig())
        engine.start(100)
        assertInvalid { engine.submit(3, 99) }
    }

    @Test fun pausedTimerAndQuestionStayFrozenAndRejectSubmissions() {
        val engine = SessionEngine(GameConfig(durationSeconds = 10), Random(1))
        engine.start(100, 100_000)
        val question = engine.currentProblem
        assertTrue(engine.pause(1100))
        assertTrue(engine.isRunning)
        assertTrue(engine.isPaused)
        assertEquals(9000L, engine.remainingMs(100_000))
        assertEquals(SubmissionResult.NOT_RUNNING, engine.submit(question!!.answer, 50_000))
        assertFalse(engine.skip(50_000))
        assertEquals(question, engine.currentProblem)
        assertEquals(0, engine.attempts)
        assertFalse(engine.pause(50_000))
        assertTrue(engine.resume(50_000))
        assertFalse(engine.isPaused)
        assertEquals(9000L, engine.remainingMs(50_000))
        assertEquals(8999L, engine.remainingMs(50_001))
    }

    @Test fun multiplePausesDoNotInflateResponseLatency() {
        val engine = SessionEngine(GameConfig(durationSeconds = 10), Random(1))
        engine.start(100, 100_000)
        engine.pause(200)
        engine.resume(1200)
        engine.pause(1300)
        engine.resume(3300)
        engine.submit(engine.currentProblem!!.answer, 3400)
        val record = engine.finish(3500)
        assertEquals(300L, record.meanResponseMs)
        assertEquals(400L, record.elapsedMs)
        assertEquals(103_400L, record.finishedAtEpochMs)
    }

    @Test fun stoppingWhilePausedKeepsActiveElapsedAndRealWallEnd() {
        val engine = SessionEngine(GameConfig(durationSeconds = 10))
        engine.start(100, 100_000)
        engine.pause(1100)
        val record = engine.finish(101_100)
        assertEquals(1000L, record.elapsedMs)
        assertEquals(201_000L, record.finishedAtEpochMs)
        assertFalse(record.completed)
        assertFalse(engine.isPaused)
        assertFalse(engine.isRunning)
        assertEquals(9000L, engine.remainingMs(500_000))
    }

    @Test fun skipResetsStreakAndCountsAnIncorrectAttempt() {
        val engine = SessionEngine(GameConfig(), Random(1))
        assertFalse(engine.skip(0))
        engine.start(0)
        engine.submit(engine.currentProblem!!.answer, 100)
        assertTrue(engine.skip(200))
        assertEquals(0, engine.streak)
        engine.submit(engine.currentProblem!!.answer, 300)
        val record = engine.finish(400)
        assertEquals(2, record.score)
        assertEquals(3, record.attempts)
        assertEquals(1, record.bestStreak)
        assertEquals(100L, record.meanResponseMs)
    }

    @Test fun skippingAtDeadlineNeverGeneratesAnotherQuestion() {
        val engine = SessionEngine(GameConfig(durationSeconds = 1))
        engine.start(0)
        val question = engine.currentProblem
        assertFalse(engine.skip(1000))
        assertEquals(question, engine.currentProblem)
        assertEquals(0, engine.attempts)
        assertTrue(engine.finish(1000).completed)
    }

    @Test fun automaticExpiryPreservesConfiguredInputMode() {
        val engine = SessionEngine(GameConfig(durationSeconds = 1))
        engine.start(0, inputMode = "touch")
        assertEquals(SubmissionResult.EXPIRED, engine.submit(4, 1000))
        assertEquals("touch", engine.finish(1000).inputMode)
    }

    @Test fun pausingAtDeadlineFinishesInsteadOfEnteringPausedState() {
        val engine = SessionEngine(GameConfig(durationSeconds = 1))
        engine.start(0)
        assertFalse(engine.pause(1000))
        assertFalse(engine.isRunning)
        assertFalse(engine.isPaused)
        assertFalse(engine.resume(2000))
        assertTrue(engine.finish(2000).completed)
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected validation error.
        }
    }
}
