package com.zetarun.app.voice

import org.junit.Assert.*
import org.junit.Test

class UtteranceGateTest {
    @Test fun `one is not emitted while a longer number is still being spoken`() {
        val gate = UtteranceGate()
        assertNull(gate.update(0, "one", false, true))
        assertNull(gate.update(200, "one", false, false))
        assertNull(gate.update(220, "one hundred", false, true))
        assertNull(gate.update(400, "one hundred twenty", false, true))
        assertNull(gate.update(690, "one hundred twenty", false, false))
        val result = gate.update(700, "one hundred twenty", false, false)
        assertEquals("one hundred twenty", result?.text)
        assertEquals(300L, result?.latencyMillis)
    }

    @Test fun `stable prefix cannot commit while audio still contains voice`() {
        val gate = UtteranceGate()
        gate.update(0, "two", false, true)
        for (time in 100L..1000L step 100) assertNull(gate.update(time, "two", false, true))
        assertNull(gate.update(1299, "two", false, false))
        assertEquals("two", gate.update(1300, "two", false, false)?.text)
    }

    @Test fun `changed decoder output restarts quiet and stability windows`() {
        val gate = UtteranceGate()
        gate.update(0, "two", false, true)
        assertNull(gate.update(299, "twenty", false, false))
        assertNull(gate.update(500, "twenty", false, false))
        assertEquals("twenty", gate.update(599, "twenty", false, false)?.text)
    }

    @Test fun `same partial emitted once but can be refined on final`() {
        val gate = UtteranceGate()
        gate.update(0, "forty", false, true)
        val first = gate.update(300, "forty", false, false)!!
        assertNull(gate.update(600, "forty", false, false))
        val final = gate.update(700, "forty two", true, false)!!
        assertEquals(first.utteranceId, final.utteranceId)
        assertTrue(final.isFinal)
        assertEquals("forty two", final.text)
    }

    @Test fun `acknowledged partial drains queued audio and supports repeated same answer`() {
        val gate = UtteranceGate()
        gate.update(0, "eight", false, true)
        val first = gate.update(300, "eight", false, false)!!
        assertTrue(gate.acknowledge(first.utteranceId, 301))
        assertTrue(gate.isSuppressed(400))
        assertNull(gate.update(400, "eight", true, true))
        assertFalse(gate.acknowledge(first.utteranceId, 405))
        assertNull(gate.update(500, "eight", false, true))
        val second = gate.update(800, "eight", false, false)!!
        assertNotEquals(first.utteranceId, second.utteranceId)
        assertEquals("eight", second.text)
    }

    @Test fun `late acknowledgement of emitted final is still honored`() {
        val gate = UtteranceGate()
        val first = gate.update(0, "six", true, true)!!
        assertTrue(gate.acknowledge(first.utteranceId, 5))
        assertNull(gate.update(100, "six", true, false))
        val next = gate.update(200, "six", true, true)!!
        assertNotEquals(first.utteranceId, next.utteranceId)
    }

    @Test fun `empty and unknown results never yield answers`() {
        val gate = UtteranceGate()
        assertNull(gate.update(0, "", true, false))
        gate.update(10, "[unk]", false, true)
        assertNull(gate.update(400, "[unk]", false, false))
        assertNull(gate.update(800, "one [unk]", true, false))
    }

    @Test fun `final boundaries assign new identity even for equal adjacent answers`() {
        val gate = UtteranceGate()
        val first = gate.update(0, "ten", true, true)!!
        val second = gate.update(1000, "ten", true, true)!!
        assertNotEquals(first.utteranceId, second.utteranceId)
    }

    @Test fun `decoder hallucination in silence is never emitted`() {
        val gate = UtteranceGate()
        assertNull(gate.update(0, "four", false, false))
        assertNull(gate.update(400, "four", false, false))
        assertNull(gate.update(800, "four", true, false))
    }

    @Test fun `acknowledged answer cannot be replayed without fresh voiced audio`() {
        val gate = UtteranceGate()
        val first = gate.update(0, "eight", true, true)!!
        assertTrue(gate.acknowledge(first.utteranceId, 1))
        assertNull(gate.update(200, "eight", false, false))
        assertNull(gate.update(600, "eight", false, false))
        assertNull(gate.update(800, "eight", true, false))
        // Fresh energy and new decoding make an equal answer valid again.
        assertNull(gate.update(900, "eight", false, true))
        assertEquals("eight", gate.update(1200, "eight", false, false)?.text)
    }

    @Test fun `fast preset waits exactly its configured quiet interval`() {
        val gate = UtteranceGate(quietMillis = 180, stableMillis = 150)
        gate.update(0, "four", false, true)
        assertNull(gate.update(179, "four", false, false))
        assertEquals("four", gate.update(180, "four", false, false)?.text)
    }

    @Test fun `careful preset waits through longer gaps inside a number`() {
        val gate = UtteranceGate(quietMillis = 450, stableMillis = 150)
        gate.update(0, "one", false, true)
        assertNull(gate.update(300, "one", false, false))
        assertNull(gate.update(400, "one hundred", false, true))
        assertNull(gate.update(849, "one hundred", false, false))
        assertEquals("one hundred", gate.update(850, "one hundred", false, false)?.text)
    }
}
