# Offline noise-filter validation

The synthetic comparison supports replacing an energy-only speech detector with Silero: all 84 speech clips qualified, and none of the 11 noise-only clips qualified. It does **not** establish reliable arithmetic transcription in loud treadmill noise. Vosk still missed or misrecognized many words at 5–10 dB signal-to-noise ratio (SNR).

This run used the reproducible [desktop benchmark](../scripts/benchmark_voice_filter.py). It does not run Android `AudioRecord`, the app's endpoint/acknowledgement gate, strict Kotlin number parser, or game acceptance logic. Its final-candidate counts are an offline diagnostic proxy, not accepted answers or an app false-acceptance rate. No S24 Ultra, human exercise breathing, real treadmill recording, or Bluetooth microphone was used.

## Method

The fixtures contain `one`, `two`, `six`, `four`, `eight`, `twenty`, and `one hundred twenty`, synthesized with FFmpeg's Flite `slt` and `kal` voices. These are generated voices, not a sample of users or accents. Every speech clip has one second of leading audio and two seconds of trailing audio. Speech is normalized to approximately 0.09 full-scale RMS after trimming; no automatic gain control is used.

Each of the 14 voice/phrase pairs is tested clean, with 50 Hz motor rumble plus white or pink fan noise at 10 dB and 5 dB SNR, and with two synthetic exhalation proxies after the answer. SNR is calculated before filtering from the trimmed synthetic speech and background RMS. The motor/fan mixture has equal-energy components; this does not model a particular treadmill or microphone. The answer/breath fixtures add an 80 ms bandlimited noise burst 150 ms after the speech span and a 300 ms huff 500 ms after it.

The 11 noise-only clips contain silence, 50 Hz rumble, white/pink fan noise, white/pink motor-plus-fan noise, and repeated synthetic huffs of 40, 80, 150, 300, or 600 ms. Each huff clip has three bursts. Breath proxies are 180–5000 Hz noise with a smooth attack/decay, not recorded human breaths. Total analyzed audio is 352.8 seconds across 95 clips; the two decoder comparisons process that audio separately.

The baseline uses raw PCM and the previous adaptive RMS threshold. The new comparison uses a causal second-order Butterworth high-pass at 80 Hz, followed by Silero v6.2.3: 16 kHz mono, 512 fresh samples per inference, 64 retained context samples, and recurrent state shaped `[2, 1, 128]`. Speech qualification requires two consecutive frames with probability at least 0.6 and releases below 0.35. Both decoders receive complete streams; audio is not muted whenever VAD says “not speech,” which preserves unvoiced consonants.

The final-candidate proxy additionally requires numeric vocabulary, overlapping qualified speech, and every final word confidence at least 0.62. It does not emulate partial transcripts, the app's 300 ms Balanced quiet interval, 150 ms stabilization, answer matching, acknowledgements, or microphone timing. Vocabulary-only numeric classification can include number sequences that the stricter app parser would reject.

## Transcription results

Exact normalized whole-clip transcript matches, out of 14 voice/phrase pairs per condition:

| Condition | Raw Vosk matches | High-pass Vosk matches | High-pass final proxy matches |
| --- | ---: | ---: | ---: |
| Clean | 11/14 | 11/14 | 10/14 |
| Motor + white fan, 10 dB | 7/14 | 7/14 | 4/14 |
| Motor + white fan, 5 dB | 2/14 | 2/14 | 2/14 |
| Motor + pink fan, 10 dB | 5/14 | 5/14 | 5/14 |
| Motor + pink fan, 5 dB | 4/14 | 5/14 | 4/14 |
| Answer followed by breath proxies | 11/14 | 11/14 | 10/14 |

Silero qualified all 84 speech-containing clips, including every single-word fixture. The remaining misses came from decoding or final confidence filtering in this comparison. For example, clean `kal` speech produced no transcript for `two` and `eight`, and recognized `six` as `three`; clean `slt` speech matched all seven phrases. Clean `kal` `twenty` was correctly transcribed but had minimum final confidence around 0.305 and failed the 0.62 proxy threshold.

In noisy clips, `one` was sometimes transcribed as `nine`, and `four` as `five`, with confidence above 0.62. Some phrases produced no final text at all. A confidence threshold cannot guarantee correctness, and lowering it solely to accommodate these synthetic examples would also admit more uncertain hypotheses. The high-pass filter removes low-frequency energy; it is not broadband speech denoising. These results do not justify a claim of improved overall ASR accuracy or a specific real-world word error rate.

## Noise qualification and answer/breath boundaries

| Noise-only category | Clips | Baseline RMS qualified | Silero qualified |
| --- | ---: | ---: | ---: |
| Silence | 1 | 0 | 0 |
| 50 Hz rumble | 1 | 1 | 0 |
| Fan and motor/fan mixtures | 4 | 4 | 0 |
| 40/80/150 ms burst clips | 3 | 3 | 0 |
| 300/600 ms huff clips | 2 | 2 | 0 |
| **Total** | **11** | **10** | **0** |

Both raw and filtered decoders produced zero numeric finals on these 11 noise-only fixtures. Thus this sample demonstrates fewer false **speech qualifications**, not a measured reduction in false answers: the baseline already produced no numeric finals on this sample.

For all 14 answer-plus-breath fixtures, qualified speech onsets, total qualified duration, and the final qualified frame endpoint matched their clean counterpart exactly. The added bursts changed the measured endpoint by **0 ms** in each pair. Whole-clip final transcript text also stayed unchanged in all 14 pairs.

The highest frame probability inside the first, 80 ms breath window was **0.600542** (`slt` `two`). It did not satisfy the two-consecutive-frame qualification rule. The highest probability inside the second, 300 ms huff window was **0.469601**. Neither window contained a qualified frame in any pair. This specifically checks that these synthetic bursts do not restart qualified speech after an answer; it does not establish that real exertion breathing cannot do so. The last qualified frame ended no more than about 79.4 ms after the trimmed speech span in these fixtures, both with and without the added bursts.

## Desktop timing

Measured on Linux x86_64, Python 3.12.14, ONNX Runtime CPU provider with one intra-op and one inter-op thread:

| Measurement | This run |
| --- | ---: |
| ONNX session/model creation | 42.33 ms |
| First inference | 0.761 ms |
| Warm inferences | 11,086 |
| Warm inference p50 | 0.144 ms |
| Warm inference p95 | 0.258 ms |
| Warm inference p99 | 1.216 ms |

Recurrent state and context reset between fixtures; they remain continuous within each fixture. These are host CPU inference measurements, including Python `session.run`, not S24 Ultra measurements, startup latency on Android, or end-to-end spoken-answer latency. Host scheduling affects the tails. The 32 ms framing, two-frame qualification, decoder, quiet interval, stabilization, and Android audio buffering are additional costs. The benchmark deliberately includes leading audio and does not validate speaking immediately when the microphone first opens.

## Reproduce

Prepare the repository's pinned speech assets first. Use a Python virtual environment outside the checkout, FFmpeg built with the `flite` source, and the benchmark dependencies (these are not Android app dependencies):

```bash
python -m pip install numpy==2.5.4 onnxruntime==1.24.3 vosk==0.3.45
python scripts/benchmark_voice_filter.py \
  --vosk-model app/src/main/assets/model-en-us \
  --vad-model app/src/main/assets/silero-vad.onnx \
  --output-json build/voice-noise-report.json
```

The script checks the Silero model SHA-256 against `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3`, uses deterministic seed `20261021`, and records versions, settings, individual transcripts/confidences, speech-qualification windows, and timing in JSON. Pass `--save-fixtures` with a directory outside the app's assets only when you want generated WAV files. Fixtures are not bundled in the APK. Changing voices, seed, thresholds, model, or software versions is a new experiment.

The desktop Vosk package is **0.3.45**. The app currently declares **`com.alphacephei:vosk-android:0.3.75@aar`** and **`com.microsoft.onnxruntime:onnxruntime-android:1.24.3`**. Package/artifact versions do not establish equivalent native decoder behavior. These desktop results must be followed by the [S24 Ultra checks](S24_ULTRA.md) and [voice latency benchmark](VOICE_BENCHMARK.md), including actual breath recordings, microphone placement, gym speech/music, airflow, and the app's complete acceptance behavior.
