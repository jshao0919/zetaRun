package com.zetarun.app.voice

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AudioPreprocessorTest {
    private fun tone(hertz: Int, count: Int = 32_000) = ShortArray(count) {
        (10_000 * sin(2 * PI * hertz * it / 16_000)).toInt().toShort()
    }

    private fun rms(pcm: ShortArray, from: Int = 16_000): Double =
        sqrt(pcm.drop(from).sumOf { it.toDouble() * it } / (pcm.size - from))

    @Test fun attenuatesMotorRumbleWhilePreservingSpeechBand() {
        for ((frequency, low, high) in listOf(Triple(30, 0.12, 0.16), Triple(80, 0.68, 0.73), Triple(1_000, 0.98, 1.02))) {
            val source = tone(frequency)
            val filtered = ShortArray(source.size)
            AudioPreprocessor().process(source, source.size, filtered)
            val gain = rms(filtered) / rms(source)
            assertTrue("$frequency Hz gain: $gain", gain in low..high)
        }
    }

    @Test fun rejectsDcAndKeepsSilenceSilent() {
        val filter = AudioPreprocessor()
        val output = ShortArray(16_000)
        filter.process(ShortArray(output.size) { 10_000 }, output.size, output)
        assertTrue(output.takeLast(1_000).all { it.toInt() == 0 })
        filter.reset()
        assertEquals(0f, filter.process(ShortArray(512), 512, output), 0f)
    }

    @Test fun arbitraryReadBoundariesMatchContinuousProcessing() {
        val source = tone(375, 4_097)
        val expected = ShortArray(source.size)
        AudioPreprocessor().process(source, source.size, expected)
        val filter = AudioPreprocessor()
        val actual = mutableListOf<Short>()
        source.toList().chunked(137).forEach { chunk ->
            val buffer = chunk.toShortArray()
            filter.process(buffer, buffer.size, buffer)
            actual.addAll(buffer.toList())
        }
        assertArrayEquals(expected, actual.toShortArray())
    }

    @Test fun reportsRmsAndResetRestoresInitialResponse() {
        val source = tone(1_000, 512)
        val first = ShortArray(source.size)
        val filter = AudioPreprocessor()
        val level = filter.process(source, source.size, first)
        assertTrue(level in 0.20f..0.23f)
        filter.reset()
        val again = ShortArray(source.size)
        filter.process(source, source.size, again)
        assertArrayEquals(first, again)
    }
}
