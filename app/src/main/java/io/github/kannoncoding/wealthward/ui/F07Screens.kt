package io.github.kannoncoding.wealthward.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.kannoncoding.wealthward.domain.*

internal fun debtKindName(kind: DebtKind) = when (kind) {
 DebtKind.PERSONAL_LOAN -> localized("Préstamo personal")
 DebtKind.INSTALLMENT -> localized("Compra en cuotas")
 DebtKind.MORTGAGE -> localized("Hipoteca")
 DebtKind.OTHER -> localized("Otra deuda")
}

internal fun extraPolicyName(policy: ExtraPaymentPolicy) = when (policy) {
 ExtraPaymentPolicy.REDUCE_TERM -> localized("Reducir plazo")
 ExtraPaymentPolicy.REDUCE_INSTALLMENT -> localized("Reducir cuota")
 ExtraPaymentPolicy.MANUAL -> localized("Condiciones por confirmar")
}

internal fun savingActionName(action: SavingAction) = when (action) {
 SavingAction.CONTRIBUTE -> localized("Aporte a ahorro propio")
 SavingAction.WITHDRAW -> localized("Retiro de ahorro a disponible")
 SavingAction.REALLOCATE -> localized("Reasignación entre metas")
 SavingAction.TRANSFER -> localized("Transferencia propia de ahorro")
 SavingAction.EXTERNAL -> localized("Aporte externo · gasto")
}

internal fun creditEvidenceName(kind: CreditEvidenceKind) = when (kind) {
 CreditEvidenceKind.PURCHASE -> localized("Compra con tarjeta")
 CreditEvidenceKind.PAYMENT -> localized("Pago de tarjeta")
}

@Composable internal fun TermDebtCards(plan: F07Snapshot, ledger: F05Snapshot, open: (String, String) -> Unit) {
 Text(localized("Deudas a plazo"), style = MaterialTheme.typography.titleLarge)
 Action(localized("Crear deuda a plazo")) { open("f07debt", "") }
 if (plan.debts.isEmpty()) Text(localized("Aún no hay deudas a plazo."))
 plan.debts.forEach { status ->
  val debt = status.debt
  InfoCard(debt.name, buildString {
   appendLine("${debt.group} · ${debtKindName(debt.kind)}")
   appendLine(localized("Principal registrado {0}", money(status.principalMinor, debt.currency)))
   appendLine(localized("Apertura {0} · {1}", debt.openingDate, money(debt.openingMinor, debt.currency)))
   if (status.principalMinor == 0L) appendLine(localized("Deuda liquidada"))
   else {
    appendLine(localized("Primera cuota {0} · cada {1} mes(es) · cuota inicial {2}", debt.firstDueDate, debt.intervalMonths, money(debt.installmentMinor, debt.currency)))
    appendLine(localized("Tasa anual {0} · {1}", debt.annualRatePercent?.let { "$it %" } ?: localized("Por confirmar"), extraPolicyName(debt.extraPaymentPolicy)))
    appendLine(if (debt.amortization == DebtAmortization.FIXED_MONTHLY) localized("Amortización mensual a tasa fija") else localized("Seguimiento manual"))
    if (status.estimated) appendLine(localized("Proyección estimada. Confirma las condiciones con el acreedor."))
    status.projectedInstallmentMinor?.let { appendLine(localized("Cuota proyectada {0}", money(it, debt.currency))) }
    status.projectedRemainingMonths?.let { appendLine(localized("Plazo restante proyectado {0} cuotas", it)) }
    debt.finalInstallmentMinor?.let { appendLine(localized("Última cuota configurada {0}", money(it, debt.currency))) }
   }
   status.creditorBalance?.let {
    append(localized("Principal informado por el acreedor: {0} · {1}\n{2}", money(it.principalMinor, debt.currency), it.date, it.reference))
   } ?: append(localized("Sin verificación del acreedor"))
  }, 1)
  Action(localized("Verificar saldo · {0}", debt.name)) { open("f07verify", debt.id) }
  if (status.principalMinor > 0) Action(localized("Registrar abono extraordinario · {0}", debt.name)) { open("f07extra", debt.id) }
  status.payments.forEach { payment ->
   val account = ledger.accounts.find { it.id == payment.accountId }
   InfoCard("${payment.date} · ${if (payment.extra) localized("Abono extraordinario") else localized("Cuota {0}", payment.installmentMonth)}",
    localized("{0}\nPrincipal {1}\nIntereses {2}\nCargos {3}\nTotal pagado {4}", account?.name.orEmpty(), money(payment.principalMinor, debt.currency), money(payment.interestMinor, debt.currency), money(payment.chargesMinor, debt.currency), money(payment.totalMinor, debt.currency)), 2)
  }
 }
}

@Composable internal fun SavingAndProjectCards(plan: F07Snapshot, ledger: F05Snapshot, open: (String, String) -> Unit) {
 Panel {
 Text(localized("Metas de ahorro"), style = MaterialTheme.typography.titleLarge)
 Action(localized("Crear meta de ahorro"), accentText = true) { open("f07goal", "") }
 plan.savings.forEach { status ->
  val goal = status.goal
  InfoCard(goal.name, buildString {
   appendLine(localized("{0} · Ahorro propio", goal.group))
   appendLine(localized("Meta {0} para {1} · Prioridad {2}", money(goal.targetMinor, goal.currency), goal.targetDate, goal.priority))
   appendLine(localized("Financiado {0}", money(status.fundedMinor, goal.currency)))
   status.accounts.forEach { reserve ->
    appendLine("${ledger.accounts.find { it.id == reserve.accountId }?.name ?: reserve.name} · ${money(reserve.minor, goal.currency)}")
   }
   appendLine(localized("Aporte mensual previsto {0}", money(goal.monthlyMinor, goal.currency)))
   appendLine(localized("Aportado en el periodo {0}", money(status.contributedThisMonthMinor, goal.currency)))
   append(localized("Aporte adicional alcanzable en el periodo, estimado: {0}", money(status.attainableThisMonthMinor, goal.currency)))
  }, 3)
  Action(localized("Aportar · {0}", goal.name)) { open("f07contribute", goal.id) }
  if (status.fundedMinor > 0) {
   Action(localized("Retirar · {0}", goal.name)) { open("f07withdraw", goal.id) }
   if (plan.savings.any { it.goal.id != goal.id && it.goal.currency == goal.currency }) {
    Action(localized("Reasignar · {0}", goal.name)) { open("f07reallocate", goal.id) }
   }
   if (ledger.accounts.count { !it.archived && it.kind == "CASH" && it.currency == goal.currency } > 1) {
    Action(localized("Transferir ahorro · {0}", goal.name)) { open("f07transfer", goal.id) }
   }
  }
  Action(localized("Registrar aporte externo · {0}", goal.name)) { open("f07external", goal.id) }
  status.events.forEach { event ->
   Text("${event.date} · ${savingActionName(event.action)} · ${money(event.minor, goal.currency)}${event.recipient?.let { " · $it" }.orEmpty()}")
  }
 }
 }
 Panel {
 Text(localized("Proyectos"), style = MaterialTheme.typography.titleLarge)
 Action(localized("Crear proyecto"), accentText = true) { open("f07project", "") }
 plan.projects.forEach { status ->
  val project = status.project
  val goal = plan.savings.find { it.goal.id == project.goalId }?.goal
  InfoCard(project.name, localized("{0} · Proyecto\nObjetivo de financiación {1}\nFinanciado {2}{3}", project.group, money(project.targetMinor, project.currency), money(status.fundedMinor, project.currency), goal?.let { localized("\nMeta vinculada: {0}", it.name) }.orEmpty()), 1)
  Action(localized("Añadir partida · {0}", project.name)) { open("f07item", project.id) }
  status.items.forEach { item ->
   InfoCard(item.item.name, localized("Estimado {0}\nReal {1}", money(item.item.estimatedMinor, project.currency), money(item.actualMinor, project.currency)), 2)
   item.payments.forEach { payment ->
    ledger.operations.find { it.id == payment.operationId }?.let { Text(localized("Movimiento vinculado: {0} · {1}", it.date, it.merchant.ifBlank { it.type })) }
   }
   Action(localized("Vincular pago existente · {0}", item.item.name)) { open("f07projectlink", item.item.id) }
   Action(localized("Registrar pago real · {0}", item.item.name)) { open("f07projectpay", item.item.id) }
  }
 }
}

}

@Composable internal fun CreditStatementCards(plan: F07Snapshot, ledger: F05Snapshot, accountId: String, open: (String, String) -> Unit) {
 val account = ledger.accounts.find { it.id == accountId } ?: return
 Text(localized("Estados de cuenta y comprobantes"), style = MaterialTheme.typography.titleLarge)
 if (!account.archived) {
  Action(localized("Registrar corte de tarjeta")) { open("f07statement", accountId) }
  Action(localized("Vincular correo o comprobante")) { open("f07evidence", accountId) }
 }
 plan.statements.filter { it.statement.accountId == accountId }.forEach { status ->
  val statement = status.statement
  val holds = ledger.holds.filter { it.id in status.pendingHoldIds }
  InfoCard(localized("Corte {0}", statement.cutoffDate), localized("Vencimiento {0}\nSaldo de apertura registrado {1}\nSaldo informado al corte {2}\nPendiente de pago {3}\nCompras pendientes de contabilizar {4} {5}", statement.dueDate, money(status.openingMinor, account.currency), money(statement.statementMinor, account.currency), money(status.pendingMinor, account.currency), account.currency, total(holds.map { it.minor })), 3)
  ledger.holds.filter { it.id in status.pendingHoldIds }.forEach { hold ->
   Text(localized("Pendiente · {0} · {1}", hold.merchant, money(hold.minor, account.currency)))
  }
  ledger.operations.filter { it.id in statement.purchaseIds || it.id in statement.paymentIds }.forEach { operation ->
   Text("${operation.date} · ${operationLabel(operation)}")
  }
  if (!account.archived) Action(localized("Vincular pago al corte {0}", statement.cutoffDate)) { open("f07statementpay", statement.id) }
 }
 plan.evidence.filter { it.accountId == accountId }.forEach { evidence ->
  val operation = ledger.operations.find { it.id == evidence.operationId }
  InfoCard(creditEvidenceName(evidence.kind), "${operation?.let { "${it.date} · ${operationLabel(it)}\n" }.orEmpty()}${evidence.reference}", 2)
 }
}

internal fun operationLabel(operation: OperationDetail) = when (operation.type) {
 "CREDIT_PAYMENT", "FX_CREDIT_PAYMENT" -> localized("Pago de tarjeta")
 else -> operation.merchant.ifBlank { operation.category.ifBlank { operation.type } }
}
