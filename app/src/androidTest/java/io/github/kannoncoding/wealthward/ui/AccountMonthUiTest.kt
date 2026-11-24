package io.github.kannoncoding.wealthward.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.kannoncoding.wealthward.domain.Currency
import io.github.kannoncoding.wealthward.domain.PlanNature
import io.github.kannoncoding.wealthward.domain.PlannedOccurrence
import io.github.kannoncoding.wealthward.domain.RecurringTemplate
import org.junit.Rule
import org.junit.Test

class AccountMonthUiTest {
    @get:Rule val ui = createComposeRule()

    @Test fun sharedBillsShowOwnShareGrossAndPaymentStatus() {
        val percent = RecurringTemplate("rent", 1, "Alquiler", "Hogar", PlanNature.FIXED,
            16_000, Currency.CRC, "2026-11", householdTotalMinor = 40_000, ownPercent = "40")
        val fixed = percent.copy(id = "utility", name = "Electricidad", householdTotalMinor = 15_000,
            ownPercent = null, ownFixedMinor = 5_000)
        val pending = PlannedOccurrence("rent:2026-11:0", percent.id, 1, "2026-11", 0,
            percent.name, percent.group, percent.nature, percent.currency, "2026-11-12",
            16_000, null, 0, 16_000, "demo", null, true, emptyList(),
            paymentTotalMinor = 40_000, paymentPendingMinor = 40_000, shared = true)
        val paid = pending.copy(id = "utility:2026-11:0", templateId = fixed.id, name = fixed.name,
            expectedMinor = 5_000, paidMinor = 5_000, pendingMinor = 0,
            paymentTotalMinor = 15_000, paymentPendingMinor = 0)
        ui.setContent {
            WealthTheme(false) {
                Column { AccountMonthlyBills(listOf(pending, paid), listOf(percent, fixed)) { _, _ -> } }
            }
        }
        listOf("Mi aporte: CRC 160.00 · 40%", "Total compartido: CRC 400.00", "Pendiente CRC 400.00",
            "Mi aporte: CRC 50.00", "Total compartido: CRC 150.00", "Pagado")
            .forEach { ui.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed() }
    }
}
