# R8 checks on Android

Release builds use R8 by default. Perf builds use it only with `-PperfOptimize`.

`-PperfVerify` adds a headless validation activity to the separate perf app. It is excluded from normal builds. The checks and app code are optimised together, so R8 can inline and rewrite their calls. No app API keep rules are added.

Set `R8_VALIDATION_DIR` to an absolute build directory for the interchange files. Generate an unoptimised Sparkler world with `:core:jvmTest --tests '*R8SaveInteropTest.prepareUnoptimizedSparklerWorld'`. Build the Android verifier with:

```sh
heavy build ./gradlew :app:assemblePerf -Parm32 -PperfOptimize -PperfVerify
```

Install that APK under `com.rm.apogee.perf`. Push `r8-input-world.json` to `/sdcard/Android/data/com.rm.apogee.perf/files/`, remove any old `r8-validation.json`, then start `com.rm.apogee.perf/com.rm.apogee.PerfValidationActivity` with adb. It writes `r8-validation.json` and six `r8-output-*` interchange files beside the input. Read the report and pull those outputs into `R8_VALIDATION_DIR`.

Check the Android exports with unoptimised code:

```sh
R8_VALIDATION_DIR="$PWD/build/r8-exports" heavy build ./gradlew   :core:jvmTest --rerun --tests '*R8SaveInteropTest.optimizedAndroidExportsLoadOnTheJvm'   :net:jvmTest --rerun --tests '*R8WireInteropTest'
```

The activity checks old-save import, burning solids and save/backup recovery, career persistence, craft sharing through FileProvider, protobuf/TCP, two game clients and native audio calls. Its temporary cache directories are removed after each case. The compatibility files remain for inspection. Audio buses are muted; this checks the native path, not listening quality.

The verification code changes reachability, so also smoke-test an ordinary `-PperfOptimize` APK without `-PperfVerify`. External instrumentation cannot safely call arbitrary APIs after R8 has inlined or removed them. Release optimisation is configured separately.
