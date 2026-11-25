package io.github.kannoncoding.wealthward.data

import io.github.kannoncoding.wealthward.domain.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import org.json.JSONObject

internal fun f07Sum(values: Iterable<Long>): Long = values.fold(0L, Math::addExact)

internal data class DebtEntry(
    val raw: JSONObject,
    val payment: DebtPayment? = null,
    val verification: CreditorBalance? = null,
) {
    val sequence get() = raw.f07Long("sequence")
    val date get() = LocalDate.parse(payment?.date ?: checkNotNull(verification).date)
    val id get() = payment?.id ?: checkNotNull(verification).id
    val resultingPrincipal get() = raw.f07Long("resultingPrincipal")
    val voided get() = raw.optBoolean("voided")
}

internal fun JSONObject.debtEntries(debtId: String): List<DebtEntry> =
    (f07Rows("payments").filter { it.getString("debtId") == debtId }.map { DebtEntry(it, payment = it.debtPayment()) } +
        f07Rows("checks").filter { it.getString("debtId") == debtId }.map { DebtEntry(it, verification = it.creditorBalance()) })
        .sortedBy { it.sequence }

internal fun debtStatus(debt: Debt, entries: List<DebtEntry>): DebtStatus {
    val active = entries.filterNot { it.voided }
    val principal = active.lastOrNull()?.resultingPrincipal ?: debt.openingMinor
    val latest = active.lastOrNull()
    val payment = active.mapNotNull { it.payment }.lastOrNull()
    val firstMonth = YearMonth.from(LocalDate.parse(debt.firstDueDate))
    val pending = if (principal == 0L) emptyList() else {
        val initialLast = firstMonth.plusMonths((debt.remainingMonths - 1L) * debt.intervalMonths)
        val last = active.fold(initialLast) { end, entry ->
            val extra = entry.payment?.takeIf { it.extra } ?: return@fold end
            val newTerm = extra.actualRemainingMonths ?: if (debt.extraPaymentPolicy == ExtraPaymentPolicy.REDUCE_TERM &&
                debt.amortization == DebtAmortization.FIXED_MONTHLY) 1 else return@fold end
            maxOf(end, YearMonth.from(entry.date).plusMonths(newTerm.toLong() * debt.intervalMonths))
        }
        debtInstallments(debt, active, last, LocalDate.now(BusinessTime.zone)).filter { it.pendingMinor > 0 }
    }
    return DebtStatus(
        debt, principal, active.lastOrNull { it.verification != null }?.verification,
        principal > 0 && (latest?.verification == null || debt.amortization == DebtAmortization.MANUAL ||
            (payment?.extra == true && debt.extraPaymentPolicy != ExtraPaymentPolicy.MANUAL &&
                payment.actualNextInstallmentMinor == null && payment.actualRemainingMonths == null)),
        active.mapNotNull { it.payment },
        pending.firstOrNull()?.expectedMinor ?: 0L,
        pending.size,
        pending.lastOrNull()?.dueDate,
    )
}

private data class DebtTerms(val installment: Long, val remainingMonths: Int, val changed: Boolean)

private fun debtTerms(debt: Debt, entries: List<DebtEntry>, month: YearMonth): DebtTerms {
    val first = YearMonth.from(LocalDate.parse(debt.firstDueDate))
    val offset = ChronoUnit.MONTHS.between(first, month).coerceAtLeast(0) / debt.intervalMonths
    var end = debt.remainingMonths.toLong()
    var installment = debt.installmentMinor
    var changed = false
    val knownRate = debt.annualRatePercent.takeIf { debt.amortization == DebtAmortization.FIXED_MONTHLY }
    val charges = debt.installmentChargesMinor ?: 0
    entries.filter { it.payment?.extra == true }.forEach { entry ->
        val payment = checkNotNull(entry.payment)
        val elapsed = ChronoUnit.MONTHS.between(first, YearMonth.from(entry.date))
        val nextOffset = if (elapsed < 0) 0L else elapsed / debt.intervalMonths + 1
        if (nextOffset > offset) return@forEach
        val remaining = (end - nextOffset).coerceAtLeast(1)
        installment = payment.actualNextInstallmentMinor ?: if (debt.extraPaymentPolicy == ExtraPaymentPolicy.REDUCE_INSTALLMENT && knownRate != null) {
            Math.addExact(estimatedInstallment(entry.resultingPrincipal, remaining, knownRate), charges)
        } else installment
        val newRemaining = payment.actualRemainingMonths?.toLong() ?: if (debt.extraPaymentPolicy == ExtraPaymentPolicy.REDUCE_TERM && knownRate != null) {
            estimatedTerm(entry.resultingPrincipal, installment - charges, remaining, knownRate)
        } else remaining
        if (payment.actualRemainingMonths != null || (debt.extraPaymentPolicy == ExtraPaymentPolicy.REDUCE_TERM && knownRate != null)) end = nextOffset + newRemaining
        changed = changed || payment.actualNextInstallmentMinor != null || payment.actualRemainingMonths != null ||
            (debt.extraPaymentPolicy != ExtraPaymentPolicy.MANUAL && knownRate != null)
    }
    return DebtTerms(installment, (end - offset).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), changed)
}

private fun monthlyInterest(principal: Long, annualRatePercent: String): Long =
    debtMonthInterest(principal, checkNotNull(normalizedDebtRate(annualRatePercent))).longValueExact()

private fun estimatedInstallment(principal: Long, months: Long, annualRatePercent: String): Long {
    if (principal == 0L) return 0
    val rate = checkNotNull(normalizedDebtRate(annualRatePercent)).divide(BigDecimal("1200"), 20, RoundingMode.HALF_UP)
    val base = BigDecimal.valueOf(principal)
    val payment = if (rate.signum() == 0) base.divide(BigDecimal.valueOf(months), 0, RoundingMode.CEILING) else {
        val discount = BigDecimal.ONE.divide(BigDecimal.ONE.add(rate).pow(Math.toIntExact(months)), 20, RoundingMode.HALF_UP)
        base.multiply(rate).divide(BigDecimal.ONE.subtract(discount), 0, RoundingMode.CEILING)
    }
    return payment.longValueExact()
}

private fun estimatedTerm(principal: Long, installment: Long, originalRemaining: Long, rate: String): Long {
    var left = principal
    for (month in 1..originalRemaining) {
        val part = installment - monthlyInterest(left, rate)
        if (part <= 0) return originalRemaining
        if (part >= left) return month
        left -= part
    }
    return originalRemaining
}

internal fun commitmentInstallments(state: JSONObject, month: String): List<PlannedOccurrence> {
    val data = state.commitments()
    val until = YearMonth.parse(month)
    val today = LocalDate.now(BusinessTime.zone)
    return data.f07Rows("debts").flatMap { row ->
        val debt = data.debtWithMaturity(row)
        val entries = data.debtEntries(debt.id).filterNot { it.voided }
        debtInstallments(debt, entries, until, today)
    }
}

private fun debtInstallments(
    debt: Debt, entries: List<DebtEntry>, until: YearMonth, today: LocalDate,
): List<PlannedOccurrence> {
    val firstDate = LocalDate.parse(debt.firstDueDate)
    val first = YearMonth.from(firstDate)
    val paid = entries.filter { it.payment?.installmentMonth != null }.groupBy { YearMonth.parse(it.payment!!.installmentMonth!!) }
    val last = maxOf(until, paid.keys.maxOrNull() ?: until)
    val limit = ChronoUnit.MONTHS.between(first, last).coerceAtMost(1199)
    return (0..limit step debt.intervalMonths.toLong()).mapNotNull { index ->
        val nominal = first.plusMonths(index)
        val due = PlanningMath.due(nominal, firstDate.dayOfMonth)
        val paymentEntries = paid[nominal].orEmpty()
        val beforeMonth = entries.filter { YearMonth.from(it.date) < nominal }
        val balance = beforeMonth.lastOrNull()?.resultingPrincipal ?: debt.openingMinor
        val terms = debtTerms(debt, beforeMonth, nominal)
        val totalPaid = f07Sum(paymentEntries.map { it.payment!!.totalMinor })
        val closed = (entries.lastOrNull()?.resultingPrincipal ?: debt.openingMinor) == 0L
        if (totalPaid == 0L && (terms.remainingMonths == 0 || balance == 0L || closed)) return@mapNotNull null
        val charges = debt.installmentChargesMinor ?: 0
        val planned = when {
            terms.remainingMonths == 1 && !terms.changed && debt.finalInstallmentMinor != null -> debt.finalInstallmentMinor
            terms.remainingMonths == 1 && !terms.changed && debt.estimateTermFromBalance -> {
                val rate = debt.annualRatePercent?.let(::normalizedDebtRate)
                    .takeIf { debt.amortization == DebtAmortization.FIXED_MONTHLY }
                val payoff = debtPayoff(debt.openingMinor, debt.installmentMinor - charges, rate, debt.remainingMonths)
                if (payoff == null) terms.installment else Math.addExact(payoff.finalPaymentMinor, charges)
            }
            terms.remainingMonths == 1 && !terms.changed && debt.maturityDate != null -> terms.installment
            terms.remainingMonths == 1 && debt.amortization == DebtAmortization.FIXED_MONTHLY -> {
                val payoff = f07Sum(listOf(balance, monthlyInterest(balance, checkNotNull(debt.annualRatePercent)), charges))
                if (terms.changed) minOf(payoff, terms.installment) else payoff
            }
            else -> terms.installment
        }
        val expected = if (closed) totalPaid else maxOf(planned, totalPaid)
        val pending = expected - totalPaid
        PlannedOccurrence(
            "${debtAccount(debt.id)}:$nominal:0", debtAccount(debt.id), 1, nominal.toString(), 0,
            debt.name, debt.group, PlanNature.DEBT, debt.currency, due.toString(), expected, null,
            totalPaid, pending, debt.suggestedAccountId, null, pending > 0 && due < today,
            paymentEntries.map { it.raw.getString("operationId") }, nominal.toString(), totalPaid,
        )
    }
}

