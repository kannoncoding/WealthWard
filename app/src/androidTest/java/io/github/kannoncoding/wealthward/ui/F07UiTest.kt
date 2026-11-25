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

class F07UiTest {
 @get:Rule val ui = createComposeRule()
 private lateinit var db: WealthDatabase
 private lateinit var repo: F07Repository
 private lateinit var ledger: F05Repository
 private val action = mutableStateOf("")
 private val subject = mutableStateOf("")
 private val status = mutableStateOf("")
 private val month = "2027-02"
 private val commitments = mutableStateOf(F07Snapshot())
 private val cash = mutableStateOf(F05Snapshot())

 @Before fun setup() {
  db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, WealthDatabase::class.java).build()
  repo = F07Repository(db)
  ledger = F05Repository(db)
  runBlocking {
   ledger.execute(F05Command.CreateAccount("first", "a", "a", "Cuenta de prueba", Currency.CRC, "CASH", 30000000, "2027-01-01"))
   ledger.execute(F05Command.CreateAccount("second", "b", "b", "Otra cuenta", Currency.CRC, "CASH", 7000000, "2027-01-01"))
   ledger.execute(F05Command.FundReserve("already", "a", "initial", "Fondo previo", 2000000))
   cash.value = ledger.snapshot()
  }
  ui.setContent {
   MaterialTheme {
    val scope = rememberCoroutineScope()
    val draft = remember { Drafts(mutableStateMapOf()) }
    draft.scope = "${action.value}:${subject.value}"
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
     Text(status.value)
     if (action.value.isEmpty()) SavingAndProjectCards(commitments.value, cash.value, ::openOnUi)
     else F07Form(action.value, subject.value, month, commitments.value, cash.value, draft, busy,
      { status.value = it },
      { command ->
       busy = true
       scope.launch {
        try {
         repo.execute(command)
         commitments.value = repo.snapshot(month)
         cash.value = ledger.snapshot()
         draft.clear()
         status.value = "Guardado"
         action.value = ""
        } catch (e: Exception) {
         status.value = userError(e)
        } finally {
         busy = false
        }
       }
      },
      { action.value = "" })
    }
   }
  }
 }

 @After fun cleanup() { db.close() }

 @Test fun t08PayInstallmentWithPrincipalAndInterestOnce() {
  open("f07debt")
  text("Nombre de la deuda", "Crédito de prueba")
  text("Saldo total pendiente de la deuda", "300000")
  ui.selectCalendarDate("Fecha de apertura", "2027-01-01")
  ui.selectCalendarDate("Primera cuota", "2027-02-15")
  text("Monto por cuota", "50000")
  text("Cuotas restantes", "6")
  save()
  val debt = runBlocking { repo.snapshot(month) }.debts.single().debt
  open("f07installment", "$month/${debt.id}")
  ui.selectCalendarDate("Fecha real del pago", "2027-02-15")
  text("Principal pagado", "40000")
  text("Intereses pagados", "10000")
  click("Guardar")
  ui.onNodeWithText("Confirma el pago real.").assertExists()
  click("Confirmo que este pago realmente ocurrió")
  save()

  val result = runBlocking { repo.snapshot(month) }
  assertEquals(26000000L, result.debts.single().principalMinor)
  assertEquals(5000000L, result.debts.single().payments.single().totalMinor)
  assertEquals(0L, result.installments.single { it.nominalMonth == month }.pendingMinor)
  val posted = runBlocking { ledger.snapshot() }
  assertEquals(25000000L, posted.accounts.single { it.id == "a" }.balanceMinor)
  assertEquals(1000000L, posted.operations.sumOf { it.expenseMinor })
 }

 @Test fun mortgageMaturityReopensWithTheExactDayAndCanBeChanged() {
  open("f07debt")
  text("Nombre de la deuda", "Hipoteca de prueba")
  click("Préstamo personal")
  click("Hipoteca")
  ui.selectCalendarDate("Fecha de apertura", "2027-01-01")
  ui.selectCalendarDate("Primera cuota", "2027-02-15")
  text("Monto por cuota", "10000")
  ui.selectCalendarDate("Fecha de fin", "2027-05-19")
  save()
  val first = runBlocking { repo.snapshot("2027-05").debts.single().debt }
  assertEquals("2027-05-19", first.maturityDate)
  assertEquals(4, first.remainingMonths)
  assertEquals(4_000_000L, first.openingMinor)
  assertTrue(first.estimatedOpeningPrincipal)

  open("f07maturity", first.id)
  val display = java.time.LocalDate.parse(checkNotNull(first.maturityDate)).format(
   java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
    .withLocale(AppLanguage.current.locale),
  )
  ui.onNodeWithContentDescription("Fecha de fin").assert(hasText(display))
  ui.selectCalendarDate("Fecha de fin", "2027-07-23")
  save()
  assertEquals("2027-07-23", runBlocking { repo.snapshot("2027-07").debts.single().debt.maturityDate })
 }

 @Test fun quarterlyDebtUsesOnlyScheduledDatesThroughEndDate() {
  open("f07debt")
  text("Nombre de la deuda", "Préstamo trimestral")
  text("Saldo total pendiente de la deuda", "300000")
  ui.selectCalendarDate("Fecha de apertura", "2027-01-01")
  ui.selectCalendarDate("Primera cuota", "2027-01-31")
  click("Mensual")
  click("Trimestral")
  text("Monto por cuota", "50000")
  click("Cantidad de cuotas")
  click("Fecha de fin")
  ui.selectCalendarDate("Fecha de fin", "2027-07-30")
  save()
  val result = runBlocking { repo.snapshot("2027-08") }
  assertEquals(2, result.debts.single().debt.remainingMonths)
  assertEquals(listOf("2027-01-31", "2027-04-30"), result.installments.map { it.dueDate })
 }

 @Test fun t09FundGoalWithoutChangingOwnMoneyAndReleaseFromOneAccount() {
  open("f07goal")
  text("Nombre de la meta", "Fondo de prueba")
  text("Monto objetivo", "90000")
  text("Aporte mensual previsto", "30000")
  save()
  assertEquals(0L, runBlocking { repo.snapshot(month) }.savings.single().fundedMinor)
  click("Aportar · Fondo de prueba")
  text("Monto/Importe", "30000")
  ui.selectCalendarDate("Fecha del aporte o movimiento", "2027-02-10")
  save()
  val funded = runBlocking { ledger.snapshot() }.accounts.filter { it.kind == "CASH" }
  assertEquals(37000000L, funded.sumOf { it.balanceMinor })
  assertEquals(5000000L, funded.sumOf { it.reservedMinor })
  assertEquals(3000000L, runBlocking { repo.snapshot(month) }.savings.single().fundedMinor)

  click("Aportar · Fondo de prueba")
  ui.onNodeWithContentDescription("Cuenta de origen").performScrollTo().performClick()
  click("Otra cuenta · CRC")
  text("Monto/Importe", "20000")
  ui.selectCalendarDate("Fecha del aporte o movimiento", "2027-02-11")
  save()
  click("Retirar · Fondo de prueba")
  text("Monto/Importe", "10000")
  ui.selectCalendarDate("Fecha del aporte o movimiento", "2027-02-12")
  save()
  val final = runBlocking { repo.snapshot(month) }.savings.single()
  assertEquals(mapOf("a" to 2000000L, "b" to 2000000L), final.accounts.associate { it.accountId to it.minor })
  assertEquals(37000000L, runBlocking { ledger.snapshot() }.accounts.filter { it.kind == "CASH" }.sumOf { it.balanceMinor })
 }

 private fun text(label: String, value: String) {
  ui.onNodeWithContentDescription(label).performScrollTo().performTextReplacement(value)
 }

 private fun click(label: String) {
  ui.onNodeWithText(label).performScrollTo().performClick()
 }

 private fun open(next: String, id: String = "") {
  ui.runOnIdle { openOnUi(next, id) }
 }

 private fun openOnUi(next: String, id: String) {
  action.value = next
  subject.value = id
  status.value = ""
 }

 private fun save() {
  click("Guardar")
  try {
   ui.waitUntil(10000) { status.value == "Guardado" }
  } catch (e: Throwable) {
   throw AssertionError("Estado visible: ${status.value}", e)
  }
 }
}
