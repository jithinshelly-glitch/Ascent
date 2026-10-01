# Ascent – progress
- [x] 1. Gradle setup (wrapper 8.11.1, AGP 8.7.3, Kotlin 2.0.21, KSP, debug keystore)
- [x] 2. Core logic (core/Progress.kt evaluateDays)
- [x] 3. Data (Room + Prefs)
- [x] 4. Sources/Tracker/Worker (Health Connect, UsageStats, WorkManager 00:05)
- [x] 5. UI: theme+shell, Home (body, analytics, NN, journey), Log, Settings
- [x] 6. CI workflow + push + green build
Notes: no Android SDK in cloud container (dl.google.com blocked) -> build verified via GitHub Actions only.
- CI run #1 green (Actions run 36897203137). Next: on-device testing/tweaks.
