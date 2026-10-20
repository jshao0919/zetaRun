package com.zetarun.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/** No Android recognizer service, recognition network requests, or stored PCM. */
class OfflineVoiceEngine(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val mutableState = MutableStateFlow(VoiceState(VoiceStatus.LOADING, "Preparing offline voice…"))
    val state: StateFlow<VoiceState> = mutableState.asStateFlow()
    private val preparation = Mutex()
    @Volatile private var model: Model? = null
    val isPrepared: Boolean get() = model != null && !closed
    private var captureJob: Job? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var closed = false
    private val generation = AtomicLong()
    private val utteranceIds = AtomicLong()
    private val acknowledgement = AtomicLong(Long.MIN_VALUE)
    @Volatile private var selectedQuietMillis = 300L
    val quietMillis: Long get() = selectedQuietMillis

    /** Applied to the next listening run. Fast settings trade pause tolerance for speed. */
    fun setQuietMillis(ms: Long) { selectedQuietMillis = ms.coerceIn(180L, 600L) }

    /** Copies the bundled model atomically, off the UI thread, then loads native state. */
    suspend fun prepare(): Boolean = preparation.withLock {
        if (closed) return@withLock false
        if (model != null) return@withLock true
        mutableState.value = VoiceState(VoiceStatus.LOADING, "Preparing offline voice…")
        var loaded: Model? = null
        try {
            withContext(Dispatchers.IO) {
                val target = unpackModel()
                loaded = Model(target.absolutePath)
            }
            if (closed) {
                loaded?.close()
                return@withLock false
            }
            model = loaded
            mutableState.value = VoiceState(VoiceStatus.READY, "Offline voice ready")
            true
        } catch (cancelled: CancellationException) {
            // withContext can be cancelled after the native constructor returns.
            loaded?.close()
            throw cancelled
        } catch (error: Exception) {
            mutableState.value = VoiceState(VoiceStatus.ERROR,
                "Voice model unavailable. Reinstall the full APK, or use the keypad. ${error.message.orEmpty()}")
            false
        } catch (error: LinkageError) {
            mutableState.value = VoiceState(VoiceStatus.ERROR,
                "Offline voice is not available for this device's CPU. Use the keypad.")
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
        if (currentModel == null) {
            mutableState.value = VoiceState(VoiceStatus.ERROR, "Offline model is still preparing. Try voice again when it is ready.")
            return
        }
        mutableState.value = VoiceState(VoiceStatus.READY, "Starting microphone…")
        val token = generation.incrementAndGet()
        val chosenQuietMillis = selectedQuietMillis
        acknowledgement.set(Long.MIN_VALUE)
        captureJob = scope.launch(Dispatchers.IO) {
            // Let the previous capture release its recorder before opening another.
            previousJob?.join()
            val previous = audioRecord
            runCatching { previous?.stop() }
            var recorder: AudioRecord? = null
            var recognizer: Recognizer? = null
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            var recordingCallback: AudioManager.AudioRecordingCallback? = null
            try {
                val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minBuffer > 0) { "This microphone does not support 16 kHz recording." }
                recorder = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minBuffer * 2, FRAME_SAMPLES * 2 * 4))
                    .build()
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize the microphone." }
                audioRecord = recorder
                recognizer = Recognizer(currentModel, SAMPLE_RATE.toFloat(), grammar())
                // Native Vosk endpoint values are seconds (see recognizer.cc).
                recognizer.setEndpointerDelays(5f, maxOf(0.5f, chosenQuietMillis / 1000f + 0.1f), 10f)
                val activeRecord = recorder
                if (Build.VERSION.SDK_INT >= 29) {
                    recordingCallback = object : AudioManager.AudioRecordingCallback() {
                        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                            if (generation.get() != token) return
                            if (configs.any { it.clientAudioSessionId == activeRecord.audioSessionId && it.isClientSilenced }) {
                                mutableState.value = VoiceState(VoiceStatus.ERROR, "The microphone is silenced by Android or another app. Pause and enable voice again.")
                                generation.incrementAndGet()
                                runCatching { activeRecord.stop() }
                            }
                        }
                    }
                    audioManager.registerAudioRecordingCallback(recordingCallback, Handler(Looper.getMainLooper()))
                }
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Another app may be using the microphone." }
                mutableState.value = VoiceState(VoiceStatus.LISTENING, "Listening offline · speak one answer")
                val gate = UtteranceGate(quietMillis = chosenQuietMillis, stableMillis = minOf(150L, chosenQuietMillis),
                    nextId = { utteranceIds.incrementAndGet() })
                val pcm = ShortArray(FRAME_SAMPLES)
                var lastPartialAt = 0L
                var lastStateAt = 0L
                var partial = ""
                var noiseFloor = 0.006f
                while (isActive && generation.get() == token) {
                    val read = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (read < 0) error("Microphone read failed ($read). Pause and restart voice.")
                    if (read == 0) continue
                    val now = SystemClock.elapsedRealtime()
                    val accepted = acknowledgement.getAndSet(Long.MIN_VALUE)
                    if (accepted != Long.MIN_VALUE && gate.acknowledge(accepted, now)) {
                        recognizer.reset()
                        partial = ""
                    }
                    if (gate.isSuppressed(now)) continue
                    val rms = energy(pcm, read)
                    val threshold = maxOf(0.015f, noiseFloor * 2.8f)
                    val voiced = rms > threshold
                    if (!voiced) noiseFloor = noiseFloor * 0.985f + rms * 0.015f
                    val finalized = recognizer.acceptWaveForm(pcm, read)
                    if (finalized) {
                        partial = JSONObject(recognizer.result).optString("text")
                    } else if (now - lastPartialAt >= 60) {
                        partial = JSONObject(recognizer.partialResult).optString("partial")
                        lastPartialAt = now
                    }
                    val emission = gate.update(now, partial, finalized, voiced)
                    if (now - lastStateAt >= 80) {
                        mutableState.value = VoiceState(VoiceStatus.LISTENING, "Listening offline · speak one answer", rms, partial)
                        lastStateAt = now
                    }
                    if (emission != null) withContext(Dispatchers.Main.immediate) {
                        if (generation.get() == token) onHypothesis(emission)
                    }
                    if (finalized) partial = ""
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation.get() == token) mutableState.value = VoiceState(VoiceStatus.ERROR,
                    "Voice paused: ${error.message ?: "Microphone unavailable"}. The keypad still works.")
            } catch (error: LinkageError) {
                if (generation.get() == token) mutableState.value = VoiceState(VoiceStatus.ERROR,
                    "Offline voice could not start on this device. The keypad still works.")
            } finally {
                recordingCallback?.let { runCatching { audioManager.unregisterAudioRecordingCallback(it) } }
                runCatching { recorder?.stop() }
                recorder?.release()
                recognizer?.close()
                if (audioRecord === recorder) audioRecord = null
                if (generation.get() == token && mutableState.value.status != VoiceStatus.ERROR) {
                    mutableState.value = VoiceState(VoiceStatus.READY, "Offline voice ready")
                }
            }
        }
    }

    /** Acknowledge only accepted answers/commands. Incorrect partials must not advance. */
    fun acknowledge(utteranceId: Long) { acknowledgement.set(utteranceId) }

    fun stop() {
        generation.incrementAndGet()
        captureJob?.cancel()
        runCatching { audioRecord?.stop() }
        if (!closed && model != null && mutableState.value.status != VoiceStatus.ERROR) {
            mutableState.value = VoiceState(VoiceStatus.READY, "Offline voice ready")
        }
    }

    override fun close() {
        closed = true
        stop()
        // Recognizer retains a native reference to its model until its own close.
        model?.close()
        model = null
    }

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

    private fun grammar(): String {
        // A compact looping number vocabulary, with an unknown token so arbitrary
        // treadmill noise is not forced into a numeric answer. Parsing is stricter.
        val words = listOf("zero", "oh", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
            "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand",
            "million", "and", "negative", "minus", "point", "skip", "pause", "[unk]")
        return JSONArray(words).toString()
    }

    private fun energy(samples: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) { val value = samples[i].toDouble() / 32768.0; sum += value * value }
        return sqrt(sum / count).toFloat()
    }

    private companion object { const val SAMPLE_RATE = 16_000; const val FRAME_SAMPLES = 320 }
}

enum class VoiceStatus { LOADING, READY, LISTENING, ERROR }
data class VoiceState(val status: VoiceStatus, val message: String, val rms: Float = 0f, val transcript: String = "")
