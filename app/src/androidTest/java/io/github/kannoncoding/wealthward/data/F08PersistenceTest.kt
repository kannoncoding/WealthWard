package io.github.kannoncoding.wealthward.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kannoncoding.wealthward.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class F08PersistenceTest {
    @Test fun cutoffUsesBookedDateAndReplacesCoveredOccurrence() = runBlocking {
        database().withDatabase { db ->
            val today = LocalDate.now(BusinessTime.zone)
            val month = YearMonth.from(today).minusMonths(1)
            val date = month.atDay(1)
            val repo = FinanceRepository(db)
            repo.workspace.execute(F05Command.CreateAccount("new-cash", "cash", "cash", "Synthetic cash", Currency.CRC, "CASH", 100_000, date.toString()))
            val plan = PeriodPlan(month.toString(), 1, listOf(PlanRow("bill", "Test bill", "Bills", PlanNature.FIXED, 30_000, Currency.CRC, "cash")))
            repo.planning.execute(F06Command.SavePlan("plan", plan))
            repo.planning.execute(F06Command.SaveTemplate("template", RecurringTemplate("bill", 1, "Test bill", "Bills", PlanNature.FIXED,
                30_000, Currency.CRC, month.toString(), day = 5, suggestedAccountId = "cash")))
            repo.planning.execute(F06Command.OpenPeriod("open", month.toString()))
            repo.workspace.execute(F05Command.Record("book", "cash", "EXPENSE", 30_000,
                date.plusDays(3).toString(), "Test bill", "Test bill", bookedDate = date.plusDays(5).toString()))
            repo.planning.execute(F06Command.LinkPayment("link", "bill:$month:0", "book"))
            val earlier = project(repo.projections.snapshot(month.toString(), date.plusDays(4), Currency.CRC))
            val later = project(repo.projections.snapshot(month.toString(), date.plusDays(5), Currency.CRC))
            assertEquals(100_000L, earlier.totals!!.liquid.minor)
            assertEquals(30_000L, earlier.totals.obligations.minor)
            assertEquals(70_000L, later.totals!!.liquid.minor)
            assertEquals(0L, later.totals.obligations.minor)
            assertEquals(earlier.freeRecommended, later.freeRecommended)
            assertEquals(70_000L, later.actualRemaining!!.minor)
            val configured = project(repo.projections.snapshot(month.toString(), date.plusDays(5), Currency.CRC,
                buffer = Money(10_000, Currency.CRC), greenMargin = Money(60_000, Currency.CRC)))
            assertEquals(60_000L, configured.freeRecommended!!.minor)
            assertEquals(ProjectionColor.GREEN, configured.color)
            assertEquals(later.cash.single().safeToday, configured.cash.single().safeToday)
            assertEquals(later.actualRemaining, configured.actualRemaining)
        }
    }

    @Test fun tenThousandMovementTiming() = runBlocking {
        database().withDatabase { db ->
            val cutoff = LocalDate.now(BusinessTime.zone)
            val month = YearMonth.from(cutoff).toString()
            val dao = db.ledger()
            db.withTransaction {
                val state = JSONObject(F05Repository.EMPTY_STATE)
                val ops = JSONArray()
                for (account in 0..7) dao.insertAccount(AccountEntity("account-$account", "Synthetic $account", "CRC", "CASH", 500_000))
                val postings = ArrayList<PostingEntity>(10_000)
                for (number in 0 until 10_000) {
                    val id = "synthetic-$number"
                    val accountId = "account-${number % 8}"
                    dao.insertTransaction(TransactionEntity(id, "2020-01-01T00:00:00Z", cutoff.toString(), "EXPENSE", true, null, 2))
                    postings.add(PostingEntity("$id:0", id, accountId, -2, "CRC"))
                    ops.put(JSONObject().put("id", id).put("date", cutoff.toString()).put("type", "EXPENSE")
                        .put("accountId", accountId).put("amountMinor", 2).put("merchant", "Comercio de prueba")
                        .put("category", "Test").put("notes", "Operación ficticia con confirmación de asiento y referencia de conciliación.")
                        .put("voided", false).put("refundedMinor", 0)
                        .put("expenseMinor", 2).put("splits", JSONArray()))
                }
                dao.insertPostings(postings)
                state.put("operations", ops)
                val planning = F06Repository.empty()
                planning.getJSONArray("plans").put(JSONObject().put("month", month).put("version", 1)
                    .put("effectiveDate", "$month-01").put("targetMinor", 0).put("targetCurrency", "CRC").put("reason", "")
                    .put("rows", JSONArray().put(JSONObject().put("id", "test").put("name", "Test").put("group", "Synthetic")
                        .put("nature", "FREE").put("minor", 30_000).put("currency", "CRC").put("accumulate", false))))
                val payload = state.put("f06", planning).toString()
                assertTrue(payload.toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024)
                dao.saveWorkspace(WorkspaceStateEntity("main", payload))
                assertEquals(payload, dao.workspaceState()?.payload)
            }
            val projections = F08Repository(db)
            val times = List(4) {
                val start = System.nanoTime()
                val result = project(projections.snapshot(month, cutoff, Currency.CRC))
                val elapsed = (System.nanoTime() - start) / 1_000_000L
                assertEquals(3_980_000L, result.totals!!.liquid.minor)
                assertEquals(10_000L, result.totals.variable.minor)
                assertEquals(3_970_000L, result.freeRecommended!!.minor)
                elapsed
            }
            android.util.Log.i("F08Benchmark", "snapshot+projection ms, cold=${times.first()}, warm=${times.drop(1)}")
        }
    }

    private inline fun WealthDatabase.withDatabase(test: (WealthDatabase) -> Unit) {
        try {
            test(this)
        } finally {
            close()
        }
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, WealthDatabase::class.java,
    ).build()
}
