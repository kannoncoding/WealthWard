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
import io.github.kannoncoding.wealthward.data.F05Repository
import io.github.kannoncoding.wealthward.data.WealthDatabase
import io.github.kannoncoding.wealthward.domain.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Production forms with the real transactional Room boundary, using fictitious CRC only. */
class F05UiTest {
 @get:Rule val ui = createComposeRule()
 private lateinit var db: WealthDatabase
 private lateinit var repo: F05Repository
 private val action = mutableStateOf("expense")
 private val subject = mutableStateOf("")
 private val status = mutableStateOf("")
 private val state = mutableStateOf(F05Snapshot())
 @Before fun setup() {
  db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, WealthDatabase::class.java).build()
  repo = F05Repository(db)
  runBlocking {
   repo.execute(F05Command.CreateAccount("setup-a", "a", "deposit-a", "Cuenta de prueba", Currency.CRC, "CASH", 50000000, "2026-01-01"))
   repo.execute(F05Command.CreateAccount("setup-b", "b", "deposit-b", "Cuenta destino", Currency.CRC, "CASH", 0, "2026-01-01"))
   repo.execute(F05Command.CreateAccount("setup-c", "c", "cash-test", "Efectivo de prueba", Currency.CRC, "CASH", 0, "2026-01-01"))
   state.value = repo.snapshot()
  }
  ui.setContent {
   MaterialTheme {
    val scope = rememberCoroutineScope()
    val values = remember { mutableStateMapOf<String, String>() }
    val draft = remember { Drafts(values) }
    draft.scope = "${action.value}:${subject.value}"
    Column(Modifier.verticalScroll(rememberScrollState())) {
     Text(status.value)
     if (action.value.isNotEmpty()) F05Form(action.value, subject.value, state.value, draft, false, { status.value = it }, { command ->
      scope.launch { try { repo.execute(command); state.value = repo.snapshot(); draft.clear(); status.value = "Guardado" } catch (e: Exception) { status.value = userError(e) } }
     }, { action.value = "" }, { repo.previewImport(it) })
    }
   }
  }
 }
 @After fun teardown() { db.close() }
 private fun text(label: String, value: String) { ui.onNodeWithContentDescription(label).performScrollTo().performTextReplacement(value) }
 private fun click(label: String) { ui.onNodeWithText(label, substring = false).performScrollTo().performClick() }
 private fun open(next: String, id: String = "") { ui.runOnIdle { action.value = next; subject.value = id; status.value = "" }; ui.waitForIdle() }
 private fun saved() { ui.waitUntil(10000) { status.value == "Guardado" }; ui.waitForIdle() }
 private fun snapshot() = runBlocking { repo.snapshot() }
 private fun expense() { text("Importe", "15000"); text("Comercio", "Comercio genérico"); text("Categoría", "Alimentos") }

 @Test fun optionalPostingDateCanBeClearedAfterReopeningDraft() {
  val label = localized("Fecha contabilizada (si difiere)")
  val chosen = LocalDate.parse("2026-02-10")
  val display = chosen.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(AppLanguage.current.locale))
  ui.selectCalendarDate(label, chosen.toString())
  ui.onNodeWithContentDescription(label).assertTextContains(display)
  click("Cancelar")
  open("expense")
  ui.onNodeWithContentDescription(label).assertTextContains(display).performScrollTo().performClick()
  ui.onNode(hasText(localized("Cancelar")) and hasAnyAncestor(isDialog())).performClick()
  ui.onNodeWithContentDescription(label).assertTextContains(display).performClick()
  ui.onNodeWithText(localized("Quitar fecha")).performClick()
  ui.onNodeWithContentDescription(label).assertTextContains(localized("Elegir fecha"))
 }

 @Test fun validationAndCancelRetainDraftWithoutPosting() {
  expense()
  click("Añadir desglose")
  text("Categoría del desglose 1", "Alimentos")
  text("Importe del desglose 1", "14999")
  click("Guardar")
  ui.onNodeWithText("La suma del desglose", substring = true).assertExists()
  assertTrue(snapshot().operations.isEmpty())
  click("Cancelar")
  open("expense")
  ui.onNodeWithContentDescription("Importe").assertTextContains("15000")
  ui.onNodeWithContentDescription("Importe del desglose 1").assertTextContains("14999")
  text("Importe del desglose 1", "15000")
  click("Guardar"); saved()
  assertEquals(1, snapshot().operations.size)
 }

 @Test fun t01T02T04AndT17ThroughProductionForms() {
  open("reserve")
  text("Nombre del propósito", "Reserva general")
  text("Importe a reservar", "460000")
  click("Guardar"); saved()
  var a = snapshot().accounts.first { it.id == "a" }
  assertEquals(50000000L, a.balanceMinor); assertEquals(46000000L, a.reservedMinor); assertEquals(4000000L, a.unassignedMinor)
  open("expense"); expense(); click("Guardar"); saved()
  a = snapshot().accounts.first { it.id == "a" }
  assertEquals(48500000L, a.balanceMinor); assertEquals(2500000L, a.unassignedMinor)
  val operation = snapshot().operations.last { it.type == "EXPENSE" }
  open("redistribute")
  text("Nombre del propósito", "Reserva nueva")
  text("Importe a reservar", "20000")
  click("Guardar"); saved()
  assertEquals(48500000L, snapshot().accounts.first { it.id == "a" }.balanceMinor)
  open("refund", operation.id)
  text("Importe del reembolso parcial", "5000")
  text("Motivo obligatorio", "Devolución parcial verificada")
  click("Confirmo el reembolso y su restitución de reserva")
  click("Guardar"); saved()
  assertEquals(1000000L, snapshot().operations.sumOf { it.expenseMinor })
  assertEquals(49000000L, snapshot().accounts.first { it.id == "a" }.balanceMinor)
 }

 @Test fun t03AtomicTransferAndWithdrawalAndT10NoImplicitAdjustment() {
  open("transfer")
  text("Importe real de salida", "50000")
  click("Confirmo que la transferencia o pago ocurrió")
  click("Guardar"); saved()
  assertEquals(50000000L, snapshot().accounts.sumOf { it.balanceMinor })
  open("withdraw")
  text("Importe real de salida", "10000")
  click("Confirmo que la transferencia o pago ocurrió")
  click("Guardar"); saved()
  assertEquals(50000000L, snapshot().accounts.sumOf { it.balanceMinor })
  assertEquals(0L, snapshot().operations.sumOf { it.expenseMinor })
  // An independent untouched destination represents the T10 opening fixture.
  runBlocking { repo.execute(F05Command.CreateAccount("t10-account", "t10", "t10", "Cuenta de corte", Currency.CRC, "CASH", 50000000, "2026-01-01")) }
  ui.runOnIdle { state.value = snapshot() }
  open("reconcile", "t10")
  text("Saldo observado (puede ser negativo)", "497500")
  click("Comparar sin modificar saldo"); saved()
  val result = snapshot()
  assertEquals(-250000L, result.reconciliations.last().differenceMinor)
  assertEquals(50000000L, result.accounts.first { it.id == "t10" }.balanceMinor)
  assertFalse(result.operations.any { it.type == "ADJUSTMENT" })
 }

 @Test fun independentT04FundsEmergencyWithoutChangingBank() {
  open("reserve"); text("Nombre del propósito", "Reserva original"); text("Importe a reservar", "460000"); click("Guardar"); saved()
  open("reserve"); text("Nombre del propósito", "Emergencia"); text("Importe a reservar", "20000"); click("Guardar"); saved()
  val account = snapshot().accounts.first { it.id == "a" }
  assertEquals(50000000L, account.balanceMinor)
  assertEquals(48000000L, account.reservedMinor)
  assertEquals(2000000L, account.unassignedMinor)
  assertEquals(2000000L, account.reserves.first { it.name == "Emergencia" }.minor)
 }

 @Test fun t17PartialRefundRestoresOnlyTheConsumedPurpose() {
  open("reserve"); text("Nombre del propósito", "Reserva de alimentos"); text("Importe a reservar", "460000"); click("Guardar"); saved()
  open("expense"); expense(); click("Añadir desglose")
  text("Categoría del desglose 1", "Alimentos"); text("Importe del desglose 1", "15000")
  ui.onNodeWithContentDescription("Propósito del desglose 1").performScrollTo().performClick()
  click("Reserva de alimentos")
  click("Guardar"); saved()
  val purchase = snapshot().operations.first { it.type == "EXPENSE" }
  assertEquals(44500000L, snapshot().accounts.first { it.id == "a" }.reservedMinor)
  open("refund", purchase.id); text("Importe del reembolso parcial", "5000"); text("Motivo obligatorio", "Devolución verificada")
  click("Confirmo el reembolso y su restitución de reserva"); click("Guardar"); saved()
  val result = snapshot()
  assertEquals(45000000L, result.accounts.first { it.id == "a" }.reservedMinor)
  assertEquals(1000000L, result.operations.sumOf { it.expenseMinor })
 }
}
