package io.github.kannoncoding.wealthward.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kannoncoding.wealthward.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class F06PersistenceTest {
 private val context=ApplicationProvider.getApplicationContext<android.content.Context>()
 private fun database()=Room.inMemoryDatabaseBuilder(context,WealthDatabase::class.java).build()
 private suspend fun F05Repository.cash(amount:Long=50000000,id:String="cash",currency:Currency=Currency.CRC,kind:String="CASH") { execute(F05Command.CreateAccount("create:$id",id,id,"Account $id",currency,kind,amount,"2026-01-01")) }
 private fun bill(id:String="bill",minor:Long=2500000,month:String="2027-01",frequency:Recurrence=Recurrence.MONTHLY)=RecurringTemplate(id,1,"Service $id","Essentials",PlanNature.FIXED,minor,Currency.CRC,month,frequency,31,suggestedAccountId="cash")
 private suspend fun fails(block:suspend ()->Unit) { try { block(); fail("Expected invalid command") } catch (_:DomainFailure) {} }
 @Test fun salaryConfirmationOnlyCreditsOnceAndUsesTheAssignedAccount(): Unit = runBlocking {
  val db = database()
  try {
   val repo = FinanceRepository(db)
   repo.workspace.cash(id = "salary-account", amount = 10_000)
   repo.workspace.cash(id = "different-account", amount = 0)
   val now = java.time.LocalDate.now(BusinessTime.zone)
   val month = java.time.YearMonth.from(now).toString()
   val id = "salary:$month:0"
   repo.planning.execute(F06Command.SaveSalary("salary-rule",
    SalaryVersion("rule", month, 80_000, Currency.CRC, "salary-account", scheduleDay = 1)))
   repo.planning.execute(F06Command.OpenPeriod("open-salary", month))
   assertTrue(repo.workspace.snapshot().operations.isEmpty())
   fails { repo.planning.confirmSalary(id, "different-account", 80_000) }
   repo.planning.confirmSalary(id, "salary-account", 80_000)
   repo.planning.confirmSalary(id, "salary-account", 80_000)
   val ledger = repo.workspace.snapshot()
   val receipt = ledger.operations.single()
   assertEquals("INCOME", receipt.type)
   assertEquals("salary-account", receipt.accountId)
   assertEquals(now.toString(), receipt.date)
   assertEquals(90_000L, ledger.accounts.first { it.id == "salary-account" }.balanceMinor)
   assertEquals(listOf(receipt.id), repo.planning.snapshot(month).occurrences.single().paymentIds)
  } finally { db.close() }
 }
 @Test fun rolloverKeepsOriginalObligationsAndDoesNotPostTheSalary(): Unit = runBlocking {
  val db = database()
  try {
   val repo = FinanceRepository(db)
   repo.workspace.cash()
   repo.planning.execute(F06Command.SaveTemplate("netflix", bill(id = "netflix").copy(day = 15, nature = PlanNature.SUBSCRIPTION)))
   repo.planning.execute(F06Command.SaveSalary("salary", SalaryVersion("salary-version", "2027-01", 200_000, Currency.CRC, "cash")))
   repo.planning.execute(F06Command.OpenPeriod("open-jan", "2027-01"))
   val openMarch = F06Command.OpenPeriod("open-mar", "2027-03")
   repo.planning.execute(openMarch)
   repo.planning.execute(openMarch)
   val periods = listOf("2027-01", "2027-02", "2027-03")
   val month = repo.planning.snapshot("2027-03")
   assertEquals(periods, month.occurrences.filter { it.templateId == "netflix" }.map { it.nominalMonth }.sorted())
   assertEquals(periods, month.occurrences.filter { it.templateId == "salary" }.map { it.nominalMonth }.sorted())
   assertTrue(repo.workspace.snapshot().operations.isEmpty())
   repo.planning.execute(F06Command.Pay("pay-jan", "netflix:2027-01:0", "cash", 2_500_000, "2027-03-15"))
   assertEquals(periods.drop(1), repo.planning.snapshot("2027-03").occurrences
    .filter { it.templateId == "netflix" && it.paymentPendingMinor > 0 }.map { it.nominalMonth }.sorted())
   assertEquals(1, repo.workspace.snapshot().operations.size)
  } finally { db.close() }
 }

 @Test fun datedSalaryOccurrencesAndRecurringIncomePreserveManualReceipts(): Unit = runBlocking {
  val db = database()
  try {
   val repo = FinanceRepository(db)
   repo.workspace.cash(amount = 1_000)
   val today = java.time.LocalDate.now(BusinessTime.zone)
   val month = java.time.YearMonth.from(today)
   val prior = month.minusMonths(1)
   val next = month.plusMonths(1)
   val anchor = prior.atEndOfMonth()
   repo.planning.execute(F06Command.SaveSalary("save-weekly",
    SalaryVersion("weekly",prior.toString(),500,Currency.CRC,"cash",
     frequency = IncomeFrequency.WEEKLY,anchorDate = anchor.toString())))
   repo.planning.execute(F06Command.OpenPeriod("open-prior",prior.toString()))
   repo.planning.execute(F06Command.OpenPeriod("open-next",next.toString()))
   val salaries = repo.planning.snapshot().occurrences.filter { it.templateId == "salary" }
   val due = salaries.first { it.dueDate == anchor.toString() }
   assertTrue(salaries.count { it.nominalMonth == month.toString() } >= 4)
   assertEquals(salaries.size,salaries.map { it.id }.distinct().size)
   assertTrue(repo.workspace.snapshot().operations.isEmpty())
   repeat(2) { repo.planning.confirmSalary(due.id,"cash",500) }

   val extra = ExtraIncomeVersion("consulting",1,"Consulting",today.toString(),today.toString(),
    IncomeFrequency.EVERY_14_DAYS,300,Currency.CRC,"cash")
   repo.planning.execute(F06Command.SaveExtraIncome("new-extra",extra))
   val occurrence = repo.planning.snapshot().occurrences.first { it.templateId == "recurring-extra:consulting" && it.dueDate == today.toString() }
   assertEquals(1_500L,repo.workspace.snapshot().accounts.single().balanceMinor)
   val receipt = F06Command.Pay("received-extra",occurrence.id,"cash",300,today.toString())
   repeat(2) { repo.planning.execute(receipt) }
   repo.planning.execute(F06Command.SaveExtraIncome("edit-extra",extra.copy(version=2,expectedMinor=400)))
   val edited = repo.planning.snapshot().occurrences.filter { it.templateId == occurrence.templateId && it.dueDate > today.toString() }
   assertTrue(edited.isNotEmpty())
   assertTrue(edited.all { it.expectedMinor == 400L })
   repo.planning.execute(F06Command.SaveExtraIncome("pause-extra",extra.copy(version=3,expectedMinor=400,paused=true)))
   fails { repo.planning.execute(receipt.copy(requestId="stale-extra",occurrenceId=edited.first().id)) }
   repo.restoreFixture(repo.exportFixture())
   val history = repo.planning.snapshot().occurrences.single { it.templateId == occurrence.templateId }
   assertEquals(occurrence.id,history.id)
   assertEquals(300L,history.paidMinor)
   assertEquals(300L,history.expectedMinor)
   assertEquals(1_800L,repo.workspace.snapshot().accounts.single().balanceMinor)
  } finally { db.close() }
 }

 @Test fun multipleReminderLeadsSurviveExportAndRestore(): Unit = runBlocking {
  val db = database()
  try {
   val repo = FinanceRepository(db)
   assertEquals(listOf(1), repo.planning.snapshot().reminders.leads)
   val preference = ReminderPreference(true, 7, 9, listOf(1, 0))
   repo.planning.execute(F06Command.SetReminders("multiple", preference))
   repo.restoreFixture(repo.exportFixture())
   assertEquals(preference, repo.planning.snapshot().reminders)
   fails { repo.planning.execute(F06Command.SetReminders("duplicate", preference.copy(additionalDays = listOf(7)))) }
  } finally { db.close() }
 }
 @Test fun canConfirmAdditionalDeductionAfterConfirmedTotalIncreases(): Unit = runBlocking {
  val db = database()
  try {
   val repo = FinanceRepository(db)
   repo.workspace.cash()
   val date = java.time.LocalDate.now(BusinessTime.zone)
   val month = java.time.YearMonth.from(date).toString()
   repo.planning.execute(F06Command.SaveTemplate("food", bill(minor = 100, month = month)))
   repo.planning.execute(F06Command.OpenPeriod("open-food", month))
   val id = repo.planning.snapshot(month).occurrences.single().id
   repo.planning.execute(F06Command.ConfirmPreviouslyDeducted("first", id, "cash", date.toString()))
   repo.planning.execute(F06Command.ConfirmAmount("raise", id, 150))
   assertEquals(50L, repo.planning.snapshot(month).occurrences.single().paymentPendingMinor)
   repo.planning.execute(F06Command.ConfirmPreviouslyDeducted("second", id, "cash", date.toString()))
   assertEquals(0L, repo.planning.snapshot(month).occurrences.single().paymentPendingMinor)
   assertEquals(150L, repo.planning.snapshot(month).occurrences.single().paidMinor)
   assertTrue(repo.workspace.snapshot().operations.isEmpty())
   repo.restoreFixture(repo.exportFixture())
  } finally { db.close() }
 }
 @Test fun sharedRentDebitsFullAmountAndReportsOnlyOwnShare(): Unit = runBlocking {
  val db = database()
  try {
   val repository = FinanceRepository(db)
   repository.workspace.cash(50_000_000)
   val month = "2027-01"
   val template = bill(month = month).copy(
    name = "Renta", householdTotalMinor = 40_000_000, ownPercent = "50")
   repository.planning.execute(F06Command.SaveTemplate("shared-rent", template))
   repository.planning.execute(F06Command.OpenPeriod("open-shared", month))
   val expected = repository.planning.snapshot(month).occurrences.single()
   assertEquals(20_000_000L, expected.pendingMinor)
   assertEquals(40_000_000L, expected.paymentPendingMinor)
   repository.planning.execute(F06Command.Pay("rent-part", expected.id, "cash", 10_000_000, "$month-31"))
   val partial = repository.planning.snapshot(month).occurrences.single()
   assertEquals(30_000_000L, partial.paymentPendingMinor)
   assertEquals(15_000_000L, partial.pendingMinor)
   repository.planning.execute(F06Command.Pay("rent-rest", expected.id, "cash", 30_000_000, "$month-31"))
   val snapshot = repository.planning.snapshot(month)
   val paid = snapshot.occurrences.single()
   assertEquals(0L, paid.paymentPendingMinor)
   assertEquals(20_000_000L, paid.paidMinor)
   assertEquals(20_000_000L, snapshot.comparisons.single().actualMinor)
   assertEquals(10_000_000L, repository.workspace.snapshot().accounts.single().balanceMinor)
   val saved = repository.exportFixture()
   repository.restoreFixture(saved)
   assertEquals(paid, repository.planning.snapshot(month).occurrences.single())
  } finally { db.close() }
 }
 @Test fun currentMonthRevisionUpdatesAnUnpaidBillEvenAfterItsOriginalDueDate(): Unit = runBlocking {
  val db = database()
  try {
   val repository = FinanceRepository(db)
   repository.workspace.cash()
   val month = java.time.YearMonth.now(BusinessTime.zone)
   val template = bill(month = month.toString()).copy(name = "CarWash", day = 1)
   repository.planning.execute(F06Command.SaveTemplate("original", template))
   repository.planning.execute(F06Command.OpenPeriod("open", month.toString()))
   val original = repository.planning.snapshot(month.toString()).occurrences.single()
   assertEquals(month.atDay(1).toString(), original.dueDate)
   repository.planning.execute(F06Command.SaveTemplate("revised", template.copy(version = 2, group = "Personal", day = 30)))
   val revised = repository.planning.snapshot(month.toString()).occurrences.single()
   assertEquals(original.id, revised.id)
   assertEquals("Personal", revised.group)
   assertEquals(month.atDay(minOf(30, month.lengthOfMonth())).toString(), revised.dueDate)
   assertEquals(original.pendingMinor, revised.pendingMinor)
   assertTrue(repository.workspace.snapshot().operations.isEmpty())
  } finally { db.close() }
 }
 @Test fun oracleT05ReceiptPostsOnceAndReducesExpectedSeparately():Unit = runBlocking {
  val db=database(); val r=FinanceRepository(db); r.workspace.cash(10000000)
  r.planning.execute(F06Command.SaveSalary("salary",SalaryVersion("salary-v1","2027-01",45000000,Currency.CRC,"cash")))
  r.planning.execute(F06Command.OpenPeriod("open","2027-01")); assertEquals(10000000,r.workspace.snapshot().accounts.single().balanceMinor)
  val occurrence=r.planning.snapshot().occurrences.single(); val initialPending=occurrence.pendingMinor
  val command=F06Command.Pay("receipt",occurrence.id,"cash",45000000,"2027-01-29")
  r.planning.execute(command); r.planning.execute(command)
  assertEquals(55000000,r.workspace.snapshot().accounts.single().balanceMinor); assertEquals(45000000,initialPending-r.planning.snapshot().occurrences.single().pendingMinor)
  assertEquals(0,r.planning.snapshot().occurrences.single().pendingMinor); assertEquals(1,r.workspace.snapshot().operations.size)
  // The equal-and-opposite A/I delta preserves the generic 220,000 reference margin.
  assertEquals(22000000,22000000+45000000-(initialPending-r.planning.snapshot().occurrences.single().pendingMinor))
  db.close()
 }
 @Test fun oracleT06PartialBillAndT16RepeatedFebruaryOpen():Unit = runBlocking {
  val db=database(); val r=FinanceRepository(db); r.workspace.cash();r.planning.execute(F06Command.SaveTemplate("template",bill(month="2027-02")))
  repeat(3) { r.planning.execute(F06Command.OpenPeriod("open$it","2027-02")) }
  var o=r.planning.snapshot().occurrences.single();assertEquals("2027-02-28",o.dueDate)
  r.planning.execute(F06Command.ConfirmAmount("confirm",o.id,2500000));r.planning.execute(F06Command.Pay("partial",o.id,"cash",1000000,"2027-02-28"));assertEquals(1500000,r.planning.snapshot().occurrences.single().pendingMinor)
  r.planning.execute(F06Command.Pay("remaining",o.id,"cash",1500000,"2027-02-28"));o=r.planning.snapshot().occurrences.single();assertEquals(0,o.pendingMinor);assertEquals(47500000,r.workspace.snapshot().accounts.single().balanceMinor)
  assertEquals(22000000,22000000-2500000+(2500000-o.pendingMinor));assertEquals(2500000,db.ledger().transactions().sumOf { it.expenseMinor });db.close()
 }
 @Test fun oracleT07AnnualReserveReleaseKeepsBillPendingAndPaymentConsumesOnce():Unit = runBlocking {
  val db=database();val r=FinanceRepository(db);r.workspace.cash(20000000)
  val t=bill(minor=12000000,month="2027-01",frequency=Recurrence.ANNUAL).copy(purposeId="annual",monthlyContributionMinor=1000000)
  r.planning.execute(F06Command.SaveTemplate("template",t));r.planning.execute(F06Command.FundPurpose("fund",t.id,"cash",12000000));r.planning.execute(F06Command.OpenPeriod("open","2027-01"))
  var a=r.workspace.snapshot().accounts.single();assertEquals(8000000,a.unassignedMinor);assertEquals(12000000,r.planning.snapshot().occurrences.single().pendingMinor)
  r.workspace.execute(F05Command.FundReserve("release","cash","annual","Annual",-12000000));a=r.workspace.snapshot().accounts.single();assertEquals(20000000,a.unassignedMinor);assertEquals(12000000,r.planning.snapshot().occurrences.single().pendingMinor)
  r.planning.execute(F06Command.Pay("pay","bill:2027-01:0","cash",12000000,"2027-01-31"));a=r.workspace.snapshot().accounts.single();assertEquals(8000000,a.unassignedMinor);assertEquals(0,a.reservedMinor);assertEquals(0,r.planning.snapshot().occurrences.single().pendingMinor);assertEquals(12000000,db.ledger().transactions().sumOf { it.expenseMinor });db.close()
 }
 @Test fun householdHistoryRevisionCopyAndFundingKeepUniqueObligation():Unit = runBlocking {
  val db=database();val r=FinanceRepository(db);r.workspace.cash();val t=bill(minor=101,month="2027-01").copy(householdTotalMinor=101,ownPercent="50")
  r.planning.execute(F06Command.SaveTemplate("template",t));r.planning.execute(F06Command.OpenPeriod("jan","2027-01"));r.planning.execute(F06Command.Pay("paid","bill:2027-01:0","cash",20,"2027-01-31"))
  r.planning.execute(F06Command.SaveTemplate("revision",t.copy(version=2,effectiveMonth="2027-02",ownPercent="75",reason="Future own share")));r.planning.execute(F06Command.OpenPeriod("feb","2027-02"))
  assertEquals(51,r.planning.snapshot().occurrences.first { it.nominalMonth=="2027-01" }.expectedMinor);assertEquals(76,r.planning.snapshot().occurrences.first { it.nominalMonth=="2027-02" }.expectedMinor)
  r.planning.execute(F06Command.AssignFundingPeriod("finance","bill:2027-01:0","2027-02"));assertEquals(2,r.planning.snapshot().occurrences.size)
  val plan=PeriodPlan("2027-01",1,listOf(PlanRow("food","Food","Needs",PlanNature.VARIABLE_NEED,30000,Currency.CRC,"cash")),9000)
  r.planning.execute(F06Command.SavePlan("plan",plan));r.planning.execute(F06Command.CopyPeriod("copy","2027-01","2027-03"));r.planning.execute(F06Command.CopyPeriod("copy","2027-01","2027-03"));assertEquals(9000,r.planning.snapshot().plans.first { it.month=="2027-03" }.targetMinor);assertEquals(1,r.workspace.snapshot().operations.size);db.close()
 }
 @Test fun linkedPaymentRefundVoidAndRepeatedCorrectionStayLiveAndAtomic():Unit = runBlocking {
  val db=database();val r=FinanceRepository(db);r.workspace.cash();r.planning.execute(F06Command.SaveTemplate("t",bill(minor=1000)));r.planning.execute(F06Command.OpenPeriod("o","2027-01"));r.planning.execute(F06Command.Pay("p","bill:2027-01:0","cash",500,"2027-01-31"))
  r.workspace.execute(F05Command.Refund("refund","p:ledger",100,"2027-02-01","Returned"));assertEquals(600,r.planning.snapshot().occurrences.single().pendingMinor)
  r.planning.execute(F06Command.Pay("p2","bill:2027-01:0","cash",200,"2027-01-31"))
  r.workspace.execute(F05Command.Correct("c1","p2:ledger",F05Command.Record("c1:replacement","cash","EXPENSE",300,"2027-01-31","Service","Service"),"Correct amount"))
  r.workspace.execute(F05Command.Correct("c2","c1:replacement",F05Command.Record("c2:replacement","cash","EXPENSE",200,"2027-01-31","Service","Service"),"Correct again"));assertEquals(400,r.planning.snapshot().occurrences.single().pendingMinor)
  val before=r.exportFixture();fails { r.workspace.execute(F05Command.Correct("bad","c2:replacement",F05Command.Record("bad:replacement","cash","INCOME",200,"2027-01-31","Service","Service"),"Wrong kind")) };assertEquals(before,r.exportFixture())
  r.workspace.execute(F05Command.Void("void","c2:replacement","Cancelled"));assertEquals(600,r.planning.snapshot().occurrences.single().pendingMinor);db.close()
 }
 @Test fun resetAndThreeMonthAccumulationAreAllowanceOnlyAndCategoryExclusive():Unit = runBlocking {
  val db=database();val r=FinanceRepository(db);r.workspace.cash()
  for(month in listOf("2027-01","2027-02","2027-03")) r.planning.execute(F06Command.SavePlan("plan:$month",PeriodPlan(month,1,listOf(PlanRow("food","Food","Needs",PlanNature.VARIABLE_NEED,100,Currency.CRC,accumulate=true),PlanRow("free","Fun","Free",PlanNature.FREE,100,Currency.CRC)))))
  val rows=r.planning.snapshot("2027-03").comparisons;assertEquals(300,rows.first { it.group=="Needs" }.budgetMinor);assertEquals(100,rows.first { it.group=="Free" }.budgetMinor);assertEquals(50000000,r.workspace.snapshot().accounts.single().balanceMinor)
  fails { r.planning.execute(F06Command.SavePlan("ambiguous",PeriodPlan("2027-04",1,listOf(PlanRow("a","Food","One",PlanNature.FREE,100,Currency.CRC),PlanRow("b","Food","Two",PlanNature.VARIABLE_NEED,100,Currency.CRC))))) };db.close()
 }
 @Test fun fixtureRoundTripIncludesCompleteHistoryAndCorruptionRollsBack():Unit = runBlocking {
  val db=database();val r=FinanceRepository(db);r.workspace.cash();r.planning.execute(F06Command.SaveTemplate("t",bill(minor=1000)));r.planning.execute(F06Command.OpenPeriod("o","2027-01"));r.planning.execute(F06Command.Pay("p","bill:2027-01:0","cash",500,"2027-01-31"));r.planning.execute(F06Command.SetReminders("rem",ReminderPreference(true,3,10)))
  val fixture=r.exportFixture();r.restoreFixture(fixture);assertEquals(fixture,r.exportFixture());val corrupt=JSONObject(fixture);corrupt.getJSONObject("workspace").getJSONObject("f06").getJSONArray("occurrences").getJSONObject(0).put("confirmedMinor",1)
  fails { r.restoreFixture(corrupt.toString()) };assertEquals(fixture,r.exportFixture());corrupt.getJSONObject("workspace").getJSONObject("f06").put("payloadVersion",99);fails { r.restoreFixture(corrupt.toString()) };assertEquals(fixture,r.exportFixture());db.close()
 }
 @Test fun financedAnnualPartialPaymentConsumesReserveOnceAndRollbackRetryIsDurable():Unit = runBlocking {
  val name="f06-durable.db";context.deleteDatabase(name)
  fun open()=Room.databaseBuilder(context,WealthDatabase::class.java,name).build()
  var db=open();var r=FinanceRepository(db);r.workspace.cash(1000000)
  val t=bill(minor=10000).copy(frequency=Recurrence.ANNUAL,purposeId="annual",monthlyContributionMinor=834,householdTotalMinor=10000,ownPercent="60")
  r.planning.execute(F06Command.SaveTemplate("annual",t));r.planning.execute(F06Command.FundPurpose("fund",t.id,"cash",6000));r.planning.execute(F06Command.OpenPeriod("open","2027-01"))
  val pay=F06Command.Pay("pay","bill:2027-01:0","cash",6000,"2027-01-31");r.planning.execute(pay)
  assertEquals(994000,r.workspace.snapshot().accounts.single().balanceMinor);assertEquals(0,r.workspace.snapshot().accounts.single().reservedMinor);assertEquals(6000,db.ledger().transactions().sumOf { it.expenseMinor })
  db.close();db=open();r=FinanceRepository(db);r.planning.execute(pay);r.planning.execute(F06Command.OpenPeriod("open","2027-01"));assertEquals(1,r.planning.snapshot().occurrences.size);assertEquals(1,r.workspace.snapshot().operations.size)
  assertEquals(4000L,r.planning.snapshot().occurrences.single().paymentPendingMinor)
  val before=r.exportFixture();fails { r.planning.execute(pay.copy(minor=1)) };fails { r.planning.execute(pay.copy(requestId="overpay",minor=4001)) };assertEquals(before,r.exportFixture());db.close();context.deleteDatabase(name);Unit
 }
 @Test fun cardChargeForBillSeparatesEconomicConsumptionFromBookedCashAndOwnFxReversal():Unit = runBlocking {
  val db=database();val r=FinanceRepository(db);r.workspace.cash(1000000);r.workspace.cash(0,"card",Currency.CRC,"LIABILITY");r.workspace.cash(0,"usd",Currency.USD)
  r.planning.execute(F06Command.SaveTemplate("t",bill(minor=1000).copy(suggestedAccountId="card",nature=PlanNature.SUBSCRIPTION)));r.planning.execute(F06Command.OpenPeriod("open","2027-01"))
  r.planning.execute(F06Command.SavePlan("plan",PeriodPlan("2027-01",1,listOf(PlanRow("food","Service bill","Needs",PlanNature.VARIABLE_NEED,1000,Currency.CRC)))))
  r.planning.execute(F06Command.Pay("charge","bill:2027-01:0","card",1000,"2027-01-15"))
  assertEquals(1000000L,r.workspace.snapshot().accounts.first { it.id=="cash" }.balanceMinor)
  assertEquals(-1000L,r.workspace.snapshot().accounts.first { it.id=="card" }.balanceMinor)
  r.restoreFixture(r.exportFixture())
  var snapshot=r.planning.snapshot("2027-01");assertEquals(0,snapshot.occurrences.single().pendingMinor);assertEquals(0,snapshot.comparisons.first { it.group=="Needs" }.consumptionMinor);assertEquals(0,snapshot.comparisons.first { it.group=="Essentials" }.actualMinor)
  r.workspace.execute(F05Command.Transfer("settle","cash","card",400,400,"2027-01-31",bookedDate="2027-02-01",creditAllocations=listOf(CreditPaymentAllocation("charge:ledger",400))))
  assertEquals(0,r.planning.snapshot("2027-01").comparisons.first { it.group=="Essentials" }.actualMinor)
  assertEquals(400,r.planning.snapshot("2027-02").comparisons.first { it.group=="Essentials" }.actualMinor);assertEquals(400,r.planning.snapshot("2027-02").occurrences.single().cashPaidMinor)
  r.workspace.execute(F05Command.Refund("refund","charge:ledger",100,"2027-02-02","Return"));assertEquals(100,r.planning.snapshot().occurrences.single().pendingMinor)
  r.workspace.execute(F05Command.Void("void-settle","settle","Reverse settlement"));assertEquals(0,r.planning.snapshot("2027-02").targetBaseMinor[Currency.CRC] ?: 0L)
  r.workspace.execute(F05Command.Transfer("fx","cash","usd",50000,100,"2027-03-01",feeMinor=500,rate="0.002",rateSource="Actual amounts"));assertEquals(-500,r.planning.snapshot("2027-03").targetBaseMinor[Currency.CRC] ?: 0L)
  r.workspace.execute(F05Command.Void("fx-void","fx","Transfer cancelled"));val target=r.planning.snapshot("2027-03").targetBaseMinor;assertEquals(0,target[Currency.CRC] ?: 0L);assertEquals(0,target[Currency.USD] ?: 0L);db.close()
 }
 @Test fun fullPlanningFixtureRestoresRevisionsIncomeAnnualHouseholdAndSettings():Unit = runBlocking {
  val db=database();val r=FinanceRepository(db);r.workspace.cash()
  r.planning.execute(F06Command.SaveSalary("salary",SalaryVersion("v1","2027-01",123400,Currency.CRC,"cash",reason="Initial")))
  r.planning.execute(F06Command.SaveSalary("salary2",SalaryVersion("v2","2027-02",135700,Currency.CRC,"cash",reason="Future",scheduleDay=25)))
  val t=bill(minor=12000).copy(frequency=Recurrence.ANNUAL,purposeId="annual",monthlyContributionMinor=1000,householdTotalMinor=12000,ownFixedMinor=6000)
  r.planning.execute(F06Command.SaveTemplate("t",t));r.planning.execute(F06Command.FundPurpose("fund",t.id,"cash",3000));r.planning.execute(F06Command.OpenPeriod("open","2027-01"));r.planning.execute(F06Command.Pay("partial","bill:2027-01:0","cash",2000,"2027-01-31"))
  val plan=PeriodPlan("2027-01",1,listOf(PlanRow("travel","Travel","Custom",PlanNature.FREE,45600,Currency.CRC)),12300,reason="Initial")
  r.planning.execute(F06Command.SavePlan("plan",plan));r.planning.execute(F06Command.SavePlan("plan2",plan.copy(version=2,targetMinor=14200,reason="Revision")));r.planning.execute(F06Command.SetReminders("rem",ReminderPreference(true,31,7)))
  val fixture=r.exportFixture();val before=r.planning.snapshot("2027-01");r.restoreFixture(fixture);assertEquals(before,r.planning.snapshot("2027-01"));assertEquals(fixture,r.exportFixture())
  val bad=JSONObject(fixture);bad.getJSONObject("workspace").getJSONObject("f06").getJSONArray("plans").getJSONObject(0).getJSONArray("rows").getJSONObject(0).put("suggestedAccountId","missing")
  fails { r.restoreFixture(bad.toString()) };assertEquals(fixture,r.exportFixture());db.close()
 }

 @Test fun repeatedCreditSettlementsAndRefundsPreserveExactCategoryMinorUnits():Unit = runBlocking {
  val db=database();val r=FinanceRepository(db);r.workspace.cash(1000000);r.workspace.cash(0,"card",Currency.CRC,"LIABILITY")
  r.planning.execute(F06Command.SavePlan("plan",PeriodPlan("2027-01",1,listOf(PlanRow("first","Category A","Needs",PlanNature.VARIABLE_NEED,100,Currency.CRC),PlanRow("second","Category B","Free",PlanNature.FREE,100,Currency.CRC)))))
  r.workspace.execute(F05Command.Record("split","card","CREDIT_PURCHASE",100,"2027-01-01","Merchant","Split",splits=listOf(ExpenseSplit("Category A",33),ExpenseSplit("Category B",67))))
  repeat(10) { index -> r.workspace.execute(F05Command.Transfer("payment:$index","cash","card",10,10,"2027-01-02",creditAllocations=listOf(CreditPaymentAllocation("split",10)))) }
  val snapshot=r.planning.snapshot("2027-01");assertEquals(33,snapshot.comparisons.first { it.group=="Needs" }.actualMinor);assertEquals(67,snapshot.comparisons.first { it.group=="Free" }.actualMinor)
  assertEquals(33,snapshot.comparisons.first { it.group=="Needs" }.consumptionMinor);assertEquals(67,snapshot.comparisons.first { it.group=="Free" }.consumptionMinor)
  // Cash refunds of another split use the same cumulative allocation rule, including partial pennies.
  r.workspace.execute(F05Command.Record("cashsplit","cash","EXPENSE",100,"2027-02-01","Merchant","Split",splits=listOf(ExpenseSplit("Category A",33),ExpenseSplit("Category B",67))))
  r.planning.execute(F06Command.CopyPeriod("copy","2027-01","2027-02"))
  repeat(10) { index -> r.workspace.execute(F05Command.Refund("refund:$index","cashsplit",10,"2027-02-02","Returned part")) }
  val refunded=r.planning.snapshot("2027-02");assertEquals(0,refunded.comparisons.first { it.group=="Needs" }.actualMinor);assertEquals(0,refunded.comparisons.first { it.group=="Free" }.actualMinor);db.close()
 }

}


