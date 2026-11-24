package io.github.kannoncoding.wealthward.data

import androidx.room.withTransaction
import io.github.kannoncoding.wealthward.domain.*
import java.math.BigInteger
import java.time.LocalDate
import java.time.YearMonth
import org.json.JSONArray
import org.json.JSONObject

/** Reads a consistent source snapshot without opening periods or writing simulated data. */
class F08Repository(private val db: WealthDatabase) {
    private val dao = db.ledger()
    private val ledger = F05Repository(db)

    suspend fun snapshot(
        month: String,
        cutoff: LocalDate,
        base: Currency,
        rates: List<ProjectionRate> = emptyList(),
        buffer: Money? = null,
        greenMargin: Money? = null,
    ): ProjectionInput = db.withTransaction {
        val period = YearMonth.parse(month)
        require(cutoff <= period.atEndOfMonth())
        require(cutoff <= LocalDate.now(BusinessTime.zone))
        require(buffer == null || buffer.minor >= 0)
        require(greenMargin == null || greenMargin.minor > 0)
        val state = JSONObject(dao.workspaceState()?.payload ?: F05Repository.EMPTY_STATE)
        val accountMeta = state.f07Rows("accounts").associateBy { it.getString("id") }
        val allAccounts = dao.accounts().associateBy { it.id }
        val accounts = allAccounts.values.filter { accountMeta[it.id]?.f07Text("openingDate")?.let(LocalDate::parse)?.let { date -> date <= cutoff } != false }
        val cashIds = accounts.filter { it.kind == "CASH" }.map { it.id }.toSet()
        val tx = dao.transactions().filter { it.confirmed && LocalDate.parse(it.businessDate) <= cutoff }.associateBy { it.id }
        val postings = dao.postings().filter { it.transactionId in tx }
        val postingByAccount = postings.groupBy { it.accountId }
        val allOps = state.f07Rows("operations").map(ledger::operation).associateBy { it.id }
        val ops = allOps.values.filter { it.id in tx }.associateBy { it.id }
        val reversed = ops.values.filter { it.type == "REVERSAL" }.mapNotNull { it.originalId }.toSet()
        val activeOps = ops.values.filter { it.type != "REVERSAL" && it.id in tx && it.id !in reversed &&
            !(it.id.endsWith(":fee") && it.originalId in reversed) }
        val activeIds = activeOps.map { it.id }.toSet()
        val refunds = activeOps.filter { it.type == "REFUND" }.groupBy { it.originalId }
        val allocations = activeOps.flatMap { it.creditAllocations }.groupBy { it.purchaseId }
        val replacements = ops.values.filter { it.type in setOf("EXPENSE", "CREDIT_PURCHASE", "INCOME") }.groupBy { it.originalId }
        fun completed(ids: List<String>): List<OperationDetail> {
            val seen = mutableSetOf<String>()
            val stack = ArrayDeque(ids)
            val result = mutableListOf<OperationDetail>()
            while (stack.isNotEmpty()) {
                val id = stack.removeLast()
                if (!seen.add(id)) continue
                if (id in activeIds) ops[id]?.let(result::add)
                replacements[id].orEmpty().forEach { stack.add(it.id) }
            }
            return result
        }
        fun currency(accountId: String) = Currency.valueOf(allAccounts.getValue(accountId).currency)
        val issues = mutableListOf<String>()
        val cashIssues = mutableListOf<String>()
        val lines = mutableListOf<ProjectionLine>()
        val events = mutableListOf<ProjectionCashEvent>()
        val groups = mutableListOf<BudgetOccurrence>()
        val linkedIds = mutableSetOf<String>()
        val planState = state.optJSONObject("f06") ?: F06Repository.empty()
        val allPlans = planState.f07Rows("plans").map(::plan)
        val plans = allPlans.filter { it.month == month && LocalDate.parse(it.effectiveDate) <= cutoff }
        val plan = plans.maxByOrNull { it.version }
        val initial = plans.minByOrNull { it.version }
        if (plan == null) issues += "Falta un plan vigente para el periodo"
        val historical = cutoff < LocalDate.now(BusinessTime.zone)
        val reserveRows = state.f07Rows("reserves")
        val usableReserves = if (historical && reserveRows.isNotEmpty()) {
            issues += "Las reservas históricas de este corte no se pueden reconstruir con el historial disponible"
            cashIssues += issues.last()
            emptyList()
        } else reserveRows
        val currentCommitments = state.commitments()
        val cutCommitments = JSONObject().apply {
            put("payloadVersion", 1)
            commitmentTables.forEach { table ->
                put(table, JSONArray(currentCommitments.f07Rows(table).filter { row ->
                    val day = row.f07Text("date") ?: (if (table == "debts") row.f07Text("openingDate") else null)
                    day == null || LocalDate.parse(day) <= cutoff
                }))
            }
        }
        val atCut = JSONObject().put("f07", cutCommitments)
        val rawOccurrences = planState.f07Rows("occurrences").filter { !it.optBoolean("suppressed") }
        if (historical && rawOccurrences.any {
            !it.isNull("confirmedMinor") || it.getString("financingMonth") != it.getString("nominalMonth")
        }) issues += "No consta la fecha de vigencia de algunas confirmaciones o cambios de financiación"
        rawOccurrences.forEach { row ->
            val ids = row.strings("paymentIds")
            val linked = completed(ids)
            linkedIds.addAll(linked.map { it.id })
            val expected = if (historical) row.getLong("expectedMinor") else row.f07OptionalLong("confirmedMinor") ?: row.getLong("expectedMinor")
            val priorAmount = previouslyDeductedPayments(row)
                .filter { LocalDate.parse(it.getString("date")) <= cutoff }.sumMinor { it.getLong("minor") }
            val covered = linked.fold(priorAmount) { sum, op -> Math.addExact(sum, op.amountMinor - refunds[op.id].orEmpty().sumMinor { it.amountMinor }) }
            groups += BudgetOccurrence(row.getString("id"), row.getString("name"), row.getString("group"),
                PlanNature.valueOf(row.getString("nature")), Currency.valueOf(row.getString("currency")),
                LocalDate.parse(row.getString("dueDate")), expected, (expected - ownShareOfPayment(covered, expected, paymentTotal(row, occurrenceTemplate(planState, row), historical))).coerceAtLeast(0),
                row.f07Text("suggestedAccountId"), row.f07Text("purposeId"), linked.map { it.id }, row.getString("financingMonth"))
        }
        commitmentInstallments(atCut, month).forEach { entry ->
            groups += BudgetOccurrence(entry.id, entry.name, entry.group, entry.nature, entry.currency, LocalDate.parse(entry.dueDate),
                entry.expectedMinor, entry.pendingMinor, entry.suggestedAccountId, entry.purposeId, entry.paymentIds, entry.financingMonth)
        }
        val projectIds = currentCommitments.f07Rows("projectPayments").map { it.getString("operationId") }
        val externalSavingIds = cutCommitments.f07Rows("savingEvents").filter { it.getString("action") == SavingAction.EXTERNAL.name }
            .mapNotNull { it.f07Text("operationId") }
        linkedIds.addAll(completed(projectIds + externalSavingIds).map { it.id })
        val fundedPeriod = groups.filter { it.month == month }
        groups.filter { it.month < month && it.pending > 0 && it.nature != PlanNature.INCOME && it.date <= period.atEndOfMonth() }
            .forEach {
                issues += "Pendiente de un periodo anterior sin reasignar: ${it.title}"
                cashIssues += issues.last()
            }
        val obligationReserves = mutableMapOf<String, Long>()
        val reserveByPurpose = usableReserves.groupBy { it.getString("accountId") to it.getString("purposeId") }
        val remainingReserves = reserveByPurpose.mapValues { (_, rows) -> rows.sumMinor { it.getLong("minor") } }.toMutableMap()
        fun addObligation(id: String, title: String, money: Money, accountId: String?, day: LocalDate?, purpose: String?, links: List<ProjectionLink>) {
            lines += ProjectionLine(id, title, ProjectionComponent.OBLIGATION, money, accountId, day, links)

            var remaining = money.minor
            var reserveUsed = 0L
            if (purpose != null) {
                val candidates = remainingReserves.keys.filter { key -> key.second == purpose && currency(key.first) == money.currency }
                    .sortedBy { if (it.first == accountId) 0 else 1 }
                candidates.forEach { key ->
                    val reserve = minOf(remaining, remainingReserves.getValue(key))
                    remainingReserves[key] = remainingReserves.getValue(key) - reserve
                    obligationReserves.merge(key.first, reserve, Math::addExact)
                    remaining -= reserve
                    if (key.first == accountId) reserveUsed = reserve
                }
            }
            if (money.minor > 0) events += ProjectionCashEvent(id, title, accountId, day, money.copy(minor = -money.minor), links = links, reserveUsedMinor = reserveUsed)
        }
        fundedPeriod.filter { it.pending > 0 }.sortedBy { it.date }.forEach { item ->
            val links = listOf(ProjectionLink(ProjectionSource.OCCURRENCE, item.id)) + item.operationIds.map { ProjectionLink(ProjectionSource.MOVEMENT, it) }
            val amount = Money(item.pending, item.currency)
            if (item.nature == PlanNature.INCOME) {
                val overdue = item.date < cutoff
                lines += ProjectionLine(item.id, item.title, ProjectionComponent.INCOME, amount.takeUnless { overdue }, item.accountId, item.date, links)
                if (overdue) {
                    issues += "Ingreso vencido sin cobro confirmado: ${item.title}"
                    cashIssues += issues.last()
                } else events += ProjectionCashEvent(item.id, item.title, item.accountId, item.date, amount, true, links)
            } else {
                // A card can charge the bill, but it cannot fund a cash outflow.
                val cashAccount = item.accountId?.takeIf { id -> accounts.any { it.id == id && it.kind == "CASH" } }
                addObligation(item.id, item.title, amount, cashAccount, item.date, item.purposeId, links)
            }
        }
        val credit = cutCommitments.f07Rows("statements").map { it.creditStatement() }
            .filter { LocalDate.parse(it.cutoffDate) <= cutoff }.groupBy { it.accountId }
        val creditLinks = currentCommitments.f07Rows("statementPayments").groupBy { it.getString("statementId") }
        val coveredPurchases = mutableSetOf<String>()
        val purchases = activeOps.filter { it.type == "CREDIT_PURCHASE" && !it.previouslyDeducted }.groupBy { it.accountId }
        fun creditNet(id: String, original: Long) = (original - allocations[id].orEmpty().sumMinor { it.minor } -
            refunds[id].orEmpty().sumMinor { it.amountMinor }).coerceAtLeast(0)
        credit.forEach { (accountId, statements) ->
            val statement = statements.maxBy { it.cutoffDate }
            val due = LocalDate.parse(statement.dueDate)
            val paymentIds = statement.paymentIds + creditLinks[statement.id].orEmpty().map { it.getString("operationId") }
            val payments = completed(paymentIds).filter { it.destinationId == accountId }
            val pending = (statement.statementMinor - payments.sumMinor { it.destinationMinor ?: 0L }).coerceAtLeast(0)
            val statedIds = statement.purchaseIds.toSet()
            val openingId = "opening:$accountId"
            coveredPurchases.addAll(statedIds)
            val classifiedPrincipal = statedIds.sumMinor { id -> ops[id]?.takeUnless { it.previouslyDeducted }?.let { creditNet(id, it.amountMinor) } ?: 0 }
            val opening = allAccounts.getValue(accountId).openingMinor
            val openingNet = creditNet(openingId, if (opening < 0) Math.negateExact(opening) else 0)
            val residual = (pending - classifiedPrincipal).coerceAtLeast(0)
            val ambiguousPurchases = purchases[accountId].orEmpty().filter { purchase ->
                purchase.id !in statedIds && tx.getValue(purchase.id).businessDate <= statement.cutoffDate &&
                    creditNet(purchase.id, purchase.amountMinor) > 0
            }
            if (residual > 0 && ambiguousPurchases.isNotEmpty()) {
                issues += "Faltan vínculos de compras al estado de tarjeta"
                coveredPurchases.addAll(ambiguousPurchases.map { it.id })
            }
            if (openingNet <= residual && ambiguousPurchases.isEmpty()) coveredPurchases += openingId
            if (residual in 1 until openingNet || pending < classifiedPrincipal) {
                issues += "El estado de tarjeta no concuerda con el principal vinculado"
            }
            if (pending > 0 && due <= period.atEndOfMonth()) {
                val sources = statedIds.mapNotNull { ops[it]?.fundingAccountId }.distinct()
                val source = sources.singleOrNull()?.takeIf { it in cashIds && currency(it) == currency(accountId) }
                addObligation("statement:${statement.id}", "Estado de tarjeta", Money(pending, currency(accountId)),
                    source, due, null, listOf(ProjectionLink(ProjectionSource.CREDIT, statement.id)) +
                        statedIds.map { ProjectionLink(ProjectionSource.MOVEMENT, it) })
            }
        }
        purchases.values.flatten().filter { it.id !in coveredPurchases }.forEach { op ->
            val pending = creditNet(op.id, op.amountMinor)
            if (pending > 0) addObligation("credit:${op.id}", op.merchant.ifBlank { "Compra con tarjeta" },
                Money(pending, currency(op.accountId)), op.fundingAccountId, null, op.fundingPurposeId,
                listOf(ProjectionLink(ProjectionSource.MOVEMENT, op.id)))
        }
        accounts.filter { it.kind == "LIABILITY" && it.openingMinor < 0 && "opening:${it.id}" !in coveredPurchases }.forEach { account ->
            val pending = creditNet("opening:${account.id}", Math.negateExact(account.openingMinor))
            if (pending > 0) issues += "Deuda inicial sin fecha o periodo de financiación: ${account.name}"
        }

        val holds = state.f07Rows("holds").filter { row ->
            LocalDate.parse(row.getString("date")) <= cutoff && (row.getString("status") == "PENDING" ||
                row.f07Text("resolvedDate")?.let(LocalDate::parse)?.let { it > cutoff } == true)
        }
        if (historical && state.f07Rows("holds").any { it.getString("status") != "PENDING" && it.isNull("resolvedDate") }) {
            cashIssues += "Falta la fecha de resolución de una retención histórica"
        }
        val holdByAccount = holds.groupBy { it.getString("accountId") }
        val protectedByAccount = remainingReserves.entries.groupBy { it.key.first }.mapValues { (_, values) -> values.sumMinor { it.value } }
        val projectedAccounts = accounts.filter { it.id in cashIds }.map { account ->
            val accountCurrency = currency(account.id)
            val balance = Money(sumPostings(account.openingMinor, postingByAccount[account.id].orEmpty()), accountCurrency)
            lines += ProjectionLine(account.id, account.name, ProjectionComponent.LIQUID, balance, account.id, links = listOf(ProjectionLink(ProjectionSource.ACCOUNT, account.id)))
            val protected = protectedByAccount[account.id] ?: 0L
            remainingReserves.filterKeys { it.first == account.id }.filterValues { it > 0 }.forEach { (key, amount) ->
                lines += ProjectionLine("reserve:${account.id}:${key.second}", reserveByPurpose.getValue(key).first().getString("name"),
                    ProjectionComponent.PROTECTED, Money(amount, accountCurrency), account.id, links = listOf(ProjectionLink(ProjectionSource.ACCOUNT, account.id)))
            }
            ProjectionAccount(account.id, account.name, balance, holdByAccount[account.id].orEmpty().sumMinor { it.getLong("minor") },
                protected, obligationReserves[account.id] ?: 0L)
        }
        val unallocatedCreditHolds = holds.filter { it.getString("accountId") !in cashIds }
        if (unallocatedCreditHolds.isNotEmpty()) {
            issues += "Retenciones de tarjeta sin cobertura de presupuesto confirmada"
            cashIssues += issues.last()
        }

        val variableKinds = setOf(PlanNature.VARIABLE_NEED, PlanNature.FREE)
        val consumptionByMonth = mutableMapOf<Triple<String, Currency, String>, Long>()
        val consumptionLinks = mutableMapOf<Pair<Currency, String>, MutableSet<String>>()
        activeOps.filter { it.type in setOf("EXPENSE", "CREDIT_PURCHASE", "REFUND") }.forEach { operation ->
            val original = if (operation.type == "REFUND") allOps[operation.originalId] ?: return@forEach else operation
            if (original.id in linkedIds) return@forEach
            val currency = currency(original.accountId)
            val periodKey = tx.getValue(operation.id).businessDate.take(7)
            if (original.amountMinor <= 0) return@forEach
            val splits = original.splits.ifEmpty { listOf(ExpenseSplit(original.category, original.amountMinor)) }
            var consumed = BigInteger.ZERO
            var prior = 0L
            splits.forEach { split ->
                consumed = consumed.add(BigInteger.valueOf(split.minor))
                val amount = consumed.multiply(BigInteger.valueOf(operation.expenseMinor)).divide(BigInteger.valueOf(original.amountMinor)).longValueExact()
                consumptionByMonth.merge(Triple(periodKey, currency, split.category), amount - prior, Math::addExact)
                if (periodKey == month) consumptionLinks.getOrPut(currency to split.category) { mutableSetOf() } += operation.id
                prior = amount
            }
        }
        val activeRows = plan?.rows.orEmpty()
        val variableRows = activeRows.filter { it.nature in variableKinds }.groupBy { it.currency to it.name }
        val effectivePlans = allPlans.filter { LocalDate.parse(it.effectiveDate) <= cutoff }.groupBy { it.month }
            .mapValues { (_, versions) -> versions.maxBy { it.version } }
        variableRows.forEach { (key, rows) ->
            val (currency, category) = key
            val matchesCurrent = budgetCategoryMatcher(rows, activeRows, currency)
            fun consumption(inMonth: String, matches: (String) -> Boolean): Long =
                consumptionByMonth.entries.filter { (spent, _) ->
                    spent.first == inMonth && spent.second == currency && matches(spent.third)
                }.sumMinor { it.value }
            var cursor = period.minusMonths(1)
            val history = mutableListOf<Pair<Long, Long>>()
            while (rows.any { it.accumulate }) {
                val priorMonth = cursor.toString()
                val priorRows = effectivePlans[priorMonth]?.rows.orEmpty()
                val capped = priorRows.filter {
                    matchesCurrent(it.name) && it.currency == currency && it.nature in variableKinds && it.accumulate
                }
                if (capped.isEmpty()) break
                history += capped.sumMinor { it.minor } to consumption(priorMonth, budgetCategoryMatcher(capped, priorRows, currency))
                cursor = cursor.minusMonths(1)
            }
            val carried = history.asReversed().fold(0L) { carry, (budget, used) -> (Math.addExact(carry, budget) - used).coerceAtLeast(0) }
            val limit = Math.addExact(rows.sumMinor { it.minor }, carried)
            val consumed = consumption(month, matchesCurrent)
            val linkedOperations = consumptionLinks.entries.filter { (spent, _) ->
                spent.first == currency && matchesCurrent(spent.second)
            }.flatMap { it.value }.distinct()
            lines += ProjectionLine("variable:${rows.first().id}", category, ProjectionComponent.VARIABLE, Money((limit - consumed).coerceAtLeast(0), currency),
                links = rows.map { ProjectionLink(ProjectionSource.PLAN, it.id) } + linkedOperations.map { ProjectionLink(ProjectionSource.MOVEMENT, it) })
        }

        val goals = cutCommitments.f07Rows("goals").map { it.savingGoal() }
        val savingRows = plan?.rows.orEmpty().filter { it.nature == PlanNature.SAVING }
        val plannedSaving = mutableListOf<Money>()
        val funded = goals.map { goal ->
            val contribution = netSavingContributions(cutCommitments, goal.id, month)
            val goalPlan = savingRows.filter { it.id == goal.id || (it.name == goal.name && it.currency == goal.currency) }
            val planned = if (goalPlan.isEmpty()) goal.monthlyMinor else goalPlan.sumMinor { it.minor }
            plannedSaving += Money(planned, goal.currency)
            lines += ProjectionLine("goal:${goal.id}", goal.name, ProjectionComponent.SAVING, Money((planned - contribution).coerceAtLeast(0), goal.currency),
                links = listOf(ProjectionLink(ProjectionSource.GOAL, goal.id)))
            Money(contribution, goal.currency)
        }
        savingRows.filter { row -> goals.none { it.id == row.id || (it.name == row.name && it.currency == row.currency) } }.forEach { row ->
            plannedSaving += Money(row.minor, row.currency)
            lines += ProjectionLine("plan-saving:${row.id}", row.name, ProjectionComponent.SAVING, Money(row.minor, row.currency),
                links = listOf(ProjectionLink(ProjectionSource.PLAN, row.id)))
        }
        val dueKinds = setOf(PlanNature.INCOME, PlanNature.FIXED, PlanNature.SUBSCRIPTION, PlanNature.DEBT)
        plan?.rows.orEmpty().filter { it.nature in dueKinds }.groupBy { Triple(it.group, it.nature, it.currency) }.forEach { (key, rows) ->
            val scheduled = fundedPeriod.filter { Triple(it.group, it.nature, it.currency) == key }.sumMinor { it.expected }
            val undated = (rows.sumMinor { it.minor } - scheduled).coerceAtLeast(0)
            if (undated > 0) {
                val component = if (key.second == PlanNature.INCOME) ProjectionComponent.INCOME else ProjectionComponent.OBLIGATION
                val title = "${key.first} (sin calendario confirmado)"
                lines += ProjectionLine("plan-gap:${rows.first().id}", title, component,
                    if (component == ProjectionComponent.INCOME) null else Money(undated, key.third),
                    links = rows.map { ProjectionLink(ProjectionSource.PLAN, it.id) })
                issues += "Faltan partidas fechadas para ${key.first}"
                cashIssues += issues.last()
            }
        }

        val opening = accounts.filter { it.id in cashIds && accountMeta[it.id]?.f07Text("openingDate")?.let(LocalDate::parse)?.let { day -> day <= period.atDay(1) } != false }
            .map { account ->
                val before = postingByAccount[account.id].orEmpty().filter { LocalDate.parse(tx.getValue(it.transactionId).businessDate) < period.atDay(1) }
                Money(sumPostings(account.openingMinor, before), currency(account.id))
            }
        val comparison = compareLedger(month, tx, postings, cashIds, ops, opening, initial, funded, plannedSaving)
        if (buffer != null) {
            lines += ProjectionLine("additional-buffer", "Colchón adicional", ProjectionComponent.BUFFER, buffer)
        }
        ProjectionInput(period, cutoff, base, projectedAccounts, lines, events, rates,
            greenMargin = greenMargin ?: ProjectionInput.DEFAULT_GREEN_MARGIN,
            planVersion = plan?.version, target = plan?.let { Money(it.targetMinor, it.targetCurrency) }, comparison = comparison,
            issues = issues.distinct(), cashIssues = cashIssues.distinct())
    }
}

private data class BudgetOccurrence(
    val id: String, val title: String, val group: String, val nature: PlanNature, val currency: Currency,
    val date: LocalDate, val expected: Long, val pending: Long, val accountId: String?, val purposeId: String?,
    val operationIds: List<String>, val month: String,
)

private fun JSONObject.strings(key: String): List<String> = getJSONArray(key).let { List(it.length(), it::getString) }
private inline fun <T> Iterable<T>.sumMinor(amount: (T) -> Long): Long =
    fold(BigInteger.ZERO) { sum, row -> sum.add(BigInteger.valueOf(amount(row))) }.longValueExact()
private fun sumPostings(opening: Long, postings: List<PostingEntity>): Long =
    postings.fold(BigInteger.valueOf(opening)) { sum, post -> sum.add(BigInteger.valueOf(post.minor)) }.longValueExact()

private fun compareLedger(
    month: String,
    transactions: Map<String, TransactionEntity>,
    postings: List<PostingEntity>,
    cashIds: Set<String>,
    operations: Map<String, OperationDetail>,
    opening: List<Money>,
    initial: PeriodPlan?,
    funded: List<Money>,
    plannedSaving: List<Money>,
): ProjectionComparison {
    val paid = mutableListOf<Money>()
    val received = mutableListOf<Money>()
    val adjustments = mutableListOf<Money>()
    val expense = mutableListOf<Money>()
    val principal = mutableListOf<Money>()
    val countedExpenses = mutableSetOf<String>()
    postings.forEach { posting ->
        val transaction = transactions.getValue(posting.transactionId)
        if (!transaction.businessDate.startsWith(month)) return@forEach
        val op = operations[transaction.id]
        val original = operations[op?.originalId]
        val isReversal = transaction.type == "REVERSAL"
        val type = if (isReversal) original?.type else transaction.type
        val currency = Currency.valueOf(posting.currency)
        val amount = Money(posting.minor, currency)
        if (transaction.expenseMinor != 0L && (op == null || posting.accountId == op.accountId) && countedExpenses.add(transaction.id)) {
            expense += amount.copy(minor = transaction.expenseMinor)
        }
        if (posting.accountId !in cashIds) return@forEach
        when (type) {
            "INCOME" -> received += amount
            "EXPENSE", "REFUND", "CREDIT_PAYMENT", "FX_CREDIT_PAYMENT", "DEBT_PAYMENT" -> {
                paid += amount.copy(minor = Math.negateExact(amount.minor))
                if (type == "DEBT_PAYMENT") principal += amount.copy(minor = Math.subtractExact(Math.negateExact(amount.minor), transaction.expenseMinor))
                if (type in setOf("CREDIT_PAYMENT", "FX_CREDIT_PAYMENT") && isReversal && original != null) {
                    // The compensating transaction can group the fee and principal in separate legs.
                }
            }
            "TRANSFER", "FX_TRANSFER" -> if (isReversal && original?.accountId == posting.accountId && original.feeMinor > 0 && amount.minor == original.feeMinor) {
                paid += amount.copy(minor = -original.feeMinor)
            }
            "ADJUSTMENT" -> adjustments += amount
        }
    }
    fun grouped(items: List<Money>) = items.groupBy { it.currency }.map { (currency, rows) -> Money(rows.sumMinor { it.minor }, currency) }
    return ProjectionComparison(grouped(opening),
        grouped(initial?.rows.orEmpty().filter { it.nature == PlanNature.INCOME }.map { Money(it.minor, it.currency) }),
        grouped(initial?.rows.orEmpty().filter { it.nature !in setOf(PlanNature.INCOME, PlanNature.TARGET) }.map { Money(it.minor, it.currency) }),
        grouped(received), paid = grouped(paid), adjustments = grouped(adjustments), economicExpense = grouped(expense),
        principalPaid = grouped(principal), funded = grouped(funded), plannedSaving = grouped(plannedSaving))
}
