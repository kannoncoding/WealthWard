package io.github.kannoncoding.wealthward.data

import android.content.Context
import androidx.room.withTransaction
import io.github.kannoncoding.wealthward.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.math.BigInteger

data class AccountSnapshot(val id: String, val name: String, val currency: Currency, val balance: Money, val kind: String)
data class DashboardSnapshot(val accounts: List<AccountSnapshot>)
class AppContainer(context: Context) {
    private val database = WealthDatabase.open(context)
    val repository = FinanceRepository(database)
    val dashboard = repository.dashboard
    suspend fun initializeDemo() = repository.seedWorkspace()
}
class FinanceRepository(private val db: WealthDatabase) {
    private val dao = db.ledger()
    val workspace = F05Repository(db)
    val planning = F06Repository(db)
    val commitments = F07Repository(db)
    val projections = F08Repository(db)
    val dashboard: Flow<DashboardSnapshot> = combine(dao.observeAccounts(), dao.observePostingCount()) { _, _ -> DashboardSnapshot(balances()) }
    suspend fun accounts() = dao.accounts()
    suspend fun savePlan(period: FinancePeriodEntity, version: PlanVersionEntity, lines: List<PlanLineEntity>) = db.withTransaction {
        val start = LocalDate.parse(period.startsOn); val end = LocalDate.parse(period.endsOn)
        if (period.id.isBlank() || start > end || version.id.isBlank() || version.periodId != period.id || version.version < 1 || lines.any { it.id.isBlank() || it.versionId != version.id || it.visualGroup.isBlank() || it.importSource.isBlank() || it.minor < 0 } || lines.map { it.id }.distinct().size != lines.size)
            throw DomainFailure.Invalid("Invalid explicit period or plan")
        lines.forEach { Currency.valueOf(it.currency); FinancialType.valueOf(it.financialType) }
        val existing = dao.period(period.id)
        if (existing != null && existing != period) throw DomainFailure.Invalid("Period bounds cannot change between versions")
        if (dao.versions(period.id).any { it.version == version.version }) throw DomainFailure.Invalid("Duplicate plan version")
        if (existing == null) dao.insertPeriod(period)
        dao.insertPlanVersion(version); lines.forEach { dao.insertPlanLine(it) }
    }
    suspend fun planLines(periodId: String, versionId: String): List<PlanLineEntity> = db.withTransaction {
        if (dao.versions(periodId).none { it.id == versionId }) throw DomainFailure.Invalid("Version does not belong to period")
        dao.planLines(versionId)
    }
    suspend fun balances(): List<AccountSnapshot> = db.withTransaction {
        val accounts = dao.accounts()
        val balances = reconstruct(accounts, dao.transactions(), dao.postings())
        accounts.map { AccountSnapshot(it.id, it.name, Currency.valueOf(it.currency), Money(balances.getValue(it.id), Currency.valueOf(it.currency)), it.kind) }
    }
    suspend fun post(request: PostingRequest) = db.withTransaction {
        request.validate()
        if (request.type in setOf(FinancialType.DEBT_PAYMENT, FinancialType.DEBT_ADJUSTMENT) ||
            dao.accounts().any { account -> account.kind == "TERM_DEBT" && request.legs.any { it.accountId == account.id } })
            throw DomainFailure.Invalid("Use the term-debt workspace")
        request.periodId?.let {
            val period = dao.period(it) ?: throw DomainFailure.Invalid("Unknown financial period")
            if (request.date < LocalDate.parse(period.startsOn) || request.date > LocalDate.parse(period.endsOn)) throw DomainFailure.Invalid("Posting falls outside period")
        }
        validateAccounts(request, dao.accounts())
        val transaction = TransactionEntity(request.id, request.occurredAt.toString(), request.date.toString(), request.type.name, true, request.periodId, request.expenseMinor)
        val postings = request.legs.mapIndexed { index, leg -> PostingEntity("${request.id}:$index", request.id, leg.accountId, leg.amount.minor, leg.amount.currency.name) }
        reconstruct(dao.accounts(), dao.transactions() + transaction, dao.postings() + postings)
        dao.insertTransaction(transaction)
        dao.insertPostings(postings)
    }
    /** Retained F04 synthetic fixture for compatibility with the baseline persistence suite. */
    suspend fun seedDemo() = db.withTransaction {
        if (dao.accounts().isEmpty()) {
            dao.insertAccount(AccountEntity("account-a", "Account A", "CRC", "CASH", 100000))
            dao.insertAccount(AccountEntity("account-b", "Account B", "CRC", "CASH", 50000))
            dao.insertAccount(AccountEntity("usd-wallet", "USD Wallet", "USD", "CASH", 10000))
            dao.insertAccount(AccountEntity("credit-a", "Credit A", "CRC", "LIABILITY", 0, 500000))
            dao.insertAccount(AccountEntity("credit-b", "Credit B", "USD", "LIABILITY", 0, 200000))
        }
    }
    suspend fun seedWorkspace() = db.withTransaction {
        if (dao.accounts().isEmpty()) {
            val specs = listOf(
                Triple("deposit-1", "Deposit One", "CRC"), Triple("deposit-2", "Deposit Two", "CRC"),
                Triple("deposit-3", "Deposit Three", "CRC"), Triple("deposit-4", "Deposit Four", "CRC"),
                Triple("deposit-5", "Deposit Five", "USD"), Triple("deposit-6", "Deposit Six", "USD"),
                Triple("card-1-crc", "Card One", "CRC"), Triple("card-1-usd", "Card One", "USD"),
                Triple("card-2-crc", "Card Two", "CRC"), Triple("card-2-usd", "Card Two", "USD")
            )
            specs.forEach { (id,name,currency) ->
                val card=id.startsWith("card")
                workspace.execute(F05Command.CreateAccount("seed:$id",id,if(card) id.substringBeforeLast('-') else id,name,Currency.valueOf(currency),if(card) "LIABILITY" else "CASH",0,"2026-01-01",if(card) 10000000 else 0))
            }
        }
    }
    /** Technical core fixture only; this is not an encrypted user backup. */
    suspend fun exportFixture(): String = db.withTransaction {
        JSONObject().put("version", 3).put("workspace", JSONObject(dao.workspaceState()?.payload ?: F05Repository.EMPTY_STATE)).put("requests", JSONArray().apply { dao.requests().forEach { put(JSONObject().put("id", it.id).put("fingerprint", it.fingerprint).put("result", it.result)) } }).put("accounts", JSONArray().apply {
            dao.accounts().forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("currency", it.currency).put("kind", it.kind).put("openingMinor", it.openingMinor).put("creditLimitMinor", it.creditLimitMinor)) }
        }).put("transactions", JSONArray().apply {
            dao.transactions().forEach { put(JSONObject().put("id", it.id).put("occurredAt", it.occurredAt).put("businessDate", it.businessDate).put("type", it.type).put("confirmed", it.confirmed).put("periodId", it.periodId ?: JSONObject.NULL).put("expenseMinor", it.expenseMinor)) }
        }).put("postings", JSONArray().apply {
            dao.postings().forEach { put(JSONObject().put("id", it.id).put("transactionId", it.transactionId).put("accountId", it.accountId).put("minor", it.minor).put("currency", it.currency)) }
        }).put("periods", JSONArray().apply {
            dao.periods().forEach { put(JSONObject().put("id", it.id).put("startsOn", it.startsOn).put("endsOn", it.endsOn)) }
        }).put("planVersions", JSONArray().apply {
            dao.allVersions().forEach { put(JSONObject().put("id", it.id).put("periodId", it.periodId).put("version", it.version)) }
        }).put("planLines", JSONArray().apply {
            dao.allPlanLines().forEach { put(JSONObject().put("id", it.id).put("versionId", it.versionId).put("visualGroup", it.visualGroup).put("financialType", it.financialType).put("importSource", it.importSource).put("minor", it.minor).put("currency", it.currency)) }
        }).put("allocations", JSONArray().apply {
            dao.allocations().forEach { put(JSONObject().put("id", it.id).put("postingId", it.postingId).put("planLineId", it.planLineId).put("minor", it.minor)) }
        }).put("creditAssociations", JSONArray().apply {
            dao.creditAssociations().forEach { put(JSONObject().put("id", it.id).put("facilityId", it.facilityId).put("accountId", it.accountId).put("currency", it.currency)) }
        }).toString()
    }
    suspend fun restoreFixture(json: String) {
        val parsed = try {
            val root = JSONObject(json)
            val fixtureVersion = root.strictLong("version")
            if (fixtureVersion !in setOf(2L, 3L)) throw DomainFailure.UnsupportedVersion()
            val accounts = root.getJSONArray("accounts").objects().map { a ->
                Currency.valueOf(a.getString("currency"))
                AccountEntity(a.getString("id"), a.getString("name"), a.getString("currency"), a.getString("kind"), a.strictLong("openingMinor"), a.strictLong("creditLimitMinor"))
            }
            val transactions = root.getJSONArray("transactions").objects().map { t ->
                Instant.parse(t.getString("occurredAt")); LocalDate.parse(t.getString("businessDate")); FinancialType.valueOf(t.getString("type"))
                TransactionEntity(t.getString("id"), t.getString("occurredAt"), t.getString("businessDate"), t.getString("type"), t.getBoolean("confirmed"), if(t.isNull("periodId")) null else t.getString("periodId"), t.strictLong("expenseMinor"))
            }
            val postings = root.getJSONArray("postings").objects().map { p ->
                Currency.valueOf(p.getString("currency"))
                PostingEntity(p.getString("id"), p.getString("transactionId"), p.getString("accountId"), p.strictLong("minor"), p.getString("currency"))
            }
            if(accounts.any { it.id.isBlank() || it.name.isBlank() || it.creditLimitMinor < 0 || it.kind !in setOf("CASH", "LIABILITY", "TERM_DEBT") } || accounts.map { it.id }.distinct().size != accounts.size || transactions.map { it.id }.distinct().size != transactions.size || postings.any { it.id.isBlank() } || postings.map { it.id }.distinct().size != postings.size) throw DomainFailure.Invalid("Invalid fixture identities")
            postings.forEach { p ->
                if (accounts.none { it.id == p.accountId && it.currency == p.currency } || transactions.none { it.id == p.transactionId } || p.minor == 0L) throw DomainFailure.Invalid("Invalid fixture references")
            }
            transactions.forEach { t ->
                PostingRequest(t.id, Instant.parse(t.occurredAt), if (fixtureVersion == 3L && root.getJSONObject("workspace").getJSONArray("operations").objects().any { it.getString("id") == t.id }) BusinessTime.date(Instant.parse(t.occurredAt)) else LocalDate.parse(t.businessDate), FinancialType.valueOf(t.type), postings.filter { it.transactionId == t.id }.map { PostingLeg(it.accountId, Money(it.minor, Currency.valueOf(it.currency))) }, t.periodId, t.expenseMinor).let { validateAccounts(it, accounts) }
            }
            val periods = root.getJSONArray("periods").objects().map { FinancePeriodEntity(it.getString("id"), it.getString("startsOn"), it.getString("endsOn")) }
            val versions = root.getJSONArray("planVersions").objects().map {
                val number = it.strictLong("version")
                if (number !in 1L..Int.MAX_VALUE.toLong()) throw DomainFailure.Invalid("Invalid plan version")
                PlanVersionEntity(it.getString("id"), it.getString("periodId"), number.toInt())
            }
            val lines = root.getJSONArray("planLines").objects().map { PlanLineEntity(it.getString("id"), it.getString("versionId"), it.getString("visualGroup"), it.getString("financialType"), it.getString("importSource"), it.strictLong("minor"), it.getString("currency")) }
            val allocations = root.getJSONArray("allocations").objects().map { AllocationEntity(it.getString("id"), it.getString("postingId"), it.getString("planLineId"), it.strictLong("minor")) }
            val associations = root.getJSONArray("creditAssociations").objects().map { CreditAssociationEntity(it.getString("id"), it.getString("facilityId"), it.getString("accountId"), it.getString("currency")) }
            listOf(periods.map { it.id }, versions.map { it.id }, lines.map { it.id }, allocations.map { it.id }, associations.map { it.id }).forEach { ids ->
                if (ids.any { it.isBlank() } || ids.distinct().size != ids.size) throw DomainFailure.Invalid("Invalid fixture identities")
            }
            periods.forEach { if (LocalDate.parse(it.startsOn) > LocalDate.parse(it.endsOn)) throw DomainFailure.Invalid("Invalid fixture period") }
            versions.forEach { if (periods.none { period -> period.id == it.periodId }) throw DomainFailure.Invalid("Unknown plan period") }
            if (versions.map { it.periodId to it.version }.distinct().size != versions.size) throw DomainFailure.Invalid("Duplicate plan version")
            lines.forEach { line ->
                Currency.valueOf(line.currency); FinancialType.valueOf(line.financialType)
                if (versions.none { it.id == line.versionId } || line.minor < 0 || line.visualGroup.isBlank() || line.importSource.isBlank()) throw DomainFailure.Invalid("Invalid plan line")
            }
            transactions.forEach { transaction ->
                transaction.periodId?.let { id ->
                    val period = periods.find { it.id == id } ?: throw DomainFailure.Invalid("Unknown fixture transaction period")
                    val date = LocalDate.parse(transaction.businessDate)
                    if (date < LocalDate.parse(period.startsOn) || date > LocalDate.parse(period.endsOn)) throw DomainFailure.Invalid("Fixture posting falls outside period")
                }
            }
            allocations.forEach { allocation ->
                val posting = postings.find { it.id == allocation.postingId } ?: throw DomainFailure.Invalid("Unknown allocation posting")
                val line = lines.find { it.id == allocation.planLineId } ?: throw DomainFailure.Invalid("Unknown allocation plan line")
                val transaction = transactions.first { it.id == posting.transactionId }
                val version = versions.first { it.id == line.versionId }
                if (allocation.minor <= 0 || posting.minor == Long.MIN_VALUE || allocation.minor > kotlin.math.abs(posting.minor) || line.currency != posting.currency || transaction.periodId != version.periodId) throw DomainFailure.Invalid("Invalid allocation currency, amount or period")
            }
            allocations.groupBy { it.postingId }.forEach { (id, splits) ->
                val total = splits.fold(0L) { sum, allocation -> Math.addExact(sum, allocation.minor) }
                if (total > kotlin.math.abs(postings.first { it.id == id }.minor)) throw DomainFailure.Invalid("Allocations exceed posting")
            }
            associations.forEach { association ->
                Currency.valueOf(association.currency)
                if (association.facilityId.isBlank() || accounts.none { it.id == association.accountId && it.kind == "LIABILITY" && it.currency == association.currency }) throw DomainFailure.Invalid("Invalid credit association")
            }
            reconstruct(accounts, transactions, postings)
            val workspace = if (fixtureVersion == 3L) root.getJSONObject("workspace").toString() else F05Repository.EMPTY_STATE
            val requests = if (fixtureVersion == 3L) root.getJSONArray("requests").objects().map { WorkspaceRequestEntity(it.getString("id"), it.getString("fingerprint"), it.getString("result")) } else emptyList()
            F05Repository.validateRestore(workspace, requests, accounts, transactions, postings)
            CoreFixture(accounts, transactions, postings, periods, versions, lines, allocations, associations, workspace, requests)
        } catch (error: DomainFailure) { throw error } catch (_: Exception) { throw DomainFailure.Invalid("Corrupt fixture") }
        db.withTransaction {
            dao.clearRequests(); dao.clearWorkspace()
            dao.clearAllocations(); dao.clearCreditAssociations()
            dao.clearTransactions(); dao.clearAccounts()
            dao.clearPlanLines(); dao.clearPlanVersions(); dao.clearPeriods()
            parsed.accounts.forEach { dao.insertAccount(it) }
            parsed.periods.forEach { dao.insertPeriod(it) }
            parsed.versions.forEach { dao.insertPlanVersion(it) }
            parsed.lines.forEach { dao.insertPlanLine(it) }
            parsed.transactions.forEach { dao.insertTransaction(it) }
            dao.insertPostings(parsed.postings)
            parsed.allocations.forEach { dao.insertAllocation(it) }
            parsed.associations.forEach { dao.insertCreditAssociation(it) }
            dao.saveWorkspace(WorkspaceStateEntity("main", parsed.workspace))
            parsed.requests.forEach { dao.insertRequest(it) }
        }
    }
}
private data class CoreFixture(val accounts: List<AccountEntity>, val transactions: List<TransactionEntity>, val postings: List<PostingEntity>, val periods: List<FinancePeriodEntity>, val versions: List<PlanVersionEntity>, val lines: List<PlanLineEntity>, val allocations: List<AllocationEntity>, val associations: List<CreditAssociationEntity>, val workspace: String, val requests: List<WorkspaceRequestEntity>)
private fun reconstruct(accounts: List<AccountEntity>, transactions: List<TransactionEntity>, postings: List<PostingEntity>): Map<String, Long> {
    val confirmed = transactions.filter { it.confirmed }.map { it.id }.toSet()
    return accounts.associate { account ->
        val total = postings.asSequence().filter { it.accountId == account.id && it.transactionId in confirmed }
            .fold(BigInteger.valueOf(account.openingMinor)) { sum, posting -> sum.add(BigInteger.valueOf(posting.minor)) }
        val minor = try { total.longValueExact() } catch (_: ArithmeticException) { throw DomainFailure.Invalid("Balance exceeds integer minor-unit range") }
        account.id to minor
    }
}
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
private fun JSONObject.strictLong(key: String): Long {
    val value = get(key)
    if (value !is Long && value !is Int) throw DomainFailure.Invalid("Minor units must be integers")
    return (value as Number).toLong()
}

private fun validateAccounts(request: PostingRequest, accountList: List<AccountEntity>) {
    request.validate()
    val accounts = accountList.associateBy { it.id }
    request.legs.forEach {
        val account = accounts[it.accountId] ?: throw DomainFailure.Invalid("Unknown account")
        if (account.currency != it.amount.currency.name) throw DomainFailure.CurrencyMismatch()
    }
    if (request.type == FinancialType.DEBT_PAYMENT) {
        val cash = request.legs.singleOrNull { accounts.getValue(it.accountId).kind == "CASH" }
        val principal = request.legs.singleOrNull { accounts.getValue(it.accountId).kind == "TERM_DEBT" }
        if (cash == null || cash.amount.minor >= 0 || cash.amount.minor == Long.MIN_VALUE ||
            request.legs.size != if (principal == null) 1 else 2)
            throw DomainFailure.Invalid("Invalid term debt payment legs")
        if (principal != null && (principal.amount.minor <= 0 || principal.amount.currency != cash.amount.currency))
            throw DomainFailure.Invalid("Invalid term debt principal")
        if (Math.addExact(request.expenseMinor, principal?.amount?.minor ?: 0L) != -cash.amount.minor)
            throw DomainFailure.Invalid("Term debt payment components must equal cash paid")
    }
    if (request.type == FinancialType.DEBT_ADJUSTMENT &&
        (request.expenseMinor != 0L || request.legs.size != 1 || accounts.getValue(request.legs.single().accountId).kind != "TERM_DEBT"))
        throw DomainFailure.Invalid("Term debt adjustment requires one liability leg")
    if (request.type == FinancialType.INCOME && (request.expenseMinor != 0L || request.legs.any { accounts.getValue(it.accountId).kind != "CASH" || it.amount.minor <= 0 }))
        throw DomainFailure.Invalid("Income must increase cash and cannot count as expense")
    if (request.type == FinancialType.EXPENSE) {
        val magnitude = request.legs.fold(BigInteger.ZERO) { sum, leg -> sum.subtract(BigInteger.valueOf(leg.amount.minor)) }
        if (request.legs.any { accounts.getValue(it.accountId).kind != "CASH" || it.amount.minor >= 0 } || request.legs.map { it.amount.currency }.distinct().size != 1 || magnitude != BigInteger.valueOf(request.expenseMinor))
            throw DomainFailure.Invalid("Expense must reduce cash in one currency and recognize its exact amount")
    }
    if (request.type == FinancialType.CREDIT_PURCHASE && (request.legs.size != 1 || accounts.getValue(request.legs.single().accountId).kind != "LIABILITY" || request.legs.single().amount.minor >= 0 || request.legs.single().amount.minor == Long.MIN_VALUE || request.expenseMinor != Math.negateExact(request.legs.single().amount.minor)))
        throw DomainFailure.Invalid("Credit purchase must recognize one expense and increase liability")
    if (request.type == FinancialType.CREDIT_PAYMENT && (request.legs.size != 2 || request.legs.none { accounts.getValue(it.accountId).kind == "LIABILITY" && it.amount.minor > 0 } || request.legs.none { accounts.getValue(it.accountId).kind == "CASH" && it.amount.minor < 0 }))
        throw DomainFailure.Invalid("Credit payment must move cash into a liability settlement")
}





