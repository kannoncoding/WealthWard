package io.github.kannoncoding.wealthward.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kannoncoding.wealthward.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun open(name: String) = Room.databaseBuilder(context, WealthDatabase::class.java, name).addMigrations(WealthDatabase.MIGRATION_1_2).build()
    @Test fun reopenReconstructsConfirmedLedgerAndSeedIsIdempotent() = runBlocking {
        val name = "reopen-test.db"; context.deleteDatabase(name)
        var db = open(name)
        var repo = FinanceRepository(db)
        repo.seedDemo(); repo.seedDemo()
        repo.savePlan(FinancePeriodEntity("period", "2026-01-01", "2026-01-31"), PlanVersionEntity("version", "period", 1), listOf(PlanLineEntity("line", "version", "Everyday", "EXPENSE", "DEMO", 500, "CRC")))
        repo.post(PostingRequest("expense", Instant.EPOCH, BusinessTime.date(Instant.EPOCH), FinancialType.EXPENSE, listOf(PostingLeg("account-a", Money(-1234, Currency.CRC))), expenseMinor = 1234))
        db.close(); db = open(name); repo = FinanceRepository(db)
        assertEquals(5, repo.accounts().size)
        assertEquals("Everyday", repo.planLines("period", "version").single().visualGroup)
        assertEquals(98766L, repo.balances().first { it.id == "account-a" }.balance.minor)
        db.close(); context.deleteDatabase(name)
        Unit
    }
    @Test fun sqlFailureRollsBackEarlierWrites() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, WealthDatabase::class.java).build()
        val repo = FinanceRepository(db); repo.seedDemo()
        try {
            db.withTransaction {
                db.ledger().insertTransaction(TransactionEntity("broken", Instant.EPOCH.toString(), "1970-01-01", "EXPENSE", true, null, 1))
                db.ledger().insertPostings(listOf(PostingEntity("p", "broken", "missing", -1, "CRC")))
            }
            fail("Foreign key must reject posting")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertTrue(db.ledger().transactions().isEmpty())
        db.close()
    }
    @Test fun fixtureRoundTripAndCorruptionLeavePriorDataIntact() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, WealthDatabase::class.java).build()
        val repo = FinanceRepository(db); repo.seedDemo()
        repo.post(PostingRequest("purchase", Instant.EPOCH, BusinessTime.date(Instant.EPOCH), FinancialType.CREDIT_PURCHASE, listOf(PostingLeg("credit-a", Money(-1000, Currency.CRC))), expenseMinor = 1000))
        val exported = repo.exportFixture(); repo.restoreFixture(exported)
        assertEquals(exported, repo.exportFixture())
        val invalidCredit = JSONObject(exported).apply { getJSONArray("postings").getJSONObject(0).put("accountId", "account-a") }.toString()
        for (invalid in listOf("{broken", invalidCredit, JSONObject(exported).put("version", 99).toString(), exported.replace("\"currency\":\"CRC\"", "\"currency\":\"BAD\""), exported.replace("\"kind\":\"CASH\"", "\"kind\":\"BAD\""))) {
            try { repo.restoreFixture(invalid); fail("Must reject corrupt fixture") } catch (_: DomainFailure) { }
            assertEquals(exported, repo.exportFixture())
        }
        db.close()
    }
    @Test fun multiplePlanVersionsPreservePeriodAndRejectWrongPeriodLookup() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, WealthDatabase::class.java).build()
        val repo = FinanceRepository(db)
        val period = FinancePeriodEntity("period", "2026-01-01", "2026-01-31")
        for (version in 1..2) repo.savePlan(period, PlanVersionEntity("v$version", "period", version), listOf(PlanLineEntity("l$version", "v$version", "Everyday", "EXPENSE", "DEMO", version * 100L, "CRC")))
        assertEquals(100L, repo.planLines("period", "v1").single().minor)
        assertEquals(200L, repo.planLines("period", "v2").single().minor)
        try { repo.planLines("other", "v1"); fail("Wrong period must reject") } catch (_: DomainFailure.Invalid) { }
        try { repo.savePlan(period.copy(endsOn = "2026-02-01"), PlanVersionEntity("v3", "period", 3), emptyList()); fail("Changed bounds must reject") } catch (_: DomainFailure.Invalid) { }
        for (invalid in listOf(PlanVersionEntity("duplicate", "period", 1), PlanVersionEntity("", "period", 3))) {
            try { repo.savePlan(period, invalid, emptyList()); fail("Invalid version must reject") } catch (_: DomainFailure.Invalid) { }
        }
        try { repo.savePlan(period, PlanVersionEntity("v3", "period", 3), listOf(PlanLineEntity("", "v3", "Everyday", "EXPENSE", "DEMO", 1, "CRC"))); fail("Blank line must reject") } catch (_: DomainFailure.Invalid) { }
        assertEquals(100L, repo.planLines("period", "v1").single().minor)
        assertEquals(200L, repo.planLines("period", "v2").single().minor)
        db.close()
    }
    @Test fun completeFixtureRecoversIntoCleanDatabaseAndRejectsBrokenGraph() = runBlocking {
        val source = Room.inMemoryDatabaseBuilder(context, WealthDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, WealthDatabase::class.java).build()
        val repo = FinanceRepository(source); repo.seedDemo()
        repo.savePlan(FinancePeriodEntity("p", "2026-01-01", "2026-01-31"), PlanVersionEntity("v", "p", 2), listOf(PlanLineEntity("line", "v", "Everyday", "EXPENSE", "TECHNICAL_FIXTURE", 12345, "CRC")))
        val instant = Instant.parse("2026-01-05T18:00:00Z")
        repo.post(PostingRequest("expense", instant, BusinessTime.date(instant), FinancialType.EXPENSE, listOf(PostingLeg("account-a", Money(-1000, Currency.CRC))), "p", 1000))
        source.ledger().insertAllocation(AllocationEntity("split", "expense:0", "line", 1000))
        source.ledger().insertCreditAssociation(CreditAssociationEntity("association", "facility", "credit-a", "CRC"))
        val exported = repo.exportFixture()
        val recovered = FinanceRepository(target)
        assertTrue(recovered.accounts().isEmpty())
        recovered.restoreFixture(exported)
        assertEquals(exported, recovered.exportFixture())
        assertEquals(source.ledger().periods(), target.ledger().periods())
        assertEquals(source.ledger().allVersions(), target.ledger().allVersions())
        assertEquals(source.ledger().allPlanLines(), target.ledger().allPlanLines())
        assertEquals(source.ledger().allocations(), target.ledger().allocations())
        assertEquals(source.ledger().creditAssociations(), target.ledger().creditAssociations())
        assertEquals(repo.balances(), recovered.balances())
        val corruptions = listOf(
            JSONObject(exported).apply { getJSONArray("allocations").getJSONObject(0).put("planLineId", "missing") },
            JSONObject(exported).apply { getJSONArray("transactions").getJSONObject(0).put("periodId", "missing") },
            JSONObject(exported).apply { getJSONArray("periods").getJSONObject(0).put("endsOn", "2026-01-02") },
            JSONObject(exported).apply { getJSONArray("creditAssociations").getJSONObject(0).put("currency", "USD") },
        )
        for (corrupt in corruptions) {
            try { recovered.restoreFixture(corrupt.toString()); fail("Broken graph must reject") } catch (_: DomainFailure) { }
            assertEquals(exported, recovered.exportFixture())
        }
        source.close(); target.close()
    }
    @Test fun invalidEconomicSignsAndOverflowLeaveLedgerUnchanged() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, WealthDatabase::class.java).build()
        val repo = FinanceRepository(db); repo.seedDemo()
        db.ledger().insertAccount(AccountEntity("limit", "Account A", "CRC", "CASH", Long.MAX_VALUE))
        val before = repo.exportFixture()
        val invalid = listOf(
            PostingRequest("expense-sign", Instant.EPOCH, BusinessTime.date(Instant.EPOCH), FinancialType.EXPENSE, listOf(PostingLeg("account-a", Money(500, Currency.CRC)))),
            PostingRequest("income-sign", Instant.EPOCH, BusinessTime.date(Instant.EPOCH), FinancialType.INCOME, listOf(PostingLeg("account-a", Money(-500, Currency.CRC)))),
            PostingRequest("expense-total", Instant.EPOCH, BusinessTime.date(Instant.EPOCH), FinancialType.EXPENSE, listOf(PostingLeg("account-a", Money(-500, Currency.CRC))), expenseMinor = 499),
            PostingRequest("expense-currency", Instant.EPOCH, BusinessTime.date(Instant.EPOCH), FinancialType.EXPENSE, listOf(PostingLeg("account-a", Money(-500, Currency.CRC)), PostingLeg("usd-wallet", Money(-500, Currency.USD))), expenseMinor = 1000),
            PostingRequest("overflow", Instant.EPOCH, BusinessTime.date(Instant.EPOCH), FinancialType.INCOME, listOf(PostingLeg("limit", Money(1, Currency.CRC)))),
        )
        for (request in invalid) {
            try { repo.post(request); fail("Invalid posting must reject") } catch (_: DomainFailure.Invalid) { }
            assertEquals(before, repo.exportFixture())
        }
        repo.post(PostingRequest("income", Instant.EPOCH, BusinessTime.date(Instant.EPOCH), FinancialType.INCOME, listOf(PostingLeg("account-a", Money(1, Currency.CRC)))))
        val valid = repo.exportFixture()
        val overflow = JSONObject(valid).apply {
            val accounts = getJSONArray("accounts")
            for (i in 0 until accounts.length()) if (accounts.getJSONObject(i).getString("id") == "account-a") accounts.getJSONObject(i).put("openingMinor", Long.MAX_VALUE)
        }
        try { repo.restoreFixture(overflow.toString()); fail("Unreconstructible fixture must reject") } catch (_: DomainFailure.Invalid) { }
        assertEquals(valid, repo.exportFixture())
        db.close()
    }
    @Test fun purchaseAndSettlementCountExpenseOnceAndLimitsAreNotCash() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, WealthDatabase::class.java).build()
        val repo = FinanceRepository(db); repo.seedDemo()
        assertEquals(0L, repo.balances().first { it.id == "credit-a" }.balance.minor)
        repo.post(PostingRequest("purchase", Instant.EPOCH, BusinessTime.date(Instant.EPOCH), FinancialType.CREDIT_PURCHASE, listOf(PostingLeg("credit-a", Money(-1000, Currency.CRC))), expenseMinor = 1000))
        repo.post(PostingRequest("payment", Instant.EPOCH, BusinessTime.date(Instant.EPOCH), FinancialType.CREDIT_PAYMENT, listOf(PostingLeg("account-a", Money(-1000, Currency.CRC)), PostingLeg("credit-a", Money(1000, Currency.CRC)))))
        assertEquals(1000L, db.ledger().transactions().sumOf { it.expenseMinor })
        assertEquals(0L, repo.balances().first { it.id == "credit-a" }.balance.minor)
        assertEquals(99000L, repo.balances().first { it.id == "account-a" }.balance.minor)
        db.close()
    }
}
