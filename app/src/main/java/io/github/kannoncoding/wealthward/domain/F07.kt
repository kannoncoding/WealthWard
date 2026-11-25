package io.github.kannoncoding.wealthward.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class DebtKind { PERSONAL_LOAN, INSTALLMENT, MORTGAGE, OTHER }
enum class DebtAmortization { MANUAL, FIXED_MONTHLY }
enum class ExtraPaymentPolicy { REDUCE_TERM, REDUCE_INSTALLMENT, MANUAL }
enum class SavingAction { CONTRIBUTE, WITHDRAW, REALLOCATE, TRANSFER, EXTERNAL }
enum class CreditEvidenceKind { PURCHASE, PAYMENT }

data class Debt(
    val id: String,
    val name: String,
    val group: String,
    val currency: Currency,
    val kind: DebtKind,
    val openingMinor: Long,
    val openingDate: String,
    val firstDueDate: String,
    val installmentMinor: Long,
    val remainingMonths: Int,
    val annualRatePercent: String? = null,
    val finalInstallmentMinor: Long? = null,
    val extraPaymentPolicy: ExtraPaymentPolicy = ExtraPaymentPolicy.MANUAL,
    val suggestedAccountId: String? = null,
    val amortization: DebtAmortization = DebtAmortization.MANUAL,
    val intervalMonths: Int = 1,
    val maturityDate: String? = null,
    val estimateTermFromBalance: Boolean = false,
    val estimatedOpeningPrincipal: Boolean = false,
    val installmentChargesMinor: Long? = null,
)

data class DebtPayment(
    val id: String,
    val debtId: String,
    val accountId: String,
    val date: String,
    val installmentMonth: String?,
    val principalMinor: Long,
    val interestMinor: Long,
    val chargesMinor: Long,
    val actualNextInstallmentMinor: Long? = null,
    val actualRemainingMonths: Int? = null,
) {
    val totalMinor: Long get() = Math.addExact(Math.addExact(principalMinor, interestMinor), chargesMinor)
    val extra: Boolean get() = installmentMonth == null
}

data class CreditorBalance(val id: String, val debtId: String, val date: String, val principalMinor: Long, val reference: String)
data class DebtStatus(
    val debt: Debt,
    val principalMinor: Long,
    val creditorBalance: CreditorBalance?,
    val estimated: Boolean,
    val payments: List<DebtPayment>,
    val projectedInstallmentMinor: Long?,
    val projectedRemainingMonths: Int?,
    val projectedPayoffDate: String? = null,
)
data class SavingGoal(
    val id: String,
    val name: String,
    val group: String,
    val currency: Currency,
    val targetMinor: Long,
    val targetDate: String,
    val priority: Int,
    val monthlyMinor: Long,
)
data class SavingEvent(
    val id: String,
    val action: SavingAction,
    val goalId: String,
    val accountId: String,
    val date: String,
    val minor: Long,
    val destinationAccountId: String? = null,
    val destinationGoalId: String? = null,
    val recipient: String? = null,
)
data class SavingStatus(val goal: SavingGoal, val accounts: List<ReserveBalance>, val fundedMinor: Long, val events: List<SavingEvent>, val contributedThisMonthMinor: Long, val attainableThisMonthMinor: Long)
data class Project(val id: String, val name: String, val group: String, val currency: Currency, val targetMinor: Long, val goalId: String? = null)
data class ProjectItem(val id: String, val projectId: String, val name: String, val estimatedMinor: Long)
data class ProjectPayment(val id: String, val itemId: String, val operationId: String)
data class ProjectItemStatus(val item: ProjectItem, val actualMinor: Long, val payments: List<ProjectPayment>)
data class ProjectStatus(val project: Project, val fundedMinor: Long, val items: List<ProjectItemStatus>)
data class CreditStatement(val id: String, val accountId: String, val cutoffDate: String, val dueDate: String, val statementMinor: Long, val purchaseIds: List<String> = emptyList(), val paymentIds: List<String> = emptyList())
data class CreditEvidence(val id: String, val accountId: String, val operationId: String, val kind: CreditEvidenceKind, val reference: String)
data class CreditStatementStatus(val statement: CreditStatement, val openingMinor: Long, val pendingMinor: Long, val pendingHoldIds: List<String>)
data class F07Snapshot(
    val debts: List<DebtStatus> = emptyList(),
    val savings: List<SavingStatus> = emptyList(),
    val projects: List<ProjectStatus> = emptyList(),
    val statements: List<CreditStatementStatus> = emptyList(),
    val evidence: List<CreditEvidence> = emptyList(),
    val installments: List<PlannedOccurrence> = emptyList(),
)

sealed interface F07Command {
    val requestId: String
    data class CreateDebt(override val requestId: String, val debt: Debt) : F07Command
    data class UpdateDebtMaturity(override val requestId: String, val debtId: String, val maturityDate: String) : F07Command
    data class PayDebt(override val requestId: String, val payment: DebtPayment) : F07Command
    data class VoidDebtPayment(override val requestId: String, val paymentId: String, val reason: String) : F07Command
    data class VerifyBalance(override val requestId: String, val balance: CreditorBalance) : F07Command
    data class SaveGoal(override val requestId: String, val goal: SavingGoal) : F07Command
    data class MoveSaving(override val requestId: String, val event: SavingEvent) : F07Command
    data class SaveProject(override val requestId: String, val project: Project) : F07Command
    data class SaveProjectItem(override val requestId: String, val item: ProjectItem) : F07Command
    data class LinkProjectPayment(override val requestId: String, val payment: ProjectPayment) : F07Command
    data class RecordProjectPayment(override val requestId: String, val itemId: String, val accountId: String, val date: String, val minor: Long) : F07Command
    data class SaveStatement(override val requestId: String, val statement: CreditStatement) : F07Command
    data class LinkStatementPayment(override val requestId: String, val statementId: String, val operationId: String) : F07Command
    data class LinkCreditEvidence(override val requestId: String, val evidence: CreditEvidence) : F07Command
}

internal const val DEBT_RATE_ERROR = "Enter an annual rate between 0 and 1000 percent with up to 12 decimal places."
internal const val DEBT_DATE_ERROR = "Maturity date must be on or after the first installment."
internal const val DEBT_TERM_LIMIT = "The term must fit within 600 installments and 100 years. Check the payment or maturity date."
internal const val DEBT_PAYMENT_INTEREST = "The installment must exceed the first month's interest. Check the annual rate, principal and installment."

internal fun normalizedDebtRate(value: String): BigDecimal? =
    value.trim().replace(',', '.').toBigDecimalOrNull()?.takeIf {
        it.signum() >= 0 && it <= BigDecimal("1000") && it.scale() in -3..12
    }

internal fun debtInstallmentLimit(intervalMonths: Int): Int = minOf(600, 1199 / intervalMonths + 1)

internal fun installmentsThrough(first: LocalDate, maturity: LocalDate, intervalMonths: Int): Long {
    val months = ChronoUnit.MONTHS.between(YearMonth.from(first), YearMonth.from(maturity))
    val index = months / intervalMonths
    return index + if (first.plusMonths(index * intervalMonths) <= maturity) 1 else 0
}

internal fun debtMonthInterest(principal: Long, annualRatePercent: BigDecimal): BigDecimal =
    BigDecimal.valueOf(principal).multiply(annualRatePercent).divide(BigDecimal("1200"), 0, RoundingMode.HALF_UP)

internal data class DebtPayoff(val installments: Int, val finalPaymentMinor: Long)

internal fun debtPayoff(principal: Long, payment: Long, rate: BigDecimal?, limit: Int): DebtPayoff? {
    var balance = principal
    val monthlyRate = rate ?: BigDecimal.ZERO
    for (installment in 1..limit) {
        val interest = debtMonthInterest(balance, monthlyRate)
        val reduction = BigDecimal.valueOf(payment).subtract(interest)
        if (reduction.signum() <= 0) return null
        if (reduction >= BigDecimal.valueOf(balance)) {
            return DebtPayoff(installment, BigDecimal.valueOf(balance).add(interest).longValueExact())
        }
        balance -= reduction.longValueExact()
    }
    return null
}

internal fun debtPrincipalFromPayments(
    payment: Long, finalPayment: Long, installments: Int, annualRate: BigDecimal?,
): Long {
    val rate = (annualRate ?: BigDecimal.ZERO).divide(BigDecimal("1200"), 24, RoundingMode.HALF_UP)
    val discount = BigDecimal.ONE.add(rate)
    var principal = BigDecimal.valueOf(finalPayment).divide(discount, 24, RoundingMode.HALF_UP)
    repeat(installments - 1) {
        principal = principal.add(BigDecimal.valueOf(payment)).divide(discount, 24, RoundingMode.HALF_UP)
    }
    return principal.setScale(0, RoundingMode.HALF_UP).longValueExact()
}

/** All inputs are native-currency minor units for the same planning month. */
object CommitmentMath {
    fun pendingSaving(monthlyMinor: Long, netContributionsMinor: Long): Long =
        Math.subtractExact(monthlyMinor, netContributionsMinor.coerceAtLeast(0)).coerceAtLeast(0)

    fun attainableSaving(pendingMinor: Long, marginBeforeSavingsMinor: Long): Long =
        minOf(pendingMinor, marginBeforeSavingsMinor.coerceAtLeast(0))

    fun remainingMargin(marginBeforeSavingsMinor: Long, pendingSavingMinor: Long): Long =
        Math.subtractExact(marginBeforeSavingsMinor, pendingSavingMinor)
}



