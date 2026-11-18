package io.github.kannoncoding.wealthward.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kannoncoding.wealthward.domain.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class F08UiTest {
 @get:Rule val ui = createComposeRule()

 private val cutoff = LocalDate.parse("2026-10-02")
 private val due = cutoff.plusDays(2)
 private val crc = Currency.CRC
 private val occurrence = PlannedOccurrence("due", "template", 1, "2026-10", 0, "Factura de prueba", "Hogar",
  PlanNature.FIXED, crc, due.toString(), 20_000, 20_000, 0, 20_000, "bills", null, false, emptyList())
 private val link = ProjectionLink(ProjectionSource.OCCURRENCE, occurrence.id)
 private val input = ProjectionInput(
  month = YearMonth.parse("2026-10"), cutoff = cutoff, base = crc,
  accounts = listOf(ProjectionAccount("bills", "Facturas", Money(10_000, crc)),
   ProjectionAccount("other", "Cuenta de apoyo", Money(70_000, crc))),
  lines = listOf(
   ProjectionLine("bills", "Facturas", ProjectionComponent.LIQUID, Money(10_000, crc)),
   ProjectionLine("other", "Cuenta de apoyo", ProjectionComponent.LIQUID, Money(70_000, crc)),
   ProjectionLine("due", "Factura de prueba", ProjectionComponent.OBLIGATION, Money(20_000, crc), "bills", due, listOf(link)),
   ProjectionLine("payday", "Cobro de prueba", ProjectionComponent.INCOME, Money(50_000, crc), "bills", due.plusDays(1)),
  ),
  cashEvents = listOf(
   ProjectionCashEvent("due", "Factura de prueba", "bills", due, Money(-20_000, crc), links = listOf(link)),
   ProjectionCashEvent("payday", "Cobro de prueba", "bills", due.plusDays(1), Money(50_000, crc), receipt = true),
  ),
  greenMargin = Money(30_000, crc), target = Money(100_000, crc), planVersion = 1,
 )

 @Test fun positiveForecastShowsEarlierAccountDeficitAndSource() {
  show()
  ui.onNodeWithText("Progreso Pronóstico 110.00 %", substring = true).performScrollTo().assertIsDisplayed()
  click("Calendario de caja")
  ui.onNodeWithText("Primer déficit detectado 2026-10-04 · CRC -100.00", substring = true).performScrollTo().assertIsDisplayed()
  click("Ver fechas · Facturas")
  ui.onNodeWithText("2026-10-04 · Factura de prueba").performScrollTo().assertIsDisplayed()
  click("Ver origen · Obligación o cobro")
  ui.onNodeWithText("Pendiente actual CRC 200.00", substring = true).performScrollTo().assertIsDisplayed()
 }

 @Test fun simulatorAppliesBothChangesAndRestoresBaseline() {
  show()
  click("Simulador")
  ui.onNodeWithText("Gasto adicional · CRC").performScrollTo().performTextInput("100")
  ui.onNodeWithText("Aumento de ahorro · CRC").performScrollTo().performTextInput("50")
  click("Calcular escenario")
  ui.waitUntil(timeoutMillis = 5_000) {
   ui.onAllNodesWithText("Remanente simulado con signo CRC 950.00").fetchSemanticsNodes().isNotEmpty()
  }
  ui.onNodeWithText("Remanente original con signo CRC 1100.00").performScrollTo().assertIsDisplayed()
  click("Restablecer escenario")
  ui.onNodeWithText("Resultado simulado").assertDoesNotExist()
 }

 @Test fun forecastSettingsValidateAndPersistLimitsForTheSelectedCurrency() {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  val preferences = context.getSharedPreferences("f08_ui_settings_test", Context.MODE_PRIVATE)
  preferences.edit().clear().commit()
  show { preferences.saveLimits(it) }
  click("Ajustar pronóstico")
  val cushion = ui.onNodeWithText("Colchón adicional (C) · CRC")
  val margin = ui.onNodeWithText("Margen para verde (G) · CRC")
  cushion.performScrollTo().performTextClearance()
  cushion.performTextInput("70")
  margin.performScrollTo().performTextInput("0")
  click("Aplicar al pronóstico")
  ui.onNodeWithText("Indica un margen mayor que cero.").performScrollTo().assertIsDisplayed()
  margin.performScrollTo().performTextClearance()
  margin.performTextInput("450")
  click("Aplicar al pronóstico")
  ui.runOnIdle {
   assertEquals(7_000L, preferences.getLong("CRC_buffer", -1))
   assertEquals(45_000L, preferences.getLong("CRC_green", -1))
   assertEquals(-1L, preferences.getLong("USD_buffer", -1))
  }
 }

 private fun show(updateSettings: (ProjectionSettings) -> Unit = {}) {
  ui.setContent {
   MaterialTheme {
    Column(Modifier.verticalScroll(rememberScrollState())) {
     ProjectionScreen("2026-10", ProjectionLoading(project(input)), ProjectionSettings(cutoff), updateSettings,
      F06Snapshot(occurrences = listOf(occurrence)), F07Snapshot(), F05Snapshot(), { _, _ -> })
    }
   }
  }
 }

 private fun click(label: String) {
  ui.onNodeWithText(label).performScrollTo().performClick()
 }
}
