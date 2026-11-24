package io.github.kannoncoding.wealthward.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kannoncoding.wealthward.data.*
import io.github.kannoncoding.wealthward.domain.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Actual production forms and real Room transactions; no mock planning or payment layer. */
class F06UiTest {
 @get:Rule val ui=createComposeRule()
 private lateinit var db:WealthDatabase
 private lateinit var repo:F06Repository
 private lateinit var ledger:F05Repository
 private val action=mutableStateOf("f06salary")
 private val subject=mutableStateOf("")
 private val month=mutableStateOf("2027-02")
 private val status=mutableStateOf("")
 private val state=mutableStateOf(F06Snapshot())
 private val cash=mutableStateOf(F05Snapshot())
 @Before fun setup() {
  db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,WealthDatabase::class.java).build()
  repo=F06Repository(db); ledger=F05Repository(db)
  runBlocking {
   ledger.execute(F05Command.CreateAccount("setup","a","demo","Cuenta de prueba",Currency.CRC,"CASH",50000000,"2026-01-01"))
   cash.value=ledger.snapshot(); state.value=repo.snapshot(month.value)
  }
  ui.setContent { MaterialTheme {
   val scope=rememberCoroutineScope()
   val values=remember { mutableStateMapOf<String,String>() }
   val drafts=remember { Drafts(values) }; drafts.scope="${action.value}:${subject.value}"
   Column(Modifier.verticalScroll(rememberScrollState())) {
    Text(status.value)
    if(!action.value.startsWith("f06")) F05Form(action.value,subject.value,cash.value,drafts,false,{ status.value=it },{ command -> scope.launch {
     try { ledger.execute(command); state.value=repo.snapshot(month.value); cash.value=ledger.snapshot(); drafts.clear(); action.value=""; status.value="Guardado" }
     catch(e:Exception) { status.value=userError(e) }
    } },{ action.value="" },{ ledger.previewImport(it) })
    else F06Form(action.value,subject.value,month.value,state.value,cash.value,drafts,false,{ status.value=it },{ command -> scope.launch {
     try { repo.execute(command); state.value=repo.snapshot(month.value); cash.value=ledger.snapshot(); drafts.clear(); action.value=""; status.value="Guardado" }
     catch(e:Exception) { status.value=userError(e) }
    } },{ action.value="" })
   }
  } }
 }
 @After fun cleanup() { db.close() }
 private fun text(label:String,value:String) { ui.onNodeWithContentDescription(label).performScrollTo().performTextReplacement(value) }
 private fun click(label:String) { ui.onNodeWithText(label).performScrollTo().performClick() }
 private fun open(next:String,id:String="") { ui.runOnIdle { action.value=next; subject.value=id; status.value="" }; ui.waitForIdle() }
 private fun saved() {
  try { ui.waitUntil(10000) { status.value=="Guardado" } }
  catch(e:Throwable) { throw AssertionError("No se guardó. Estado visible: ${status.value}",e) }
  ui.waitForIdle()
 }
 private fun snapshot()=runBlocking { repo.snapshot(month.value) }
 private fun refresh() { val next=snapshot(); ui.runOnIdle { state.value=next } }
 private fun seedTemplate(day:Int=28,purpose:String?=null) = runBlocking {
  repo.execute(F06Command.SaveTemplate("template",RecurringTemplate("bill",1,"Servicio de prueba","Hogar",PlanNature.FIXED,100000,Currency.CRC,"2027-02",day=day,suggestedAccountId="a",purposeId=purpose,reason="Alta")))
  repo.execute(F06Command.OpenPeriod("open","2027-02"))
 }
 @Test fun t05SalaryExpectationRequiresRealReceiptAndPartialPayment() {
  text("Salario neto","2000"); text("Motivo de la versión","Alta de prueba"); click("Guardar"); saved()
  assertTrue(runBlocking { ledger.snapshot() }.operations.isEmpty())
  runBlocking { repo.execute(F06Command.OpenPeriod("open","2027-02")) }; refresh()
  val occurrence=snapshot().occurrences.single()
  assertEquals("2027-02-26",occurrence.dueDate)
  open("f06pay",occurrence.id)
  text("Importe real recibido o pagado","1500"); text("Fecha real del movimiento","2027-02-26"); click("Guardar")
  ui.onNodeWithText("Confirma el movimiento real",substring=true).assertExists()
  assertTrue(runBlocking { ledger.snapshot() }.operations.isEmpty())
  click("Confirmo que este movimiento realmente ocurrió"); click("Guardar"); saved()
  assertEquals(150000L,snapshot().occurrences.single().paidMinor)
  assertEquals(50000L,snapshot().occurrences.single().pendingMinor)
  assertEquals(1,runBlocking { ledger.snapshot() }.operations.size)
 }
 @Test fun t06ConfirmActualThenPayPartAndRestWithoutDuplicateExpense() {
  seedTemplate(); refresh(); val id=snapshot().occurrences.single().id
  open("f06confirm",id); text("Importe real total","1200"); click("Guardar"); saved()
  open("f06pay",id); text("Importe real recibido o pagado","400"); text("Fecha real del movimiento","2027-02-28"); click("Confirmo que este movimiento realmente ocurrió"); click("Guardar"); saved()
  assertEquals(80000L,snapshot().occurrences.single().pendingMinor)
  open("f06pay",id); text("Fecha real del movimiento","2027-02-28"); click("Confirmo que este movimiento realmente ocurrió"); click("Guardar"); saved()
  assertEquals(0L,snapshot().occurrences.single().pendingMinor)
  assertEquals(2,runBlocking { ledger.snapshot() }.operations.size)
 }
 @Test fun alreadyDeductedClosesBillWithoutPostingAnotherDebit() {
  seedTemplate(); refresh()
  val item = snapshot().occurrences.single()
  open("f06pay",item.id)
  text("Importe real recibido o pagado","0")
  click("Confirmo que este movimiento realmente ocurrió")
  click("Guardar")
  assertEquals(item.pendingMinor,snapshot().occurrences.single().pendingMinor)
  click("Este pago ya se había descontado de la cuenta seleccionada")
  ui.onNodeWithText("Importe a descontar: 0.", substring = true).assertExists()
  click("Guardar"); saved()
  val paid = snapshot().occurrences.single()
  assertEquals(0L,paid.pendingMinor)
  assertTrue(paid.previouslyDeducted)
  assertEquals(0L,paid.cashPaidMinor)
  assertTrue(runBlocking { ledger.snapshot() }.operations.isEmpty())
  assertEquals(50000000L,runBlocking { ledger.snapshot() }.accounts.single().balanceMinor)
 }
 @Test fun t16FebruaryClampsAndRepeatedOpenRetainsHistoryAfterRevision() {
  open("f06template","FIXED")
  text("Nombre de la obligación","Cuota de prueba"); text("Importe propio estimado","1000"); text("Día nominal (1–31)","31"); text("Motivo de esta revisión","Alta de prueba"); click("Guardar"); saved()
  runBlocking { repeat(3) { repo.execute(F06Command.OpenPeriod("open-$it","2027-02")) } }; refresh()
  val item=snapshot().occurrences.single(); assertEquals("2027-02-28",item.dueDate)
  open("f06pay",item.id); text("Importe real recibido o pagado","100"); text("Fecha real del movimiento","2027-02-28"); click("Confirmo que este movimiento realmente ocurrió"); click("Guardar"); saved()
  open("f06template",item.templateId); text("Vigente desde · AAAA-MM","2027-03"); text("Importe propio estimado","2000"); text("Motivo de esta revisión","Revisión futura"); click("Guardar"); saved()
  runBlocking { repo.execute(F06Command.OpenPeriod("march","2027-03")) }
  val rows=snapshot().occurrences
  assertEquals(2,rows.size); assertEquals(100000L,rows.first { it.nominalMonth=="2027-02" }.expectedMinor)
  assertEquals("2027-03-31",rows.first { it.nominalMonth=="2027-03" }.dueDate)
  assertEquals(200000L,rows.first { it.nominalMonth=="2027-03" }.expectedMinor)
 }
 @Test fun t07ProtectedAnnualReserveReleaseThenPaymentConsumesExpenseOnce() {
  runBlocking {
   ledger.execute(F05Command.FundReserve("reserve","a","annual","Reserva anual",100000))
   repo.execute(F06Command.SaveTemplate("annual",RecurringTemplate("annual",1,"Anual de prueba","Anuales",PlanNature.FIXED,100000,Currency.CRC,"2027-02",Recurrence.ANNUAL,28,suggestedAccountId="a",purposeId="annual",reason="Alta")))
   repo.execute(F06Command.OpenPeriod("open","2027-02"))
  }
  refresh(); ui.runOnIdle { cash.value=runBlocking { ledger.snapshot() } }
  val id=snapshot().occurrences.single().id
  open("release"); text("Importe a liberar","1000"); click("Guardar"); saved()
  assertEquals(100000L,snapshot().occurrences.single().pendingMinor)
  assertEquals(0L,runBlocking { ledger.snapshot() }.accounts.single().reservedMinor)
  open("f06pay",id); text("Fecha real del movimiento","2027-02-28"); click("Confirmo que este movimiento realmente ocurrió"); click("Guardar"); saved()
  assertEquals(0L,snapshot().occurrences.single().pendingMinor)
  assertEquals(1,runBlocking { ledger.snapshot() }.operations.size)
  assertEquals(49900000L,runBlocking { ledger.snapshot() }.accounts.single().balanceMinor)
 }
 @Test fun householdOnlyOwnShareIsSavedAndFuturePauseRetainsOriginal() {
  open("f06template","FIXED"); text("Nombre de la obligación","Hogar compartido"); text("Importe propio estimado","1"); click("Es un gasto compartido del hogar")
  text("Total del hogar","3000"); text("Mi importe fijo","1200"); text("Motivo de esta revisión","Mi parte acordada"); click("Guardar"); saved()
  val template=snapshot().templates.single(); assertEquals(120000L,template.minor); assertEquals(300000L,template.householdTotalMinor)
  open("f06template",template.id); text("Vigente desde · AAAA-MM","2027-03"); click("Pausar desde la vigencia elegida"); text("Motivo de esta revisión","Pausa futura"); click("Guardar"); saved()
  runBlocking { repo.execute(F06Command.OpenPeriod("feb","2027-02")); repo.execute(F06Command.OpenPeriod("march","2027-03")) }
  assertEquals(listOf("2027-02"),snapshot().occurrences.map { it.nominalMonth })
 }
 @Test fun budgetRevisionAndCopyKeepInitialPlanAndNeverCopyMoney() {
  open("f06budget")
  text("Motivo de la versión","Presupuesto inicial")
  text("Categoría 1","Alimentos"); text("Presupuesto 1","1000")
  click("Guardar presupuesto"); saved()
  open("f06budget"); text("Presupuesto 1","1200"); text("Motivo de la versión","Revisión explícita"); click("Guardar presupuesto"); saved()
  assertEquals(2,snapshot().plans.size)
  assertEquals(100000L,snapshot().plans.first { it.version==1 }.rows.single().minor)
  assertEquals(120000L,snapshot().plans.first { it.version==2 }.rows.single().minor)
  open("f06target"); text("Monto de la meta","250"); click("Guardar"); saved()
  assertEquals(120000L,snapshot().plans.maxBy { it.version }.rows.single().minor)
  open("f06copy"); text("Periodo a preparar","2027-03"); click("Guardar"); saved()
  val copied=snapshot().plans.single { it.month=="2027-03" }
  assertEquals(25000L,copied.targetMinor)
  assertEquals(120000L,copied.rows.single().minor)
  assertEquals("a",copied.rows.single().suggestedAccountId)
  assertTrue(runBlocking { ledger.snapshot() }.operations.isEmpty())
  assertEquals(50000000L,runBlocking { ledger.snapshot() }.accounts.single().balanceMinor)
 }
}

