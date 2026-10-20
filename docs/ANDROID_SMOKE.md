# Android runtime smoke attempt

Attempted on 2026-10-20 with the final debug APK and an official checksum-verified Android 16/API 36 x86_64 system image.

The cloud machine has no `/dev/kvm`, so the emulator used software CPU emulation and SwiftShader. Android progressed through initial filesystem setup, APEX decompression, ART class preloading, system-server startup, and first-boot dex verification. ADB, PackageManager, WindowManager, and ActivityManager became available. `pm.dexopt.first-boot` already used `verify`; no verification or readiness flags were disabled or forced.

The complete APK transferred to the guest successfully (115,638,221 bytes). Installation attempts before and after first-boot dex verification both returned:

```text
Error: device is still booting.
```

`sys.boot_completed` remained unset at the end of the bounded attempt. The emulator was stopped to release cloud CPU resources. This is an incomplete Android platform startup check, not evidence of an application failure.

Application launch, visual screenshots, keypad/session/history flows, Android microphone behavior, and real-device speech accuracy/latency were **not executed** in this emulator. Google/Firebase end-to-end behavior also requires the owner's Firebase configuration. Build, unit-test, native-library alignment, and signature results are reported separately in the repository's validation documentation.

Use [S24 Ultra testing](S24_ULTRA.md) and [Voice benchmarking](VOICE_BENCHMARK.md) for the required handset checks. A successful APK build or desktop recognition test does not establish mobile runtime performance.
