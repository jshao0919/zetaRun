package com.zetarun.app

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zetarun.app.data.ProgressRepository
import com.zetarun.app.domain.*
import com.zetarun.app.voice.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Stage { HOME, RUNNING, PAUSED, RESULT }

data class TrainerState(
    val stage: Stage = Stage.HOME,
    val config: GameConfig = GameConfig(),
    val voiceEnabled: Boolean = true,
    val quietMillis: Long = 300,
    val problem: String = "",
    val score: Int = 0,
    val streak: Int = 0,
    val remainingMs: Long = 120_000,
    val feedback: String = "",
    val heard: String = "",
    val endpointMs: Long? = null,
    val result: SessionRecord? = null,
    val error: String? = null,
    val savePending: Boolean = false,
)

class TrainerViewModel(application: Application) : AndroidViewModel(application) {
    val repository = ProgressRepository(application)
    val voice = OfflineVoiceEngine(application)
    private val mutableState = MutableStateFlow(TrainerState())
    val state = mutableState.asStateFlow()
    private var engine: SessionEngine? = null
    private var ticker: Job? = null
    private var lastHandledUtterance = Long.MIN_VALUE
    private var usedKeypad = false
    private var usedVoice = false

    init { viewModelScope.launch { voice.prepare() } }

    fun configure(config: GameConfig) {
        if (mutableState.value.stage == Stage.HOME || mutableState.value.stage == Stage.RESULT)
            mutableState.update { it.copy(config = config, remainingMs = config.durationSeconds * 1000L) }
    }

    fun setVoice(enabled: Boolean) {
        mutableState.update { it.copy(voiceEnabled = enabled) }
        if (!enabled) voice.stop()
        else if (mutableState.value.stage == Stage.RUNNING) startListening()
    }

    fun setQuietMillis(millis: Long) {
        voice.setQuietMillis(millis)
        mutableState.update { it.copy(quietMillis = voice.quietMillis) }
    }

    fun start() {
        val config = mutableState.value.config
        if (mutableState.value.voiceEnabled && !voice.isPrepared) {
            mutableState.update { it.copy(error = "Wait for offline voice to be ready, or switch to Keypad.") }
            return
        }
        voice.stop()
        usedKeypad = false
        usedVoice = false
        lastHandledUtterance = Long.MIN_VALUE
        engine = SessionEngine(config).also { it.start(now(), inputMode = if (mutableState.value.voiceEnabled) "voice" else "keypad") }
        mutableState.value = TrainerState(stage = Stage.RUNNING, config = config,
            voiceEnabled = mutableState.value.voiceEnabled,
            quietMillis = mutableState.value.quietMillis,
            problem = engine!!.currentProblem!!.display, remainingMs = config.durationSeconds * 1000L)
        if (mutableState.value.voiceEnabled) startListening()
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                delay(80)
                val current = engine ?: break
                if (!current.isRunning) break
                val remaining = current.remainingMs(now())
                mutableState.update { it.copy(remainingMs = remaining) }
                if (remaining == 0L) { finish(); break }
            }
        }
    }

    private fun startListening() = voice.start(viewModelScope, ::onVoice)

    private fun onVoice(hypothesis: VoiceHypothesis) {
        if (mutableState.value.stage != Stage.RUNNING || hypothesis.utteranceId == lastHandledUtterance) return
        val text = hypothesis.text.trim().lowercase()
        mutableState.update { it.copy(heard = text) }
        // Commands require the same endpoint gate as numeric answers.
        if (text == "pause" || text == "skip") {
            lastHandledUtterance = hypothesis.utteranceId
            voice.acknowledge(hypothesis.utteranceId)
            if (text == "pause") pause() else skip()
            return
        }
        val answer = SpokenNumberParser.parseInt(text)
        if (answer == null) {
            if (hypothesis.isFinal) mutableState.update { it.copy(feedback = "Didn't catch a number. Say the full answer again.") }
            return
        }
        val current = engine ?: return
        // A partial may only submit the expected answer; final wrong answers stay on this problem.
        if (!hypothesis.isFinal && answer != current.currentProblem?.answer) return
        lastHandledUtterance = hypothesis.utteranceId
        usedVoice = true
        voice.acknowledge(hypothesis.utteranceId)
        mutableState.update { it.copy(endpointMs = hypothesis.latencyMillis) }
        submit(answer)
    }

    fun manualAnswer(text: String) {
        val answer = text.toIntOrNull() ?: return
        if (mutableState.value.stage != Stage.RUNNING) return
        // Reset the voice generation so queued speech cannot answer the next problem.
        if (mutableState.value.voiceEnabled) voice.stop()
        usedKeypad = true
        submit(answer)
        if (mutableState.value.voiceEnabled && mutableState.value.stage == Stage.RUNNING) startListening()
    }

    private fun submit(answer: Int) {
        val current = engine ?: return
        val time = now()
        if (current.remainingMs(time) == 0L) { finish(); return }
        when (current.submit(answer, time)) {
            SubmissionResult.CORRECT -> mutableState.update { it.copy(
                problem = current.currentProblem!!.display, score = current.score,
                streak = current.streak, feedback = "Correct") }
            SubmissionResult.INCORRECT -> mutableState.update { it.copy(streak = 0, feedback = "Heard $answer · try again") }
            SubmissionResult.EXPIRED -> finish()
            SubmissionResult.NOT_RUNNING -> Unit
        }
    }

    fun skip() {
        if (mutableState.value.stage != Stage.RUNNING) return
        val current = engine ?: return
        val time = now()
        if (current.remainingMs(time) == 0L) { finish(); return }
        if (mutableState.value.voiceEnabled) voice.stop()
        if (current.skip(time)) mutableState.update { it.copy(
            problem = current.currentProblem!!.display, streak = 0, feedback = "Skipped") }
        if (mutableState.value.voiceEnabled && mutableState.value.stage == Stage.RUNNING) startListening()
    }

    fun pause() {
        if (mutableState.value.stage != Stage.RUNNING) return
        if (engine?.pause(now()) != true) { finish(); return }
        voice.stop()
        mutableState.update { it.copy(stage = Stage.PAUSED, feedback = "Round paused · microphone off") }
    }

    fun resume() {
        if (mutableState.value.stage != Stage.PAUSED) return
        if (engine?.resume(now()) != true) { finish(); return }
        mutableState.update { it.copy(stage = Stage.RUNNING, feedback = "") }
        if (mutableState.value.voiceEnabled) startListening()
    }

    fun finish() {
        val current = engine ?: return
        if (mutableState.value.stage != Stage.RUNNING && mutableState.value.stage != Stage.PAUSED) return
        ticker?.cancel()
        voice.stop()
        val mode = if (usedVoice && usedKeypad) "mixed" else if (usedKeypad || !mutableState.value.voiceEnabled) "keypad" else "voice"
        val record = current.finish(now(), mode)
        mutableState.update { it.copy(stage = Stage.RESULT, result = record, savePending = true) }
        viewModelScope.launch {
            repository.record(record)
            mutableState.update { it.copy(savePending = false) }
        }
    }

    fun home() { voice.stop(); mutableState.update { it.copy(stage = Stage.HOME, result = null, error = null) } }
    fun clearError() { mutableState.update { it.copy(error = null) } }
    private fun now() = SystemClock.elapsedRealtime()
    override fun onCleared() { ticker?.cancel(); voice.close(); repository.close() }
}
