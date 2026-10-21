package com.zetarun.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Offline, speech-qualified recognition. No microphone audio is recorded to disk. */
class OfflineVoiceEngine(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val mutableState = MutableStateFlow(VoiceState(VoiceStatus.LOADING, "Preparing offline voice…"))
    val state: StateFlow<VoiceState> = mutableState.asStateFlow()
    private val preparation = Mutex()
    private val captureDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "zetarun-voice").apply { priority = Thread.NORM_PRIORITY }
    }.asCoroutineDispatcher()
    private val cleanupScope = CoroutineScope(SupervisorJob() + captureDispatcher)
    @Volatile private var model: Model? = null
    @Volatile private var vad: SileroVad? = null
    val isPrepared: Boolean get() = model != null && vad != null && !closed
    private var captureJob: Job? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var closed = false
    private val generation = AtomicLong()
    private val utteranceIds = AtomicLong()
    private val acknowledgement = AtomicLong(Long.MIN_VALUE)
    @Volatile private var selectedQuietMillis = 300L
    val quietMillis: Long get() = selectedQuietMillis

    /** Applied to the next listening run. Fast settings tolerate shorter word gaps. */
    fun setQuietMillis(ms: Long) { selectedQuietMillis = ms.coerceIn(180L, 600L) }

    /** Both native models must be available; never fall back to an energy-only gate. */
    suspend fun prepare(): Boolean = preparation.withLock {
        if (closed) return@withLock false
        if (isPrepared) return@withLock true
        mutableState.value = VoiceState(VoiceStatus.LOADING, "Preparing offline voice and noise filter…")
        try {
            val prepared = withContext(captureDispatcher) {
                var loadedModel: Model? = null
                var loadedVad: SileroVad? = null
                try {
                    if (closed) return@withContext false
                    loadedModel = Model(unpackModel().absolutePath)
                    loadedVad = SileroVad(context).also { it.prepare() }
                    if (closed) {
                        loadedVad.close()
                        loadedModel.close()
                        false
                    } else {
                        model = loadedModel
                        vad = loadedVad
                        true
                    }
                } catch (error: Throwable) {
                    loadedVad?.close()
                    loadedModel?.close()
                    throw error
                }
            }
            if (prepared && !closed) {
                mutableState.value = VoiceState(VoiceStatus.READY, "Offline voice · speech and noise filter ready")
            }
            prepared && !closed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.value = VoiceState(VoiceStatus.ERROR,
                "Voice or noise-filter model unavailable. Reinstall the full APK, or use the keypad. ${error.message.orEmpty()}")
            false
        } catch (error: LinkageError) {
            mutableState.value = VoiceState(VoiceStatus.ERROR,
                "Offline voice and noise filtering are unavailable for this device's CPU. Use the keypad.")
            false
        }
    }

    /** Caller owns lifecycle: stop when paused, backgrounded, or round ends. */
    fun start(scope: CoroutineScope, onHypothesis: (VoiceHypothesis) -> Unit) {
        val previousJob = captureJob
        stop()
        if (closed) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mutableState.value = VoiceState(VoiceStatus.ERROR, "Microphone permission is needed for voice answers. The keypad still works.")
            return
        }
        val currentModel = model
        val currentVad = vad
        if (currentModel == null || currentVad == null) {
            mutableState.value = VoiceState(VoiceStatus.ERROR, "Offline voice and noise filter are still preparing. Try again when ready.")
            return
        }
        // Clear a previous microphone error before UI observers evaluate a retry.
        mutableState.value = VoiceState(VoiceStatus.READY, "Starting microphone…")
        val token = generation.incrementAndGet()
        val chosenQuietMillis = selectedQuietMillis
        acknowledgement.set(Long.MIN_VALUE)
        captureJob = scope.launch(captureDispatcher) {
            previousJob?.join()
            if (!isActive || generation.get() != token) return@launch
            var recorder: AudioRecord? = null
            var recognizer: Recognizer? = null
            var noiseSuppressor: NoiseSuppressor? = null
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            var recordingCallback: AudioManager.AudioRecordingCallback? = null
            try {
                currentVad.reset()
                val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minBuffer > 0) { "This microphone does not support 16 kHz recording." }
                val capture = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minBuffer * 2, FRAME_SAMPLES * 2 * 4))
                    .build()
                recorder = capture
                check(capture.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize the microphone." }
                audioRecord = capture
                noiseSuppressor = optionalNoiseSuppressor(capture.audioSessionId)
                // No AGC: it can promote quiet breaths and motor noise to loud input.
                val decoder = Recognizer(currentModel, SAMPLE_RATE.toFloat(), grammar())
                recognizer = decoder
                decoder.setWords(true)
                // Native Vosk endpoint values are seconds (see recognizer.cc).
                decoder.setEndpointerDelays(5f, maxOf(0.5f, chosenQuietMillis / 1000f + 0.1f), 10f)
                if (Build.VERSION.SDK_INT >= 29) {
                    recordingCallback = object : AudioManager.AudioRecordingCallback() {
                        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                            if (generation.get() != token) return
                            if (configs.any { it.clientAudioSessionId == capture.audioSessionId && it.isClientSilenced }) {
                                mutableState.value = VoiceState(VoiceStatus.ERROR, "The microphone is silenced by Android or another app. Pause and enable voice again.")
                                generation.incrementAndGet()
                                runCatching { capture.stop() }
                            }
                        }
                    }
                    audioManager.registerAudioRecordingCallback(recordingCallback, Handler(Looper.getMainLooper()))
                }
                capture.startRecording()
                check(capture.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Another app may be using the microphone." }
                mutableState.value = VoiceState(VoiceStatus.LISTENING, "Listening offline · filtering noise and short breaths")
                val gate = UtteranceGate(quietMillis = chosenQuietMillis, stableMillis = minOf(150L, chosenQuietMillis),
                    nextId = { utteranceIds.incrementAndGet() })
                val incoming = ShortArray(FRAME_SAMPLES)
                val filtered = ShortArray(FRAME_SAMPLES)
                val frames = PcmFrameBuffer(FRAME_SAMPLES)
                val preprocessor = AudioPreprocessor()
                val lookbehind = PcmLookbehind()
                var lastPartialAt = 0L
                var lastStateAt = 0L
                var transcript = ""
                var pending: VoiceHypothesis? = null

                fun offer(hypothesis: VoiceHypothesis?) {
                    if (pending == null) pending = hypothesis
                }
                fun readResult(now: Long, json: String, isFinal: Boolean) {
                    val result = JSONObject(json)
                    transcript = result.optString(if (isFinal) "text" else "partial")
                    var minimumConfidence: Float? = null
                    if (isFinal) {
                        val words = result.optJSONArray("result")
                        if (words != null && words.length() > 0) {
                            var minimum = 1f
                            var allValid = true
                            for (index in 0 until words.length()) {
                                val word = words.getJSONObject(index)
                                val value = if (word.has("conf")) word.optDouble("conf", Double.NaN) else Double.NaN
                                if (!value.isFinite() || value !in 0.0..1.0) allValid = false
                                else minimum = minOf(minimum, value.toFloat())
                            }
                            if (allValid) minimumConfidence = minimum
                        }
                    }
                    offer(gate.onRecognition(now, transcript, isFinal, minimumConfidence))
                }
                fun decode(frame: ShortArray, now: Long, report: Boolean) {
                    if (decoder.acceptWaveForm(frame, frame.size)) {
                        val json = decoder.result
                        if (report) readResult(now, json, true)
                    } else if (report && now - lastPartialAt >= 60) {
                        readResult(now, decoder.partialResult, false)
                        lastPartialAt = now
                    }
                }

                while (isActive && generation.get() == token) {
                    val read = capture.read(incoming, 0, incoming.size, AudioRecord.READ_BLOCKING)
                    if (read < 0) error("Microphone read failed ($read). Pause and restart voice.")
                    if (read == 0) continue
                    frames.append(incoming, 0, read) { frame ->
                        val now = SystemClock.elapsedRealtime()
                        val accepted = acknowledgement.getAndSet(Long.MIN_VALUE)
                        if (accepted != Long.MIN_VALUE && gate.acknowledge(accepted, now)) {
                            decoder.reset()
                            transcript = ""
                            lookbehind.clear()
                            lastPartialAt = 0L
                        }
                        val rms = preprocessor.process(frame, frame.size, filtered)
                        // Run the recurrent VAD on ALL contiguous audio, including
                        // acknowledged-answer drains, so its context never jumps.
                        val probability = currentVad.probability(filtered)
                        val observation = gate.observeAudio(now, probability, FRAME_MILLIS)
                        if (observation.finalizeDecoder) readResult(now, decoder.finalResult, true)
                        if (observation.resetDecoder) {
                            decoder.reset()
                            transcript = ""
                            lastPartialAt = 0L
                        }
                        if (observation.startedSpeech) {
                            if (!observation.resetDecoder) decoder.reset()
                            // These are earlier frames only; current PCM is fed
                            // below exactly once. Preserve initial /s/, /th/, /t/.
                            lookbehind.forEach { previous -> decode(previous, now, false) }
                            transcript = ""
                            lastPartialAt = 0L
                        }
                        if (observation.shouldDecode) decode(filtered, now, true)
                        offer(gate.poll(now))
                        if (gate.isSuppressed(now)) lookbehind.clear() else lookbehind.append(filtered)
                        if (now - lastStateAt >= 80) {
                            mutableState.value = VoiceState(VoiceStatus.LISTENING,
                                "Listening offline · filtering noise and short breaths", rms, transcript, probability)
                            lastStateAt = now
                        }
                    }
                    // A short AudioRecord read can span two chunks but produces
                    // at most one complete 512-sample frame per append call.
                    val emission = pending
                    pending = null
                    if (emission != null) withContext(Dispatchers.Main.immediate) {
                        if (generation.get() == token) onHypothesis(emission)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation.get() == token) mutableState.value = VoiceState(VoiceStatus.ERROR,
                    "Voice paused: ${error.message ?: "Microphone unavailable"}. The keypad still works.")
            } catch (error: LinkageError) {
                if (generation.get() == token) mutableState.value = VoiceState(VoiceStatus.ERROR,
                    "Offline voice and noise filtering could not start on this device. The keypad still works.")
            } finally {
                recordingCallback?.let { runCatching { audioManager.unregisterAudioRecordingCallback(it) } }
                runCatching { recorder?.stop() }
                noiseSuppressor?.release()
                recorder?.release()
                recognizer?.close()
                if (audioRecord === recorder) audioRecord = null
                if (generation.get() == token && mutableState.value.status != VoiceStatus.ERROR) {
                    mutableState.value = VoiceState(VoiceStatus.READY, "Offline voice · speech and noise filter ready")
                }
            }
        }
    }

    fun acknowledge(utteranceId: Long) { acknowledgement.set(utteranceId) }

    fun stop() {
        generation.incrementAndGet()
        captureJob?.cancel()
        runCatching { audioRecord?.stop() }
        if (isPrepared && mutableState.value.status != VoiceStatus.ERROR) {
            mutableState.value = VoiceState(VoiceStatus.READY, "Offline voice · speech and noise filter ready")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        stop()
        cleanupScope.launch {
            try {
                captureJob?.join()
                preparation.withLock {
                    vad?.close(); vad = null
                    model?.close(); model = null
                }
            } finally {
                captureDispatcher.close()
            }
        }
    }

    private fun optionalNoiseSuppressor(sessionId: Int): NoiseSuppressor? = runCatching {
        if (!NoiseSuppressor.isAvailable()) return@runCatching null
        val effect = NoiseSuppressor.create(sessionId) ?: return@runCatching null
        try {
            if (effect.setEnabled(true) == AudioEffect.SUCCESS) effect
            else { effect.release(); null }
        } catch (error: RuntimeException) {
            effect.release()
            null
        }
    }.getOrNull()

    private fun unpackModel(): File {
        val destination = File(context.filesDir, "model-en-us-v1")
        if (File(destination, ".complete").isFile && File(destination, "am/final.mdl").isFile) return destination
        val temporary = File(context.filesDir, "model-en-us-v1.unpacking")
        temporary.deleteRecursively()
        temporary.mkdirs()
        copyAssetDirectory("model-en-us", temporary)
        check(File(temporary, "am/final.mdl").isFile) { "Bundled English model is missing." }
        File(temporary, ".complete").writeText("1")
        destination.deleteRecursively()
        check(temporary.renameTo(destination)) { "Could not store the offline voice model." }
        return destination
    }

    private fun copyAssetDirectory(assetPath: String, destination: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> destination.outputStream().use { input.copyTo(it) } }
        } else {
            check(destination.mkdirs() || destination.isDirectory) { "Not enough storage for voice model." }
            children.forEach { child -> copyAssetDirectory("$assetPath/$child", File(destination, child)) }
        }
    }

    private fun grammar(): String = JSONArray(listOf(
        "zero", "oh", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand",
        "million", "and", "negative", "minus", "point", "skip", "pause", "[unk]",
    )).toString()

    /** Five preallocated earlier frames: exactly 160 ms at 16 kHz. */
    private class PcmLookbehind {
        private val frames = Array(5) { ShortArray(FRAME_SAMPLES) }
        private var next = 0
        private var size = 0
        fun append(pcm: ShortArray) {
            pcm.copyInto(frames[next])
            next = (next + 1) % frames.size
            size = minOf(size + 1, frames.size)
        }
        fun clear() { size = 0; next = 0 }
        fun forEach(block: (ShortArray) -> Unit) {
            val first = (next - size + frames.size) % frames.size
            repeat(size) { block(frames[(first + it) % frames.size]) }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 512
        const val FRAME_MILLIS = 32L
    }
}

enum class VoiceStatus { LOADING, READY, LISTENING, ERROR }
data class VoiceState(
    val status: VoiceStatus,
    val message: String,
    val rms: Float = 0f,
    val transcript: String = "",
    val speechProbability: Float = 0f,
)
