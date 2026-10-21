package com.zetarun.app.voice

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Streaming Silero v6.2.3 at 16 kHz. Prepare, infer, reset and close on one thread.
 * The official model takes 512 new samples plus 64 retained context samples and a
 * recurrent [2, 1, 128] state. No audio leaves this process or is written to disk.
 */
class SileroVad(context: Context) : AutoCloseable {
    private val assets = context.applicationContext.assets
    private var session: OrtSession? = null
    private var inputTensor: OnnxTensor? = null
    private var stateTensor: OnnxTensor? = null
    private var sampleRateTensor: OnnxTensor? = null
    private val inputBuffer = floats(CONTEXT_SAMPLES + FRAME_SAMPLES)
    private val stateBuffer = floats(STATE_SAMPLES)
    private val contextSamples = FloatArray(CONTEXT_SAMPLES)
    private var ownerThread: Thread? = null

    /** Performs native initialization and model loading; call off the main thread. */
    fun prepare() {
        check(session == null) { "Speech detector was already prepared." }
        ownerThread = Thread.currentThread()
        val environment = OrtEnvironment.getEnvironment()
        try {
            OrtSession.SessionOptions().use { options ->
                options.setInterOpNumThreads(1)
                options.setIntraOpNumThreads(1)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                options.addConfigEntry("session.inter_op.allow_spinning", "0")
                val modelBytes = assets.open("silero-vad.onnx").use { it.readBytes() }
                session = environment.createSession(modelBytes, options)
            }
            val loaded = checkNotNull(session)
            check(loaded.inputNames.containsAll(listOf("input", "state", "sr")) &&
                loaded.outputNames.containsAll(listOf("output", "stateN"))) {
                "Bundled speech detector has an unsupported input/output format."
            }
            inputTensor = OnnxTensor.createTensor(environment, inputBuffer, longArrayOf(1, 576))
            stateTensor = OnnxTensor.createTensor(environment, stateBuffer, longArrayOf(2, 1, 128))
            val sampleRate = ByteBuffer.allocateDirect(java.lang.Long.BYTES)
                .order(ByteOrder.nativeOrder()).asLongBuffer().apply { put(0, SAMPLE_RATE.toLong()) }
            sampleRateTensor = OnnxTensor.createTensor(environment, sampleRate, longArrayOf())
            reset()
            // Warm up kernels before a round can start; then discard warm-up state.
            probability(ShortArray(FRAME_SAMPLES))
            reset()
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    /** Probability for exactly 32 ms of fresh, contiguous mono PCM16 audio. */
    fun probability(pcm: ShortArray): Float {
        checkThread()
        require(pcm.size == FRAME_SAMPLES) { "Silero requires exactly 512 PCM samples at 16 kHz." }
        val loaded = checkNotNull(session) { "Speech detector is not prepared." }
        inputBuffer.clear()
        inputBuffer.put(contextSamples)
        for (sample in pcm) inputBuffer.put(sample.toFloat() / 32768f)
        inputBuffer.flip()
        stateBuffer.rewind()
        val inputs = mapOf(
            "input" to checkNotNull(inputTensor),
            "state" to checkNotNull(stateTensor),
            "sr" to checkNotNull(sampleRateTensor),
        )
        val probability = loaded.run(inputs).use { result ->
            val output = result.get("output").orElseThrow { IllegalStateException("Missing speech probability.") } as OnnxTensor
            val recurrent = result.get("stateN").orElseThrow { IllegalStateException("Missing speech detector state.") } as OnnxTensor
            val nextState = recurrent.floatBuffer
            check(nextState.remaining() == STATE_SAMPLES) { "Unexpected speech detector state size." }
            stateBuffer.clear()
            stateBuffer.put(nextState)
            stateBuffer.flip()
            output.floatBuffer.get(0)
        }
        for (i in contextSamples.indices) {
            contextSamples[i] = pcm[FRAME_SAMPLES - CONTEXT_SAMPLES + i].toFloat() / 32768f
        }
        check(probability.isFinite()) { "Speech detector returned a non-finite probability." }
        return probability.coerceIn(0f, 1f)
    }

    /** Start a fresh stream; answer boundaries normally keep VAD context continuous. */
    fun reset() {
        checkThread()
        contextSamples.fill(0f)
        stateBuffer.clear()
        repeat(STATE_SAMPLES) { stateBuffer.put(0f) }
        stateBuffer.flip()
    }

    override fun close() {
        if (ownerThread != null) checkThread()
        inputTensor?.close(); inputTensor = null
        stateTensor?.close(); stateTensor = null
        sampleRateTensor?.close(); sampleRateTensor = null
        session?.close(); session = null
        // OrtEnvironment is an application-wide singleton; do not close it here.
    }

    private fun checkThread() {
        check(Thread.currentThread() === ownerThread) { "Speech detector must stay on its capture thread." }
    }

    private fun floats(size: Int): FloatBuffer = ByteBuffer.allocateDirect(size * java.lang.Float.BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    companion object {
        const val FRAME_SAMPLES = 512
        private const val CONTEXT_SAMPLES = 64
        private const val STATE_SAMPLES = 256
        private const val SAMPLE_RATE = 16_000
    }
}
