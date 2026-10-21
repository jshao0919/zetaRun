package com.zetarun.app.voice

import java.util.Locale
import kotlin.math.max

/**
 * Acoustic eligibility is separate from decoder endpointing. Silero probability
 * is evidence of speech, not proof of who spoke or of an intended answer.
 * Call from the capture thread once per new audio frame, before decoding it.
 */
class UtteranceGate(
    private val quietMillis: Long = 300,
    private val stableMillis: Long = 150,
    private val rearmMillis: Long = 120,
    private val nextId: () -> Long = run { var id = 0L; { ++id } },
) {
    private var id: Long? = null
    private var decoderOpen = false
    private var nativeFinalSeen = false
    private var finalEmitted = false
    private var candidate = ""
    private var candidateIsFinal = false
    private var changedAt = 0L
    private var partialEmitted = ""
    private var latestEmissionId = Long.MIN_VALUE
    private var lastSpeechAt = Long.MIN_VALUE
    private var lastQualifiedAt = Long.MIN_VALUE
    private var observedRelease = false
    private var confirmedRun = false
    private var strongRunMillis = 0L
    private var strongRunFrames = 0
    private var releaseRunMillis = 0L
    private var needsRelease = false
    private var suppressUntil = Long.MIN_VALUE
    private var resetPending = false
    private var finalizePending = false
    private var lastTime = Long.MIN_VALUE
    private var lastAudioAt = Long.MIN_VALUE

    init {
        require(quietMillis in 100..600)
        require(stableMillis in 0..600)
        require(rearmMillis >= 0)
    }

    /**
     * A start includes the confirming frame. Replay retained pre-roll before
     * decoding that frame so confirmation does not clip a short word's onset.
     * During suppression observe only release; those frames cannot rearm speech.
     */
    fun observeAudio(nowMs: Long, speechProbability: Float, frameMillis: Long = 32): AudioGateObservation {
        checkTime(nowMs)
        require(speechProbability.isFinite() && speechProbability in 0f..1f)
        require(frameMillis in 1..64)
        if (lastAudioAt != Long.MIN_VALUE && nowMs - lastAudioAt > max(96L, frameMillis * 3)) {
            clearRuns()
            releaseRunMillis = 0
        }
        lastAudioAt = nowMs
        var startedSpeech = false
        if (id != null && !freshProof(nowMs)) discardSegment(requireRelease = true)

        if (speechProbability < WEAK_SPEECH_PROBABILITY) {
            clearRuns()
            releaseRunMillis += frameMillis
            if (releaseRunMillis >= MIN_RELEASE_MILLIS) needsRelease = false
            if (id != null && nowMs - lastSpeechAt >= quietMillis) observedRelease = true
        } else {
            releaseRunMillis = 0
            if (nowMs < suppressUntil || needsRelease) {
                clearRuns()
            } else {
                if (speechProbability >= STRONG_SPEECH_PROBABILITY) {
                    strongRunFrames++
                    strongRunMillis += frameMillis
                } else {
                    strongRunFrames = 0
                    strongRunMillis = 0
                }
                val qualified = strongRunFrames >= MIN_STRONG_FRAMES && strongRunMillis >= MIN_SPEECH_MILLIS
                if (qualified && (id == null || (!decoderOpen && observedRelease))) {
                    beginSegment(nowMs)
                    startedSpeech = true
                } else if (id != null && (confirmedRun || qualified)) {
                    // Weak frames can continue a confirmed run. After silence,
                    // a new isolated spike cannot extend its endpoint.
                    lastSpeechAt = nowMs
                    if (qualified) lastQualifiedAt = nowMs
                    confirmedRun = true
                    observedRelease = false
                }
            }
        }

        if (id != null && decoderOpen && nowMs - lastSpeechAt >= decoderTailMillis()) {
            // Let Vosk process a bounded quiet tail, then request finalResult
            // before reset so an ignored wrong partial can become a final attempt.
            decoderOpen = false
            resetPending = true
            finalizePending = true
            observedRelease = true
        }
        val reset = resetPending
        val finalize = finalizePending
        resetPending = false
        finalizePending = false
        return AudioGateObservation(
            shouldDecode = id != null && decoderOpen && nowMs >= suppressUntil,
            startedSpeech = startedSpeech,
            resetDecoder = reset,
            finalizeDecoder = finalize,
        )
    }

    /** Decoder text and native final boundaries cannot manufacture acoustic proof. */
    fun onRecognition(
        nowMs: Long,
        text: String,
        isFinal: Boolean,
        minWordConfidence: Float? = null,
    ): VoiceHypothesis? {
        checkTime(nowMs)
        if (id == null || nowMs < suppressUntil || !freshProof(nowMs) || nativeFinalSeen) return null
        val clean = text.trim().lowercase(Locale.ROOT).replace(WHITESPACE, " ")
        if (isFinal) {
            nativeFinalSeen = true
            val wasDecoderOpen = decoderOpen
            decoderOpen = false
            if (wasDecoderOpen) resetPending = true
            if (minWordConfidence == null || !minWordConfidence.isFinite() ||
                minWordConfidence < MIN_FINAL_CONFIDENCE || minWordConfidence > 1f) {
                // Do not retain a plausible partial when the completed word
                // evidence is absent or contradicts it.
                candidate = ""
                candidateIsFinal = false
                return null
            }
        }
        if (clean != candidate) {
            candidate = clean
            changedAt = nowMs
        }
        candidateIsFinal = isFinal
        return poll(nowMs)
    }

    /** Evaluate on every frame, including quiet frames while decoding is idle. */
    fun poll(nowMs: Long): VoiceHypothesis? {
        checkTime(nowMs)
        val currentId = id ?: return null
        if (nowMs < suppressUntil || !freshProof(nowMs) || candidate.isEmpty() || "[unk]" in candidate) return null
        // Resolve a possible new onset on the next frame before accepting an
        // older candidate. A lone spike never resets either endpoint clock.
        if (strongRunFrames > 0 && !confirmedRun) return null
        if (nowMs - lastSpeechAt < quietMillis || nowMs - changedAt < stableMillis) return null
        if (candidateIsFinal) {
            if (finalEmitted) return null
            finalEmitted = true
        } else {
            if (candidate == partialEmitted) return null
            partialEmitted = candidate
        }
        latestEmissionId = currentId
        return VoiceHypothesis(candidate, currentId, candidateIsFinal, nowMs - lastSpeechAt)
    }

    /** Capture-thread acknowledgement, including a final emitted on the prior frame. */
    fun acknowledge(utteranceId: Long, nowMs: Long): Boolean {
        checkTime(nowMs)
        if (utteranceId != id || utteranceId != latestEmissionId) return false
        discardSegment(requireRelease = true)
        // The app already accepted this answer: discard, never replay finalResult.
        finalizePending = false
        suppressUntil = nowMs + rearmMillis
        return true
    }

    fun isSuppressed(nowMs: Long): Boolean = nowMs < suppressUntil

    private fun beginSegment(nowMs: Long) {
        id = nextId()
        decoderOpen = true
        nativeFinalSeen = false
        finalEmitted = false
        candidate = ""
        candidateIsFinal = false
        changedAt = nowMs
        partialEmitted = ""
        latestEmissionId = Long.MIN_VALUE
        lastSpeechAt = nowMs
        lastQualifiedAt = nowMs
        observedRelease = false
        confirmedRun = true
        resetPending = true
        finalizePending = false
    }

    private fun discardSegment(requireRelease: Boolean) {
        resetPending = true
        finalizePending = decoderOpen
        id = null
        decoderOpen = false
        nativeFinalSeen = false
        finalEmitted = false
        candidate = ""
        candidateIsFinal = false
        partialEmitted = ""
        latestEmissionId = Long.MIN_VALUE
        lastSpeechAt = Long.MIN_VALUE
        lastQualifiedAt = Long.MIN_VALUE
        observedRelease = false
        clearRuns()
        releaseRunMillis = 0
        needsRelease = requireRelease
    }

    private fun clearRuns() {
        confirmedRun = false
        strongRunMillis = 0
        strongRunFrames = 0
    }

    private fun freshProof(nowMs: Long): Boolean =
        lastQualifiedAt != Long.MIN_VALUE && nowMs - lastQualifiedAt <= MAX_PROOF_AGE_MILLIS

    private fun decoderTailMillis(): Long = max(700L, quietMillis + stableMillis)

    private fun checkTime(nowMs: Long) {
        require(nowMs >= lastTime) { "Gate timestamps must use one monotonic clock." }
        lastTime = nowMs
    }

    private companion object {
        const val STRONG_SPEECH_PROBABILITY = 0.60f
        const val WEAK_SPEECH_PROBABILITY = 0.35f
        const val MIN_SPEECH_MILLIS = 64L
        const val MIN_STRONG_FRAMES = 2
        const val MIN_RELEASE_MILLIS = 64L
        const val MAX_PROOF_AGE_MILLIS = 1200L
        const val MIN_FINAL_CONFIDENCE = 0.62f
        val WHITESPACE = Regex("\\s+")
    }
}

data class AudioGateObservation(
    val shouldDecode: Boolean,
    val startedSpeech: Boolean,
    val resetDecoder: Boolean,
    /** Fetch and submit the old native final result before resetting. */
    val finalizeDecoder: Boolean = false,
)

data class VoiceHypothesis(
    val text: String,
    val utteranceId: Long,
    val isFinal: Boolean,
    /** Approximate interval since the last qualified acoustic speech frame. */
    val latencyMillis: Long,
)
