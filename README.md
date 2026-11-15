# WealthWard

Offline Android personal finance app built with Kotlin, Compose and Room. A fresh development installation starts with eight generic products (six deposits and two credit cards), represented by ten native CRC/USD ledgers. Existing local databases are migrated and preserved; no personal bank names, balances or credentials are included.

The five Spanish sections are Plan, Movimientos, Cuentas, Metas and Config. Supported workflows include dated account openings and archiving, income and exact expense splits, audited corrections/voids/partial refunds, atomic own transfers and cash withdrawals, explicit foreign exchange amounts, funded purpose reserves, booked/available bank comparisons and reasoned adjustments, pending authorizations, and separate credit purchases and payments. The period budget editor is planned for F06.

Assisted import accepts pasted CSV or TSV with Cuenta, Contenido and Notas. Account mapping, opening amounts/dates and optional funded reserves require explicit review and confirmation. Notes are preserved without guessing balances. Import is local and transactional; no banking or spreadsheet connection is configured.

Requirements: Android Studio, Android SDK 37 and JDK 25. Create an untracked local.properties with sdk.dir, or set ANDROID_HOME. Minimum Android API: 26, provisional.

Run `./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --no-daemon`. On Windows use `gradlew.bat`. Run instrumented Room and Compose acceptance tests on an authorized emulator or device with `./gradlew connectedDebugAndroidTest`. Build success alone does not establish device test results. Install the development app with `./gradlew installDebug`.

Debug application ID: `io.github.kannoncoding.wealthward.dev`; release ID: `io.github.kannoncoding.wealthward`. Development upgrades retain this application ID and signing configuration to preserve existing app data. Release signing is intentionally unconfigured. No license has been selected.
