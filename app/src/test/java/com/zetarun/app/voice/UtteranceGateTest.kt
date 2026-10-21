package com.zetarun.app.voice

import org.junit.Assert.*
import org.junit.Test

class UtteranceGateTest {
    @Test fun `two strong frames qualify without losing the short word onset`() {
        val gate = UtteranceGate()
        assertFalse(gate.observeAudio(0, 0.95f).shouldDecode)
        val start = gate.observeAudio(32, 0.95f)
        assertTrue(start.startedSpeech)
        assertTrue(start.shouldDecode)
        assertTrue(start.resetDecoder)
        assertFalse(start.finalizeDecoder)
        assertNull(gate.onRecognition(32, "two", false))
        silence(gate, 64, 320)
        assertNull(gate.poll(331))
        assertEquals("two", gate.poll(332)?.text)
    }

    @Test fun `one is not emitted while a longer number is still being spoken`() {
        val gate = UtteranceGate()
        qualify(gate)
        gate.onRecognition(32, "one", false)
        gate.observeAudio(64, 0.5f)
        assertNull(gate.poll(200))
        gate.observeAudio(220, 0.9f)
        gate.observeAudio(252, 0.9f)
        gate.onRecognition(252, "one hundred", false)
        gate.observeAudio(284, 0.5f)
        gate.observeAudio(316, 0.9f)
        gate.observeAudio(348, 0.9f)
        gate.onRecognition(348, "one hundred twenty", false)
        silence(gate, 380, 636)
        assertNull(gate.poll(647))
        val result = gate.poll(648)
        assertEquals("one hundred twenty", result?.text)
        assertEquals(300L, result?.latencyMillis)
    }

    @Test fun `stable prefix cannot commit while confirmed speech continues`() {
        val gate = UtteranceGate()
        qualify(gate)
        gate.onRecognition(32, "two", false)
        for (time in 64L..1024L step 32) {
            gate.observeAudio(time, 0.9f)
            assertNull(gate.poll(time))
        }
        silence(gate, 1056, 1312)
        assertNull(gate.poll(1323))
        assertEquals("two", gate.poll(1324)?.text)
    }

    @Test fun `decoder churn changes stability but cannot reset acoustic silence`() {
        val gate = UtteranceGate()
        qualify(gate)
        gate.onRecognition(32, "two", false)
        silence(gate, 64, 288)
        assertNull(gate.onRecognition(300, "twenty", false))
        assertNull(gate.poll(449))
        assertEquals("twenty", gate.poll(450)?.text)
    }

    @Test fun `wrong partial can be corrected by one stable final in the same utterance`() {
        val gate = UtteranceGate()
        qualify(gate)
        gate.onRecognition(32, "forty", false)
        silence(gate, 64, 320)
        val first = gate.poll(332)!!
        assertNull(gate.poll(400))
        assertNull(gate.onRecognition(500, "forty two", true, 0.92f))
        assertNull(gate.poll(649))
        val final = gate.poll(650)!!
        assertEquals(first.utteranceId, final.utteranceId)
        assertTrue(final.isFinal)
        assertEquals("forty two", final.text)
        assertNull(gate.onRecognition(660, "forty two", true, 0.92f))
        assertNull(gate.poll(700))
    }

    @Test fun `a final waits for real silence rather than bypassing the endpoint`() {
        val gate = UtteranceGate()
        qualify(gate)
        assertNull(gate.onRecognition(32, "six", true, 0.9f))
        silence(gate, 64, 320)
        assertNull(gate.poll(331))
        val result = gate.poll(332)!!
        assertTrue(result.isFinal)
        assertEquals("six", result.text)
    }

    @Test fun `accepted answer drains audio and a fresh equal answer remains valid`() {
        val gate = UtteranceGate()
        qualify(gate)
        gate.onRecognition(32, "eight", false)
        silence(gate, 64, 320)
        val first = gate.poll(332)!!
        assertTrue(gate.acknowledge(first.utteranceId, 333))
        assertTrue(gate.isSuppressed(400))
        assertTrue(gate.observeAudio(365, 0.05f).resetDecoder)
        gate.observeAudio(397, 0.05f)
        assertNull(gate.onRecognition(400, "eight", true, 0.9f))
        assertFalse(gate.acknowledge(first.utteranceId, 405))
        assertFalse(gate.observeAudio(453, 0.9f).shouldDecode)
        assertTrue(gate.observeAudio(485, 0.9f).startedSpeech)
        gate.onRecognition(485, "eight", false)
        silence(gate, 517, 773)
        val second = gate.poll(785)!!
        assertNotEquals(first.utteranceId, second.utteranceId)
    }

    @Test fun `late acknowledgement of a final still works on next capture frame`() {
        val gate = UtteranceGate()
        qualify(gate)
        gate.onRecognition(32, "six", true, 0.9f)
        silence(gate, 64, 320)
        val first = gate.poll(332)!!
        assertTrue(gate.acknowledge(first.utteranceId, 350))
        assertNull(gate.poll(400))
    }

    @Test fun `empty and unknown results never yield answers`() {
        val gate = UtteranceGate()
        qualify(gate)
        assertNull(gate.onRecognition(32, "", false))
        gate.onRecognition(64, "[unk]", false)
        silence(gate, 96, 352)
        assertNull(gate.poll(400))
        assertNull(gate.onRecognition(500, "one [unk]", true, 0.95f))
    }

    @Test fun `decoder hallucinations without qualified speech never emit`() {
        val gate = UtteranceGate()
        for (time in 0L..1600L step 32) {
            assertFalse(gate.observeAudio(time, 0.1f).shouldDecode)
            assertNull(gate.onRecognition(time, "four", false))
            assertNull(gate.onRecognition(time, "four", true, 0.99f))
            assertNull(gate.poll(time))
        }
    }

    @Test fun `isolated breath spikes and periodic footsteps never qualify speech`() {
        val gate = UtteranceGate()
        for (frame in 0..100) {
            val now = frame * 32L
            val probability = if (frame % 6 == 0) 0.95f else 0.1f
            val observation = gate.observeAudio(now, probability)
            assertFalse(observation.shouldDecode)
            assertFalse(observation.startedSpeech)
            assertNull(gate.onRecognition(now, "two", true, 0.99f))
        }
    }

    @Test fun `steady ambiguous probabilities cannot bootstrap speech proof`() {
        val gate = UtteranceGate()
        for (time in 0L..3200L step 32) {
            assertFalse(gate.observeAudio(time, 0.5f).shouldDecode)
            assertNull(gate.onRecognition(time, "six", true, 0.9f))
        }
    }

    @Test fun `single breath during quiet does not extend the old word endpoint`() {
        val gate = UtteranceGate()
        qualify(gate)
        gate.onRecognition(32, "two", false)
        silence(gate, 64, 256)
        gate.observeAudio(288, 0.95f)
        gate.observeAudio(320, 0.1f)
        assertEquals("two", gate.poll(332)?.text)
    }

    @Test fun `weak frames preserve the tail of an already qualified short word`() {
        val gate = UtteranceGate()
        qualify(gate, probability = 0.61f)
        gate.onRecognition(32, "six", false)
        assertTrue(gate.observeAudio(64, 0.4f).shouldDecode)
        assertTrue(gate.observeAudio(96, 0.36f).shouldDecode)
        silence(gate, 128, 384)
        assertNull(gate.poll(395))
        assertEquals("six", gate.poll(396)?.text)
    }

    @Test fun `stale acoustic proof cannot authorize a later hallucination`() {
        val gate = UtteranceGate()
        qualify(gate)
        gate.observeAudio(1240, 0.1f)
        assertNull(gate.onRecognition(1240, "two", true, 0.99f))
        assertNull(gate.poll(1600))
    }

    @Test fun `weak continuous noise eventually expires old strong proof`() {
        val gate = UtteranceGate()
        qualify(gate)
        gate.onRecognition(32, "four", false)
        for (time in 64L..1216L step 32) {
            gate.observeAudio(time, 0.4f)
            assertNull(gate.poll(time))
        }
        val expired = gate.observeAudio(1248, 0.4f)
        assertFalse(expired.shouldDecode)
        assertTrue(expired.resetDecoder)
        assertNull(gate.onRecognition(1248, "four", true, 0.99f))
    }

    @Test fun `low or missing confidence final invalidates the partial candidate`() {
        for (confidence in listOf(null, 0.4f)) {
            val gate = UtteranceGate()
            qualify(gate)
            gate.onRecognition(32, "two", false)
            silence(gate, 64, 288)
            assertNull(gate.onRecognition(300, "two", true, confidence))
            assertNull(gate.poll(500))
            assertNull(gate.onRecognition(550, "two", true, 0.99f))
        }
    }

    @Test fun `confidence threshold accepts inclusive bound and rejects invalid values`() {
        for (confidence in listOf(0.61f, Float.NaN, Float.POSITIVE_INFINITY, 1.01f)) {
            val gate = UtteranceGate()
            qualify(gate)
            gate.onRecognition(32, "six", false)
            silence(gate, 64, 320)
            assertNull(gate.onRecognition(332, "six", true, confidence))
        }
        val gate = UtteranceGate()
        qualify(gate)
        gate.onRecognition(32, "six", false)
        silence(gate, 64, 320)
        assertEquals("six", gate.onRecognition(332, "six", true, 0.62f)?.text)
    }

    @Test fun `native finals during continuous speech cannot mint new utterances`() {
        val gate = UtteranceGate()
        qualify(gate)
        assertNull(gate.onRecognition(32, "ten", true, 0.95f))
        for (time in 64L..640L step 32) {
            assertFalse(gate.observeAudio(time, 0.9f).startedSpeech)
            assertNull(gate.onRecognition(time, "ten", true, 0.95f))
            assertNull(gate.poll(time))
        }
        silence(gate, 672, 928)
        val result = gate.poll(940)!!
        assertEquals("ten", result.text)
        assertNull(gate.onRecognition(950, "ten", true, 0.95f))
        assertNull(gate.poll(960))
    }

    @Test fun `new qualified onset after real release receives a different identity`() {
        val gate = UtteranceGate()
        qualify(gate)
        gate.onRecognition(32, "ten", true, 0.9f)
        silence(gate, 64, 320)
        val first = gate.poll(332)!!
        gate.observeAudio(364, 0.1f)
        assertFalse(gate.observeAudio(396, 0.9f).startedSpeech)
        assertTrue(gate.observeAudio(428, 0.9f).startedSpeech)
        gate.onRecognition(428, "ten", true, 0.9f)
        silence(gate, 460, 716)
        val second = gate.poll(728)!!
        assertNotEquals(first.utteranceId, second.utteranceId)
        assertFalse(gate.acknowledge(first.utteranceId, 730))
        assertTrue(gate.acknowledge(second.utteranceId, 731))
    }

    @Test fun `suppressed high frames cannot qualify the next answer`() {
        val gate = UtteranceGate()
        qualify(gate)
        gate.onRecognition(32, "eight", true, 0.9f)
        silence(gate, 64, 320)
        val first = gate.poll(332)!!
        gate.acknowledge(first.utteranceId, 333)
        for (time in 365L..685L step 32) {
            assertFalse(gate.observeAudio(time, 0.95f).startedSpeech)
            assertNull(gate.onRecognition(time, "eight", true, 0.99f))
        }
        gate.observeAudio(717, 0.1f)
        gate.observeAudio(749, 0.1f)
        assertFalse(gate.observeAudio(781, 0.9f).startedSpeech)
        assertTrue(gate.observeAudio(813, 0.9f).startedSpeech)
    }

    @Test fun `quiet timeout requests final result exactly once before reset`() {
        val gate = UtteranceGate()
        qualify(gate)
        gate.onRecognition(32, "forty", false)
        silence(gate, 64, 704)
        val stop = gate.observeAudio(736, 0.1f)
        assertFalse(stop.shouldDecode)
        assertTrue(stop.resetDecoder)
        assertTrue(stop.finalizeDecoder)
        assertEquals("forty", gate.onRecognition(736, "forty", true, 0.9f)?.text)
        val next = gate.observeAudio(768, 0.1f)
        assertFalse(next.finalizeDecoder)
        assertFalse(next.shouldDecode)
        assertFalse(next.resetDecoder)
    }

    @Test fun `rejected final cannot leak its candidate into the next segment`() {
        val gate = UtteranceGate()
        qualify(gate)
        gate.onRecognition(32, "two", true, 0.2f)
        silence(gate, 64, 352)
        gate.observeAudio(384, 0.9f)
        assertTrue(gate.observeAudio(416, 0.9f).startedSpeech)
        assertNull(gate.poll(720))
        assertNull(gate.onRecognition(720, "six", true, 0.9f))
        assertEquals("six", gate.poll(870)?.text)
    }

    @Test fun `fast and careful profiles keep their own acoustic quiet interval`() {
        for (quiet in listOf(180L, 450L)) {
            val gate = UtteranceGate(quietMillis = quiet)
            qualify(gate)
            gate.onRecognition(32, "four", false)
            silence(gate, 64, 160)
            assertNull(gate.poll(32 + quiet - 1))
            assertEquals("four", gate.poll(32 + quiet)?.text)
        }
    }

    @Test fun `short gaps inside speech do not start another decoder segment`() {
        val gate = UtteranceGate()
        qualify(gate)
        gate.onRecognition(32, "forty", false)
        silence(gate, 64, 352)
        val partial = gate.poll(364)!!
        assertFalse(gate.observeAudio(396, 0.9f).startedSpeech)
        assertFalse(gate.observeAudio(428, 0.9f).startedSpeech)
        gate.onRecognition(428, "forty two", false)
        silence(gate, 460, 716)
        val refined = gate.poll(728)!!
        assertEquals(partial.utteranceId, refined.utteranceId)
        assertEquals("forty two", refined.text)
    }

    @Test fun `probability and frame validation reject malformed detector output`() {
        for (probability in listOf(-0.1f, 1.1f, Float.NaN)) {
            assertInvalid { UtteranceGate().observeAudio(0, probability) }
        }
        assertInvalid { UtteranceGate().observeAudio(0, 0.9f, frameMillis = 0) }
        assertInvalid { UtteranceGate().observeAudio(0, 0.9f, frameMillis = 128) }
    }

    @Test fun `capture gaps cannot splice two old probability spikes into one onset`() {
        val gate = UtteranceGate()
        assertFalse(gate.observeAudio(0, 0.9f).startedSpeech)
        assertFalse(gate.observeAudio(1000, 0.9f).startedSpeech)
        assertTrue(gate.observeAudio(1032, 0.9f).startedSpeech)
    }

    @Test fun `possible new onset cannot submit a stale candidate between its two frames`() {
        val gate = UtteranceGate()
        qualify(gate)
        gate.onRecognition(32, "one", false)
        silence(gate, 64, 320)
        gate.observeAudio(352, 0.9f)
        assertNull(gate.poll(352))
        gate.observeAudio(384, 0.9f)
        assertNull(gate.poll(384))
        gate.onRecognition(384, "one hundred", false)
        silence(gate, 416, 672)
        assertEquals("one hundred", gate.poll(684)?.text)
    }

    @Test fun `one pending onset spike delays eligibility only until its rejection`() {
        val gate = UtteranceGate()
        qualify(gate)
        gate.onRecognition(32, "two", false)
        silence(gate, 64, 320)
        gate.observeAudio(352, 0.9f)
        assertNull(gate.poll(352))
        gate.observeAudio(384, 0.1f)
        val result = gate.poll(384)!!
        assertEquals("two", result.text)
        assertEquals(352L, result.latencyMillis)
    }

    private fun qualify(gate: UtteranceGate, probability: Float = 0.9f) {
        assertFalse(gate.observeAudio(0, probability).shouldDecode)
        assertTrue(gate.observeAudio(32, probability).startedSpeech)
    }

    private fun silence(gate: UtteranceGate, start: Long, end: Long) {
        for (time in start..end step 32) gate.observeAudio(time, 0.1f)
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid detector input to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected validation error.
        }
    }
}
