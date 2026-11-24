package io.github.kannoncoding.wealthward.ui

import io.github.kannoncoding.wealthward.domain.*
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountMonthTest {
    @Test fun sharedRentUsesFullPendingAmountAndDoesNotCountReservedFundsTwice() {
        val account = AccountDetail("sample", "demo", "Cuenta de prueba", Currency.CRC, "CASH",
            null, false, 60_000_000, 0, 60_000_000, 0, emptyList(), null)
        val rent = PlannedOccurrence("rent", "rent", 1, "2026-11", 0, "Renta", "Hogar",
            PlanNature.FIXED, Currency.CRC, "2026-11-30", 20_000_000, null, 0, 20_000_000,
            account.id, "rent-reserve", false, emptyList(),
            paymentTotalMinor = 40_000_000, paymentPendingMinor = 40_000_000, shared = true)
        val bills = monthlyAccountBills(F06Snapshot(occurrences = listOf(rent,
            rent.copy(id = "next", dueDate = "2026-12-01"))), "2026-11").getValue(account.id)
        assertEquals(BigDecimal("200000.00"), BigDecimal.valueOf(account.unassignedMinor, 2) - pendingOutsideReserves(account, bills))
        val funded = account.copy(reserves = listOf(ReserveBalance(account.id, "rent-reserve", "Renta", 10_000_000)))
        assertEquals(BigDecimal("300000.00"), pendingOutsideReserves(funded, bills))
        assertEquals(BigDecimal("0.00"), pendingOutsideReserves(account, listOf(rent.copy(paymentPendingMinor = 0))))
    }
}
