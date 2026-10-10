# BMW ENET Test v0.2

Read-only Android/Kotlin transport and DME test for BMW ENET/HSFZ.

- Finds Android Ethernet network.
- BMW HSFZ discovery on UDP 6811.
- Connects TCP 6801.
- Sends UDS ReadDataByIdentifier `22 F1 90` to DME target `0x12` and tries to decode VIN.
- Sends experimental read-only OBD `01 0C` RPM request to DME and displays raw response even if unsupported.
- Does not send coding, flashing, reset, routine-control, security-access, write-data or IO-control services.

Build with Android Studio (compileSdk 35, minSdk 26).

## GitHub Actions APK build

The repository includes `.github/workflows/build-apk.yml`.

It automatically builds the debug APK on pushes to `main`/`master`, pull requests, and manual `workflow_dispatch` runs.

To download the APK on GitHub:
1. Open **Actions** → **Build Android APK**.
2. Open the latest successful run.
3. In **Artifacts**, download **BmwEnetTest-v0.2-debug-apk**.
4. Unzip it to get `app-debug.apk`.

The CI build uses JDK 17, Gradle 8.9, Android SDK 35 and Android Build Tools 35.0.0. No signing secrets are required for this debug APK.

## Automated quality gates (CI)

Every push or pull request now runs the following **before** the debug APK artifact is published:

1. `gradle :app:testDebugUnitTest` — JVM tests, no car/Android emulator required.
2. `gradle :app:lintDebug` — Android Lint (fatal and error findings fail CI).
3. `gradle :app:assembleDebug` — build the APK only after the gates pass.

Tests cover HSFZ stream framing, UDS multi-DID response validation, AUTO A/B/C/D mode selection and fallback, read-only DME probe parsing, fuel-refill detection, frozen octane reference boundaries, and STEADY robust statistics.

CI attaches a `bmw-octane-quality-reports` artifact (JUnit XML, HTML tests and Lint reports), even when tests or Lint fail. The APK artifact is not published on a failing run.

Run the same checks locally with JDK 17, Gradle 8.9 and Android SDK 35:

```sh
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

These are software tests, **not** verification of ENET/VXSCAN compatibility on a real vehicle. Firmware-specific timing and multi-TCP support still require car-side log validation.
