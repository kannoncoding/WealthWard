package io.github.kannoncoding.wealthward.domain

import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class F05MoneyTest {
 @Test fun tinyFxRatesRoundToIntegerMinorUnitsWithHalfUpAndRejectOverflow() {
  assertEquals(1L,Money(500,Currency.CRC).convert(Currency.USD,BigDecimal("0.001")).minor)
  assertEquals(0L,Money(499,Currency.CRC).convert(Currency.USD,BigDecimal("0.001")).minor)
  assertThrows(DomainFailure.Invalid::class.java) { Money(Long.MAX_VALUE,Currency.USD).convert(Currency.CRC,BigDecimal("2")) }
  assertThrows(DomainFailure.Invalid::class.java) { Money(100,Currency.USD).convert(Currency.CRC,BigDecimal.ZERO) }
 }
 @Test fun signedRefundAndReversalCanReduceRecognizedExpenseButOrdinaryExpenseCannot() {
  val instant=Instant.parse("2026-11-01T18:00:00Z")
  PostingRequest("refund",instant,BusinessTime.date(instant),FinancialType.REFUND,listOf(PostingLeg("a",Money(500,Currency.CRC))),expenseMinor=-500).validate()
  assertThrows(DomainFailure.Invalid::class.java) { PostingRequest("bad",instant,BusinessTime.date(instant),FinancialType.EXPENSE,listOf(PostingLeg("a",Money(-500,Currency.CRC))),expenseMinor=-500).validate() }
 }
}
