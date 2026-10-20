# Validation — October 20, 2026

The Linux cloud environment used a full JDK 21 and Android SDK 36. These commands completed successfully against the final application sources:

```bash
source scripts/cloud-env.sh
./gradlew testDebugUnitTest lintDebug assembleDebug bundleRelease --console=plain
python3 scripts/check_apk.py app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 4 app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose app/build/outputs/apk/debug/app-debug.apk
```

- **58 unit tests passed:** 26 arithmetic/session/parser, 12 voice endpoint/duplicate tests, and 20 local storage/serialization/account-isolation/deletion tests. Zero failed, errored, or skipped.
- **Android lint passed:** zero errors and three warnings: `ApplySharedPref`, `UseKtx`, and `DataExtractionRules`. Disk-result checks intentionally run on the IO dispatcher for durable migration/deletion handling. The manifest sets `allowBackup=false` and `fullBackupContent=false`, and supplies explicit Android 12+ backup/transfer exclusions; lint still recommends legacy backup metadata.
- **Debug APK built and signature verified.** It contains the full offline English model and both ARM64 and x86-64 speech libraries.
- **16 KB compatibility checks passed:** all packaged native ELF load segments and the APK's uncompressed ZIP alignment passed the explicit checks. This is packaging validation, not execution on a 16 KB-page device.
- **Optimized release App Bundle built.** It is unsigned; it has not been uploaded or published to Google Play.
- **Setup repeatability:** the cloud installer completed twice without replacing existing local settings; model preparation also passed its repeat check. Tool/model downloads preserve TLS and publisher checksums.
- **Desktop speech smoke check:** eight synthesized English number/control phrases were recognized with the bundled model and constrained vocabulary, including “one hundred twenty,” “one thousand two hundred,” “skip,” and “pause.” This tests model/vocabulary behavior on prerecorded audio, not real microphone accuracy or S24 latency.

The S24 Ultra microphone, treadmill/fan conditions, Bluetooth routing, and Google/Firebase end-to-end flows still require the owner's device and Firebase configuration. Follow [the phone guide](S24_ULTRA.md), [voice benchmark](VOICE_BENCHMARK.md), and [Firebase guide](FIREBASE.md). The owner subsequently authorized pushing the project and running [GitHub Actions](https://github.com/jshao0919/zetaRun/actions/workflows/android.yml); its runs provide separate CI results and downloadable APK artifacts. No Play Store publication or fresh-cloud-task restoration is claimed.
