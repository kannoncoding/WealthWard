package io.github.kannoncoding.wealthward.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import io.github.kannoncoding.wealthward.domain.AccountDetail
import io.github.kannoncoding.wealthward.domain.F06Snapshot
import io.github.kannoncoding.wealthward.domain.F07Snapshot
import io.github.kannoncoding.wealthward.domain.PlanNature
import io.github.kannoncoding.wealthward.domain.PlannedOccurrence
import io.github.kannoncoding.wealthward.domain.RecurringTemplate
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal fun monthlyAccountBills(plan: F06Snapshot, month: String): Map<String?, List<PlannedOccurrence>> =
    plan.occurrences.filter {
        it.suggestedAccountId != null && it.dueDate.startsWith(month) &&
            (it.nature == PlanNature.FIXED || it.nature == PlanNature.SUBSCRIPTION)
    }.sortedBy { it.dueDate }.groupBy { it.suggestedAccountId }

internal fun pendingOutsideReserves(account: AccountDetail, bills: List<PlannedOccurrence>): BigDecimal {
    val reserves = account.reserves.associate { it.purposeId to it.minor }.toMutableMap()
    return bills.filter { it.currency == account.currency && it.suggestedAccountId == account.id }
        .sumOf { bill ->
            val purpose = bill.purposeId
            val reserved = if (purpose == null) 0L else reserves[purpose] ?: 0L
            val covered = minOf(reserved, bill.paymentPendingMinor)
            if (purpose != null) reserves[purpose] = reserved - covered
            BigDecimal.valueOf(bill.paymentPendingMinor - covered, 2)
        }
}

@Composable
internal fun AccountMonthlyBills(bills: List<PlannedOccurrence>, templates: List<RecurringTemplate>, open: (String, String) -> Unit) {
    Text(localized("Gastos y apps del mes"), style = MaterialTheme.typography.titleLarge)
    if (bills.isEmpty()) Text(localized("No hay gastos ni apps ligados a esta cuenta este mes."))
    bills.forEachIndexed { index, bill ->
        if (index > 0) GoldClasp()
        val pending = bill.paymentPendingMinor > 0
        Panel(if (pending) Modifier.clickable(role = Role.Button) { open("f06pay", bill.id) } else Modifier) {
            Text(bill.name, style = MaterialTheme.typography.titleMedium)
            val date = LocalDate.parse(bill.dueDate)
                .format(DateTimeFormatter.ofPattern("d MMM uuuu", AppLanguage.current.locale))
            Text((if (bill.nature == PlanNature.SUBSCRIPTION) localized("App o suscripción") else localized("Gasto fijo")) + " · " + date)
            if (bill.shared) {
                val template = templates.firstOrNull { it.id == bill.templateId && it.version == bill.templateVersion }
                val percent = template?.ownPercent?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()
                val ownAmount = money(bill.confirmedMinor ?: bill.expectedMinor, bill.currency)
                Text(localized("Mi aporte: {0}", ownAmount) + if (percent == null) "" else " · $percent%")
                Text(localized("Total compartido: {0}", money(bill.paymentTotalMinor, bill.currency)))
            } else Text(localized("Total ") + money(bill.paymentTotalMinor, bill.currency))
            Text(if (pending) localized("Pendiente ") + money(bill.paymentPendingMinor, bill.currency) else localized("Pagado"),
                color = LocalPalette.current.balanceColor(if (pending) -1 else 1),
                style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun AccountMonthlySavings(account: AccountDetail, commitments: F07Snapshot, month: String) {
    val events = commitments.savings.flatMap { status ->
        status.events.filter { it.date.startsWith(month) && (it.accountId == account.id || it.destinationAccountId == account.id) }
            .map { status.goal.name to it }
    }.sortedByDescending { it.second.date }
    Text(localized("Ahorros de esta cuenta"), style = MaterialTheme.typography.titleLarge)
    if (account.reserves.isEmpty() && events.isEmpty()) Text(localized("No hay reservas ni movimientos de ahorro este mes."))
    account.reserves.forEachIndexed { index, reserve ->
        if (index > 0) GoldClasp()
        Panel {
            Text(reserve.name, style = MaterialTheme.typography.titleMedium)
            Text(localized("Reservado ") + money(reserve.minor, account.currency))
        }
    }
    events.forEachIndexed { index, (name, event) ->
        if (index > 0 || account.reserves.isNotEmpty()) GoldClasp()
        Panel {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(event.date + " · " + savingActionName(event.action))
            Text(money(event.minor, account.currency))
        }
    }
}
