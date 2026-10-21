package com.zetarun.app.voice

import org.junit.Assert.*
import org.junit.Test

class PcmFrameBufferTest {
    @Test fun partialReadsKeepEverySampleInOrderExactlyOnce() {
        val buffer = PcmFrameBuffer(512)
        val source = ShortArray(2_048) { it.toShort() }
        val frames = mutableListOf<ShortArray>()
        buffer.append(source, 0, 37) { frames.add(it.copyOf()) }
        assertTrue(frames.isEmpty())
        buffer.append(source, 37, 1_000) { frames.add(it.copyOf()) }
        buffer.append(source, 1_037, source.size - 1_037) { frames.add(it.copyOf()) }
        assertEquals(4, frames.size)
        assertArrayEquals(source, frames.flatMap { it.toList() }.toShortArray())
    }

    @Test fun resetDiscardsOnlyIncompleteFrame() {
        val buffer = PcmFrameBuffer(4)
        buffer.append(shortArrayOf(1, 2, 3), 0, 3) { fail("Incomplete frame") }
        buffer.reset()
        var actual = shortArrayOf()
        buffer.append(shortArrayOf(9, 4, 5, 6, 7, 8), 1, 4) { actual = it.copyOf() }
        assertArrayEquals(shortArrayOf(4, 5, 6, 7), actual)
        buffer.append(shortArrayOf(), 0, 0) { fail("Empty input") }
    }
}
