# Ascent
Personal fat-loss journey tracker (Android 14+, Compose).
- Build locally: `./gradlew assembleDebug` (needs Android SDK, JDK 17+) → `app/build/outputs/apk/debug/app-debug.apk`
- CI: every push builds the APK; download it from the Actions run → Artifacts → `ascent-debug-apk`.
- Core progress rules: `app/src/main/java/com/ascent/app/core/Progress.kt`.
