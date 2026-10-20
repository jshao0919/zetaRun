package com.zetarun.app.voice

/**
 * Pure endpoint gate. A matching numeric prefix is never a reason to submit early:
 * both an unchanged decoder hypothesis and a quiet interval must be observed.
 * Long pauses inside a number can still look like an endpoint; this is a tunable
 * tradeoff, not a guarantee that a recognizer can infer a speaker's intention.
 */
class UtteranceGate(
    private val quietMillis: Long = 300,
    private val stableMillis: Long = 150,
    private val rearmMillis: Long = 120,
    private val nextId: () -> Long = run { var id = 0L; { ++id } },
) {
    private var id = nextId()
    private var text = ""
    private var changedAt = 0L
    private var lastSpeechAt = 0L
    private var partialEmitted = ""
    private var suppressUntil = Long.MIN_VALUE
    private var latestEmissionId = Long.MIN_VALUE
    private var heardVoicedFrame = false

    /** Called on the capture thread. Time must be monotonic, in milliseconds. */
    fun update(now: Long, hypothesis: String, isFinal: Boolean, voiced: Boolean): VoiceHypothesis? {
        if (now < suppressUntil) return null
        val clean = hypothesis.trim().lowercase().replace(Regex("\\s+"), " ")
        if (voiced) {
            heardVoicedFrame = true
            lastSpeechAt = now
        }
        if (clean != text) {
            text = clean
            changedAt = now
            // A changed hypothesis is also evidence that decoding has not settled,
            // even if the phone's simple energy gate missed a softly spoken word.
            lastSpeechAt = now
        }
        // Decoder text alone can be a hallucination in silence. Every utterance,
        // including one after an acknowledged answer, needs new acoustic activity.
        // This energy check is conservative; it cannot distinguish loud music from
        // a user's voice and does not replace real-device treadmill evaluation.
        val canEmit = heardVoicedFrame && clean.isNotEmpty() && "[unk]" !in clean &&
            (isFinal || (now - changedAt >= stableMillis && now - lastSpeechAt >= quietMillis))
        val result = if (canEmit && (isFinal || clean != partialEmitted)) {
            latestEmissionId = id
            partialEmitted = clean
            VoiceHypothesis(clean, id, isFinal, (now - lastSpeechAt).coerceAtLeast(0))
        } else null
        if (isFinal) newUtterance(now)
        return result
    }

    /** Returns false for stale/duplicate acknowledgements. */
    fun acknowledge(utteranceId: Long, now: Long): Boolean {
        if (utteranceId != latestEmissionId) return false
        latestEmissionId = Long.MIN_VALUE
        newUtterance(now)
        // Drain queued PCM without feeding it back to the reset decoder. A new
        // decoder hypothesis then necessarily comes from audio after this guard.
        suppressUntil = now + rearmMillis
        return true
    }

    fun isSuppressed(now: Long): Boolean = now < suppressUntil

    private fun newUtterance(now: Long) {
        id = nextId()
        text = ""
        changedAt = now
        lastSpeechAt = now
        partialEmitted = ""
        heardVoicedFrame = false
    }
}

data class VoiceHypothesis(
    val text: String,
    val utteranceId: Long,
    val isFinal: Boolean,
    /** Approximate endpoint delay after the last energetic/changing frame. */
    val latencyMillis: Long,
)
