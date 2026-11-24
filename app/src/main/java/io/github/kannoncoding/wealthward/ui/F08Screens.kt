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
 Text(localized("Pronóstico de cierre"), style = MaterialTheme.typography.titleLarge)
 val result = loading.result
 if (result == null) {
  ProjectionStatus(loading)
 } else {
  Text(localized("Periodo {0} · Corte {1} · Base {2}", month, result.input.cutoff, result.input.base))
  ProjectionHeadline(result)
  InfoCard(localized("Comparativo del periodo"), localized("Remanente inicial {0}\nRemanente de flujo registrado {1}\nAhorro propio financiado {2}", result.initialRemaining.display(), result.actualRemaining.display(), result.input.comparison.funded.displayByCurrency()), 2)
  val first = result.cash.mapNotNull { it.firstDeficit }.minByOrNull { it.date }
  if (first != null) {
   val account = result.cash.first { it.account.id == first.accountId }.account
   Text(localized("Primer déficit de caja detectado · {0} · {1} · {2}", first.date, account.name, first.balance.display()))
  }
  if (result.cashIssues.isNotEmpty()) Text(localized("Disponible hoy y calendario con datos incompletos."))
  val obligations = result.lines.filter { it.component == ProjectionComponent.OBLIGATION && it.date != null }
   .sortedBy { it.date }.take(3)
  obligations.forEach { Text("${it.date} · ${it.title} · ${it.amount.display()}") }
 }
 Action(localized("Abrir proyección y calendario"), onClick = onDetail)
}

@Composable private fun ProjectionStatus(loading: ProjectionLoading) {
 if (loading.failed) {
  Text(localized("No se pudo calcular el pronóstico. Revisa el periodo y el corte."), color = MaterialTheme.colorScheme.error)
 } else {
  LinearProgressIndicator(Modifier.fillMaxWidth())
  Text(localized("Calculando pronóstico…"))
 }
}

@Composable private fun ProjectionHeadline(result: ProjectionResult) {
 val label = when (result.color) {
  ProjectionColor.RED -> localized("Rojo")
  ProjectionColor.ORANGE -> localized("Naranja")
  ProjectionColor.YELLOW -> localized("Amarillo")
  ProjectionColor.GREEN -> localized("Verde")
  null -> localized("Datos incompletos")
 }
 val primary = result.freeRecommended?.let { localized("Libre proyectado {0}", it.display()) }
  ?: localized("Libre recomendado pendiente de confirmación")
 val shortfall = result.shortfall?.takeIf { it.minor > 0L }?.let { localized("\nFaltante {0}", it.display()) }.orEmpty()
 val provisional = result.totals?.free?.takeIf { result.color == null }?.let {
  localized("\nRemanente provisional {0}", it.display())
 }.orEmpty()
 val palette = LocalPalette.current
 val status = when (result.color) {
  ProjectionColor.RED -> palette.red
  ProjectionColor.ORANGE, ProjectionColor.YELLOW -> palette.orange
  ProjectionColor.GREEN -> palette.green
  null -> StatusColors(palette.panel, palette.ink)
 }
 Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = status.background, contentColor = status.text)) {
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
   Text(label, style = MaterialTheme.typography.titleMedium)
   Text("$primary$shortfall$provisional\n${localized(result.explanation)}")
  }
 }
}

private enum class ProjectionSection(val title: String) {
 SUMMARY(localized("Comparativo")), CALCULATION(localized("Detalle del cálculo")), CASH(localized("Calendario de caja")), SCENARIO(localized("Simulador"))
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
 draft: Drafts? = null,
) {
 var section by rememberSaveable { mutableStateOf(ProjectionSection.SUMMARY.name) }
 var showSettings by rememberSaveable { mutableStateOf(false) }
 val sectionState = rememberSaveableStateHolder()
 val result = loading.result
 Text(if (result == null) localized("Corte {0} · Base {1}", settings.cutoff, settings.base) else
  localized("Periodo {0} · Corte {1} · Base {2}", result.input.month, settings.cutoff, settings.base))
 Action(if (showSettings) localized("Ocultar ajustes del pronóstico") else localized("Ajustar pronóstico")) { showSettings = !showSettings }
 if (showSettings) ProjectionSettingsForm(month, settings, draft) {
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

@Composable private fun ProjectionSettingsForm(month: String, settings: ProjectionSettings, draft: Drafts?, apply: (ProjectionSettings) -> Unit) {
 var date by rememberSaveable(settings.cutoff) { mutableStateOf(draft?.get("cutoff", settings.cutoff.toString()) ?: settings.cutoff.toString()) }
 var base by rememberSaveable(settings.base) { mutableStateOf(draft?.get("base", settings.base.name) ?: settings.base.name) }
 val currency = Currency.valueOf(base)
 val other = Currency.entries.first { it != currency }
 val active = settings.rates.lastOrNull { it.from == other && it.to == currency }
 val limits = settings.limits[currency] ?: ProjectionLimits()
 fun stored(field: String, default: String) = draft?.get("$base:$field", default) ?: default
 fun store(field: String, value: String) { draft?.set("$base:$field", value) }
 var value by rememberSaveable(base, active?.rate) { mutableStateOf(stored("rate", active?.rate?.toPlainString().orEmpty())) }
 var rateDate by rememberSaveable(base, active?.date) { mutableStateOf(stored("rateDate", active?.date?.toString() ?: date)) }
 var source by rememberSaveable(base, active?.source) { mutableStateOf(stored("source", active?.source.orEmpty())) }
 var buffer by rememberSaveable(base, limits.buffer) { mutableStateOf(stored("buffer", decimal(limits.buffer))) }
 var green by rememberSaveable(base, limits.green) { mutableStateOf(stored("green", limits.green?.let(::decimal).orEmpty())) }
 var error by rememberSaveable { mutableStateOf("") }
 CalendarDateField(localized("Fecha de corte"), date) { date = it; draft?.set("cutoff", it) }
 Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  Currency.entries.forEach {
   FilterChip(selected = it == currency, onClick = { base = it.name; draft?.set("base", it.name) }, label = { Text(it.name) },
    modifier = Modifier.heightIn(min = 48.dp))
  }
 }
 ProjectionField(localized("Tasa opcional · {0} por 1 {1}", currency, other), value, numeric = true) { value = it; store("rate", it) }
 if (value.isNotBlank()) {
  CalendarDateField(localized("Fecha de la tasa"), rateDate) { rateDate = it; store("rateDate", it) }
  ProjectionField(localized("Fuente de la tasa"), source) { source = it; store("source", it) }
 }
 ProjectionField(localized("Colchón adicional (C) · {0}", currency), buffer, numeric = true) { buffer = it; store("buffer", it) }
 Text(localized("Incluye solo el colchón adicional a los fondos ya protegidos."))
 ProjectionField(localized("Margen para verde (G) · {0}", currency), green, numeric = true) { green = it; store("green", it) }
 Text(localized("Vacío aplica el margen predeterminado de {0}.", ProjectionInput.DEFAULT_GREEN_MARGIN.display()))
 Action(localized("Aplicar al pronóstico")) {
  val cutoff = runCatching { LocalDate.parse(date) }.getOrNull()
  val rate = value.replace(',', '.').trim().toBigDecimalOrNull()
  val rateAt = runCatching { LocalDate.parse(rateDate) }.getOrNull()
  val cushion = runCatching { Money.parse(buffer.trim().replace(',', '.').ifBlank { "0" }, currency).minor }.getOrNull()
  val threshold = runCatching { Money.parse(green.trim().replace(',', '.'), currency).minor }.getOrNull()
  error = when {
   cutoff == null -> localized("Indica una fecha de corte válida.")
   cutoff > LocalDate.now(BusinessTime.zone) -> localized("El corte debe ser de hoy o anterior.")
   cutoff > YearMonth.parse(month).atEndOfMonth() -> localized("El corte debe ser anterior al cierre del periodo o coincidir con él.")
   value.isNotBlank() && (rate == null || rate.signum() <= 0 || rateAt == null || rateAt > cutoff || source.isBlank()) ->
    localized("Indica una tasa positiva, su fuente y una fecha válida hasta el corte.")
   cushion == null || cushion < 0L -> localized("Indica un colchón mayor o igual a cero.")
   green.isNotBlank() && (threshold == null || threshold <= 0L) -> localized("Indica un margen mayor que cero.")
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

private fun Money?.display(): String = this?.let { money(it.minor, it.currency) } ?: localized("Sin confirmar")

private fun List<Money>.displayByCurrency(): String = groupBy { it.currency }.entries
 .joinToString(" · ") { (currency, amounts) -> "$currency ${total(amounts.map { it.minor })}" }
 .ifEmpty { "—" }

@Composable private fun ProjectionComparisonCards(result: ProjectionResult) {
 val comparison = result.input.comparison
 val initialNote = when {
  result.input.planVersion == null -> localized("Sin presupuesto inicial registrado")
  comparison.initialOpening == null -> localized("Apertura prevista pendiente de confirmar")
  result.initialRemaining == null -> localized("Para calcular el remanente en la moneda base, indica la tasa al corte")
  else -> null
 }
 val initialEntries = comparison.initialIncome + comparison.initialFinancing
 val initialUses = comparison.initialUses + comparison.initialBuffer
 val initialFlow = (initialEntries + initialUses).map { it.currency }.distinct().joinToString(" · ") { currency ->
  val inflow = initialEntries.filter { it.currency == currency }.sumOf { it.decimal() }
  val outflow = initialUses.filter { it.currency == currency }.sumOf { it.decimal() }
  "$currency ${(inflow - outflow).toPlainString()}"
 }.ifEmpty { "—" }
 InfoCard(localized("Presupuesto inicial · {0}", result.input.month), listOfNotNull(
  initialNote,
  localized("Remanente inicial {0}", result.initialRemaining.display()),
  localized("Subtotal previsto sin apertura {0}", initialFlow),
  comparison.initialOpening?.let { localized("Apertura utilizable prevista {0}", it.displayByCurrency()) },
  localized("Ingresos previstos {0}", comparison.initialIncome.displayByCurrency()),
  localized("Financiación prevista {0}", comparison.initialFinancing.displayByCurrency()),
  localized("Usos previstos {0}", comparison.initialUses.displayByCurrency()),
  localized("Colchón previsto {0}", comparison.initialBuffer.displayByCurrency()),
  localized("Ahorro previsto {0}", comparison.plannedSaving.displayByCurrency()),
 ).joinToString("\n"), 0)
 InfoCard(localized("Real registrado al {0}", result.input.cutoff), listOf(
  localized("Remanente de flujo {0}", result.actualRemaining.display()),
  localized("Remanente de flujo sin apertura {0}", result.actualRemainingWithoutOpening.display()),
  localized("Apertura de caja {0}", comparison.opening.displayByCurrency()),
  localized("Ingresos ganados recibidos {0}", comparison.received.displayByCurrency()),
  localized("Financiación recibida {0}", comparison.financing.displayByCurrency()),
  localized("Pagos externos netos {0}", comparison.paid.displayByCurrency()),
  localized("Ajustes de caja {0}", comparison.adjustments.displayByCurrency()),
  localized("Gasto económico {0}", comparison.economicExpense.displayByCurrency()),
  localized("Principal pagado {0}", comparison.principalPaid.displayByCurrency()),
  localized("Ahorro propio financiado {0}", comparison.funded.displayByCurrency()),
 ).joinToString("\n"), 2)
 result.native.values.forEach {
  InfoCard(localized("Pronóstico · {0}", it.free.currency), listOf(
   localized("Saldo líquido al corte {0}", it.liquid.display()),
   localized("Fondos protegidos {0}", it.protected.display()),
  localized("Saldo utilizable para el periodo {0}", it.available.display()),
   localized("Obligaciones pendientes {0}", it.obligations.display()),
   localized("Ahorro previsto pendiente {0}", it.saving.display()),
   localized("Remanente {0}{1}", if (result.color == null) localized("provisional ") else "", it.free.display()),
  ).joinToString("\n"), 3)
 }
 val target = result.input.target
 if (target == null || target.minor == 0L) {
  InfoCard(localized("Meta Sobrante · {0}", result.input.month), localized("Sin meta"), 1)
 } else {
  val forecast = result.targetProgress?.movePointRight(2)?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()
  val basis = result.totals?.free.display()
  InfoCard(localized("Meta Sobrante · {0}", result.input.month), buildString {
   append(localized("Querés que te sobre: {0}\nSobrante proyectado: {1}", target.display(), basis))
   append(localized("\nAvance hacia la meta {0}", forecast?.let { "$it %" } ?: localized("Sin confirmar")))
   if (result.color == null) append(localized("\nPronóstico provisional"))
  }, 1)
 }
 ProjectionIssues(result.issues)
}

@Composable private fun ProjectionIssues(issues: List<String>) {
 if (issues.isNotEmpty()) {
  Text(localized("Datos por completar"), style = MaterialTheme.typography.titleMedium)
  ProjectionPage(issues.distinct()) { Text("• ${AppLanguage.current.backendMessage(it)}") }
 }
}

private val projectionComponentNames get() = mapOf(
 ProjectionComponent.LIQUID to localized("L · Saldo líquido contabilizado"),
 ProjectionComponent.PROTECTED to localized("P · Fondos financiados protegidos"),
 ProjectionComponent.INCOME to localized("I · Ingresos pendientes incluidos"),
 ProjectionComponent.OBLIGATION to localized("O · Obligaciones pendientes"),
 ProjectionComponent.VARIABLE to localized("V · Presupuesto variable restante"),
 ProjectionComponent.SAVING to localized("S · Ahorro por financiar"),
 ProjectionComponent.BUFFER to localized("C · Colchón adicional"),
)

@Composable private fun ProjectionCalculation(
 result: ProjectionResult,
 names: Map<String, String>,
 source: @Composable (ProjectionLink) -> Unit,
) {
 Text(localized("A = L − P\nAntes del colchón y ahorro = A + I − O − V\nF = A + I − O − V − C − S"))
 Text(localized("Margen para verde (G) · {0}", result.input.greenMargin.display()))
 result.input.rates.filter { it.date <= result.input.cutoff && it.to == result.input.base }.forEach {
  Text(localized("Tasa registrada · 1 {0} = {1} {2} · {3} · {4}", it.from, it.rate.toPlainString(), it.to, it.date, it.source))
 }
 result.totals?.let {
  Text(localized("Disponible para el periodo (A) {0}", it.available.display()))
  Text(localized("Antes del colchón y ahorro {0}", it.obligationsMargin.display()))
  Text(localized("Después del colchón {0}", it.afterBuffer.display()))
  Text(localized("Remanente con signo (F) {0}", it.free.display()))
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
 Text(localized("Saldos por cuenta desde el corte. Cuando falta el orden del día, se muestran los pagos antes de los cobros."))
 ProjectionIssues(result.cashIssues)
 result.cashWarnings.forEach { warning ->
  val suffix = ": se proyectan pagos antes de cobros porque falta su orden real"
  Text(if (warning.endsWith(suffix)) localized("{0}: se proyectan pagos antes de cobros porque falta su orden real",
   warning.removeSuffix(suffix)) else AppLanguage.current.backendMessage(warning))
 }
 if (result.cash.isEmpty()) Text(localized("Sin cuentas líquidas en este corte."))
 var selected by rememberSaveable { mutableStateOf("") }
 result.cash.forEach { cash ->
  val account = cash.account
  val first = cash.firstDeficit
  val candidateNames = cash.suggestedTransferFrom.mapNotNull { names[it] }
  InfoCard(account.name, buildString {
   append(localized("Saldo al corte {0}", account.balance.display()))
   append(localized("\nDisponible hoy {0} · horizonte {1}", cash.safeToday.display(), cash.horizon))
   if (first != null) append(localized("\nPrimer déficit detectado {0} · {1}", first.date, first.balance.display()))
   if (candidateNames.isNotEmpty()) append(localized("\nPosible origen de transferencia: {0}", candidateNames.joinToString()))
  }, if (first == null) 3 else 1)
  Action(if (selected == account.id) localized("Ocultar fechas · {0}", account.name) else localized("Ver fechas · {0}", account.name)) {
   selected = if (selected == account.id) "" else account.id
  }
  if (selected == account.id) {
   val points = remember(result.calendar, account.id) { result.calendar.filter { it.accountId == account.id } }
   if (points.isEmpty()) Text(localized("Sin eventos de caja previstos."))
   key(account.id) {
    ProjectionPage(points) { point ->
     val change = point.amount?.let { localized("Movimiento previsto {0}\n", it.display()) }.orEmpty()
     InfoCard("${point.date} · ${point.title}", localized("{0}Saldo previsto {1}", change, point.balance.display()), 2)
     ProjectionSources(point.links, source)
    }
   }
  }
 }
 var showDaily by rememberSaveable { mutableStateOf(false) }
 if (result.cashDaily.isNotEmpty()) {
  Action(if (showDaily) localized("Ocultar cierres diarios") else localized("Ver cierres diarios por moneda")) { showDaily = !showDaily }
  if (showDaily) {
   ProjectionPage(result.cashDaily.entries.sortedBy { it.key }) { (day, balances) ->
    InfoCard(day.toString(), localized("Cierre previsto {0}", balances.displayByCurrency()), 2)
   }
  }
 }
}

@Composable private fun <T> ProjectionPage(items: List<T>, content: @Composable (T) -> Unit) {
 var limit by rememberSaveable { mutableStateOf(20) }
 items.take(limit).forEach { content(it) }
 if (items.size > limit) Action(localized("Ver 20 más · {0} restantes", items.size - limit)) { limit += 20 }
}

@Composable private fun ProjectionSources(links: List<ProjectionLink>, source: @Composable (ProjectionLink) -> Unit) {
 if (links.isEmpty()) return
 val unique = remember(links) { links.distinct() }
 var showSources by rememberSaveable { mutableStateOf(false) }
 if (unique.size > 3) {
  Action(if (showSources) localized("Ocultar orígenes · {0}", unique.size) else localized("Ver orígenes · {0}", unique.size)) { showSources = !showSources }
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
  ProjectionSource.ACCOUNT -> localized("Cuenta")
  ProjectionSource.OCCURRENCE -> localized("Obligación o cobro")
  ProjectionSource.MOVEMENT -> localized("Movimiento")
  ProjectionSource.GOAL -> localized("Meta de ahorro")
  ProjectionSource.PLAN -> localized("Partida del plan")
  ProjectionSource.SCENARIO -> localized("Escenario simulado")
  ProjectionSource.CREDIT -> localized("Estado de tarjeta")
 }
 var expanded by rememberSaveable(link.id) { mutableStateOf(false) }
 Action(if (expanded) localized("Ocultar origen · {0}", heading) else localized("Ver origen · {0}", heading)) { expanded = !expanded }
 if (!expanded) return
 val occurrence = (plan.occurrences + commitments.installments).firstOrNull { it.id == link.id }
 val operation = ledger.operations.firstOrNull { it.id == link.id }
 val account = ledger.accounts.firstOrNull { it.id == link.id }
 val goal = commitments.savings.firstOrNull { it.goal.id == link.id }
 val statement = commitments.statements.firstOrNull { it.statement.id == link.id }
 val row = plan.plans.asReversed().firstNotNullOfOrNull { version -> version.rows.firstOrNull { it.id == link.id } }
 when (link.source) {
  ProjectionSource.ACCOUNT -> if (account != null) InfoCard(account.name,
   localized("Saldo registrado actual {0}\nReservas actuales {1}", money(account.balanceMinor, account.currency), money(account.reservedMinor, account.currency)), 0)
  else Text(localized("La cuenta ya no aparece en el registro actual."))
  ProjectionSource.OCCURRENCE -> if (occurrence != null) {
   InfoCard(occurrence.name, listOf(
    localized("Vence {0} · periodo financiador {1}", occurrence.dueDate, occurrence.financingMonth),
    localized("Estimado {0}", money(occurrence.expectedMinor, occurrence.currency)),
    localized("Pagado {0}", money(occurrence.cashPaidMinor, occurrence.currency)),
    localized("Pendiente actual {0}", money(occurrence.pendingMinor, occurrence.currency)),
   ).joinToString("\n"), 0)
   if (occurrence.pendingMinor > 0L) {
    val debt = occurrence.templateId.startsWith("term-debt:")
    Action(if (occurrence.nature == PlanNature.INCOME) localized("Registrar recepción real") else localized("Registrar pago real")) {
     if (debt) open("f07installment", "${occurrence.nominalMonth}/${occurrence.templateId.removePrefix("term-debt:")}")
     else open("f06pay", occurrence.id)
    }
   }
  } else Text(localized("El detalle de este vencimiento aún no está disponible en el plan actual."))
  ProjectionSource.MOVEMENT -> if (operation != null) {
   val origin = ledger.accounts.firstOrNull { it.id == operation.accountId }
   InfoCard(operation.merchant.ifBlank { operation.category.ifBlank { operation.type } },
    listOfNotNull(operation.date, origin?.name, origin?.currency?.let { money(operation.amountMinor, it) },
     operation.category.ifBlank { null }, operation.notes.ifBlank { null }).joinToString("\n"), 0)
  } else Text(localized("El movimiento ya no aparece en el registro actual."))
  ProjectionSource.GOAL -> if (goal != null) InfoCard(goal.goal.name, listOf(
   localized("Meta {0}", money(goal.goal.targetMinor, goal.goal.currency)),
   localized("Aporte mensual previsto {0}", money(goal.goal.monthlyMinor, goal.goal.currency)),
   localized("Ahorro financiado actual {0}", money(goal.fundedMinor, goal.goal.currency)),
  ).joinToString("\n"), 0) else Text(localized("La meta ya no aparece en el registro actual."))
  ProjectionSource.PLAN -> if (row != null) InfoCard(row.name,
   "${row.group} · ${natureName(row.nature)} · ${money(row.minor, row.currency)}", 0)
  else Text(localized("El cálculo muestra el importe de la partida al corte."))
  ProjectionSource.CREDIT -> if (statement != null) InfoCard(localized("Corte {0}", statement.statement.cutoffDate),
   localized("Vence {0} · Pendiente actual ", statement.statement.dueDate) +
    (ledger.accounts.firstOrNull { it.id == statement.statement.accountId }?.let { money(statement.pendingMinor, it.currency) }
     ?: localized("Sin confirmar")), 0)
  else Text(localized("Consulta el origen del movimiento vinculado para ver la compra."))
  ProjectionSource.SCENARIO -> Text(localized("Importe temporal introducido en el simulador."))
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
 Text(localized("Prueba un gasto adicional o un aumento de ahorro. El escenario es temporal."))
 ProjectionField(localized("Gasto adicional · {0}", currency), spend, numeric = true) { spend = it }
 ProjectionField(localized("Aumento de ahorro · {0}", currency), saving, numeric = true) { saving = it }
 val accounts = result.input.accounts.filter { it.balance.currency == currency }
 if (accounts.isNotEmpty()) {
  Text(localized("Cuenta para el gasto simulado"))
  FilterChip(selected = accountId.isEmpty(), onClick = { accountId = "" },
   label = { Text(localized("Sin cuenta · solo cierre")) }, modifier = Modifier.heightIn(min = 48.dp))
  accounts.forEach { account ->
   FilterChip(selected = account.id == accountId, onClick = { accountId = account.id },
    label = { Text(account.name) }, modifier = Modifier.heightIn(min = 48.dp))
  }
 }
 Action(localized("Calcular escenario")) {
  fun amount(raw: String): Long? = runCatching {
   Money.parse(raw.trim().replace(',', '.').ifBlank { "0" }, currency).minor.takeIf { it >= 0 }
  }.getOrNull()
  val spending = amount(spend)
  val contribution = amount(saving)
  if (spending == null || contribution == null) {
   error = localized("Indica importes válidos mayores o iguales a cero.")
  } else {
   error = ""
   submittedSpend = spending
   submittedSaving = contribution
   submittedAccount = accounts.firstOrNull { it.id == accountId }?.id.orEmpty()
  }
 }
 Action(localized("Restablecer escenario")) {
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
 Text(localized("Resultado simulado"), style = MaterialTheme.typography.titleMedium)
 Text(localized("Remanente original con signo {0}", result.totals?.free.display()))
 val output = simulated
 if (output != null) {
  ProjectionHeadline(output)
  Text(localized("Remanente simulado con signo {0}", output.totals?.free.display()))
  if (submittedAccount.isNotBlank()) output.cash.firstOrNull { it.account.id == submittedAccount }?.let {
   Text(localized("Disponible simulado · {0} · {1}", it.account.name, it.safeToday.display()))
   if (it.firstDeficit != null) Text(localized("Déficit simulado · {0} · {1}", it.firstDeficit.date, it.firstDeficit.balance.display()))
  }
  ProjectionIssues(output.issues)
 } else if (failed) ProjectionError(localized("No se pudo calcular el escenario con esos importes."))
 else LinearProgressIndicator(Modifier.fillMaxWidth())
}
