package io.github.kannoncoding.wealthward.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.kannoncoding.wealthward.data.F08Repository
import io.github.kannoncoding.wealthward.data.ProjectionLimits
import io.github.kannoncoding.wealthward.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

internal data class ProjectionSettings(
 val cutoff: LocalDate,
 val base: Currency = Currency.CRC,
 val rates: List<ProjectionRate> = emptyList(),
 val limits: Map<Currency, ProjectionLimits> = emptyMap(),
)

@Composable internal fun rememberProjectionSettings(month: String) = rememberSaveable(
 month,
 stateSaver = listSaver<ProjectionSettings, String>(
  save = { settings ->
   listOf(settings.cutoff.toString(), settings.base.name) + settings.rates.flatMap {
    listOf(it.from.name, it.to.name, it.rate.toPlainString(), it.date.toString(), it.source)
   }
  },
  restore = { values ->
   ProjectionSettings(LocalDate.parse(values[0]), Currency.valueOf(values[1]), values.drop(2).chunked(5).map {
    ProjectionRate(Currency.valueOf(it[0]), Currency.valueOf(it[1]), BigDecimal(it[2]), LocalDate.parse(it[3]), it[4])
   })
  },
 ),
) { mutableStateOf(ProjectionSettings(minOf(LocalDate.now(BusinessTime.zone), YearMonth.parse(month).atEndOfMonth()))) }

internal data class ProjectionLoading(val result: ProjectionResult? = null, val failed: Boolean = false)

@Composable internal fun loadProjection(
 repo: F08Repository,
 month: String,
 settings: ProjectionSettings,
 revision: List<Any?>,
): ProjectionLoading {
 var state by remember(repo, month, settings, revision) { mutableStateOf(ProjectionLoading()) }
 LaunchedEffect(repo, month, settings, revision) {
  try {
   val limits = settings.limits[settings.base]
   val input = repo.snapshot(month, settings.cutoff, settings.base, settings.rates,
    buffer = limits?.buffer?.let { Money(it, settings.base) },
    greenMargin = limits?.green?.let { Money(it, settings.base) })
   state = ProjectionLoading(withContext(Dispatchers.Default) { project(input) })
  } catch (e: CancellationException) {
   throw e
  } catch (_: Exception) {
   state = ProjectionLoading(failed = true)
  }
 }
 return state
}

@Composable internal fun ProjectionDashboard(month: String, loading: ProjectionLoading, onDetail: () -> Unit) {
 Text("Pronóstico de cierre", style = MaterialTheme.typography.titleLarge)
 val result = loading.result
 if (result == null) {
  ProjectionStatus(loading)
 } else {
  Text("Periodo $month · Corte ${result.input.cutoff} · Base ${result.input.base}")
  ProjectionHeadline(result)
  InfoCard("Comparativo del periodo", "Remanente inicial ${result.initialRemaining.display()}\nRemanente de flujo registrado ${result.actualRemaining.display()}\nAhorro propio financiado ${result.input.comparison.funded.displayByCurrency()}", 2)
  val first = result.cash.mapNotNull { it.firstDeficit }.minByOrNull { it.date }
  if (first != null) {
   val account = result.cash.first { it.account.id == first.accountId }.account
   Text("Primer déficit de caja detectado · ${first.date} · ${account.name} · ${first.balance.display()}")
  }
  if (result.cashIssues.isNotEmpty()) Text("Disponible hoy y calendario con datos incompletos.")
  val obligations = result.lines.filter { it.component == ProjectionComponent.OBLIGATION && it.date != null }
   .sortedBy { it.date }.take(3)
  obligations.forEach { Text("${it.date} · ${it.title} · ${it.amount.display()}") }
 }
 Action("Abrir proyección y calendario", onClick = onDetail)
}

@Composable private fun ProjectionStatus(loading: ProjectionLoading) {
 if (loading.failed) {
  Text("No se pudo calcular el pronóstico. Revisa el periodo y el corte.", color = MaterialTheme.colorScheme.error)
 } else {
  LinearProgressIndicator(Modifier.fillMaxWidth())
  Text("Calculando pronóstico…")
 }
}

@Composable private fun ProjectionHeadline(result: ProjectionResult) {
 val label = when (result.color) {
  ProjectionColor.RED -> "Rojo"
  ProjectionColor.ORANGE -> "Naranja"
  ProjectionColor.YELLOW -> "Amarillo"
  ProjectionColor.GREEN -> "Verde"
  null -> "Datos incompletos"
 }
 val primary = result.freeRecommended?.let { "Libre proyectado ${it.display()}" }
  ?: "Libre recomendado pendiente de confirmación"
 val shortfall = result.shortfall?.takeIf { it.minor > 0L }?.let { "\nFaltante ${it.display()}" }.orEmpty()
 val provisional = result.totals?.free?.takeIf { result.color == null }?.let {
  "\nRemanente provisional ${it.display()}"
 }.orEmpty()
 val tint = when (result.color) {
  ProjectionColor.RED -> Color(0xFFFADDE0)
  ProjectionColor.ORANGE -> Color(0xFFFFE4C9)
  ProjectionColor.YELLOW -> Color(0xFFFFF0BB)
  ProjectionColor.GREEN -> Color(0xFFD9EEDC)
  null -> Pastels[0]
 }
 Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = tint, contentColor = Color(0xFF272238))) {
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
   Text(label, style = MaterialTheme.typography.titleMedium)
   Text("$primary$shortfall$provisional\n${result.explanation}")
  }
 }
}

private enum class ProjectionSection(val title: String) {
 SUMMARY("Comparativo"), CALCULATION("Detalle del cálculo"), CASH("Calendario de caja"), SCENARIO("Simulador")
}

@Composable internal fun ProjectionScreen(
 month: String,
 loading: ProjectionLoading,
 settings: ProjectionSettings,
 updateSettings: (ProjectionSettings) -> Unit,
 plan: F06Snapshot,
 commitments: F07Snapshot,
 ledger: F05Snapshot,
 open: (String, String) -> Unit,
) {
 var section by rememberSaveable { mutableStateOf(ProjectionSection.SUMMARY.name) }
 var showSettings by rememberSaveable { mutableStateOf(false) }
 val sectionState = rememberSaveableStateHolder()
 val result = loading.result
 Text(if (result == null) "Corte ${settings.cutoff} · Base ${settings.base}" else
  "Periodo ${result.input.month} · Corte ${settings.cutoff} · Base ${settings.base}")
 Action(if (showSettings) "Ocultar ajustes del pronóstico" else "Ajustar pronóstico") { showSettings = !showSettings }
 if (showSettings) ProjectionSettingsForm(month, settings) {
  updateSettings(it)
  showSettings = false
 }
 if (result == null) {
  ProjectionStatus(loading)
  return
 }
 ProjectionHeadline(result)
 Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
  ProjectionSection.entries.forEach { option ->
   FilterChip(
    selected = section == option.name,
    onClick = { section = option.name },
    label = { Text(option.title) },
    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
   )
  }
 }
 val names = remember(result) { result.input.accounts.associate { it.id to it.name } }
 val source: @Composable (ProjectionLink) -> Unit = { ProjectionSourceDetail(it, plan, commitments, ledger, open) }
 sectionState.SaveableStateProvider(section) {
  when (ProjectionSection.valueOf(section)) {
   ProjectionSection.SUMMARY -> ProjectionComparisonCards(result)
   ProjectionSection.CALCULATION -> ProjectionCalculation(result, names, source)
   ProjectionSection.CASH -> ProjectionCalendar(result, names, source)
   ProjectionSection.SCENARIO -> ProjectionSimulator(result)
  }
 }
}

@Composable private fun ProjectionSettingsForm(month: String, settings: ProjectionSettings, apply: (ProjectionSettings) -> Unit) {
 var date by rememberSaveable(settings.cutoff) { mutableStateOf(settings.cutoff.toString()) }
 var base by rememberSaveable(settings.base) { mutableStateOf(settings.base.name) }
 val currency = Currency.valueOf(base)
 val other = Currency.entries.first { it != currency }
 val active = settings.rates.lastOrNull { it.from == other && it.to == currency }
 val limits = settings.limits[currency] ?: ProjectionLimits()
 var value by rememberSaveable(base, active?.rate) { mutableStateOf(active?.rate?.toPlainString().orEmpty()) }
 var rateDate by rememberSaveable(base, active?.date) { mutableStateOf(active?.date?.toString() ?: date) }
 var source by rememberSaveable(base, active?.source) { mutableStateOf(active?.source.orEmpty()) }
 var buffer by rememberSaveable(base, limits.buffer) { mutableStateOf(decimal(limits.buffer)) }
 var green by rememberSaveable(base, limits.green) { mutableStateOf(limits.green?.let(::decimal).orEmpty()) }
 var error by rememberSaveable { mutableStateOf("") }
 ProjectionField("Fecha de corte · AAAA-MM-DD", date) { date = it }
 Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  Currency.entries.forEach {
   FilterChip(selected = it == currency, onClick = { base = it.name }, label = { Text(it.name) },
    modifier = Modifier.heightIn(min = 48.dp))
  }
 }
 ProjectionField("Tasa opcional · $currency por 1 $other", value, numeric = true) { value = it }
 if (value.isNotBlank()) {
  ProjectionField("Fecha de la tasa · AAAA-MM-DD", rateDate) { rateDate = it }
  ProjectionField("Fuente de la tasa", source) { source = it }
 }
 ProjectionField("Colchón adicional (C) · $currency", buffer, numeric = true) { buffer = it }
 Text("Incluye solo el colchón adicional a los fondos ya protegidos.")
 ProjectionField("Margen para verde (G) · $currency", green, numeric = true) { green = it }
 Text("Vacío aplica el margen predeterminado de ${ProjectionInput.DEFAULT_GREEN_MARGIN.display()}.")
 Action("Aplicar al pronóstico") {
  val cutoff = runCatching { LocalDate.parse(date) }.getOrNull()
  val rate = value.replace(',', '.').trim().toBigDecimalOrNull()
  val rateAt = runCatching { LocalDate.parse(rateDate) }.getOrNull()
  val cushion = runCatching { Money.parse(buffer.trim().replace(',', '.').ifBlank { "0" }, currency).minor }.getOrNull()
  val threshold = runCatching { Money.parse(green.trim().replace(',', '.'), currency).minor }.getOrNull()
  error = when {
   cutoff == null -> "Indica una fecha de corte válida."
   cutoff > LocalDate.now(BusinessTime.zone) -> "El corte debe ser de hoy o anterior."
   cutoff > YearMonth.parse(month).atEndOfMonth() -> "El corte debe ser anterior al cierre del periodo o coincidir con él."
   value.isNotBlank() && (rate == null || rate.signum() <= 0 || rateAt == null || rateAt > cutoff || source.isBlank()) ->
    "Indica una tasa positiva, su fuente y una fecha válida hasta el corte."
   cushion == null || cushion < 0L -> "Indica un colchón mayor o igual a cero."
   green.isNotBlank() && (threshold == null || threshold <= 0L) -> "Indica un margen mayor que cero."
   else -> ""
  }
  if (error.isEmpty() && cutoff != null && cushion != null) {
   val rates = settings.rates.filterNot { it.from == other && it.to == currency }
   val nextRates = if (value.isBlank()) rates else
    rates + ProjectionRate(other, currency, checkNotNull(rate), checkNotNull(rateAt), source.trim())
   apply(ProjectionSettings(cutoff, currency, nextRates, settings.limits + (currency to ProjectionLimits(cushion, threshold))))
  }
 }
 if (error.isNotEmpty()) ProjectionError(error)
}

@Composable private fun ProjectionField(
 label: String, value: String, numeric: Boolean = false, change: (String) -> Unit,
) = OutlinedTextField(
 value, change, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) },
 keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
)

@Composable private fun ProjectionError(error: String) {
 Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

private fun Money?.display(): String = this?.let { money(it.minor, it.currency) } ?: "Sin confirmar"

private fun List<Money>.displayByCurrency(): String = groupBy { it.currency }.entries
 .joinToString(" · ") { (currency, amounts) -> "$currency ${total(amounts.map { it.minor })}" }
 .ifEmpty { "—" }

@Composable private fun ProjectionComparisonCards(result: ProjectionResult) {
 val comparison = result.input.comparison
 val initialNote = when {
  result.input.planVersion == null -> "Sin presupuesto inicial registrado"
  comparison.initialOpening == null -> "Apertura prevista pendiente de confirmar"
  result.initialRemaining == null -> "Para calcular el remanente en la moneda base, indica la tasa al corte"
  else -> null
 }
 val initialEntries = comparison.initialIncome + comparison.initialFinancing
 val initialUses = comparison.initialUses + comparison.initialBuffer
 val initialFlow = (initialEntries + initialUses).map { it.currency }.distinct().joinToString(" · ") { currency ->
  val inflow = initialEntries.filter { it.currency == currency }.sumOf { it.decimal() }
  val outflow = initialUses.filter { it.currency == currency }.sumOf { it.decimal() }
  "$currency ${(inflow - outflow).toPlainString()}"
 }.ifEmpty { "—" }
 InfoCard("Presupuesto inicial · ${result.input.month}", listOfNotNull(
  initialNote,
  "Remanente inicial ${result.initialRemaining.display()}",
  "Subtotal previsto sin apertura $initialFlow",
  comparison.initialOpening?.let { "Apertura utilizable prevista ${it.displayByCurrency()}" },
  "Ingresos previstos ${comparison.initialIncome.displayByCurrency()}",
  "Financiación prevista ${comparison.initialFinancing.displayByCurrency()}",
  "Usos previstos ${comparison.initialUses.displayByCurrency()}",
  "Colchón previsto ${comparison.initialBuffer.displayByCurrency()}",
  "Ahorro previsto ${comparison.plannedSaving.displayByCurrency()}",
 ).joinToString("\n"), 0)
 InfoCard("Real registrado al ${result.input.cutoff}", listOf(
  "Remanente de flujo ${result.actualRemaining.display()}",
  "Remanente de flujo sin apertura ${result.actualRemainingWithoutOpening.display()}",
  "Apertura de caja ${comparison.opening.displayByCurrency()}",
  "Ingresos ganados recibidos ${comparison.received.displayByCurrency()}",
  "Financiación recibida ${comparison.financing.displayByCurrency()}",
  "Pagos externos netos ${comparison.paid.displayByCurrency()}",
  "Ajustes de caja ${comparison.adjustments.displayByCurrency()}",
  "Gasto económico ${comparison.economicExpense.displayByCurrency()}",
  "Principal pagado ${comparison.principalPaid.displayByCurrency()}",
  "Ahorro propio financiado ${comparison.funded.displayByCurrency()}",
 ).joinToString("\n"), 2)
 result.native.values.forEach {
  InfoCard("Pronóstico · ${it.free.currency}", listOf(
   "Saldo líquido al corte ${it.liquid.display()}",
   "Fondos protegidos ${it.protected.display()}",
  "Saldo utilizable para el periodo ${it.available.display()}",
   "Obligaciones pendientes ${it.obligations.display()}",
   "Ahorro previsto pendiente ${it.saving.display()}",
   "Remanente ${if (result.color == null) "provisional " else ""}${it.free.display()}",
  ).joinToString("\n"), 3)
 }
 val target = result.input.target
 if (target == null || target.minor == 0L) {
  InfoCard("Meta T · ${result.input.month}", "Sin meta", 1)
 } else {
  val forecast = result.targetProgress?.movePointRight(2)?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()
  val basis = result.totals?.free.display()
  InfoCard("Meta T · ${result.input.month}", buildString {
   append("Meta ${target.display()}\nBase Pronóstico $basis")
   append("\nProgreso Pronóstico ${forecast?.let { "$it %" } ?: "Sin confirmar"}")
   if (result.color == null) append("\nPronóstico provisional")
  }, 1)
 }
 ProjectionIssues(result.issues)
}

@Composable private fun ProjectionIssues(issues: List<String>) {
 if (issues.isNotEmpty()) {
  Text("Datos por completar", style = MaterialTheme.typography.titleMedium)
  ProjectionPage(issues.distinct()) { Text("• $it") }
 }
}

private val projectionComponentNames = mapOf(
 ProjectionComponent.LIQUID to "L · Saldo líquido contabilizado",
 ProjectionComponent.PROTECTED to "P · Fondos financiados protegidos",
 ProjectionComponent.INCOME to "I · Ingresos pendientes incluidos",
 ProjectionComponent.OBLIGATION to "O · Obligaciones pendientes",
 ProjectionComponent.VARIABLE to "V · Presupuesto variable restante",
 ProjectionComponent.SAVING to "S · Ahorro por financiar",
 ProjectionComponent.BUFFER to "C · Colchón adicional",
)

@Composable private fun ProjectionCalculation(
 result: ProjectionResult,
 names: Map<String, String>,
 source: @Composable (ProjectionLink) -> Unit,
) {
 Text("A = L − P\nAntes del colchón y ahorro = A + I − O − V\nF = A + I − O − V − C − S")
 Text("Margen para verde (G) · ${result.input.greenMargin.display()}")
 result.input.rates.filter { it.date <= result.input.cutoff && it.to == result.input.base }.forEach {
  Text("Tasa registrada · 1 ${it.from} = ${it.rate.toPlainString()} ${it.to} · ${it.date} · ${it.source}")
 }
 result.totals?.let {
  Text("Disponible para el periodo (A) ${it.available.display()}")
  Text("Antes del colchón y ahorro ${it.obligationsMargin.display()}")
  Text("Después del colchón ${it.afterBuffer.display()}")
  Text("Remanente con signo (F) ${it.free.display()}")
 }
 ProjectionIssues(result.issues)
 val groups = remember(result.lines) { result.lines.groupBy { it.component } }
 var expanded by rememberSaveable { mutableStateOf("") }
 val baseAmounts = result.totals?.let {
  mapOf(
   ProjectionComponent.LIQUID to it.liquid, ProjectionComponent.PROTECTED to it.protected,
   ProjectionComponent.INCOME to it.income, ProjectionComponent.OBLIGATION to it.obligations,
   ProjectionComponent.VARIABLE to it.variable, ProjectionComponent.SAVING to it.saving,
   ProjectionComponent.BUFFER to it.buffer,
  )
 }
 projectionComponentNames.forEach { (component, label) ->
  val lines = groups[component].orEmpty()
  val amount = baseAmounts?.get(component)?.display() ?: lines.mapNotNull { it.amount }.displayByCurrency()
  Action("$label · $amount", enabled = lines.isNotEmpty()) {
   expanded = if (expanded == component.name) "" else component.name
  }
  if (expanded == component.name) {
   key(component) {
    ProjectionPage(lines) { line ->
     InfoCard(line.title, listOfNotNull(
      line.amount.display(),
      line.date?.toString(),
      line.accountId?.let { names[it] },
     ).joinToString(" · "), 2)
     ProjectionSources(line.links, source)
    }
   }
  }
 }
}

@Composable private fun ProjectionCalendar(
 result: ProjectionResult,
 names: Map<String, String>,
 source: @Composable (ProjectionLink) -> Unit,
) {
 Text("Saldos por cuenta desde el corte. Cuando falta el orden del día, se muestran los pagos antes de los cobros.")
 ProjectionIssues(result.cashIssues)
 result.cashWarnings.forEach { Text(it) }
 if (result.cash.isEmpty()) Text("Sin cuentas líquidas en este corte.")
 var selected by rememberSaveable { mutableStateOf("") }
 result.cash.forEach { cash ->
  val account = cash.account
  val first = cash.firstDeficit
  val candidateNames = cash.suggestedTransferFrom.mapNotNull { names[it] }
  InfoCard(account.name, buildString {
   append("Saldo al corte ${account.balance.display()}")
   append("\nDisponible hoy ${cash.safeToday.display()} · horizonte ${cash.horizon}")
   if (first != null) append("\nPrimer déficit detectado ${first.date} · ${first.balance.display()}")
   if (candidateNames.isNotEmpty()) append("\nPosible origen de transferencia: ${candidateNames.joinToString()}")
  }, if (first == null) 3 else 1)
  Action(if (selected == account.id) "Ocultar fechas · ${account.name}" else "Ver fechas · ${account.name}") {
   selected = if (selected == account.id) "" else account.id
  }
  if (selected == account.id) {
   val points = remember(result.calendar, account.id) { result.calendar.filter { it.accountId == account.id } }
   if (points.isEmpty()) Text("Sin eventos de caja previstos.")
   key(account.id) {
    ProjectionPage(points) { point ->
     val change = point.amount?.let { "Movimiento previsto ${it.display()}\n" }.orEmpty()
     InfoCard("${point.date} · ${point.title}", "${change}Saldo previsto ${point.balance.display()}", 2)
     ProjectionSources(point.links, source)
    }
   }
  }
 }
 var showDaily by rememberSaveable { mutableStateOf(false) }
 if (result.cashDaily.isNotEmpty()) {
  Action(if (showDaily) "Ocultar cierres diarios" else "Ver cierres diarios por moneda") { showDaily = !showDaily }
  if (showDaily) {
   ProjectionPage(result.cashDaily.entries.sortedBy { it.key }) { (day, balances) ->
    InfoCard(day.toString(), "Cierre previsto ${balances.displayByCurrency()}", 2)
   }
  }
 }
}

@Composable private fun <T> ProjectionPage(items: List<T>, content: @Composable (T) -> Unit) {
 var limit by rememberSaveable { mutableStateOf(20) }
 items.take(limit).forEach { content(it) }
 if (items.size > limit) Action("Ver 20 más · ${items.size - limit} restantes") { limit += 20 }
}

@Composable private fun ProjectionSources(links: List<ProjectionLink>, source: @Composable (ProjectionLink) -> Unit) {
 if (links.isEmpty()) return
 val unique = remember(links) { links.distinct() }
 var showSources by rememberSaveable { mutableStateOf(false) }
 if (unique.size > 3) {
  Action(if (showSources) "Ocultar orígenes · ${unique.size}" else "Ver orígenes · ${unique.size}") { showSources = !showSources }
 }
 if (unique.size <= 3 || showSources) ProjectionPage(unique, source)
}

@Composable private fun ProjectionSourceDetail(
 link: ProjectionLink,
 plan: F06Snapshot,
 commitments: F07Snapshot,
 ledger: F05Snapshot,
 open: (String, String) -> Unit,
) {
 val heading = when (link.source) {
  ProjectionSource.ACCOUNT -> "Cuenta"
  ProjectionSource.OCCURRENCE -> "Obligación o cobro"
  ProjectionSource.MOVEMENT -> "Movimiento"
  ProjectionSource.GOAL -> "Meta de ahorro"
  ProjectionSource.PLAN -> "Partida del plan"
  ProjectionSource.SCENARIO -> "Escenario simulado"
  ProjectionSource.CREDIT -> "Estado de tarjeta"
 }
 var expanded by rememberSaveable(link.id) { mutableStateOf(false) }
 Action(if (expanded) "Ocultar origen · $heading" else "Ver origen · $heading") { expanded = !expanded }
 if (!expanded) return
 val occurrence = (plan.occurrences + commitments.installments).firstOrNull { it.id == link.id }
 val operation = ledger.operations.firstOrNull { it.id == link.id }
 val account = ledger.accounts.firstOrNull { it.id == link.id }
 val goal = commitments.savings.firstOrNull { it.goal.id == link.id }
 val statement = commitments.statements.firstOrNull { it.statement.id == link.id }
 val row = plan.plans.asReversed().firstNotNullOfOrNull { version -> version.rows.firstOrNull { it.id == link.id } }
 when (link.source) {
  ProjectionSource.ACCOUNT -> if (account != null) InfoCard(account.name,
   "Saldo registrado actual ${money(account.balanceMinor, account.currency)}\nReservas actuales ${money(account.reservedMinor, account.currency)}", 0)
  else Text("La cuenta ya no aparece en el registro actual.")
  ProjectionSource.OCCURRENCE -> if (occurrence != null) {
   InfoCard(occurrence.name, listOf(
    "Vence ${occurrence.dueDate} · periodo financiador ${occurrence.financingMonth}",
    "Estimado ${money(occurrence.expectedMinor, occurrence.currency)}",
    "Pagado ${money(occurrence.cashPaidMinor, occurrence.currency)}",
    "Pendiente actual ${money(occurrence.pendingMinor, occurrence.currency)}",
   ).joinToString("\n"), 0)
   if (occurrence.pendingMinor > 0L) {
    val debt = occurrence.templateId.startsWith("term-debt:")
    Action(if (occurrence.nature == PlanNature.INCOME) "Registrar recepción real" else "Registrar pago real") {
     if (debt) open("f07installment", "${occurrence.nominalMonth}/${occurrence.templateId.removePrefix("term-debt:")}")
     else open("f06pay", occurrence.id)
    }
   }
  } else Text("El detalle de este vencimiento aún no está disponible en el plan actual.")
  ProjectionSource.MOVEMENT -> if (operation != null) {
   val origin = ledger.accounts.firstOrNull { it.id == operation.accountId }
   InfoCard(operation.merchant.ifBlank { operation.category.ifBlank { operation.type } },
    listOfNotNull(operation.date, origin?.name, origin?.currency?.let { money(operation.amountMinor, it) },
     operation.category.ifBlank { null }, operation.notes.ifBlank { null }).joinToString("\n"), 0)
  } else Text("El movimiento ya no aparece en el registro actual.")
  ProjectionSource.GOAL -> if (goal != null) InfoCard(goal.goal.name, listOf(
   "Meta ${money(goal.goal.targetMinor, goal.goal.currency)}",
   "Aporte mensual previsto ${money(goal.goal.monthlyMinor, goal.goal.currency)}",
   "Ahorro financiado actual ${money(goal.fundedMinor, goal.goal.currency)}",
  ).joinToString("\n"), 0) else Text("La meta ya no aparece en el registro actual.")
  ProjectionSource.PLAN -> if (row != null) InfoCard(row.name,
   "${row.group} · ${natureName(row.nature)} · ${money(row.minor, row.currency)}", 0)
  else Text("El cálculo muestra el importe de la partida al corte.")
  ProjectionSource.CREDIT -> if (statement != null) InfoCard("Corte ${statement.statement.cutoffDate}",
   "Vence ${statement.statement.dueDate} · Pendiente actual " +
    (ledger.accounts.firstOrNull { it.id == statement.statement.accountId }?.let { money(statement.pendingMinor, it.currency) }
     ?: "Sin confirmar"), 0)
  else Text("Consulta el origen del movimiento vinculado para ver la compra.")
  ProjectionSource.SCENARIO -> Text("Importe temporal introducido en el simulador.")
 }
}

@Composable private fun ProjectionSimulator(result: ProjectionResult) {
 val currency = result.input.base
 var spend by rememberSaveable(currency) { mutableStateOf("") }
 var saving by rememberSaveable(currency) { mutableStateOf("") }
 var accountId by rememberSaveable(currency) { mutableStateOf("") }
 var submittedSpend by rememberSaveable(currency) { mutableStateOf(0L) }
 var submittedSaving by rememberSaveable(currency) { mutableStateOf(0L) }
 var submittedAccount by rememberSaveable(currency) { mutableStateOf("") }
 var error by rememberSaveable { mutableStateOf("") }
 Text("Prueba un gasto adicional o un aumento de ahorro. El escenario es temporal.")
 ProjectionField("Gasto adicional · $currency", spend, numeric = true) { spend = it }
 ProjectionField("Aumento de ahorro · $currency", saving, numeric = true) { saving = it }
 val accounts = result.input.accounts.filter { it.balance.currency == currency }
 if (accounts.isNotEmpty()) {
  Text("Cuenta para el gasto simulado")
  FilterChip(selected = accountId.isEmpty(), onClick = { accountId = "" },
   label = { Text("Sin cuenta · solo cierre") }, modifier = Modifier.heightIn(min = 48.dp))
  accounts.forEach { account ->
   FilterChip(selected = account.id == accountId, onClick = { accountId = account.id },
    label = { Text(account.name) }, modifier = Modifier.heightIn(min = 48.dp))
  }
 }
 Action("Calcular escenario") {
  fun amount(raw: String): Long? = runCatching {
   Money.parse(raw.trim().replace(',', '.').ifBlank { "0" }, currency).minor.takeIf { it >= 0 }
  }.getOrNull()
  val spending = amount(spend)
  val contribution = amount(saving)
  if (spending == null || contribution == null) {
   error = "Indica importes válidos mayores o iguales a cero."
  } else {
   error = ""
   submittedSpend = spending
   submittedSaving = contribution
   submittedAccount = accounts.firstOrNull { it.id == accountId }?.id.orEmpty()
  }
 }
 Action("Restablecer escenario") {
  spend = ""
  saving = ""
  accountId = ""
  submittedSpend = 0L
  submittedSaving = 0L
  submittedAccount = ""
  error = ""
 }
 if (error.isNotEmpty()) ProjectionError(error)
 val scenario = ProjectionScenario(submittedSpend, submittedSaving, submittedAccount.ifBlank { null })
 if (scenario == ProjectionScenario()) return
 var simulated by remember(result.input, scenario) { mutableStateOf<ProjectionResult?>(null) }
 var failed by remember(result.input, scenario) { mutableStateOf(false) }
 LaunchedEffect(result.input, scenario) {
  try {
   simulated = withContext(Dispatchers.Default) { project(result.input, scenario) }
  } catch (e: CancellationException) {
   throw e
  } catch (_: Exception) {
   failed = true
  }
 }
 Text("Resultado simulado", style = MaterialTheme.typography.titleMedium)
 Text("Remanente original con signo ${result.totals?.free.display()}")
 val output = simulated
 if (output != null) {
  ProjectionHeadline(output)
  Text("Remanente simulado con signo ${output.totals?.free.display()}")
  if (submittedAccount.isNotBlank()) output.cash.firstOrNull { it.account.id == submittedAccount }?.let {
   Text("Disponible simulado · ${it.account.name} · ${it.safeToday.display()}")
   if (it.firstDeficit != null) Text("Déficit simulado · ${it.firstDeficit.date} · ${it.firstDeficit.balance.display()}")
  }
  ProjectionIssues(output.issues)
 } else if (failed) ProjectionError("No se pudo calcular el escenario con esos importes.")
 else LinearProgressIndicator(Modifier.fillMaxWidth())
}
