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
 @Test fun financedAnnualPaymentConsumesOnlyOwnReserveOnceAndRollbackRetryIsDurable():Unit = runBlocking {
  val name="f06-durable.db";context.deleteDatabase(name)
  fun open()=Room.databaseBuilder(context,WealthDatabase::class.java,name).build()
  var db=open();var r=FinanceRepository(db);r.workspace.cash(1000000)
  val t=bill(minor=10000).copy(frequency=Recurrence.ANNUAL,purposeId="annual",monthlyContributionMinor=834,householdTotalMinor=10000,ownPercent="60")
  r.planning.execute(F06Command.SaveTemplate("annual",t));r.planning.execute(F06Command.FundPurpose("fund",t.id,"cash",6000));r.planning.execute(F06Command.OpenPeriod("open","2027-01"))
  val pay=F06Command.Pay("pay","bill:2027-01:0","cash",6000,"2027-01-31");r.planning.execute(pay)
  assertEquals(994000,r.workspace.snapshot().accounts.single().balanceMinor);assertEquals(0,r.workspace.snapshot().accounts.single().reservedMinor);assertEquals(6000,db.ledger().transactions().sumOf { it.expenseMinor })
  db.close();db=open();r=FinanceRepository(db);r.planning.execute(pay);r.planning.execute(F06Command.OpenPeriod("open","2027-01"));assertEquals(1,r.planning.snapshot().occurrences.size);assertEquals(1,r.workspace.snapshot().operations.size)
  val before=r.exportFixture();fails { r.planning.execute(pay.copy(minor=1)) };fails { r.planning.execute(pay.copy(requestId="overpay",minor=1)) };assertEquals(before,r.exportFixture());db.close();context.deleteDatabase(name);Unit
 }
 @Test fun linkedCreditBillSeparatesEconomicConsumptionFromBookedCashAndOwnFxReversal():Unit = runBlocking {
  val db=database();val r=FinanceRepository(db);r.workspace.cash(1000000);r.workspace.cash(0,"card",Currency.CRC,"LIABILITY");r.workspace.cash(0,"usd",Currency.USD)
  r.planning.execute(F06Command.SaveTemplate("t",bill(minor=1000)));r.planning.execute(F06Command.OpenPeriod("open","2027-01"))
  r.planning.execute(F06Command.SavePlan("plan",PeriodPlan("2027-01",1,listOf(PlanRow("food","Service bill","Needs",PlanNature.VARIABLE_NEED,1000,Currency.CRC)))))
  r.workspace.execute(F05Command.Record("purchase","card","CREDIT_PURCHASE",1000,"2027-01-15","Service","Service bill"))
  r.planning.execute(F06Command.LinkPayment("link","bill:2027-01:0","purchase"))
  var snapshot=r.planning.snapshot("2027-01");assertEquals(0,snapshot.occurrences.single().pendingMinor);assertEquals(0,snapshot.comparisons.first { it.group=="Needs" }.consumptionMinor);assertEquals(0,snapshot.comparisons.first { it.group=="Essentials" }.actualMinor)
  r.workspace.execute(F05Command.Transfer("settle","cash","card",400,400,"2027-01-31",bookedDate="2027-02-01",creditAllocations=listOf(CreditPaymentAllocation("purchase",400))))
  assertEquals(0,r.planning.snapshot("2027-01").comparisons.first { it.group=="Essentials" }.actualMinor)
  assertEquals(400,r.planning.snapshot("2027-02").comparisons.first { it.group=="Essentials" }.actualMinor);assertEquals(400,r.planning.snapshot("2027-02").occurrences.single().cashPaidMinor)
  r.workspace.execute(F05Command.Refund("refund","purchase",100,"2027-02-02","Return"));assertEquals(100,r.planning.snapshot().occurrences.single().pendingMinor)
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


