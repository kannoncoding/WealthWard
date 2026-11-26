package io.github.kannoncoding.wealthward.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.kannoncoding.wealthward.domain.*

internal fun debtKindName(kind: DebtKind) = when (kind) {
 DebtKind.PERSONAL_LOAN -> localized("Préstamo")
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

internal fun debtOpeningAssumption(amortization: DebtAmortization): String =
 if (amortization == DebtAmortization.FIXED_MONTHLY)
  localized("El saldo se estima con la tasa fija mensual. Si faltan cargos, se suponen cero.")
 else localized("Con seguimiento manual, el saldo se estima sin intereses. Si faltan cargos, se suponen cero.")

internal fun debtEstimateExplanation(mode: String, amortization: DebtAmortization): String? = when {
 mode == "DATE" -> localized("El plazo estimado cuenta las cuotas según la frecuencia hasta la fecha de fin indicada.")
 mode == "BALANCE" && amortization == DebtAmortization.FIXED_MONTHLY ->
  localized("El plazo estimado usa la tasa fija mensual y descuenta los cargos indicados de cada cuota. Si faltan cargos, se suponen cero.")
 mode == "BALANCE" -> localized("La estimación descuenta los cargos indicados y supone que el resto de cada cuota reduce el principal, sin intereses.")
 else -> null
}

@Composable internal fun TermDebtCards(plan: F07Snapshot, ledger: F05Snapshot, open: (String, String) -> Unit, installments: @Composable (String) -> Unit) {
 Action(localized("Crear deuda")) { open("f07debt", "") }
 GoldClasp()
 Text(localized("Lista de deudas actuales"), style = MaterialTheme.typography.titleLarge)
 if (plan.debts.isEmpty()) Text(localized("Aún no hay deudas a plazo."))
 plan.debts.forEachIndexed { index, status ->
  if (index > 0) GoldClasp()
  val debt = status.debt
  val actions = buildList {
   if (debt.kind == DebtKind.PERSONAL_LOAN) {
    add(recordAction(localized("Editar préstamo"), debt.name) { open("f07editLoan", debt.id) })
   }
   if (debt.kind == DebtKind.MORTGAGE || debt.maturityDate != null) {
    add(recordAction(localized("Editar vencimiento"), debt.name) { open("f07maturity", debt.id) })
   }
   add(recordAction(localized("Verificar saldo"), debt.name) { open("f07verify", debt.id) })
   if (status.principalMinor > 0) {
    add(recordAction(localized("Registrar abono extraordinario"), debt.name) { open("f07extra", debt.id) })
   }
  }
  RecordCard(debt.name, buildString {
   appendLine("${debt.group} · ${debtKindName(debt.kind)}")
   appendLine(localized("Principal registrado {0}", money(status.principalMinor, debt.currency)))
   val opening = money(debt.openingMinor, debt.currency)
   appendLine(if (debt.estimatedOpeningPrincipal) localized("Apertura estimada {0} · {1}", debt.openingDate, opening)
    else localized("Apertura {0} · {1}", debt.openingDate, opening))
   debt.maturityDate?.let { appendLine(localized("Fecha de fin registrada {0}", it)) }
   if (status.principalMinor == 0L) appendLine(localized("Deuda liquidada"))
   else {
    appendLine(localized("Primera cuota {0} · cada {1} mes(es) · cuota inicial {2}", debt.firstDueDate, debt.intervalMonths, money(debt.installmentMinor, debt.currency)))
    appendLine(localized("Tasa anual {0} · {1}", debt.annualRatePercent?.let { "$it %" } ?: localized("Por confirmar"), extraPolicyName(debt.extraPaymentPolicy)))
    appendLine(if (debt.amortization == DebtAmortization.FIXED_MONTHLY) localized("Amortización mensual a tasa fija") else localized("Seguimiento manual"))
    if (status.estimated) appendLine(localized("Proyección estimada. Confirma las condiciones con el acreedor."))
    status.projectedInstallmentMinor?.let { appendLine(localized("Cuota proyectada {0}", money(it, debt.currency))) }
    val mode = when {
     debt.maturityDate != null -> "DATE"
     debt.estimateTermFromBalance -> "BALANCE"
     else -> "COUNT"
    }
    debtEstimateExplanation(mode, debt.amortization)?.let { appendLine(it) }
    if (debt.estimatedOpeningPrincipal) appendLine(debtOpeningAssumption(debt.amortization))
    debt.installmentChargesMinor?.let { appendLine(localized("Cargos por cuota {0}", money(it, debt.currency))) }
    if (debt.estimateTermFromBalance) status.projectedPayoffDate?.let {
     appendLine(localized("Fecha de cancelación estimada {0}", it))
    }
    status.projectedRemainingMonths?.let { appendLine(localized("Plazo restante proyectado {0} cuotas", it)) }
    debt.finalInstallmentMinor?.let { appendLine(localized("Última cuota configurada {0}", money(it, debt.currency))) }
   }
   status.creditorBalance?.let {
    append(localized("Principal informado por el acreedor: {0} · {1}\n{2}", money(it.principalMinor, debt.currency), it.date, it.reference))
   } ?: append(localized("Sin verificación del acreedor"))
  }, actions)
  status.payments.forEach { payment ->
   val account = ledger.accounts.find { it.id == payment.accountId }
   InfoCard("${payment.date} · ${if (payment.extra) localized("Abono extraordinario") else localized("Cuota {0}", payment.installmentMonth)}",
    localized("{0}\nPrincipal {1}\nIntereses {2}\nCargos {3}\nTotal pagado {4}", account?.name.orEmpty(), money(payment.principalMinor, debt.currency), money(payment.interestMinor, debt.currency), money(payment.chargesMinor, debt.currency), money(payment.totalMinor, debt.currency)), 2)
  }
  installments(debt.id)
 }
}

@Composable internal fun SavingAndProjectCards(plan: F07Snapshot, ledger: F05Snapshot, open: (String, String) -> Unit) {
 Panel {
 Text(localized("Metas de ahorro"), style = MaterialTheme.typography.titleLarge)
 Action(localized("Crear meta de ahorro"), accentText = true) { open("f07goal", "") }
 plan.savings.forEach { status ->
  val goal = status.goal
  val actions = buildList {
   add(recordAction(localized("Aportar"), goal.name) { open("f07contribute", goal.id) })
   if (status.fundedMinor > 0) {
    add(recordAction(localized("Retirar"), goal.name) { open("f07withdraw", goal.id) })
    if (plan.savings.any { it.goal.id != goal.id && it.goal.currency == goal.currency }) {
     add(recordAction(localized("Reasignar"), goal.name) { open("f07reallocate", goal.id) })
    }
    if (ledger.accounts.count { !it.archived && it.kind == "CASH" && it.currency == goal.currency } > 1) {
     add(recordAction(localized("Transferir ahorro"), goal.name) { open("f07transfer", goal.id) })
    }
   }
   add(recordAction(localized("Registrar aporte externo"), goal.name) { open("f07external", goal.id) })
  }
  RecordCard(goal.name, buildString {
   appendLine(localized("{0} · Ahorro propio", goal.group))
   appendLine(localized("Meta {0} para {1} · Prioridad {2}", money(goal.targetMinor, goal.currency), goal.targetDate, goal.priority))
   appendLine(localized("Financiado {0}", money(status.fundedMinor, goal.currency)))
   status.accounts.forEach { reserve ->
    appendLine("${ledger.accounts.find { it.id == reserve.accountId }?.name ?: reserve.name} · ${money(reserve.minor, goal.currency)}")
   }
   appendLine(localized("Aporte mensual previsto {0}", money(goal.monthlyMinor, goal.currency)))
   appendLine(localized("Aportado en el periodo {0}", money(status.contributedThisMonthMinor, goal.currency)))
   append(localized("Aporte adicional alcanzable en el periodo, estimado: {0}", money(status.attainableThisMonthMinor, goal.currency)))
  }, actions)
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
  RecordCard(project.name, localized("{0} · Proyecto\nObjetivo de financiación {1}\nFinanciado {2}{3}", project.group, money(project.targetMinor, project.currency), money(status.fundedMinor, project.currency), goal?.let { localized("\nMeta vinculada: {0}", it.name) }.orEmpty()),
   listOf(recordAction(localized("Añadir partida"), project.name) { open("f07item", project.id) }))
  status.items.forEach { item ->
   RecordCard(item.item.name, localized("Estimado {0}\nReal {1}", money(item.item.estimatedMinor, project.currency), money(item.actualMinor, project.currency)),
    listOf(
     recordAction(localized("Vincular pago existente"), item.item.name) { open("f07projectlink", item.item.id) },
     recordAction(localized("Registrar pago real"), item.item.name) { open("f07projectpay", item.item.id) },
    ))
   item.payments.forEach { payment ->
    ledger.operations.find { it.id == payment.operationId }?.let { Text(localized("Movimiento vinculado: {0} · {1}", it.date, it.merchant.ifBlank { it.type })) }
   }
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
