# WealthWard

Android personal finance app foundation built with Kotlin, Compose and Room. The development build uses synthetic demonstration data only.

Requirements: Android Studio, Android SDK 37 and JDK 25 (JDK 17 or newer supported by the selected Android plugin; Gradle requires a compatible runtime). Create an untracked local.properties with sdk.dir, or set ANDROID_HOME.

Run `./gradlew assembleDebug testDebugUnitTest lintDebug`. On Windows use `gradlew.bat`. Install the dev APK with `./gradlew installDebug` on an authorized Android device. Minimum Android API: 26, provisional.

The five sections are Plan, Movements, Accounts, Goals and Settings. This foundation includes monetary validation, local persistence and synthetic fixtures; subsequent product workflows remain under development. No banking connection is configured.

Debug application ID: `io.github.kannoncoding.wealthward.dev`. Release ID: `io.github.kannoncoding.wealthward`. Release signing is intentionally unconfigured. No license has been selected.
