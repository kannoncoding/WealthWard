package io.github.kannoncoding.wealthward.data

import androidx.room.withTransaction
import io.github.kannoncoding.wealthward.domain.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/** Commitments and all their financial effects share one Room transaction. */
class F07Repository(private val db: WealthDatabase) {
    private val dao = db.ledger()
    private val ledger = F05Repository(db)
    val observe = dao.observeWorkspace().map { snapshot() }

    private suspend fun load() = JSONObject(dao.workspaceState()?.payload ?: F05Repository.EMPTY_STATE)

    internal suspend fun debts(): List<DebtStatus> = load().commitments().debtStatuses()

    suspend fun snapshot(month: String = YearMonth.now(BusinessTime.zone).toString()): F07Snapshot = db.withTransaction {
        val state = load()
        val data = state.commitments()
        val financial = ledger.snapshot()
        val schedule = commitmentInstallments(state, month)
        val goals = data.f07Rows("goals").map { it.savingGoal() }
        val events = data.f07Rows("savingEvents").map { it.savingEvent() }
        val plan = F06Repository(db).snapshot(month)
        val margin = Currency.entries.associateWith { currency ->
            val accounts = financial.accounts.filter { !it.archived && it.kind == "CASH" && it.currency == currency }
            val obligations = plan.occurrences.filter { it.currency == currency && it.financingMonth <= month && it.nature != PlanNature.INCOME }
            val currentPurposes = obligations.mapNotNull { it.purposeId }.toSet()
            val liquid = f07Sum(accounts.map { account ->
                Math.subtractExact(account.balanceMinor, f07Sum(account.reserves.filter { it.purposeId !in currentPurposes }.map { it.minor }))
            })
            val expectedIncome = f07Sum(plan.occurrences.filter {
                it.currency == currency && it.financingMonth == month && it.nature == PlanNature.INCOME && it.confirmedMinor != null
            }.map { it.pendingMinor })
            val variables = f07Sum(plan.comparisons.filter { it.currency == currency && it.nature == PlanNature.VARIABLE_NEED }
                .map { Math.subtractExact(it.budgetMinor, it.consumptionMinor).coerceAtLeast(0L) })
            Math.subtractExact(Math.subtractExact(Math.addExact(liquid, expectedIncome), f07Sum(obligations.map { it.pendingMinor })), variables)
        }.toMutableMap()
        val savings = goals.sortedWith(compareBy<SavingGoal> { it.priority }.thenBy { it.targetDate }.thenBy { it.id }).map { goal ->
            val related = events.filter { it.goalId == goal.id || it.destinationGoalId == goal.id }
            val monthly = netSavingContributions(data, goal.id, month)
            val accounts = financial.accounts.flatMap { it.reserves }.filter { it.purposeId == savingPurpose(goal.id) && it.minor > 0 }
            val funded = f07Sum(accounts.map { it.minor })
            val pending = minOf(CommitmentMath.pendingSaving(goal.monthlyMinor, monthly), (goal.targetMinor - funded).coerceAtLeast(0))
            val available = margin.getValue(goal.currency)
            val attainable = CommitmentMath.attainableSaving(pending, available)
            margin[goal.currency] = available - attainable
            SavingStatus(goal, accounts, funded, related, monthly, attainable)
        }
        val byOperation = financial.operations.associateBy { it.id }
        val items = data.f07Rows("items").map { it.projectItem() }
        val projectPayments = data.f07Rows("projectPayments").map { it.projectPayment() }
        val projects = data.f07Rows("projects").map { row ->
            val project = row.project()
            val projectItems = items.filter { it.projectId == project.id }.map { item ->
                val payments = projectPayments.filter { it.itemId == item.id }
                ProjectItemStatus(item, f07Sum(payments.map { netProjectPayment(it.operationId, byOperation) }), payments)
            }
            ProjectStatus(project, savings.find { it.goal.id == project.goalId }?.fundedMinor ?: 0, projectItems)
        }
        val statements = data.f07Rows("statements").map { row ->
            val original = row.creditStatement()
            val extraPayments = data.f07Rows("statementPayments").filter { it.getString("statementId") == original.id }
                .map { it.getString("operationId") }
            val statement = original.copy(paymentIds = original.paymentIds + extraPayments)
            val cashPaid = f07Sum(statement.paymentIds.map { id ->
                byOperation.getValue(id).let { if (it.voided) 0L else it.destinationMinor ?: 0L }
            })
            val holdIds = financial.holds.filter { it.accountId == statement.accountId && it.status == "PENDING" }.map { it.id }
            CreditStatementStatus(statement, financial.accounts.first { it.id == statement.accountId }.openingMinor,
                (statement.statementMinor - cashPaid).coerceAtLeast(0), holdIds)
        }
        F07Snapshot(data.debtStatuses(),
            savings, projects, statements, data.f07Rows("evidence").map { it.creditEvidence() }, schedule)
    }

    suspend fun execute(command: F07Command): String = db.withTransaction {
        if (command.requestId.isBlank()) f07Invalid("A stable request key is required")
        val fingerprint = canonicalPlanning(command).toString()
        dao.request(command.requestId)?.let {
            if (it.fingerprint != fingerprint) f07Invalid("Request key already used for different content")
            return@withTransaction it.result
        }
        var state = load()
        var data = state.commitments()
        val linkId = "f07-ledger:${command.requestId}"
        when (command) {
            is F07Command.CreateDebt -> {
                val debt = command.debt
                validateDebt(debt, dao.accounts())
                val accountId = debtAccount(debt.id)
                if (dao.accounts().any { it.id == accountId }) f07Invalid("A debt with this identity already exists")
                dao.insertAccount(AccountEntity(accountId, debt.name, debt.currency.name, "TERM_DEBT", -debt.openingMinor))
                data.f07Add("debts", row(debt, command.requestId))
            }
            is F07Command.PayDebt -> {
                val payment = command.payment
                val debt = data.f07Row("debts", payment.debtId).debt()
                val entries = data.debtEntries(debt.id)
                val day = LocalDate.parse(payment.date)
                val principal = entries.lastOrNull { !it.voided }?.resultingPrincipal ?: debt.openingMinor
                validateDebtPayment(payment, debt, principal, dao.accounts())
                validateDebtDate(debt, entries, day)
                // Validate the logical installment before posting. A verified last installment can differ from the estimate.
                payment.installmentMonth?.let { month ->
                    if (commitmentInstallments(state, month).none { it.templateId == debtAccount(debt.id) && it.nominalMonth == month }) {
                        f07Invalid("Choose a scheduled installment")
                    }
                }
                ledger.postDebtPayment(state, debt, payment, linkId)
                data.f07Add("payments", row(payment, command.requestId)
                    .put("sequence", entries.size + 1).put("operationId", linkId)
                    .put("resultingPrincipal", principal - payment.principalMinor).put("voided", false))
            }
            is F07Command.VoidDebtPayment -> {
                val payment = data.f07Row("payments", command.paymentId)
                val entries = data.debtEntries(payment.getString("debtId"))
                if (entries.lastOrNull { !it.voided }?.payment?.id != command.paymentId)
                    f07Invalid("Only the latest debt payment can be reversed")
                ledger.reverseDebtPayment(state, payment.getString("operationId"), linkId, command.reason)
                payment.put("voided", true).put("voidRequestId", command.requestId).put("voidReason", command.reason)
                    .put("voidAfterSequence", entries.size)
            }
            is F07Command.VerifyBalance -> {
                val check = command.balance
                val debt = data.f07Row("debts", check.debtId).debt()
                val entries = data.debtEntries(debt.id)
                validateDebtDate(debt, entries, LocalDate.parse(check.date))
                if (check.principalMinor < 0 || check.reference.isBlank()) f07Invalid("Enter the creditor balance and a verification reference")
                val principal = entries.lastOrNull { !it.voided }?.resultingPrincipal ?: debt.openingMinor
                val delta = principal - check.principalMinor
                ledger.postDebtVerification(state, debt, check, delta, linkId)
                data.f07Add("checks", row(check, command.requestId).put("sequence", entries.size + 1)
                    .put("operationId", if (delta != 0L) linkId else JSONObject.NULL).put("resultingPrincipal", check.principalMinor))
            }
            is F07Command.SaveGoal -> data.f07Add("goals", row(command.goal, command.requestId))
            is F07Command.MoveSaving -> {
                val event = command.event
                val goal = data.f07Row("goals", event.goalId).savingGoal()
                validateSavingEvent(event, data, dao.accounts())
                val account = ledger.snapshot().accounts.first { it.id == event.accountId }
                if (account.openingDate?.let { LocalDate.parse(event.date) < LocalDate.parse(it) } == true) f07Invalid("Event predates account opening")
                val purpose = savingPurpose(goal.id)
                val savedEvent = row(event, command.requestId).put("ledgerRequestId", linkId)
                    .put("operationId", if (event.action in setOf(SavingAction.TRANSFER, SavingAction.EXTERNAL)) linkId else JSONObject.NULL)
                SavingLots(data).describeMove(savedEvent, account.reserves.find { it.purposeId == purpose }?.minor ?: 0)
                ledger.executeCommitment(savingLedgerCommand(event, goal, data, linkId))
                state = load()
                data = state.commitments()
                data.f07Add("savingEvents", savedEvent)
            }
            is F07Command.SaveProject -> data.f07Add("projects", row(command.project, command.requestId))
            is F07Command.SaveProjectItem -> data.f07Add("items", row(command.item, command.requestId))
            is F07Command.LinkProjectPayment -> data.f07Add("projectPayments", row(command.payment, command.requestId))
            is F07Command.RecordProjectPayment -> {
                val item = data.f07Row("items", command.itemId).projectItem()
                val project = data.f07Row("projects", item.projectId).project()
                val account = ledger.snapshot().accounts.firstOrNull { it.id == command.accountId }
                if (account == null || account.kind != "CASH" || account.currency != project.currency) f07Invalid("Choose a cash account in the project currency")
                val purpose = project.goalId?.let(::savingPurpose)
                val funded = account.reserves.find { it.purposeId == purpose }?.minor ?: 0L
                val reservedPart = minOf(funded, command.minor)
                val splits = if (reservedPart > 0) listOf(ExpenseSplit(item.name, reservedPart, purpose)) +
                    listOfNotNull(ExpenseSplit(item.name, command.minor - reservedPart).takeIf { it.minor > 0 }) else emptyList()
                ledger.executeCommitment(F05Command.Record(linkId, account.id, "EXPENSE", command.minor,
                    command.date, project.name, item.name, splits = splits))
                state = load()
                data = state.commitments()
                data.f07Add("projectPayments", row(ProjectPayment(command.requestId, item.id, linkId), command.requestId))
            }
            is F07Command.SaveStatement -> data.f07Add("statements", row(command.statement, command.requestId))
            is F07Command.LinkStatementPayment -> data.f07Add("statementPayments",
                JSONObject().put("id", command.requestId).put("statementId", command.statementId).put("operationId", command.operationId))
            is F07Command.LinkCreditEvidence -> data.f07Add("evidence", row(command.evidence, command.requestId))
        }
        state.put("f07", data)
        state.getJSONArray("audit").put(JSONObject().put("requestId", command.requestId)
            .put("action", command.javaClass.simpleName).put("subjectId", command.requestId)
            .put("reason", when (command) {
                is F07Command.VerifyBalance -> command.balance.reference
                is F07Command.VoidDebtPayment -> command.reason
                else -> ""
            }))

        val request = WorkspaceRequestEntity(command.requestId, fingerprint, command.requestId)
        validateCommitmentRestore(state, dao.requests() + request, dao.accounts(), dao.transactions(), dao.postings())
        dao.saveWorkspace(WorkspaceStateEntity("main", state.toString()))
        dao.insertRequest(request)
        // Materialize the read model before commit so an overflow rolls back the whole command.
        snapshot()
        command.requestId
    }

    private fun row(value: Any, request: String) = (canonicalPlanning(value) as JSONObject).put("requestId", request)
}

private fun JSONObject.debtStatuses(): List<DebtStatus> = f07Rows("debts").map { row ->
    val debt = row.debt()
    debtStatus(debt, debtEntries(debt.id))
}

private fun netProjectPayment(id: String, operations: Map<String, OperationDetail>): Long {
    val entry = operations.getValue(id)
    if (!entry.voided) return entry.amountMinor - entry.refundedMinor
    return f07Sum(operations.values.filter { it.originalId == id && it.type in setOf("EXPENSE", "CREDIT_PURCHASE") }
        .map { netProjectPayment(it.id, operations) })
}

internal fun validateDebt(debt: Debt, accounts: List<AccountEntity>) {
    val rate = debt.annualRatePercent?.let {
        it.toBigDecimalOrNull() ?: f07Invalid("Invalid annual interest rate")
    }
    val open = LocalDate.parse(debt.openingDate)
    val due = LocalDate.parse(debt.firstDueDate)
    if (debt.id.isBlank() || debt.name.isBlank() || debt.group.isBlank() || debt.openingMinor <= 0 ||
        debt.installmentMinor <= 0 || debt.remainingMonths !in 1..600 || debt.finalInstallmentMinor?.let { it <= 0 } == true ||
        (rate == null && debt.amortization == DebtAmortization.FIXED_MONTHLY) ||
        (rate != null && (rate.signum() < 0 || rate > BigDecimal("1000") || rate.scale() !in -3..12)) || due < open)
        f07Invalid("Enter valid term debt details")
    debt.suggestedAccountId?.let { validateCommitmentAccount(it, debt.currency, "CASH", accounts) }
}

internal fun validateDebtPayment(payment: DebtPayment, debt: Debt, remaining: Long, accounts: List<AccountEntity>) {
    validateCommitmentAccount(payment.accountId, debt.currency, "CASH", accounts)
    val nominal = payment.installmentMonth?.let(YearMonth::parse)
    val first = YearMonth.from(LocalDate.parse(debt.firstDueDate))
    if (payment.principalMinor !in 0..remaining || payment.interestMinor < 0 || payment.chargesMinor < 0 ||
        payment.totalMinor <= 0 || remaining == 0L || (payment.extra && payment.principalMinor <= 0) ||
        payment.actualNextInstallmentMinor?.let { !payment.extra || it <= 0 } == true ||
        payment.actualRemainingMonths?.let { !payment.extra || it !in 1..600 } == true ||
        (nominal != null && ChronoUnit.MONTHS.between(first, nominal) !in 0..1199)) {
        f07Invalid("Enter valid principal, interest and charges for an open term debt")
    }
}

internal fun validateDebtDate(debt: Debt, entries: List<DebtEntry>, date: LocalDate) {
    if (date < LocalDate.parse(debt.openingDate) || entries.lastOrNull()?.date?.let { date < it } == true)
        f07Invalid("Enter debt events in date order, starting at opening")
}

internal fun validateCommitmentAccount(id: String, currency: Currency, kind: String, accounts: List<AccountEntity>) {
    if (accounts.none { it.id == id && it.currency == currency.name && it.kind == kind })
        f07Invalid("Unknown or incompatible commitment account")
}

internal fun validateSavingEvent(event: SavingEvent, data: JSONObject, accounts: List<AccountEntity>) {
    val goal = data.f07Row("goals", event.goalId).savingGoal()
    validateCommitmentAccount(event.accountId, goal.currency, "CASH", accounts)
    LocalDate.parse(event.date)
    if (event.minor <= 0) f07Invalid("Saving amount must be positive")
    fun noDestination() = event.destinationAccountId == null && event.destinationGoalId == null
    val valid = when (event.action) {
        SavingAction.CONTRIBUTE, SavingAction.WITHDRAW -> noDestination() && event.recipient == null
        SavingAction.REALLOCATE -> event.destinationAccountId == null && event.recipient == null &&
            event.destinationGoalId?.let { it != goal.id && data.f07Row("goals", it).savingGoal().currency == goal.currency } == true
        SavingAction.TRANSFER -> event.destinationGoalId == null && event.recipient == null &&
            event.destinationAccountId?.let { id ->
                validateCommitmentAccount(id, goal.currency, "CASH", accounts)
                id != event.accountId
            } == true
        SavingAction.EXTERNAL -> noDestination() && !event.recipient.isNullOrBlank()
    }
    if (!valid) f07Invalid("Invalid savings destination")
}







