package io.github.kannoncoding.wealthward.data

import io.github.kannoncoding.wealthward.domain.*
import java.time.LocalDate
import java.time.format.DateTimeParseException
import org.json.JSONArray
import org.json.JSONObject

internal fun debtAccount(id: String) = "term-debt:$id"
internal fun savingPurpose(id: String) = "saving-goal:$id"
internal fun f07Invalid(message: String): Nothing = throw DomainFailure.Invalid(message)

internal val commitmentTables = listOf("debts", "payments", "checks", "goals", "savingEvents", "projects", "items", "projectPayments", "statements", "statementPayments", "evidence")

internal fun JSONObject.commitments() = optJSONObject("f07") ?: JSONObject().apply {
    put("payloadVersion", 1)
    commitmentTables.forEach { put(it, JSONArray()) }
}
internal fun JSONObject.f07Rows(key: String): List<JSONObject> =
    getJSONArray(key).let { rows -> List(rows.length(), rows::getJSONObject) }

internal fun JSONObject.f07Row(key: String, id: String): JSONObject =
    f07Rows(key).firstOrNull { it.getString("id") == id } ?: f07Invalid("Unknown commitment reference")

internal fun JSONObject.f07Add(key: String, row: JSONObject) {
    if (row.getString("id").isBlank() || f07Rows(key).any { it.getString("id") == row.getString("id") }) {
        f07Invalid("Commitment identities must be unique")
    }
    getJSONArray(key).put(row)
}
internal fun JSONObject.f07Text(key: String) = if (isNull(key)) null else getString(key)
internal fun JSONObject.f07Long(key: String): Long = when (val value = get(key)) {
    is Int -> value.toLong()
    is Long -> value
    else -> f07Invalid("Amounts must be integer minor units")
}
internal fun JSONObject.f07OptionalLong(key: String) = if (isNull(key)) null else f07Long(key)
private fun JSONObject.int(key: String) = Math.toIntExact(f07Long(key))
private fun JSONObject.optionalInt(key: String) = f07OptionalLong(key)?.let(Math::toIntExact)
private fun JSONObject.currency() = Currency.valueOf(getString("currency"))
private fun JSONObject.strings(key: String) = getJSONArray(key).let { List(it.length(), it::getString) }

internal fun JSONObject.debt() = Debt(
    getString("id"), getString("name"), getString("group"), currency(), DebtKind.valueOf(getString("kind")),
    f07Long("openingMinor"), getString("openingDate"), getString("firstDueDate"), f07Long("installmentMinor"),
    int("remainingMonths"), f07Text("annualRatePercent"), f07OptionalLong("finalInstallmentMinor"),
    ExtraPaymentPolicy.valueOf(getString("extraPaymentPolicy")), f07Text("suggestedAccountId"),
    DebtAmortization.valueOf(getString("amortization")),
    if (has("intervalMonths")) int("intervalMonths") else 1,
    f07Text("maturityDate"), optBoolean("estimateTermFromBalance"),
    optBoolean("estimatedOpeningPrincipal"), f07OptionalLong("installmentChargesMinor"),
)
internal fun JSONObject.debtMaturityEdits(): List<JSONObject> =
    if (has("debtMaturityEdits")) f07Rows("debtMaturityEdits") else emptyList()

internal fun debtDate(value: String, error: String): LocalDate = try {
    LocalDate.parse(value)
} catch (_: DateTimeParseException) {
    f07Invalid(error)
}

internal fun Debt.withMaturity(date: String): Debt {
    val due = debtDate(firstDueDate, "Choose a valid first installment date.")
    val maturity = debtDate(date, "Choose a valid maturity date.")
    if (maturity < due) f07Invalid(DEBT_DATE_ERROR)
    val count = installmentsThrough(due, maturity, intervalMonths)
    if (count !in 1L..debtInstallmentLimit(intervalMonths).toLong()) f07Invalid(DEBT_TERM_LIMIT)
    return copy(maturityDate = date, remainingMonths = count.toInt(), estimateTermFromBalance = false)
}

internal fun JSONObject.loanEdits(): List<JSONObject> =
    if (has("loanEdits")) f07Rows("loanEdits") else emptyList()

internal fun JSONObject.debtRevisions(id: String): List<JSONObject> {
    val data = commitments()
    val revisions = (data.debtMaturityEdits().filter { it.getString("debtId") == id } +
        data.loanEdits().filter { it.getJSONObject("debt").getString("id") == id })
        .associateBy { it.getString("requestId") }
    if (revisions.isEmpty()) return emptyList()
    return f07Rows("audit").mapNotNull { revisions[it.getString("requestId")] }
}

internal fun Debt.applyRevision(row: JSONObject): Debt =
    if (row.has("debt")) row.getJSONObject("debt").debt() else withMaturity(row.getString("maturityDate"))

internal fun JSONObject.revisedDebt(debt: Debt): Debt = debtRevisions(debt.id).fold(debt, Debt::applyRevision)

internal fun JSONObject.debtPayment() = DebtPayment(
    getString("id"), getString("debtId"), getString("accountId"), getString("date"), f07Text("installmentMonth"),
    f07Long("principalMinor"), f07Long("interestMinor"), f07Long("chargesMinor"),
    f07OptionalLong("actualNextInstallmentMinor"), optionalInt("actualRemainingMonths"),
)
internal fun JSONObject.creditorBalance() = CreditorBalance(
    getString("id"), getString("debtId"), getString("date"), f07Long("principalMinor"), getString("reference"),
)
internal fun JSONObject.savingGoal() = SavingGoal(
    getString("id"), getString("name"), getString("group"), currency(), f07Long("targetMinor"),
    getString("targetDate"), int("priority"), f07Long("monthlyMinor"),
)
internal fun JSONObject.savingEvent() = SavingEvent(
    getString("id"), SavingAction.valueOf(getString("action")), getString("goalId"), getString("accountId"),
    getString("date"), f07Long("minor"), f07Text("destinationAccountId"), f07Text("destinationGoalId"), f07Text("recipient"),
)
internal fun JSONObject.project() = Project(
    getString("id"), getString("name"), getString("group"), currency(), f07Long("targetMinor"), f07Text("goalId"),
)
internal fun JSONObject.projectItem() = ProjectItem(getString("id"), getString("projectId"), getString("name"), f07Long("estimatedMinor"))
internal fun JSONObject.projectPayment() = ProjectPayment(getString("id"), getString("itemId"), getString("operationId"))
internal fun JSONObject.creditStatement() = CreditStatement(
    getString("id"), getString("accountId"), getString("cutoffDate"), getString("dueDate"), f07Long("statementMinor"),
    strings("purchaseIds"), strings("paymentIds"),
)
internal fun JSONObject.creditEvidence() = CreditEvidence(
    getString("id"), getString("accountId"), getString("operationId"),
    CreditEvidenceKind.valueOf(getString("kind")), getString("reference"),
)


