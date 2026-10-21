package com.zetarun.app.voice

/** Accumulates arbitrary AudioRecord reads into contiguous, fixed-size model frames. */
class PcmFrameBuffer(frameSize: Int = 512) {
    private val frame = ShortArray(frameSize.also { require(it > 0) })
    private var used = 0

    /** The callback borrows the frame until it returns; copy it if retaining it. */
    fun append(input: ShortArray, offset: Int, count: Int, onFrame: (ShortArray) -> Unit) {
        require(offset >= 0 && count >= 0 && offset <= input.size - count)
        var position = offset
        val end = offset + count
        while (position < end) {
            val copied = minOf(frame.size - used, end - position)
            input.copyInto(frame, used, position, position + copied)
            used += copied
            position += copied
            if (used == frame.size) {
                used = 0
                onFrame(frame)
            }
        }
    }

    fun reset() { used = 0 }
}
