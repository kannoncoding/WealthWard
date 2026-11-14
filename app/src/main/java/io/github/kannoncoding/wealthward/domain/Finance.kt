package io.github.kannoncoding.wealthward.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class Currency { CRC, USD }
sealed class DomainFailure(message: String) : IllegalArgumentException(message) {
    class Invalid(message: String) : DomainFailure(message)
    class CurrencyMismatch : DomainFailure("Currencies must match")
    class UnsupportedVersion : DomainFailure("Unsupported fixture version")
}
data class Money(val minor: Long, val currency: Currency) {
    operator fun plus(other: Money): Money {
        if (currency != other.currency) throw DomainFailure.CurrencyMismatch()
        return try { copy(minor = Math.addExact(minor, other.minor)) } catch (_: ArithmeticException) { throw DomainFailure.Invalid("Money overflow") }
    }
    fun convert(to: Currency, rate: BigDecimal): Money {
        if (rate.signum() <= 0) throw DomainFailure.Invalid("Rate must be positive")
        return try { Money(BigDecimal.valueOf(minor).multiply(rate).setScale(0, RoundingMode.HALF_UP).longValueExact(), to) } catch (_: ArithmeticException) { throw DomainFailure.Invalid("Money overflow") }
    }
    fun decimal(): BigDecimal = BigDecimal.valueOf(minor, 2)
    companion object {
        fun parse(value: String, currency: Currency): Money = try {
            Money(BigDecimal(value).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact(), currency)
        } catch (_: NumberFormatException) { throw DomainFailure.Invalid("Invalid money") } catch (_: ArithmeticException) { throw DomainFailure.Invalid("Money overflow") }
    }
}
object BusinessTime {
    val zone: ZoneId = ZoneId.of("America/Costa_Rica")
    fun date(instant: Instant): LocalDate = instant.atZone(zone).toLocalDate()
}
enum class FinancialType { INCOME, EXPENSE, TRANSFER, CREDIT_PURCHASE, CREDIT_PAYMENT }
data class PostingLeg(val accountId: String, val amount: Money)
data class PostingRequest(
    val id: String, val occurredAt: Instant, val date: LocalDate,
    val type: FinancialType, val legs: List<PostingLeg>, val periodId: String? = null,
    val expenseMinor: Long = 0,
) {
    fun validate() {
        if (date != BusinessTime.date(occurredAt)) throw DomainFailure.Invalid("Business date must use Costa Rica time")
        if (id.isBlank() || legs.isEmpty() || legs.any { it.accountId.isBlank() || it.amount.minor == 0L })
            throw DomainFailure.Invalid("Posting requires an id and nonzero account legs")
        if (expenseMinor < 0 || (type == FinancialType.CREDIT_PAYMENT && expenseMinor != 0L))
            throw DomainFailure.Invalid("Settlement cannot count as an expense")
        if (type == FinancialType.TRANSFER && (legs.size < 2 || legs.map { it.accountId }.distinct().size != legs.size || expenseMinor != 0L))
            throw DomainFailure.Invalid("Transfer needs distinct source and destination and no expense")
        if (type in setOf(FinancialType.TRANSFER, FinancialType.CREDIT_PAYMENT, FinancialType.CREDIT_PURCHASE)) {
            if (legs.map { it.amount.currency }.distinct().size != 1) throw DomainFailure.CurrencyMismatch()
            if (type != FinancialType.CREDIT_PURCHASE && legs.fold(0L) { sum, leg -> Math.addExact(sum, leg.amount.minor) } != 0L)
                throw DomainFailure.Invalid("Transfer legs must balance")
        }
    }
}
