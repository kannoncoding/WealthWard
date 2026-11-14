package io.github.kannoncoding.wealthward.domain

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class MoneyTest {
    @Test fun roundsHalfUpAndConvertsWithoutBinaryFloats() {
        assertEquals(101L, Money.parse("1.005", Currency.USD).minor)
        assertEquals(505L, Money(101, Currency.USD).convert(Currency.CRC, BigDecimal("5")).minor)
    }
    @Test fun rejectsImplicitCurrencyMixing() {
        assertThrows(DomainFailure.CurrencyMismatch::class.java) { Money(1, Currency.CRC) + Money(1, Currency.USD) }
    }
    @Test fun businessDateUsesCostaRicaMidnight() {
        assertEquals("2026-01-01", BusinessTime.date(Instant.parse("2026-01-02T05:59:59Z")).toString())
    }
    @Test fun settlementCannotDoubleCountExpense() {
        assertThrows(DomainFailure.Invalid::class.java) {
            PostingRequest("pay", Instant.EPOCH, BusinessTime.date(Instant.EPOCH), FinancialType.CREDIT_PAYMENT, listOf(PostingLeg("a", Money(-100, Currency.CRC)), PostingLeg("b", Money(100, Currency.CRC))), expenseMinor = 100).validate()
        }
    }
}
