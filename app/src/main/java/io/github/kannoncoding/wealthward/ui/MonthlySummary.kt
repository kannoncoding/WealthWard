package io.github.kannoncoding.wealthward.ui

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale
import java.time.format.DateTimeFormatter
import io.github.kannoncoding.wealthward.domain.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth


private data class Due(
    val id: String,
    val name: String,
    val date: LocalDate?,
    val amount: Money,
    val account: String?,
    val occurrence: PlannedOccurrence? = null,
)

@Composable
internal fun MonthlySummary(
    month: String,
    today: LocalDate,
    ledger: F05Snapshot,
    plan: F06Snapshot,
    commitments: F07Snapshot,
    projection: ProjectionResult?,
    showAllPayments: Boolean = false,
    open: (String, String) -> Unit,
) {
    val palette = LocalPalette.current
    val accounts = ledger.accounts.filter { !it.archived }.associateBy { it.id }
    val cash = accounts.values.filter { it.kind == "CASH" }
    val end = YearMonth.parse(month).atEndOfMonth()
    val occurrences = plan.occurrences.filter { it.nature != PlanNature.INCOME && it.paymentPendingMinor > 0 }
    val pending = occurrences.map {
        Due(it.id, it.name, LocalDate.parse(it.dueDate), Money(it.paymentPendingMinor, it.currency), it.suggestedAccountId, it)
    }
    val ids = plan.occurrences.map { it.id }.toSet()
    val lines = projection?.input?.lines.orEmpty()
    val other = lines.filter {
        it.component == ProjectionComponent.OBLIGATION && it.id !in ids && it.amount != null
    }.map { line ->
        val statement = commitments.statements.find { "statement:${it.statement.id}" == line.id }
        Due(line.id, line.title, line.date, checkNotNull(line.amount), line.accountId ?: statement?.statement?.accountId)
    }
    val personalTemplates = plan.templates.filter { it.effectiveMonth <= month }.groupBy { it.id }.values
        .map { it.maxWith(compareBy<RecurringTemplate> { it.effectiveMonth }.thenBy { it.version }) }
        .filter { it.group.isPersonalGroup() }.map { it.id }.toSet()
    // Statements and purchases supply due dates, but the ledger balance is the
    // current debt, including opening balances and amounts without a statement.
    val cardPayments = accounts.values.filter { it.kind == "LIABILITY" && it.balanceMinor < 0 }.map {
        Due("card:${it.id}", it.name, null, Money(Math.negateExact(it.balanceMinor), it.currency), it.id)
    }
    val thisMonth = pending.filter { it.date!! <= end } +
        other.filter { !it.id.startsWith("credit:") && !it.id.startsWith("statement:") } + cardPayments
    if (showAllPayments) {
        Panel {
            if (thisMonth.isEmpty()) Text(localized("No hay pagos pendientes para este mes."))
            thisMonth.sortedWith(compareBy<Due> { it.date == null }.thenBy { it.date }.thenBy { it.name })
                .forEachIndexed { index, due ->
                    if (index > 0) HorizontalDivider(color = palette.divider)
                    val action = due.occurrence?.let {
                        if (it.templateId.startsWith("term-debt:"))
                            "f07installment" to (it.nominalMonth + "/" + it.templateId.removePrefix("term-debt:"))
                        else "f06pay" to it.id
                    } ?: due.account?.takeIf { due.id.startsWith("card:") }?.let { "payment" to it }
                    UpcomingPaymentRow(due, month, onClick = action?.let { (route, argument) -> { open(route, argument) } })
                }
        }
        return
    }
    val currencies = (cash.map { it.currency } + thisMonth.map { it.amount.currency } +
        lines.mapNotNull { it.amount?.currency }).distinct().ifEmpty { listOf(Currency.CRC) }
    fun Money.value() = BigDecimal.valueOf(minor, 2)
    fun List<Due>.amount(currency: Currency) = filter { it.amount.currency == currency }.sumOf { it.amount.value() }
    fun lineSum(component: ProjectionComponent, currency: Currency) =
        lines.filter { it.component == component }.mapNotNull { it.amount }.filter { it.currency == currency }.sumOf { it.value() }

    val remainingToPay = currencies.associateWith { thisMonth.amount(it) }
    val currentRemaining = currencies.associateWith { currency ->
        cash.filter { it.currency == currency }.sumOf { BigDecimal.valueOf(it.balanceMinor, 2) } -
            (if (projection != null) lineSum(ProjectionComponent.PROTECTED, currency)
             else cash.filter { it.currency == currency }.sumOf { BigDecimal.valueOf(it.reservedMinor, 2) }) -
            remainingToPay.getValue(currency) - lineSum(ProjectionComponent.SAVING, currency) -
            lineSum(ProjectionComponent.VARIABLE, currency)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 5.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BrandLockup()
        Text(localized("Resumen"), style = MaterialTheme.typography.headlineLarge, fontSize = 25.sp, lineHeight = 31.sp)
    }
    BalanceCard(localized("Saldo disponible"), currentRemaining, available = true, ready = projection != null)
    Column(Modifier.fillMaxWidth().padding(horizontal = 5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(localized("Cuentas"), style = MaterialTheme.typography.titleLarge, fontSize = 18.sp)
        accounts.values.forEachIndexed { index, account ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(account.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(summaryAmount(BigDecimal.valueOf(account.balanceMinor, 2), account.currency),
                    color = palette.balanceColor(account.balanceMinor.compareTo(0L)),
                    style = MaterialTheme.typography.titleSmall)
            }
            if (index < accounts.size - 1) HorizontalDivider(color = palette.divider)
        }
    }
    GoldClasp()
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .clickable(role = Role.Button) { open("upcoming", "") }
        .padding(horizontal = 5.dp, vertical = 5.dp)) {
        Text(localized("Próximamente"), style = MaterialTheme.typography.titleLarge, fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 5.dp))
        val preview = (pending + other.filter { it.date != null })
            .filter { it.date != null && it.date <= end }
            .filterNot { due -> due.occurrence?.let { it.group.isPersonalGroup() || it.templateId in personalTemplates } == true }
            .sortedBy { it.date }.take(3)
        preview.forEachIndexed { index, due ->
            UpcomingPaymentRow(due, month)
            if (index < preview.lastIndex) HorizontalDivider(color = palette.divider)
        }
    }
    GoldClasp()
    BalanceCard(localized("Restante por pagar"), remainingToPay, ready = projection != null) {
        if (thisMonth.isNotEmpty()) {
            HorizontalDivider(color = palette.cardInk.copy(alpha = .28f))
            thisMonth.groupBy { it.account?.takeIf(accounts::containsKey) }.forEach { (id, payments) ->
                val label = accounts[id]?.name ?: localized("Sin cuenta asignada")
                for (currency in payments.map { it.amount.currency }.distinct().sortedBy { it.name }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(label, modifier = Modifier.weight(1f), color = palette.cardInk,
                            style = MaterialTheme.typography.bodySmall)
                        Text(summaryAmount(payments.amount(currency), currency), color = palette.cardInk,
                            style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun GoldClasp(double: Boolean = false) {
    Canvas(Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(16.dp)) {
        val gold = Color(0xFFAF8A49)
        val x = center.x
        val r = 3.dp.toPx()
        val gap = 9.dp.toPx()
        val width = 1.dp.toPx()
        for (y in if (double) listOf(center.y - 4.dp.toPx(), center.y + 4.dp.toPx()) else listOf(center.y)) {
            drawLine(gold.copy(alpha = .65f), Offset(0f, y), Offset(x - gap, y), width, StrokeCap.Round)
            drawLine(gold.copy(alpha = .65f), Offset(x + gap, y), Offset(size.width, y), width, StrokeCap.Round)
            drawPath(Path().apply {
                moveTo(x, y - r)
                lineTo(x + r, y)
                lineTo(x, y + r)
                lineTo(x - r, y)
                close()
            }, gold)
        }
    }
}

@Composable
private fun BalanceCard(title: String, amounts: Map<Currency, BigDecimal>, available: Boolean = false, ready: Boolean, detail: @Composable ColumnScope.() -> Unit = {}) {
    val p = LocalPalette.current
    Panel(color = p.card) {
        Text(title, color = p.cardInk, style = MaterialTheme.typography.bodyMedium)
        if (!ready) Text("—", color = p.cardInk, style = MaterialTheme.typography.headlineSmall)
        else amounts.entries.sortedBy { it.key.name }.forEachIndexed { index, (currency, amount) ->
            val color = when {
                !available || amount.signum() == 0 -> p.cardInk
                amount.signum() < 0 -> p.cardNegative
                else -> p.cardPositive
            }
            val size = if (available && index == 0) 30 else 24
            Text(summaryAmount(amount, currency), color = color, style = MaterialTheme.typography.headlineSmall,
                fontSize = size.sp, lineHeight = (size + 3).sp)
        }
        detail()
    }
}

private fun summaryAmount(value: BigDecimal, currency: Currency): String =
    currency.name + " " + NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(value)

private val summaryDate get() = DateTimeFormatter.ofPattern(
    if (AppLanguage.current == AppLanguage.ENGLISH) "MMM d, uuuu" else "d MMM uuuu",
    AppLanguage.current.locale,
)

@Composable
private fun UpcomingPaymentRow(due: Due, month: String, onClick: (() -> Unit)? = null) {
    val click = if (onClick == null) Modifier else Modifier.actionSurface()
        .clickable(role = Role.Button, onClick = onClick)
    Row(Modifier.fillMaxWidth().heightIn(min = if (onClick == null) 40.dp else 48.dp)
        .then(click).padding(horizontal = 3.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(due.name, style = MaterialTheme.typography.titleMedium)
                due.occurrence?.nominalMonth?.let { origin ->
                    val originMonth = YearMonth.parse(origin)
                    val currentMonth = YearMonth.parse(month)
                    val monthFormat = DateTimeFormatter.ofPattern(if (originMonth.year == currentMonth.year) "MMMM" else "MMMM uuuu", AppLanguage.current.locale)
                    if (originMonth < currentMonth) PaymentMonthTag(localized("Pendiente"), overdue = true)
                    PaymentMonthTag(originMonth.format(monthFormat).replaceFirstChar { it.titlecase() })
                }
            }
            Text(due.date?.format(summaryDate)?.replace(".", "") ?: localized("Sin fecha de pago"), style = MaterialTheme.typography.bodySmall)
        }
        Text(summaryAmount(BigDecimal.valueOf(due.amount.minor, 2), due.amount.currency), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PaymentMonthTag(label: String, overdue: Boolean = false) {
    val palette = LocalPalette.current
    Text(label, modifier = Modifier.panelShape(if (overdue) palette.red.background else palette.panel, radius = 6)
        .padding(horizontal = 6.dp, vertical = 2.dp),
        color = if (overdue) palette.red.text else palette.ink, style = MaterialTheme.typography.labelSmall)
}

private fun String.isPersonalGroup() = trim().equals("Personal", ignoreCase = true)
