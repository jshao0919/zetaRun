# Validation — October 21, 2026 (UTC)

The Linux cloud environment used a full JDK 21 and Android SDK 36. These commands completed successfully against the final application sources:

```bash
source scripts/cloud-env.sh
./gradlew testDebugUnitTest lintDebug assembleDebug bundleRelease --console=plain
python3 scripts/check_apk.py app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 4 app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose app/build/outputs/apk/debug/app-debug.apk
```

- **81 unit tests passed:** 26 arithmetic/session/parser, 29 speech qualification/endpoint/duplicate tests, six preprocessing/framing tests, and 20 local storage/serialization/account-isolation/deletion tests. Zero failed, errored, or skipped. Voice regressions cover isolated breath/footstep spikes, stale acoustic proof, low-confidence finals, quiet/stability timing, repeated answers, and a possible new word arriving as an old answer becomes eligible.
- **Android lint passed:** zero errors and two warnings: `ApplySharedPref` and `UseKtx`. Disk-result checks intentionally run on the IO dispatcher for durable migration/deletion handling. The manifest disables backup and supplies explicit Android 12+ backup/transfer exclusions.
- **Version 0.2.0 debug APK built and signature verified.** It contains the full offline English model, checksum-verified Silero VAD model, required license notices, and ARM64/x86-64 Vosk, JNA, and ONNX Runtime native libraries.
- **16 KB compatibility checks passed:** all packaged native ELF load segments and the APK's uncompressed ZIP alignment passed the explicit checks. This is packaging validation, not execution on a 16 KB-page device.
- **Optimized release App Bundle built.** It is unsigned; it has not been uploaded or published to Google Play.
- **Setup repeatability:** the cloud installer completed twice without replacing existing local settings; model preparation also passed its repeat check. Tool/model downloads preserve TLS and publisher checksums.
- **Desktop speech smoke check:** eight synthesized English number/control phrases were recognized with the bundled model and constrained vocabulary, including “one hundred twenty,” “one thousand two hundred,” “skip,” and “pause.” This tests model/vocabulary behavior on prerecorded audio, not real microphone accuracy or S24 latency.
- **New noise-filter comparison:** 95 synthetic clips included 84 spoken clips and 11 noise-only clips. Silero qualified every spoken clip and none of the noise-only clips; the former RMS gate qualified ten noise-only clips. Both decoder comparisons produced zero numeric finals on the noise-only sample, so this does not demonstrate a reduced false-answer rate. All 14 answer/breath pairs retained the same qualified speech endpoint as their clean counterpart. Heavy fan-noise transcription remained weak. See [the full methods, failures, and desktop timing](NOISE_VALIDATION.md).
- **Silero Java/native parity:** the production Kotlin wrapper, using desktop ONNX Runtime 1.24.3 with test asset/context stubs, matched the Python reference across 78 PCM frames within `2.94e-8` absolute probability. Reset reproduced the stream; malformed frame size, calls from another physical thread, and inference after close were rejected. This checks tensor/state handling, not Android microphone behavior.

The S24 Ultra microphone, treadmill/fan conditions, Bluetooth routing, and Google/Firebase end-to-end flows still require the owner's device and Firebase configuration. Follow [the phone guide](S24_ULTRA.md), [voice benchmark](VOICE_BENCHMARK.md), and [Firebase guide](FIREBASE.md). The owner subsequently authorized pushing the project and running [GitHub Actions](https://github.com/jshao0919/zetaRun/actions/workflows/android.yml); its runs provide separate CI results and downloadable APK artifacts. No Play Store publication or fresh-cloud-task restoration is claimed.
