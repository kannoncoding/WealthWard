package io.github.kannoncoding.wealthward.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kannoncoding.wealthward.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class CategoryCatalogTest {
 @Test fun legacyBudgetAliasesDoNotShareSpendingBetweenGroups(): Unit = runBlocking {
  val context = ApplicationProvider.getApplicationContext<Context>()
  val db = Room.inMemoryDatabaseBuilder(context, WealthDatabase::class.java).build()
  try {
   val ledger = F05Repository(db)
   val planning = F06Repository(db)
   val month = "2026-01"
   ledger.execute(F05Command.CreateAccount("open", "cash", "cash", "Cash", Currency.CRC, "CASH", 10_000, "$month-01"))
   planning.execute(F06Command.SavePlan("budget", PeriodPlan(month, 1, listOf(
    PlanRow("need", "Food", "Needs", PlanNature.VARIABLE_NEED, 2_000, Currency.CRC),
    PlanRow("free", "Dining", "Free", PlanNature.FREE, 3_000, Currency.CRC),
   ))))
   // Older app versions allowed case variants in separate variable groups.
   val dao = db.ledger()
   val previous = dao.workspaceState()!!
   val payload = JSONObject(previous.payload)
   payload.remove("categories")
   payload.getJSONObject("f06").getJSONArray("plans").getJSONObject(0)
    .getJSONArray("rows").getJSONObject(1).put("name", "food")
   dao.saveWorkspace(previous.copy(payload = payload.toString()))
   ledger.execute(F05Command.Record("expense", "cash", "EXPENSE", 1_000, "$month-05", "Merchant", "Food"))
   val comparison = planning.snapshot(month).comparisons.associate { it.group to it.consumptionMinor }
   assertEquals(mapOf("Needs" to 1_000L, "Free" to 0L), comparison)
   val remaining = F08Repository(db).snapshot(month, LocalDate.parse("$month-10"), Currency.CRC)
    .lines.filter { it.component == ProjectionComponent.VARIABLE }.associate { it.title to it.amount?.minor }
   assertEquals(mapOf("Food" to 1_000L, "food" to 3_000L), remaining)
  } finally { db.close() }
 }

 @Test fun categoriesReuseIdentityAcrossLegacySplitsBudgetsAndSubscriptions(): Unit = runBlocking {
  val context = ApplicationProvider.getApplicationContext<Context>()
  val db = Room.inMemoryDatabaseBuilder(context, WealthDatabase::class.java).build()
  try {
   val ledger = F05Repository(db)
   val planning = F06Repository(db)
   val month = "2026-01"
   ledger.execute(F05Command.CreateAccount("open", "cash", "cash", "Cash", Currency.CRC, "CASH", 10_000, "$month-01"))
   ledger.execute(F05Command.Record("receipt", "cash", "EXPENSE", 1_000, "$month-02", "Store", "Shopping",
    splits = listOf(ExpenseSplit(" Groceries ", 700), ExpenseSplit("Household", 300))))
   val dao = db.ledger()
   val legacy = dao.workspaceState()!!
   val payload = JSONObject(legacy.payload)
   payload.remove("categories")
   dao.saveWorkspace(legacy.copy(payload = payload.toString()))
   val original = ledger.snapshot()
   assertEquals(" Groceries ", original.operations.single().splits.first().category)
   assertTrue(original.operations.single().matchesCategory("GROCER"))
   val groceries = original.categories.single { it.name == "Groceries" }

   planning.execute(F06Command.SavePlan("budget", PeriodPlan(month, 1, listOf(
    PlanRow("food", "GROCERIES", "Needs", PlanNature.VARIABLE_NEED, 2_000, Currency.CRC)
   ))))
   assertEquals(700L, planning.snapshot(month).comparisons.single().consumptionMinor)

   planning.execute(F06Command.SaveTemplate("subscription", RecurringTemplate(
    "delivery", 1, "Delivery pass", "Subscriptions", PlanNature.SUBSCRIPTION, 100, Currency.CRC, month,
    suggestedAccountId = "cash", category = "groceries",
   )))
   planning.execute(F06Command.OpenPeriod("generate", month))
   val bill = planning.snapshot(month).occurrences.single()
   planning.execute(F06Command.Pay("payment", bill.id, "cash", 100, "$month-10"))

   val reopened = F05Repository(db).snapshot()
   assertEquals(groceries, reopened.categories.single { it.name.equals("groceries", ignoreCase = true) })
   assertEquals("groceries", reopened.operations.single { it.id == "payment:ledger" }.category)
   assertEquals(original.operations.single(), reopened.operations.single { it.id == "receipt" })
   val storedCategories = JSONObject(dao.workspaceState()!!.payload).getJSONArray("categories")
   val stored = List(storedCategories.length()) { storedCategories.getJSONObject(it) }
   assertEquals(groceries.id, stored.single { it.getString("name") == "Groceries" }.getString("id"))
  } finally { db.close() }
 }
}
