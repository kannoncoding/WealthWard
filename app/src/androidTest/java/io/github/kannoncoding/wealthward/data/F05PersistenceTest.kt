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
class F05PersistenceTest {
 private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
 private val day="2026-11-01"
 private fun database() = Room.inMemoryDatabaseBuilder(context,WealthDatabase::class.java).build()
 private suspend fun F05Repository.cash(id:String="a",amount:Long=50000000,currency:Currency=Currency.CRC,kind:String="CASH",product:String=id) = execute(F05Command.CreateAccount("create:$id",id,product,"Account $id",currency,kind,amount,"2026-01-01"))
 private suspend fun fails(block:suspend ()->Unit) { try { block(); fail("Invalid command must fail") } catch (_:DomainFailure) {} }
 @Test fun accountEditConvertsAnUntouchedOpeningAndPreservesLaterHistory(): Unit = runBlocking {
  val db = database()
  try {
   val repo = FinanceRepository(db)
   val workspace = repo.workspace
   workspace.cash(kind = "LIABILITY", amount = -10_001)
   val change = F05Command.EditAccount("edit", "a", "Tarjeta USD", Currency.USD, "LIABILITY",
    "2026-02-01", 50_000, "0.0025", Currency.CRC, -10_001)
   fails { workspace.execute(change.copy(expectedBalanceMinor = -10_000)) }
   fails { workspace.execute(change.copy(conversionRate = "0")) }
   workspace.execute(change)
   workspace.execute(change)
   val converted = workspace.snapshot().accounts.single()
   assertEquals("Tarjeta USD", converted.name)
   assertEquals(Currency.USD, converted.currency)
   assertEquals(-25L, converted.balanceMinor)
   assertEquals(50_000L, converted.creditLimitMinor)
   assertTrue(db.ledger().transactions().isEmpty())
   val audit = workspace.snapshot().audit.single { it.requestId == "edit" }
   assertEquals("a", audit.subjectId)
   assertEquals("0.0025", JSONObject(audit.reason).getString("manualRate"))

   workspace.execute(F05Command.Record("purchase", "a", "CREDIT_PURCHASE", 500, day, "Merchant", "Other"))
   val metadataEdit = change.copy(requestId = "metadata", name = "Tarjeta", openingDate = day,
    creditLimitMinor = 55_000, conversionRate = null, expectedCurrency = Currency.USD, expectedBalanceMinor = -525)
   val postings = db.ledger().postings()
   val operations = workspace.snapshot().operations
   fails { workspace.execute(metadataEdit.copy(currency = Currency.CRC, conversionRate = "400")) }
   fails { workspace.execute(metadataEdit.copy(kind = "CASH", creditLimitMinor = 0)) }
   fails { workspace.execute(metadataEdit.copy(openingDate = "2026-11-02")) }
   workspace.execute(metadataEdit)
   val edited = workspace.snapshot().accounts.single()
   assertEquals("Tarjeta", edited.name)
   assertEquals(day, edited.openingDate)
   assertEquals(-525L, edited.balanceMinor)
   assertEquals(55_000L, edited.creditLimitMinor)
   assertEquals(postings, db.ledger().postings())
   assertEquals(operations, workspace.snapshot().operations)
   repo.restoreFixture(repo.exportFixture())
   assertEquals(edited, workspace.snapshot().accounts.single())

   workspace.cash(id = "unused", amount = 500)
   val physical = F05Command.EditAccount("to-physical", "unused", "Efectivo", Currency.CRC, "PHYSICAL_CASH",
    "2026-01-01", 0, expectedCurrency = Currency.CRC, expectedBalanceMinor = 500)
   workspace.execute(physical)
   assertTrue(workspace.snapshot().accounts.single { it.id == "unused" }.productId.startsWith("cash-"))
   workspace.execute(physical.copy(requestId = "to-deposit", kind = "CASH", name = "Depósito"))
   val deposit = workspace.snapshot().accounts.single { it.id == "unused" }
   assertFalse(deposit.productId.startsWith("cash-"))
   assertEquals(500L, deposit.balanceMinor)
  } finally { db.close() }
 }

 @Test fun alreadyDeductedExpenseKeepsBalanceAndRestoresWithoutPostings(): Unit = runBlocking {
  val db = database()
  try {
   val repo = FinanceRepository(db)
   repo.workspace.cash(amount = 100_000L)
   val expense = F05Command.Record("food", "a", "EXPENSE", 12_500L, day, "Supermercado", "Comida", previouslyDeducted = true)
   repo.workspace.execute(expense)
   repo.workspace.execute(expense)
   fails { repo.workspace.execute(expense.copy(requestId = "invalid-zero", amountMinor = 0, previouslyDeducted = false)) }
   repo.workspace.execute(expense.copy(requestId = "zero", amountMinor = 0))
   assertEquals(100_000L, repo.workspace.snapshot().accounts.single().balanceMinor)
   assertTrue(repo.workspace.snapshot().operations.all { it.previouslyDeducted })
   assertTrue(db.ledger().postings().isEmpty())
   repo.restoreFixture(repo.exportFixture())
   repo.workspace.execute(F05Command.Void("void-food", expense.requestId, "Duplicado"))
   assertEquals(100_000L, repo.workspace.snapshot().accounts.single().balanceMinor)
   repo.restoreFixture(repo.exportFixture())
  } finally { db.close() }
 }
 @Test fun oracleT01T02T04T17ReserveExpenseRedistributionAndPartialRefund() = runBlocking {
  val db=database(); val r=FinanceRepository(db).workspace
  r.cash(); r.execute(F05Command.FundReserve("fund","a","essentials","Essentials",46000000))
  var a=r.snapshot().accounts.single(); assertEquals(50000000,a.balanceMinor); assertEquals(46000000,a.reservedMinor); assertEquals(4000000,a.unassignedMinor)
  r.execute(F05Command.Record("expense","a","EXPENSE",1500000,day,"Generic merchant","Food"))
  a=r.snapshot().accounts.single(); assertEquals(48500000,a.balanceMinor); assertEquals(2500000,a.unassignedMinor)
  r.execute(F05Command.Redistribute("move","a","essentials","transport","Transport",2000000))
  a=r.snapshot().accounts.single(); assertEquals(48500000,a.balanceMinor); assertEquals(46000000,a.reservedMinor)
  r.execute(F05Command.Refund("refund","expense",500000,day,"Partial returned item"))
  assertEquals(1000000,db.ledger().transactions().sumOf { it.expenseMinor })
  assertEquals(49000000,r.snapshot().accounts.single().balanceMinor)
  db.close()
 }
 @Test fun oracleT03OwnTransferWithdrawalConservesEachCurrencyAndCountsNoPurchase() = runBlocking {
  val db=database(); val r=FinanceRepository(db).workspace
  r.cash();r.cash("b",0);r.cash("cash",0)
  r.execute(F05Command.Transfer("transfer","a","b",5000000,5000000,day))
  r.execute(F05Command.Transfer("withdraw","a","cash",1000000,1000000,day))
  assertEquals(50000000,r.snapshot().accounts.sumOf { it.balanceMinor })
  assertEquals(0,db.ledger().transactions().sumOf { it.expenseMinor })
  assertEquals(1000000,r.snapshot().accounts.first { it.id=="cash" }.balanceMinor)
  db.close()
 }
 @Test fun oracleT10ComparisonDoesNotWriteLedgerUntilReasonedAdjustment() = runBlocking {
  val db=database(); val r=FinanceRepository(db).workspace; r.cash()
  r.execute(F05Command.Reconcile("compare","a",day,"BOOKED",49750000,emptyList()))
  assertEquals(-250000,r.snapshot().reconciliations.single().differenceMinor)
  assertEquals(50000000,r.snapshot().accounts.single().balanceMinor)
  assertTrue(db.ledger().postings().isEmpty())
  fails { r.execute(F05Command.ResolveDifference("bad","compare","")) }
  r.execute(F05Command.ResolveDifference("resolve","compare","Verified omitted bank charge"))
  assertEquals(49750000,r.snapshot().accounts.single().balanceMinor)
  assertEquals(0,db.ledger().transactions().sumOf { it.expenseMinor })
  assertEquals("ADJUSTED",r.snapshot().reconciliations.single().status)
  db.close()
 }
 @Test fun durableIdempotencyDistinguishesRetriesFromLegitimateIdenticalPurchases() = runBlocking {
  val name="f05-idempotency.db";context.deleteDatabase(name)
  fun open()=Room.databaseBuilder(context,WealthDatabase::class.java,name).build()
  var db=open();var r=FinanceRepository(db).workspace;r.cash()
  val purchase=F05Command.Record("request","a","EXPENSE",100,day,"Merchant","Food")
  r.execute(purchase);r.execute(purchase);db.close();db=open();r=FinanceRepository(db).workspace
  r.execute(purchase);assertEquals(1,r.snapshot().operations.size)
  fails { r.execute(purchase.copy(amountMinor=101)) }
  r.execute(purchase.copy(requestId="legitimate"));assertEquals(2,r.snapshot().operations.size)
  assertEquals(49999800,r.snapshot().accounts.single().balanceMinor)
  db.close();context.deleteDatabase(name);Unit
 }
 @Test fun invalidSplitsAndSecondLegFailureAreAtomicAndDoNotConsumeRequestKey() = runBlocking {
  val db=database(); val r=FinanceRepository(db).workspace;r.cash();r.cash("limit",Long.MAX_VALUE)
  val before=FinanceRepository(db).exportFixture()
  fails { r.execute(F05Command.Record("split","a","EXPENSE",100,day,"Merchant","Food",splits=listOf(ExpenseSplit("Food",99)))) }
  fails { r.execute(F05Command.Transfer("overflow","a","limit",100,100,day)) }
  assertEquals(before,FinanceRepository(db).exportFixture())
  r.execute(F05Command.Record("split","a","EXPENSE",100,day,"Merchant","Food",splits=listOf(ExpenseSplit("Food",100))))
  db.close()
 }
 @Test fun correctionVoidRefundRetainEvidenceAndInvalidateReconciledCut() = runBlocking {
  val db=database();val r=FinanceRepository(db).workspace;r.cash()
  r.execute(F05Command.Record("purchase","a","EXPENSE",1000,day,"Merchant","Food"))
  r.execute(F05Command.Reconcile("rec","a",day,"BOOKED",49999000,listOf("purchase")))
  r.execute(F05Command.Correct("edit","purchase",F05Command.Record("edit:replacement","a","EXPENSE",900,day,"Correct merchant","Food"),"Receipt correction"))
  assertTrue(r.snapshot().operations.first { it.id=="purchase" }.voided)
  assertEquals("INVALIDATED",r.snapshot().reconciliations.single().status)
  r.execute(F05Command.Void("void","edit:replacement","Duplicate receipt"))
  assertEquals(50000000,r.snapshot().accounts.single().balanceMinor)
  fails { r.execute(F05Command.Refund("bad","purchase",1,day,"Voided")) }
  assertEquals(3,r.snapshot().audit.count { it.action in setOf("Correct","Void","Record") })
  assertEquals(4,db.ledger().transactions().size)
  db.close()
 }
 @Test fun insufficientReserveAllowsRealExpenseButRejectsNewFundingAndRefundRestoresOnlyConsumedMoney() = runBlocking {
  val db=database();val r=FinanceRepository(db).workspace;r.cash(amount=1000)
  r.execute(F05Command.FundReserve("fund","a","food","Food",500))
  r.execute(F05Command.Record("buy","a","EXPENSE",1500,day,"Merchant","Food",splits=listOf(ExpenseSplit("Food",1500,"food"))))
  assertEquals(-500,r.snapshot().accounts.single().unassignedMinor)
  fails { r.execute(F05Command.FundReserve("extra","a","food","Food",1)) }
  r.execute(F05Command.Refund("refund","buy",1500,day,"Returned"))
  val a=r.snapshot().accounts.single();assertEquals(1000,a.balanceMinor);assertEquals(500,a.reservedMinor)
  fails { r.execute(F05Command.Refund("excess","buy",1,day,"Excess")) }
  r.execute(F05Command.FundReserve("release","a","food","Food",-500));assertEquals(0,r.snapshot().accounts.single().reservedMinor)
  db.close()
 }
 @Test fun purposeTransferMovesActualCashAndFundingTogetherAndFxUsesActualNativeAmountsSeparateFee() = runBlocking {
  val db=database();val r=FinanceRepository(db).workspace;r.cash();r.cash("b",0);r.cash("usd",0,Currency.USD)
  r.execute(F05Command.FundReserve("fund","a","travel","Travel",100000))
  r.execute(F05Command.Transfer("move","a","b",50000,50000,day,purposeId="travel"))
  assertEquals(50000,r.snapshot().accounts.first { it.id=="b" }.reservedMinor)
  r.execute(F05Command.Transfer("fx","a","usd",50000,100,day,feeMinor=500,rate="0.002",rateSource="Explicit generic quote"))
  assertEquals(100,r.snapshot().accounts.first { it.id=="usd" }.balanceMinor)
  assertEquals(49899500,r.snapshot().accounts.first { it.id=="a" }.balanceMinor)
  assertEquals(500,db.ledger().transactions().sumOf { it.expenseMinor })
  val restored=FinanceRepository(db).exportFixture();FinanceRepository(db).restoreFixture(restored);assertEquals(restored,FinanceRepository(db).exportFixture())
  db.close()
 }
 @Test fun pendingConfirmationReplacesAvailableImpactWithoutDoubleChargeAndCreditSettlementAddsNoExpense() = runBlocking {
  val db=database();val r=FinanceRepository(db).workspace;r.cash();r.cash("card",0,Currency.CRC,"LIABILITY","card")
  r.execute(F05Command.CreateHold("hold","card",1000,"Merchant",day))
  assertEquals(0,r.snapshot().accounts.first { it.id=="card" }.balanceMinor)
  assertEquals(1000,r.snapshot().accounts.first { it.id=="card" }.heldMinor)
  r.execute(F05Command.ConfirmHold("confirm","hold","Food",day));r.execute(F05Command.ConfirmHold("confirm","hold","Food",day))
  assertEquals(-1000,r.snapshot().accounts.first { it.id=="card" }.balanceMinor);assertEquals(0,r.snapshot().accounts.first { it.id=="card" }.heldMinor)
  fails { r.execute(F05Command.ConfirmHold("again","hold","Food",day)) }
  r.execute(F05Command.Transfer("pay","a","card",1000,1000,day,creditAllocations=listOf(CreditPaymentAllocation("confirm",1000))))
  assertEquals(0,r.snapshot().accounts.first { it.id=="card" }.balanceMinor)
  assertEquals(1000,db.ledger().transactions().sumOf { it.expenseMinor })
  db.close()
 }
 @Test fun assistedImportRejectsAmbiguityDuplicatesAndBatchFailureThenPreservesRawNotesAndRetry() = runBlocking {
  val db=database();val r=FinanceRepository(db).workspace;r.cash(amount=0);r.cash("b",0)
  val unknown=ImportRow("unknown","Alias","checkbox","Amount in notes 999999",null,null,null)
  assertTrue(r.previewImport(listOf(unknown)).size>=3)
  fails { r.execute(F05Command.ImportBatch("ambiguous",listOf(unknown))) }
  val row=ImportRow("row-a","Alias A","Original content","Notes are preserved", "a",1000,day,500,"food","Food",true)
  assertTrue(r.previewImport(listOf(row,row.copy(rowId="alias",confirmedOpeningMinor=2000))).isNotEmpty())
  fails { r.execute(F05Command.ImportBatch("atomic",listOf(row,row.copy(rowId="row-b",accountId="b",confirmedReserveMinor=2000)))) }
  assertEquals(0,r.snapshot().accounts.first { it.id=="a" }.balanceMinor)
  val batch=F05Command.ImportBatch("batch",listOf(row));r.execute(batch);r.execute(batch)
  assertEquals(1000,r.snapshot().accounts.first { it.id=="a" }.balanceMinor)
  assertTrue(FinanceRepository(db).exportFixture().contains("Notes are preserved"))
  fails { r.execute(batch.copy(requestId="other-batch")) }
  db.close()
 }
 @Test fun genericProductsAreEightWithTwoNativeLedgersPerCreditCardAndAccountCurrencyRemainsLocked() = runBlocking {
  val db=database();val repo=FinanceRepository(db);repo.seedWorkspace();repo.seedWorkspace()
  val r=repo.workspace;val all=r.snapshot().accounts
  assertEquals(10,all.size);assertEquals(8,all.map { it.productId }.distinct().size)
  assertEquals(4,all.count { it.kind=="LIABILITY" })
  r.execute(F05Command.RenameAccount("rename","deposit-1","Renamed deposit"))
  r.execute(F05Command.ArchiveAccount("archive","deposit-1"))
  fails { r.execute(F05Command.Record("archived","deposit-1","INCOME",1,day,"Origin","Income")) }
  assertEquals(Currency.CRC,r.snapshot().accounts.first { it.id=="deposit-1" }.currency)
  db.close()
 }
 @Test fun fullWorkspaceRestoreAndLegacyVersionTwoCompatibilityAreAtomic() = runBlocking {
  val db=database();val repo=FinanceRepository(db);val r=repo.workspace;r.cash()
  r.execute(F05Command.FundReserve("fund","a","food","Food",1000))
  r.execute(F05Command.Record("buy","a","EXPENSE",200,day,"Merchant","Food",splits=listOf(ExpenseSplit("Food",200,"food"))))
  r.execute(F05Command.Reconcile("rec","a",day,"BOOKED",49999800,listOf("buy")))
  val exported=repo.exportFixture();repo.restoreFixture(exported);assertEquals(exported,repo.exportFixture())
  val corrupt=JSONObject(exported).apply { getJSONObject("workspace").getJSONArray("reserves").getJSONObject(0).put("minor",-1) }.toString()
  fails { repo.restoreFixture(corrupt) };assertEquals(exported,repo.exportFixture())
  val legacy=JSONObject(exported).put("version",2).apply { remove("workspace");remove("requests") }.toString()
  repo.restoreFixture(legacy);assertEquals(49999800,repo.balances().single().balance.minor);assertTrue(r.snapshot().accounts.single().reserves.isEmpty())
  db.close()
 }
 @Test fun exactIndependentT04RedistributionPreservesBankAndT17RestoresFundedPurpose() = runBlocking {
  val db=database();val r=FinanceRepository(db).workspace;r.cash()
  r.execute(F05Command.FundReserve("food","a","food","Food",12000000))
  r.execute(F05Command.FundReserve("transport","a","transport","Transport",2000000))
  r.execute(F05Command.Redistribute("redistribute","a","food","transport","Transport",2000000))
  var a=r.snapshot().accounts.single();assertEquals(50000000,a.balanceMinor);assertEquals(14000000,a.reservedMinor)
  assertEquals(10000000,a.reserves.first { it.purposeId=="food" }.minor);assertEquals(4000000,a.reserves.first { it.purposeId=="transport" }.minor)
  r.execute(F05Command.Record("buy","a","EXPENSE",1500000,day,"Merchant","Food",splits=listOf(ExpenseSplit("Food",1500000,"food"))))
  val reserveAfterPurchase=r.snapshot().accounts.single().reservedMinor
  r.execute(F05Command.Refund("refund","buy",500000,day,"Partial refund"))
  a=r.snapshot().accounts.single();assertEquals(reserveAfterPurchase+500000,a.reservedMinor)
  assertEquals(1000000,db.ledger().transactions().sumOf { it.expenseMinor });db.close()
 }
 @Test fun creditFundingIsConsumedOnlyByLinkedPartialPaymentFromSelectedNativeSourceAndRefundStaysOnCard() = runBlocking {
  val db=database();val repo=FinanceRepository(db);val r=repo.workspace
  r.cash("a",10000);r.cash("b",10000);r.cash("card-crc",0,Currency.CRC,"LIABILITY","card");r.cash("card-usd",0,Currency.USD,"LIABILITY","card")
  r.execute(F05Command.FundReserve("fund","a","food","Food",5000))
  r.execute(F05Command.Record("buy1","card-crc","CREDIT_PURCHASE",3000,day,"Merchant One","Food",fundingAccountId="a",fundingPurposeId="food"))
  r.execute(F05Command.Record("buy2","card-crc","CREDIT_PURCHASE",2000,day,"Merchant Two","Food"))
  assertEquals(5000,r.snapshot().accounts.first { it.id=="a" }.reservedMinor)
  val partial=F05Command.Transfer("partial","a","card-crc",1500,1500,day,creditAllocations=listOf(CreditPaymentAllocation("buy1",1500,"food")))
  r.execute(partial);r.execute(partial)
  assertEquals(3500,r.snapshot().accounts.first { it.id=="a" }.reservedMinor)
  assertEquals(1500,r.snapshot().operations.first { it.id=="buy1" }.settledMinor)
  fails { r.execute(F05Command.Transfer("bad","a","card-usd",100,100,day,rate="1",rateSource="Actual",creditAllocations=listOf(CreditPaymentAllocation("buy1",100,sourceMinor=100)))) }
  r.execute(F05Command.Transfer("rest","b","card-crc",3500,3500,day,creditAllocations=listOf(CreditPaymentAllocation("buy1",1500),CreditPaymentAllocation("buy2",2000))))
  assertEquals(6500,r.snapshot().accounts.first { it.id=="b" }.balanceMinor)
  r.execute(F05Command.Refund("refund","buy1",500,day,"Issuer returned item"))
  assertEquals(500,r.snapshot().accounts.first { it.id=="card-crc" }.balanceMinor)
  assertEquals(8500,r.snapshot().accounts.first { it.id=="a" }.balanceMinor)
  assertEquals(3500,r.snapshot().accounts.first { it.id=="a" }.reservedMinor)
  r.execute(F05Command.Void("reverse-payment","partial","Erroneous partial settlement"))
  assertEquals(10000,r.snapshot().accounts.first { it.id=="a" }.balanceMinor)
  assertEquals(5000,r.snapshot().accounts.first { it.id=="a" }.reservedMinor)
  assertEquals(1500,r.snapshot().operations.first { it.id=="buy1" }.settledMinor)
  val fixture=repo.exportFixture();repo.restoreFixture(fixture);assertEquals(fixture,repo.exportFixture())
  assertEquals(4500,db.ledger().transactions().sumOf { it.expenseMinor });db.close()
 }
 @Test fun bookedDatesHistoricalHoldsAndNegativeAdjustmentsRoundTripWithoutMixingCutoffs() = runBlocking {
  val db=database();val repo=FinanceRepository(db);val r=repo.workspace;r.cash(amount=10000)
  r.execute(F05Command.CreateHold("hold","a",1000,"Merchant","2026-10-01"))
  r.execute(F05Command.ConfirmHold("confirm","hold","Food","2026-10-03",bookedDate="2026-10-04"))
  r.execute(F05Command.Reconcile("historical","a","2026-10-02","AVAILABLE",9000,emptyList(),true))
  assertEquals(9000,r.snapshot().reconciliations.single().recordedMinor)
  r.execute(F05Command.Reconcile("booked","a","2026-10-03","BOOKED",10000,emptyList(),true))
  assertEquals("MATCHED",r.snapshot().reconciliations.last().status)
  r.execute(F05Command.Reconcile("booking-gap","a","2026-10-03","AVAILABLE",9000,emptyList(),true))
  assertEquals(9000,r.snapshot().reconciliations.last().recordedMinor)
  r.execute(F05Command.Reconcile("difference","a","2026-10-04","BOOKED",8500,listOf("confirm")))
  r.execute(F05Command.ResolveDifference("adjust","difference","Verified fee"))
  r.execute(F05Command.Record("backdated","a","INCOME",100,"2026-10-02","Origin","Income",bookedDate="2026-10-03"))
  assertEquals("INVALIDATED",r.snapshot().reconciliations.first { it.id=="booked" }.status)
  val fixture=repo.exportFixture();repo.restoreFixture(fixture);assertEquals(fixture,repo.exportFixture())
  db.close()
 }
 @Test fun transferFeeChildCannotBeReversedOrRefundedIndependentlyAndParentReversalCountsFeeOnce() = runBlocking {
  val db=database();val repo=FinanceRepository(db);val r=repo.workspace;r.cash();r.cash("b",0)
  r.execute(F05Command.Transfer("transfer","a","b",1000,1000,day,feeMinor=100))
  fails { r.execute(F05Command.Void("bad","transfer:fee","Separate fee")) }
  fails { r.execute(F05Command.Refund("bad2","transfer:fee",100,day,"Separate fee")) }
  r.execute(F05Command.Void("reverse","transfer","Wrong destination"))
  assertEquals(50000000,r.snapshot().accounts.first { it.id=="a" }.balanceMinor)
  assertEquals(0,db.ledger().transactions().sumOf { it.expenseMinor })
  assertEquals("b",r.snapshot().operations.first { it.id=="reverse" }.destinationId)
  val fixture=repo.exportFixture();repo.restoreFixture(fixture);assertEquals(fixture,repo.exportFixture());db.close()
 }
 @Test fun exactT04UnassignedFundingPreservesBankAndOpeningDebtPaymentHasNoFictitiousExpense() = runBlocking {
  val db=database();val repo=FinanceRepository(db);val r=repo.workspace;r.cash()
  r.execute(F05Command.FundReserve("fund","a","existing","Existing purposes",46000000))
  r.execute(F05Command.FundReserve("emergency","a","emergency","Emergency",2000000))
  val a=r.snapshot().accounts.single();assertEquals(50000000,a.balanceMinor);assertEquals(48000000,a.reservedMinor);assertEquals(2000000,a.unassignedMinor)
  r.cash("card",-500000,Currency.CRC,"LIABILITY","card")
  val payment=F05Command.Transfer("opening-pay","a","card",100000,100000,day,creditAllocations=listOf(CreditPaymentAllocation("opening:card",100000)))
  r.execute(payment);r.execute(payment)
  assertEquals(-400000,r.snapshot().accounts.first { it.id=="card" }.balanceMinor)
  assertEquals(0,db.ledger().transactions().sumOf { it.expenseMinor })
  fails { r.execute(payment.copy(requestId="too-much",amountMinor=400001,destinationMinor=400001,creditAllocations=listOf(CreditPaymentAllocation("opening:card",400001)))) }
  val fixture=repo.exportFixture();repo.restoreFixture(fixture);assertEquals(fixture,repo.exportFixture());db.close()
 }
}


