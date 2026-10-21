package com.zetarun.app.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Causal 80 Hz Butterworth high-pass: reduce DC/rumble without boosting quiet breaths. */
class AudioPreprocessor(sampleRate: Int = 16_000) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var z1 = 0.0
    private var z2 = 0.0

    init {
        require(sampleRate > 160) { "Sample rate must exceed twice the cutoff." }
        val angle = 2 * PI * 80 / sampleRate
        val cosine = cos(angle)
        val alpha = sin(angle) / sqrt(2.0)
        val denominator = 1 + alpha
        b0 = (1 + cosine) / (2 * denominator)
        b1 = -(1 + cosine) / denominator
        b2 = b0
        a1 = -2 * cosine / denominator
        a2 = (1 - alpha) / denominator
    }

    /** Input/output may be the same array. Returns filtered RMS on a 0..1 PCM scale. */
    fun process(input: ShortArray, count: Int, output: ShortArray): Float {
        require(count >= 0 && count <= input.size && count <= output.size)
        if (count == 0) return 0f
        var squares = 0.0
        for (index in 0 until count) {
            val sample = input[index].toDouble()
            val filtered = b0 * sample + z1
            z1 = b1 * sample - a1 * filtered + z2
            z2 = b2 * sample - a2 * filtered
            val pcm = filtered.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            output[index] = pcm
            val normalized = pcm.toDouble() / 32768
            squares += normalized * normalized
        }
        return sqrt(squares / count).toFloat()
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }
}
