package io.github.kannoncoding.wealthward.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.kannoncoding.wealthward.domain.*

internal fun debtKindName(kind: DebtKind) = when (kind) {
 DebtKind.PERSONAL_LOAN -> "Préstamo personal"
 DebtKind.INSTALLMENT -> "Compra en cuotas"
 DebtKind.MORTGAGE -> "Hipoteca"
 DebtKind.OTHER -> "Otra deuda"
}

internal fun extraPolicyName(policy: ExtraPaymentPolicy) = when (policy) {
 ExtraPaymentPolicy.REDUCE_TERM -> "Reducir plazo"
 ExtraPaymentPolicy.REDUCE_INSTALLMENT -> "Reducir cuota"
 ExtraPaymentPolicy.MANUAL -> "Condiciones por confirmar"
}

internal fun savingActionName(action: SavingAction) = when (action) {
 SavingAction.CONTRIBUTE -> "Aporte a ahorro propio"
 SavingAction.WITHDRAW -> "Retiro de ahorro a disponible"
 SavingAction.REALLOCATE -> "Reasignación entre metas"
 SavingAction.TRANSFER -> "Transferencia propia de ahorro"
 SavingAction.EXTERNAL -> "Aporte externo · gasto"
}

internal fun creditEvidenceName(kind: CreditEvidenceKind) = when (kind) {
 CreditEvidenceKind.PURCHASE -> "Compra con tarjeta"
 CreditEvidenceKind.PAYMENT -> "Pago de tarjeta"
}

@Composable internal fun TermDebtCards(plan: F07Snapshot, ledger: F05Snapshot, open: (String, String) -> Unit) {
 Text("Deudas a plazo", style = MaterialTheme.typography.titleLarge)
 Action("Crear deuda a plazo") { open("f07debt", "") }
 if (plan.debts.isEmpty()) Text("Aún no hay deudas a plazo.")
 plan.debts.forEach { status ->
  val debt = status.debt
  InfoCard(debt.name, buildString {
   appendLine("${debt.group} · ${debtKindName(debt.kind)}")
   appendLine("Principal registrado ${money(status.principalMinor, debt.currency)}")
   appendLine("Apertura ${debt.openingDate} · ${money(debt.openingMinor, debt.currency)}")
   if (status.principalMinor == 0L) appendLine("Deuda liquidada")
   else {
    appendLine("Primera cuota ${debt.firstDueDate} · cuota inicial ${money(debt.installmentMinor, debt.currency)}")
    appendLine("Tasa anual ${debt.annualRatePercent?.let { "$it %" } ?: "Por confirmar"} · ${extraPolicyName(debt.extraPaymentPolicy)}")
    appendLine(if (debt.amortization == DebtAmortization.FIXED_MONTHLY) "Amortización mensual a tasa fija" else "Seguimiento manual")
    if (status.estimated) appendLine("Proyección estimada. Confirma las condiciones con el acreedor.")
    status.projectedInstallmentMinor?.let { appendLine("Cuota proyectada ${money(it, debt.currency)}") }
    status.projectedRemainingMonths?.let { appendLine("Plazo restante proyectado $it meses") }
    debt.finalInstallmentMinor?.let { appendLine("Última cuota configurada ${money(it, debt.currency)}") }
   }
   status.creditorBalance?.let {
    append("Principal informado por el acreedor: ${money(it.principalMinor, debt.currency)} · ${it.date}\n${it.reference}")
   } ?: append("Sin verificación del acreedor")
  }, 1)
  Action("Verificar saldo · ${debt.name}") { open("f07verify", debt.id) }
  if (status.principalMinor > 0) Action("Registrar abono extraordinario · ${debt.name}") { open("f07extra", debt.id) }
  status.payments.forEach { payment ->
   val account = ledger.accounts.find { it.id == payment.accountId }
   InfoCard("${payment.date} · ${if (payment.extra) "Abono extraordinario" else "Cuota ${payment.installmentMonth}"}",
    "${account?.name.orEmpty()}\nPrincipal ${money(payment.principalMinor, debt.currency)}\nIntereses ${money(payment.interestMinor, debt.currency)}\nCargos ${money(payment.chargesMinor, debt.currency)}\nTotal pagado ${money(payment.totalMinor, debt.currency)}", 2)
  }
 }
}

@Composable internal fun SavingAndProjectCards(plan: F07Snapshot, ledger: F05Snapshot, open: (String, String) -> Unit) {
 Text("Metas de ahorro", style = MaterialTheme.typography.titleLarge)
 Action("Crear meta de ahorro") { open("f07goal", "") }
 plan.savings.forEach { status ->
  val goal = status.goal
  InfoCard(goal.name, buildString {
   appendLine("${goal.group} · Ahorro propio")
   appendLine("Meta ${money(goal.targetMinor, goal.currency)} para ${goal.targetDate} · Prioridad ${goal.priority}")
   appendLine("Financiado ${money(status.fundedMinor, goal.currency)}")
   status.accounts.forEach { reserve ->
    appendLine("${ledger.accounts.find { it.id == reserve.accountId }?.name ?: reserve.name} · ${money(reserve.minor, goal.currency)}")
   }
   appendLine("Aporte mensual previsto ${money(goal.monthlyMinor, goal.currency)}")
   appendLine("Aportado en el periodo ${money(status.contributedThisMonthMinor, goal.currency)}")
   append("Aporte adicional alcanzable en el periodo, estimado: ${money(status.attainableThisMonthMinor, goal.currency)}")
  }, 3)
  Action("Aportar · ${goal.name}") { open("f07contribute", goal.id) }
  if (status.fundedMinor > 0) {
   Action("Retirar · ${goal.name}") { open("f07withdraw", goal.id) }
   if (plan.savings.any { it.goal.id != goal.id && it.goal.currency == goal.currency }) {
    Action("Reasignar · ${goal.name}") { open("f07reallocate", goal.id) }
   }
   if (ledger.accounts.count { !it.archived && it.kind == "CASH" && it.currency == goal.currency } > 1) {
    Action("Transferir ahorro · ${goal.name}") { open("f07transfer", goal.id) }
   }
  }
  Action("Registrar aporte externo · ${goal.name}") { open("f07external", goal.id) }
  status.events.forEach { event ->
   Text("${event.date} · ${savingActionName(event.action)} · ${money(event.minor, goal.currency)}${event.recipient?.let { " · $it" }.orEmpty()}")
  }
 }
 Text("Proyectos", style = MaterialTheme.typography.titleLarge)
 Action("Crear proyecto") { open("f07project", "") }
 plan.projects.forEach { status ->
  val project = status.project
  val goal = plan.savings.find { it.goal.id == project.goalId }?.goal
  InfoCard(project.name, "${project.group} · Proyecto\nObjetivo de financiación ${money(project.targetMinor, project.currency)}\nFinanciado ${money(status.fundedMinor, project.currency)}${goal?.let { "\nMeta vinculada: ${it.name}" }.orEmpty()}", 1)
  Action("Añadir partida · ${project.name}") { open("f07item", project.id) }
  status.items.forEach { item ->
   InfoCard(item.item.name, "Estimado ${money(item.item.estimatedMinor, project.currency)}\nReal ${money(item.actualMinor, project.currency)}", 2)
   item.payments.forEach { payment ->
    ledger.operations.find { it.id == payment.operationId }?.let { Text("Movimiento vinculado: ${it.date} · ${it.merchant.ifBlank { it.type }}") }
   }
   Action("Vincular pago existente · ${item.item.name}") { open("f07projectlink", item.item.id) }
   Action("Registrar pago real · ${item.item.name}") { open("f07projectpay", item.item.id) }
  }
 }
}

@Composable internal fun CreditStatementCards(plan: F07Snapshot, ledger: F05Snapshot, accountId: String, open: (String, String) -> Unit) {
 val account = ledger.accounts.find { it.id == accountId } ?: return
 Text("Estados de cuenta y comprobantes", style = MaterialTheme.typography.titleLarge)
 if (!account.archived) {
  Action("Registrar corte de tarjeta") { open("f07statement", accountId) }
  Action("Vincular correo o comprobante") { open("f07evidence", accountId) }
 }
 plan.statements.filter { it.statement.accountId == accountId }.forEach { status ->
  val statement = status.statement
  val holds = ledger.holds.filter { it.id in status.pendingHoldIds }
  InfoCard("Corte ${statement.cutoffDate}", "Vencimiento ${statement.dueDate}\nSaldo de apertura registrado ${money(status.openingMinor, account.currency)}\nSaldo informado al corte ${money(statement.statementMinor, account.currency)}\nPendiente de pago ${money(status.pendingMinor, account.currency)}\nCompras pendientes de contabilizar ${account.currency} ${total(holds.map { it.minor })}", 3)
  ledger.holds.filter { it.id in status.pendingHoldIds }.forEach { hold ->
   Text("Pendiente · ${hold.merchant} · ${money(hold.minor, account.currency)}")
  }
  ledger.operations.filter { it.id in statement.purchaseIds || it.id in statement.paymentIds }.forEach { operation ->
   Text("${operation.date} · ${operationLabel(operation)}")
  }
  if (!account.archived) Action("Vincular pago al corte ${statement.cutoffDate}") { open("f07statementpay", statement.id) }
 }
 plan.evidence.filter { it.accountId == accountId }.forEach { evidence ->
  val operation = ledger.operations.find { it.id == evidence.operationId }
  InfoCard(creditEvidenceName(evidence.kind), "${operation?.let { "${it.date} · ${operationLabel(it)}\n" }.orEmpty()}${evidence.reference}", 2)
 }
}

internal fun operationLabel(operation: OperationDetail) = when (operation.type) {
 "CREDIT_PAYMENT", "FX_CREDIT_PAYMENT" -> "Pago de tarjeta"
 else -> operation.merchant.ifBlank { operation.category.ifBlank { operation.type } }
}
