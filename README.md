# WealthWard

Offline Android personal finance app built with Kotlin, Compose and Room. A fresh development installation starts with eight generic products (six deposits and two credit cards), represented by ten native CRC/USD ledgers. Existing local databases are migrated and preserved; no personal bank names, balances or credentials are included.

The five Spanish sections are Plan, Movimientos, Cuentas, Metas and Config. Supported workflows include dated account openings and archiving, income and exact expense splits, audited corrections/voids/partial refunds, atomic own transfers and cash withdrawals, explicit foreign exchange amounts, funded purpose reserves, booked/available bank comparisons and reasoned adjustments, pending authorizations, and separate credit purchases and payments.

The Plan section includes period budgets and revisions, expected salary and extraordinary income, recurring bills and subscriptions, household own shares, annual reserve contributions, linked actual receipts and payments, and an upcoming and overdue calendar. Open or synchronize a period after configuring templates. Expected amounts and planned savings are planning figures. Monthly schedules clamp missing dates to month end while retaining the nominal day. The default salary date is the last Monday–Friday at 09:00 America/Costa_Rica, without holiday adjustment. A fixed day can be selected. Preparing a period copies planning settings while keeping paid movements and funded balances in their original periods.

Version 0.4 adds term debts in Config, with creditor balance verification, scheduled installments, and actual payments split into principal, interest and charges. Use the Plan calendar to record an installment, or open Config to record an extra payment. Enter confirmed terms when available. Projected installments and remaining terms can be estimates. Credit card accounts in Cuentas can record statement dates and amounts and link local email or receipt references to specific purchases or payments.

In Metas, set a savings target and monthly contribution goal, then fund it from available money. See the amount held by each account and use the goal actions to release, reallocate or transfer it. The monthly savings estimate leaves balances untouched. A contribution to an outside recipient is recorded as an expense. Projects can link to a savings goal and track estimated and actual costs by item. Use an existing ledger movement when the payment is already recorded.

Local reminders default off. Configure anticipation and hour in Config, then intentionally request Android notification permission. Denying permission leaves financial workflows and the calendar available. Android may delay inexact reminders; delivery at the precise configured minute is not guaranteed. Reminder preferences are part of the local planning data; no banking service or remote notification service is used.

Assisted import accepts pasted CSV or TSV with Cuenta, Contenido and Notas. Account mapping, opening amounts/dates and optional funded reserves require explicit review and confirmation. Notes are preserved without guessing balances. Import is local and transactional; no banking or spreadsheet connection is configured.

Requirements: Android Studio, Android SDK 37 and JDK 25. Create an untracked local.properties with sdk.dir, or set ANDROID_HOME. Minimum Android API: 26, provisional.

Run `./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --no-daemon`. On Windows use `gradlew.bat`. Run instrumented Room and Compose acceptance tests on an authorized emulator or device with `./gradlew connectedDebugAndroidTest`. Build success alone does not establish device test results. Install the development app with `./gradlew installDebug`.

Debug application ID: `io.github.kannoncoding.wealthward.dev`; release ID: `io.github.kannoncoding.wealthward`. Development upgrades retain this application ID and signing configuration to preserve existing app data. Release signing is intentionally unconfigured. No license has been selected.
