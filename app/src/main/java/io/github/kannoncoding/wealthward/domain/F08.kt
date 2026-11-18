package io.github.kannoncoding.wealthward.domain

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

enum class ProjectionColor { RED, ORANGE, YELLOW, GREEN }
enum class ProjectionComponent { LIQUID, PROTECTED, INCOME, OBLIGATION, VARIABLE, SAVING, BUFFER }
enum class ProjectionSource { ACCOUNT, OCCURRENCE, MOVEMENT, GOAL, PLAN, SCENARIO, CREDIT }
data class ProjectionRate(val from: Currency, val to: Currency, val rate: BigDecimal, val date: LocalDate, val source: String)
data class ProjectionLink(val source: ProjectionSource, val id: String)
data class ProjectionLine(
    val id: String,
    val title: String,
    val component: ProjectionComponent,
    val amount: Money?,
    val accountId: String? = null,
    val date: LocalDate? = null,
    val links: List<ProjectionLink> = emptyList(),
)
data class ProjectionAccount(
    val id: String,
    val name: String,
    val balance: Money,
    val heldMinor: Long = 0,
    val protectedMinor: Long = 0,
    val obligationReserveMinor: Long = 0,
)
data class ProjectionCashEvent(
    val id: String,
    val title: String,
    val accountId: String?,
    val date: LocalDate?,
    val amount: Money,
    val receipt: Boolean = false,
    val links: List<ProjectionLink> = emptyList(),
    val reserveUsedMinor: Long = 0,
)
data class ProjectionComparison(
    val opening: List<Money> = emptyList(),
    val initialIncome: List<Money> = emptyList(),
    val initialUses: List<Money> = emptyList(),
    val received: List<Money> = emptyList(),
    val financing: List<Money> = emptyList(),
    val paid: List<Money> = emptyList(),
    val adjustments: List<Money> = emptyList(),
    val economicExpense: List<Money> = emptyList(),
    val principalPaid: List<Money> = emptyList(),
    val funded: List<Money> = emptyList(),
    val plannedSaving: List<Money> = emptyList(),
    val initialOpening: List<Money>? = null,
    val initialFinancing: List<Money> = emptyList(),
    val initialBuffer: List<Money> = emptyList(),
)
data class ProjectionInput(
    val month: YearMonth,
    val cutoff: LocalDate,
    val base: Currency,
    val accounts: List<ProjectionAccount>,
    val lines: List<ProjectionLine>,
    val cashEvents: List<ProjectionCashEvent> = emptyList(),
    val rates: List<ProjectionRate> = emptyList(),
    val greenMargin: Money = DEFAULT_GREEN_MARGIN,
    val target: Money? = null,
    val planVersion: Int? = null,
    val comparison: ProjectionComparison = ProjectionComparison(),
    val issues: List<String> = emptyList(),
    val cashIssues: List<String> = emptyList(),
) {
    companion object {
        val DEFAULT_GREEN_MARGIN = Money(3_000_000L, Currency.CRC)
    }
}
data class ProjectionScenario(val spendMinor: Long = 0, val addSavingMinor: Long = 0, val accountId: String? = null)
data class ProjectionTotals(
    val liquid: Money, val protected: Money, val available: Money, val income: Money,
    val obligations: Money, val variable: Money, val saving: Money, val buffer: Money,
    val obligationsMargin: Money, val afterBuffer: Money, val free: Money,
)
data class ProjectionCashPoint(
    val date: LocalDate,
    val accountId: String,
    val title: String,
    val balance: Money,
    val links: List<ProjectionLink> = emptyList(),
    val amount: Money? = null,
)
data class ProjectionCashAccount(
    val account: ProjectionAccount,
    val horizon: LocalDate,
    val safeToday: Money?,
    val firstDeficit: ProjectionCashPoint?,
    val suggestedTransferFrom: List<String>,
)
data class ProjectionResult(
    val input: ProjectionInput,
    val lines: List<ProjectionLine>,
    val native: Map<Currency, ProjectionTotals>,
    val totals: ProjectionTotals?,
    val color: ProjectionColor?,
    val explanation: String,
    val freeRecommended: Money?,
    val shortfall: Money?,
    val issues: List<String>,
    val cashIssues: List<String>,
    val cash: List<ProjectionCashAccount>,
    val calendar: List<ProjectionCashPoint>,
    val actualRemaining: Money?,
    val initialRemaining: Money?,
    val targetProgress: BigDecimal?,
    val actualRemainingWithoutOpening: Money?,
    val cashDaily: Map<LocalDate, List<Money>>,
    val cashWarnings: List<String>,
)

/** Computes a projection from immutable, cut-dated inputs. Cash remains denominated per account. */
fun project(input: ProjectionInput, scenario: ProjectionScenario = ProjectionScenario()): ProjectionResult {
    require(input.cutoff <= input.month.atEndOfMonth())
    require(scenario.spendMinor >= 0 && scenario.addSavingMinor >= 0)
    val account = input.accounts.find { it.id == scenario.accountId }
    val spend = Money(scenario.spendMinor, account?.balance?.currency ?: input.base)
    val lines = input.lines + listOfNotNull(
        if (spend.minor > 0) ProjectionLine("scenario-spend", "Gasto simulado", ProjectionComponent.VARIABLE, spend, account?.id, input.cutoff) else null,
        if (scenario.addSavingMinor > 0) ProjectionLine("scenario-saving", "Ahorro adicional simulado", ProjectionComponent.SAVING, Money(scenario.addSavingMinor, input.base)) else null,
    )
    val issues = input.issues.toMutableList()
    lines.filter { it.amount == null }.forEach { issues += "Importe pendiente de confirmar: ${it.title}" }
    val rates = input.rates.filter { it.to == input.base && it.date <= input.cutoff && it.rate.signum() > 0 && it.source.isNotBlank() }
        .groupBy { it.from }.mapValues { (_, rates) -> rates.maxBy { it.date }.rate }
    fun converted(amount: Money, required: Boolean = true): BigDecimal? {
        if (amount.currency == input.base || amount.minor == 0L) return BigDecimal.valueOf(amount.minor)
        val rate = rates[amount.currency]
        if (rate == null && required) issues += "Falta tipo de cambio ${amount.currency}/${input.base} al corte"
        return rate?.multiply(BigDecimal.valueOf(amount.minor))
    }
    fun money(value: BigDecimal, currency: Currency = input.base) =
        Money(value.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact(), currency)
    val native = (lines.mapNotNull { it.amount?.currency } + input.base).distinct().associateWith { currency ->
        val amounts = lines.filter { it.amount?.currency == currency }.groupBy { it.component }
            .mapValues { (_, entries) -> entries.sumOf { BigDecimal.valueOf(requireNotNull(it.amount).minor) } }
        ProjectionMagnitudes(amounts).totals { money(it, currency) }
    }
    val groups = lines.mapNotNull { line -> line.amount?.let { line.component to converted(it) } }
    val partial = groups.any { it.second == null }
    val amounts = groups.groupBy({ it.first }, { it.second }).mapValues { (_, values) -> values.filterNotNull().sumOf { it } }
    // Derive margins after exact conversion, rounding each displayed magnitude once.
    val magnitudes = if (partial) null else ProjectionMagnitudes(amounts)
    val total = magnitudes?.totals(moneyForBase(input.base))
    val margin = converted(input.greenMargin)
    if (input.greenMargin.minor <= 0) issues += "El margen verde debe ser positivo"
    val summary = input.comparison
    fun sum(items: List<Money>): Money? {
        val values = items.map { converted(it, required = false) }
        return if (values.any { it == null }) null else money(values.filterNotNull().sumOf { it })
    }
    fun signed(items: List<Money>) = items.map { it.copy(minor = Math.negateExact(it.minor)) }
    val initial = summary.initialOpening?.let {
        sum(it + summary.initialIncome + summary.initialFinancing + signed(summary.initialUses) + signed(summary.initialBuffer))
    }
    val flow = summary.received + summary.financing + signed(summary.paid) + summary.adjustments
    val actual = sum(summary.opening + flow)
    val actualWithoutOpening = sum(flow)
    val target = input.target?.let { converted(it, required = false) }
    val authoritative = issues.isEmpty() && total != null
    val color = if (authoritative && margin != null && magnitudes != null) when {
        magnitudes.obligationsMargin.signum() < 0 -> ProjectionColor.RED
        magnitudes.free.signum() < 0 -> ProjectionColor.ORANGE
        magnitudes.free < margin -> ProjectionColor.YELLOW
        else -> ProjectionColor.GREEN
    } else null
    val explanation = when (color) {
        ProjectionColor.RED -> "Los fondos previstos no cubren obligaciones y gasto variable"
        ProjectionColor.ORANGE -> {
            val t = requireNotNull(magnitudes)
            val savingGap = (t.saving - t.obligationsMargin).max(BigDecimal.ZERO)
            val bufferGap = (t.buffer - (t.obligationsMargin - t.saving).max(BigDecimal.ZERO)).max(BigDecimal.ZERO)
            when {
                savingGap.signum() == 0 -> "Falta cubrir el colchón"
                bufferGap.signum() == 0 -> "Falta cubrir el ahorro previsto"
                else -> "Falta cubrir el ahorro previsto y el colchón"
            }
        }
        ProjectionColor.YELLOW -> "El libre proyectado está por debajo del margen verde"
        ProjectionColor.GREEN -> "El libre proyectado alcanza el margen verde"
        null -> "Pronóstico incompleto"
    }
    val cashIssues = input.cashIssues.toMutableList()
    val events = input.cashEvents + if (spend.minor > 0) listOf(
        ProjectionCashEvent("scenario-spend", "Gasto simulado", account?.id, input.cutoff, spend.copy(minor = -spend.minor)),
    ) else emptyList()
    val accountIds = input.accounts.associate { it.id to it.balance.currency }
    val knownEvents = events.filter { event ->
        val valid = event.date != null && accountIds[event.accountId] == event.amount.currency
        if (!valid) cashIssues += "Caja: falta cuenta, fecha o moneda compatible para ${event.title}"
        valid
    }.groupBy { it.accountId }
    val calendar = mutableListOf<ProjectionCashPoint>()
    val cashWarnings = mutableListOf<String>()
    val cash = input.accounts.map { origin ->
        val scheduled = knownEvents[origin.id].orEmpty()
            .sortedWith(compareBy({ maxOf(requireNotNull(it.date), input.cutoff) }, { it.amount.minor >= 0 }, { it.id }))
        scheduled.groupBy { maxOf(requireNotNull(it.date), input.cutoff) }.forEach { (date, sameDay) ->
            if (sameDay.any { it.amount.minor > 0 } && sameDay.any { it.amount.minor < 0 })
                cashWarnings += "${origin.name} · $date: se proyectan pagos antes de cobros porque falta su orden real"
        }
        val receipt = scheduled.firstOrNull { it.receipt && it.amount.minor > 0 }
        val horizon = receipt?.date?.let { maxOf(it, input.cutoff) } ?: input.month.atEndOfMonth()
        var balance = origin.balance.minor
        var usable = listOf(origin.heldMinor, origin.protectedMinor, origin.obligationReserveMinor).fold(balance, Math::subtractExact)
        var minimum = usable
        var deficit: ProjectionCashPoint? = null
        if (usable < 0) deficit = ProjectionCashPoint(input.cutoff, origin.id, "Disponible al corte", Money(usable, origin.balance.currency))
        var duringHorizon = true
        scheduled.forEach { event ->
            val day = maxOf(requireNotNull(event.date), input.cutoff)
            if (event === receipt) duringHorizon = false
            balance = Math.addExact(balance, event.amount.minor)
            usable = Math.addExact(Math.addExact(usable, event.amount.minor), event.reserveUsedMinor)
            val point = ProjectionCashPoint(day, origin.id, event.title, Money(balance, origin.balance.currency), event.links, event.amount)
            calendar += point
            if (usable < 0 && deficit == null) deficit = point.copy(balance = point.balance.copy(minor = usable))
            if (duringHorizon && day <= horizon) minimum = minOf(minimum, usable)
        }
        ProjectionCashAccount(origin, horizon,
            if (cashIssues.isEmpty()) Money(minimum.coerceAtLeast(0), origin.balance.currency) else null,
            deficit, emptyList())
    }.map { entry ->
        if (entry.firstDeficit == null) entry else entry.copy(suggestedTransferFrom = input.accounts.filter {
            it.id != entry.account.id && it.balance.currency == entry.account.balance.currency &&
                it.balance.minor > it.protectedMinor + it.heldMinor + it.obligationReserveMinor
        }.map { it.id })
    }
    val running = input.accounts.groupBy { it.balance.currency }.mapValues { (_, accounts) ->
        accounts.sumOf { BigDecimal.valueOf(it.balance.minor) }
    }.toMutableMap()
    val calendarByDay = calendar.groupBy { it.date }
    val daily = (calendarByDay.keys + input.cutoff).sorted().associateWith { day ->
        calendarByDay[day].orEmpty().forEach { point ->
            val amount = requireNotNull(point.amount)
            running.merge(amount.currency, BigDecimal.valueOf(amount.minor), BigDecimal::add)
        }
        running.map { (currency, value) -> money(value, currency) }
    }
    return ProjectionResult(input, lines, native, total, color, explanation,
        if (authoritative) total?.free?.let { it.copy(minor = it.minor.coerceAtLeast(0)) } else null,
        if (authoritative) total?.free?.let { it.copy(minor = if (it.minor < 0) Math.negateExact(it.minor) else 0) } else null,
        issues.distinct(), cashIssues.distinct(), cash, calendar.sortedBy { it.date }, actual, initial,
        if (authoritative && target != null && target.signum() > 0 && magnitudes != null)
            magnitudes.free.divide(target, java.math.MathContext.DECIMAL64) else null,
        actualWithoutOpening, daily, cashWarnings)
}

private fun moneyForBase(currency: Currency): (BigDecimal) -> Money = {
    Money(it.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact(), currency)
}

private class ProjectionMagnitudes(amounts: Map<ProjectionComponent, BigDecimal>) {
    private fun Map<ProjectionComponent, BigDecimal>.component(key: ProjectionComponent) = this[key] ?: BigDecimal.ZERO
    private val liquid = amounts.component(ProjectionComponent.LIQUID)
    private val protectedFunds = amounts.component(ProjectionComponent.PROTECTED)
    private val available = liquid - protectedFunds
    private val income = amounts.component(ProjectionComponent.INCOME)
    private val obligations = amounts.component(ProjectionComponent.OBLIGATION)
    private val variable = amounts.component(ProjectionComponent.VARIABLE)
    val saving = amounts.component(ProjectionComponent.SAVING)
    val buffer = amounts.component(ProjectionComponent.BUFFER)
    val obligationsMargin = available + income - obligations - variable
    private val afterBuffer = obligationsMargin - buffer
    val free = afterBuffer - saving

    fun totals(money: (BigDecimal) -> Money) =
        ProjectionTotals(money(liquid), money(protectedFunds), money(available), money(income), money(obligations), money(variable),
            money(saving), money(buffer), money(obligationsMargin), money(afterBuffer), money(free))
}
