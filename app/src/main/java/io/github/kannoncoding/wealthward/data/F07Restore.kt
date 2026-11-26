package io.github.kannoncoding.wealthward.data

import io.github.kannoncoding.wealthward.domain.*
import java.time.LocalDate
import org.json.JSONObject

/** Validates the commitment journal against the restorable ledger and its idempotency records. */
internal fun validateCommitmentRestore(
    state: JSONObject,
    requests: List<WorkspaceRequestEntity>,
    accounts: List<AccountEntity>,
    transactions: List<TransactionEntity>,
    postings: List<PostingEntity>,
) {
    val data = state.commitments()
    fun invalid(): Nothing = f07Invalid("Invalid commitment fixture graph")
    if (state.has("f07") && data !== state.getJSONObject("f07")) invalid()
    if (data.f07Long("payloadVersion") != 1L) throw DomainFailure.UnsupportedVersion()
    val requestById = requests.associateBy { it.id }
    val usedRequests = mutableSetOf<String>()
    val usedOperations = mutableSetOf<String>()
    val operations = state.f07Rows("operations").associateBy { it.getString("id") }
    val tx = transactions.associateBy { it.id }
    val legs = postings.groupBy { it.transactionId }
    val audits = state.f07Rows("audit").groupBy { it.getString("requestId") }

    fun checkRequest(command: F07Command) {
        val request = requestById[command.requestId] ?: invalid()
        if (!usedRequests.add(command.requestId) || request.result != command.requestId ||
            request.fingerprint != canonicalPlanning(command).toString() ||
            audits[command.requestId]?.singleOrNull()?.getString("action") != command.javaClass.simpleName) invalid()
    }
    fun ledgerLink(command: F05Command) {
        val id = command.requestId
        val request = requestById[id] ?: invalid()
        if (!usedRequests.add(id) || request.result != id || request.fingerprint != canonicalLedgerCommand(command).toString() ||
            audits[id]?.singleOrNull()?.getString("action") != command.javaClass.simpleName) invalid()
    }
    fun checkOperation(id: String, type: String, date: String, account: String, minor: Long): JSONObject {
        val op = operations[id] ?: invalid()
        val transaction = tx[id] ?: invalid()
        if (!usedOperations.add(id) || !transaction.confirmed || op.getString("type") != type ||
            transaction.type != type || op.getString("date") != date || op.getString("accountId") != account ||
            op.f07Long("amountMinor") != minor) invalid()
        return op
    }
    fun checkLegs(id: String, expected: Map<String, Long>, expense: Long) {
        val actual = legs[id] ?: invalid()
        if (actual.size != expected.size || actual.associate { it.accountId to it.minor } != expected ||
            tx.getValue(id).expenseMinor != expense) invalid()
    }

    commitmentTables.forEach { table ->
        val ids = data.f07Rows(table).map { it.getString("id") }
        if (ids.any(String::isBlank) || ids.distinct().size != ids.size) invalid()
    }
    val auditOrder = state.f07Rows("audit").mapIndexed { index, row -> row.getString("requestId") to index }.toMap()
    fun order(row: JSONObject) = auditOrder[row.getString("requestId")] ?: invalid()
    val openings = data.f07Rows("debts")
    val debts = openings.map { raw ->
        raw.debt().also { checkRequest(F07Command.CreateDebt(raw.getString("requestId"), it)) }
    }
    val debtAccounts = debts.map { debtAccount(it.id) }.toSet()
    if (accounts.filter { it.kind == "TERM_DEBT" }.map { it.id }.toSet() != debtAccounts) invalid()
    val debtIds = debts.map { it.id }.toSet()
    val maturityEdits = data.debtMaturityEdits()
    val maturityAuditIds = state.f07Rows("audit")
        .filter { it.getString("action") == F07Command.UpdateDebtMaturity::class.java.simpleName }
        .map { it.getString("requestId") }
    if (maturityEdits.map { it.getString("requestId") } != maturityAuditIds) invalid()
    maturityEdits.forEach { row ->
        val command = F07Command.UpdateDebtMaturity(row.getString("requestId"), row.getString("debtId"), row.getString("maturityDate"))
        checkRequest(command)
        if (command.debtId !in debtIds) invalid()
    }
    val edits = data.loanEdits()
    val editAuditIds = state.f07Rows("audit")
        .filter { it.getString("action") == F07Command.EditLoan::class.java.simpleName }
        .map { it.getString("requestId") }
    if (edits.map { it.getString("requestId") } != editAuditIds) invalid()
    edits.forEach { row ->
        val command = F07Command.EditLoan(row.getString("requestId"), row.getJSONObject("debt").debt())
        checkRequest(command)
        if (command.debt.id !in debtIds ||
            audits[command.requestId]?.singleOrNull()?.getString("subjectId") != command.debt.id) invalid()
    }
    if ((data.f07Rows("checks") + data.f07Rows("payments")).any { it.getString("debtId") !in debtIds }) invalid()
    openings.forEach { opening ->
        val original = opening.debt()
        validateDebt(original, accounts)
        val accountId = debtAccount(original.id)
        val entries = data.debtEntries(original.id)
        val openingOrder = order(opening)
        var revision = original
        var debt = original
        val firstEntryOrder = entries.firstOrNull()?.let { order(it.raw) } ?: Int.MAX_VALUE
        val entryOrders = entries.map { order(it.raw) }
        if (entryOrders.zipWithNext().any { (left, right) -> left >= right } || firstEntryOrder <= openingOrder) invalid()
        val revisions = state.debtRevisions(original.id)
        revisions.forEach { row ->
            val revisionOrder = order(row)
            if (revisionOrder <= openingOrder) invalid()
            val updated = revision.applyRevision(row)
            if (row.has("debt")) {
                validateLoanEdit(revision, updated, entries.takeWhile { order(it.raw) < revisionOrder }, accounts)
            } else {
                validateDebt(updated, accounts)
            }
            revision = updated
            if (revisionOrder < firstEntryOrder) debt = updated
        }
        val liability = accounts.single { it.id == accountId }
        if (liability.openingMinor != -revision.openingMinor || liability.currency != revision.currency.name ||
            liability.creditLimitMinor != 0L || liability.name != revision.name) invalid()
        var principal = debt.openingMinor
        var previous = LocalDate.parse(debt.openingDate)
        val voids = entries.filter { it.voided }.groupBy { it.raw.f07Long("voidAfterSequence") }
        if (voids.keys.any { it !in 1..entries.size.toLong() }) invalid()
        val active = mutableListOf<DebtEntry>()
        val pendingRevisions = revisions.iterator()
        var nextRevision = if (pendingRevisions.hasNext()) pendingRevisions.next() else null
        var entryDebt = original
        entries.forEachIndexed { index, entry ->
            if (entry.sequence != index + 1L || entry.date < previous) invalid()
            previous = entry.date
            while (nextRevision?.let { order(it) < order(entry.raw) } == true) {
                entryDebt = entryDebt.applyRevision(checkNotNull(nextRevision))
                nextRevision = if (pendingRevisions.hasNext()) pendingRevisions.next() else null
            }
            val requestId = entry.raw.getString("requestId")
            val link = "f07-ledger:$requestId"
            val payment = entry.payment
            if (payment != null) {
                checkRequest(F07Command.PayDebt(requestId, payment))
                validateDebtPayment(payment, entryDebt, principal, accounts)
                if (entry.raw.get("voided") !is Boolean ||
                    entry.resultingPrincipal != principal - payment.principalMinor || entry.raw.f07Text("operationId") != link) invalid()
                val operation = checkOperation(link, "DEBT_PAYMENT", payment.date, payment.accountId, payment.totalMinor)
                if (operation.getBoolean("voided") != entry.voided || operation.f07Text("destinationId") != accountId ||
                    operation.f07Long("destinationMinor") != payment.principalMinor) invalid()
                val expected = mutableMapOf(payment.accountId to -payment.totalMinor)
                if (payment.principalMinor > 0) expected[accountId] = payment.principalMinor
                val expense = Math.addExact(payment.interestMinor, payment.chargesMinor)
                checkLegs(link, expected, expense)
                if (entry.voided) {
                    val voidId = entry.raw.getString("voidRequestId")
                    val reason = entry.raw.getString("voidReason")
                    if (reason.isBlank()) invalid()
                    checkRequest(F07Command.VoidDebtPayment(voidId, payment.id, reason))
                    val reversal = "f07-ledger:$voidId"
                    val reversed = checkOperation(reversal, "REVERSAL", payment.date, payment.accountId, payment.totalMinor)
                    if (reversed.f07Text("originalId") != link || reversed.getString("notes") != reason) invalid()
                    checkLegs(reversal, expected.mapValues { -it.value }, -expense)
                }
                principal = entry.resultingPrincipal
            } else {
                val check = entry.verification ?: invalid()
                checkRequest(F07Command.VerifyBalance(requestId, check))
                if (check.principalMinor < 0 || check.reference.isBlank() || entry.resultingPrincipal != check.principalMinor) invalid()
                val delta = principal - check.principalMinor
                if (delta == 0L) {
                    if (entry.raw.f07Text("operationId") != null) invalid()
                } else {
                    if (entry.raw.f07Text("operationId") != link) invalid()
                    val operation = checkOperation(link, "DEBT_ADJUSTMENT", check.date, accountId, delta)
                    if (operation.getBoolean("voided") || operation.getString("notes") != check.reference) invalid()
                    checkLegs(link, mapOf(accountId to delta), 0)
                }
                principal = check.principalMinor
            }
            active.add(entry)
            voids[entry.sequence].orEmpty().sortedByDescending { it.sequence }.forEach { reversed ->
                if (active.lastOrNull() != reversed) invalid()
                active.removeAt(active.lastIndex)
                principal = active.lastOrNull()?.resultingPrincipal ?: debt.openingMinor
            }
        }
    }
    if (transactions.any { it.type in setOf("DEBT_PAYMENT", "DEBT_ADJUSTMENT") && it.id !in usedOperations } ||
        postings.any { it.accountId in debtAccounts && it.transactionId !in usedOperations }) invalid()

    val goals = data.f07Rows("goals").map { row ->
        row.savingGoal().also { goal ->
            checkRequest(F07Command.SaveGoal(row.getString("requestId"), goal))
            LocalDate.parse(goal.targetDate)
            if (goal.name.isBlank() || goal.group.isBlank() || goal.targetMinor <= 0 || goal.priority < 1 || goal.monthlyMinor < 0) invalid()
        }
    }
    val purposes = goals.associateBy { savingPurpose(it.id) }
    state.f07Rows("reserves").filter { it.getString("purposeId").startsWith("saving-goal:") }.forEach {
        val goal = purposes[it.getString("purposeId")] ?: invalid()
        validateCommitmentAccount(it.getString("accountId"), goal.currency, "CASH", accounts)
    }
    data.f07Rows("savingEvents").forEach { row ->
        val event = row.savingEvent()
        val requestId = row.getString("requestId")
        val id = "f07-ledger:$requestId"
        checkRequest(F07Command.MoveSaving(requestId, event))
        validateSavingEvent(event, data, accounts)
        if (row.getString("ledgerRequestId") != id) invalid()
        ledgerLink(savingLedgerCommand(event, data.f07Row("goals", event.goalId).savingGoal(), data, id))
        when (event.action) {
            SavingAction.CONTRIBUTE, SavingAction.WITHDRAW, SavingAction.REALLOCATE -> Unit
            SavingAction.TRANSFER -> {
                val move = checkOperation(id, "TRANSFER", event.date, event.accountId, event.minor)
                if (move.getBoolean("voided") || move.f07Text("destinationId") != event.destinationAccountId ||
                    move.f07Long("destinationMinor") != event.minor ||
                    move.f07Rows("reserveEffects").associate {
                        it.getString("accountId") to (it.getString("purposeId") to it.f07Long("minor"))
                    } != mapOf(event.accountId to (savingPurpose(event.goalId) to event.minor),
                        event.destinationAccountId to (savingPurpose(event.goalId) to -event.minor))) invalid()
            }
            SavingAction.EXTERNAL -> {
                val move = checkOperation(id, "EXPENSE", event.date, event.accountId, event.minor)
                if (move.getBoolean("voided") || move.f07Long("refundedMinor") != 0L ||
                    move.getString("merchant") != event.recipient) invalid()
                checkLegs(id, mapOf(event.accountId to -event.minor), event.minor)
            }
        }
        val posts = event.action in setOf(SavingAction.TRANSFER, SavingAction.EXTERNAL)
        if (row.f07Text("operationId") != if (posts) id else null) invalid()
    }

    SavingLots(data)

    val projects = data.f07Rows("projects").map { row ->
        row.project().also { project ->
            checkRequest(F07Command.SaveProject(row.getString("requestId"), project))
            if (project.name.isBlank() || project.group.isBlank() || project.targetMinor <= 0 ||
                project.goalId?.let { id -> goals.none { it.id == id && it.currency == project.currency } } == true) invalid()
        }
    }.associateBy { it.id }
    if (projects.values.mapNotNull { it.goalId }.let { it.size != it.distinct().size }) invalid()
    val items = data.f07Rows("items").map { row ->
        row.projectItem().also {
            checkRequest(F07Command.SaveProjectItem(row.getString("requestId"), it))
            if (it.projectId !in projects || it.name.isBlank() || it.estimatedMinor < 0) invalid()
        }
    }.associateBy { it.id }
    val planLinks = state.optJSONObject("f06")?.f07Rows("occurrences").orEmpty()
        .flatMap { occurrence -> occurrence.getJSONArray("paymentIds").let { links -> List(links.length(), links::getString) } }.toSet()
    if (data.f07Rows("savingEvents").any { it.f07Text("operationId") in planLinks }) invalid()
    val projectLinks = mutableSetOf<String>()
    data.f07Rows("projectPayments").forEach { row ->
        val payment = row.projectPayment()
        val requestId = row.getString("requestId")
        val item = items[payment.itemId] ?: invalid()
        val project = projects.getValue(item.projectId)
        val op = operations[payment.operationId] ?: invalid()
        val type = op.getString("type")
        if (type !in setOf("EXPENSE", "CREDIT_PURCHASE") || payment.operationId in usedOperations || payment.operationId in planLinks ||
            op.f07Text("originalId") != null) invalid()
        validateCommitmentAccount(op.getString("accountId"), project.currency,
            if (type == "EXPENSE") "CASH" else "LIABILITY", accounts)
        if (!projectLinks.add(payment.operationId)) invalid()
        if (payment.operationId == "f07-ledger:$requestId") {
            checkRequest(F07Command.RecordProjectPayment(requestId, item.id, op.getString("accountId"),
                op.getString("date"), op.f07Long("amountMinor")))
            val splits = op.f07Rows("splits").map { ExpenseSplit(it.getString("category"), it.f07Long("minor"), it.f07Text("purposeId")) }
            ledgerLink(F05Command.Record(payment.operationId, op.getString("accountId"), "EXPENSE", op.f07Long("amountMinor"),
                op.getString("date"), project.name, item.name, splits = splits))
            if (type != "EXPENSE") invalid()
        } else checkRequest(F07Command.LinkProjectPayment(requestId, payment))
        val replacements = operations.values.filter { it.f07Text("originalId") == payment.operationId &&
            it.getString("type") in setOf("EXPENSE", "INCOME", "CREDIT_PURCHASE") }
        if (replacements.isNotEmpty()) invalid()
    }

    fun card(id: String): AccountEntity = accounts.find { it.id == id && it.kind == "LIABILITY" } ?: invalid()
    fun cardOperation(id: String, account: String, kind: CreditEvidenceKind): JSONObject {
        val op = operations[id] ?: invalid()
        val type = op.getString("type")
        if (kind == CreditEvidenceKind.PURCHASE && (type != "CREDIT_PURCHASE" || op.getString("accountId") != account)) invalid()
        if (kind == CreditEvidenceKind.PAYMENT && (type !in setOf("CREDIT_PAYMENT", "FX_CREDIT_PAYMENT") || op.f07Text("destinationId") != account)) invalid()
        return op
    }
    val statementKeys = mutableSetOf<Pair<String, String>>()
    val purchaseLinks = mutableSetOf<String>()
    val paymentLinks = mutableSetOf<String>()
    data.f07Rows("statements").forEach { row ->
        val statement = row.creditStatement()
        checkRequest(F07Command.SaveStatement(row.getString("requestId"), statement))
        val cutoff = LocalDate.parse(statement.cutoffDate)
        val due = LocalDate.parse(statement.dueDate)
        card(statement.accountId)
        if (due < cutoff || statement.statementMinor < 0 || !statementKeys.add(statement.accountId to statement.cutoffDate)) invalid()
        statement.purchaseIds.forEach {
            val op = cardOperation(it, statement.accountId, CreditEvidenceKind.PURCHASE)
            if (!purchaseLinks.add(it) || LocalDate.parse(op.getString("bookedDate")) > cutoff) invalid()
        }
        statement.paymentIds.forEach {
            val op = cardOperation(it, statement.accountId, CreditEvidenceKind.PAYMENT)
            if (!paymentLinks.add(it) || LocalDate.parse(op.getString("bookedDate")) < cutoff) invalid()
        }
    }
    data.f07Rows("statementPayments").forEach { row ->
        val statement = data.f07Row("statements", row.getString("statementId")).creditStatement()
        val id = row.getString("operationId")
        checkRequest(F07Command.LinkStatementPayment(row.getString("id"), statement.id, id))
        val op = cardOperation(id, statement.accountId, CreditEvidenceKind.PAYMENT)
        if (!paymentLinks.add(id) || LocalDate.parse(op.getString("bookedDate")) < LocalDate.parse(statement.cutoffDate)) invalid()
    }
    val evidenceReferences = mutableSetOf<String>()
    data.f07Rows("evidence").forEach { row ->
        val evidence = row.creditEvidence()
        checkRequest(F07Command.LinkCreditEvidence(row.getString("requestId"), evidence))
        card(evidence.accountId)
        cardOperation(evidence.operationId, evidence.accountId, evidence.kind)
        if (evidence.reference.isBlank() || !evidenceReferences.add(evidence.reference.trim())) invalid()
    }
}





