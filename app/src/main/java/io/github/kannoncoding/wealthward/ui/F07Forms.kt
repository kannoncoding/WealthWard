package io.github.kannoncoding.wealthward.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.kannoncoding.wealthward.domain.*

internal fun f07Title(action: String) = when (action) {
 "f07debt" -> "Crear deuda a plazo"
 "f07verify" -> "Verificar saldo del acreedor"
 "f07installment" -> "Registrar cuota de deuda"
 "f07extra" -> "Registrar abono extraordinario"
 "f07goal" -> "Meta de ahorro"
 "f07contribute" -> "Aportar a ahorro propio"
 "f07withdraw" -> "Retirar ahorro"
 "f07reallocate" -> "Reasignar ahorro"
 "f07transfer" -> "Transferir ahorro entre cuentas propias"
 "f07external" -> "Registrar aporte externo"
 "f07project" -> "Proyecto"
 "f07item" -> "Partida del proyecto"
 "f07projectlink" -> "Vincular pago al proyecto"
 "f07projectpay" -> "Registrar pago del proyecto"
 "f07statement" -> "Corte de tarjeta"
 "f07statementpay" -> "Vincular pago al corte"
 "f07evidence" -> "Vincular correo o comprobante"
 else -> "Deudas, metas y proyectos"
}

@Composable internal fun F07Form(
 action: String, subject: String, month: String, plan: F07Snapshot, ledger: F05Snapshot,
 draft: Drafts, busy: Boolean, onError: (String) -> Unit, submit: (F07Command) -> Unit, cancel: () -> Unit,
) {
 val f = Fields(draft, busy)
 val cash = ledger.accounts.filter { !it.archived && it.kind == "CASH" }
 val command = when (action) {
  "f07debt", "f07verify", "f07installment", "f07extra" -> DebtFields(action, subject, plan, cash, f)
  "f07goal", "f07contribute", "f07withdraw", "f07reallocate", "f07transfer", "f07external" -> SavingFields(action, subject, month, plan, cash, f)
  "f07project", "f07item", "f07projectlink", "f07projectpay" -> ProjectFields(action, subject, plan, ledger, cash, f)
  "f07statement", "f07statementpay", "f07evidence" -> CreditFields(action, subject, plan, ledger, f)
  else -> null
 }
 if (command == null) Text("El registro solicitado no está disponible.")
 else Action("Guardar", enabled = !busy) {
  runCatching(command).onSuccess(submit).onFailure { onError(it.message ?: "Revisa los datos.") }
 }
 Action("Volver", enabled = !busy, onClick = cancel)
}

@Composable private fun DebtFields(
 action: String, subject: String, plan: F07Snapshot, cash: List<AccountDetail>, f: Fields,
): (() -> F07Command)? {
 if (action == "f07debt") {
  f.text("name", "Nombre de la deuda")
  f.text("group", "Grupo visual", "Deudas")
  val currency = f.currency()
  val kind = f.choice("kind", "Tipo financiero", DebtKind.entries.map { it.name to debtKindName(it) })
  f.text("opening", "Principal de apertura", numeric = true)
  f.text("openingDate", "Fecha de apertura · AAAA-MM-DD", today())
  f.text("due", "Primera cuota · AAAA-MM-DD", today())
  f.text("installment", "Importe de la cuota", numeric = true)
  f.text("months", "Plazo restante en meses", numeric = true)
  val amortization = DebtAmortization.valueOf(f.choice("amortization", "Modo de amortización",
   listOf(DebtAmortization.MANUAL.name to "Seguimiento manual", DebtAmortization.FIXED_MONTHLY.name to "Tasa fija mensual")))
  f.text("rate", if (amortization == DebtAmortization.MANUAL) "Tasa anual en % (opcional)" else "Tasa anual confirmada en %", numeric = true)
  f.text("final", "Última cuota si es distinta (opcional)", numeric = true)
  val policy = f.choice("policy", "Condiciones para abonos extras", ExtraPaymentPolicy.entries.map { it.name to extraPolicyName(it) }, ExtraPaymentPolicy.MANUAL.name)
  val account = f.choice("account", "Cuenta de pago sugerida",
   listOf("" to "Elegir al pagar") + cash.filter { it.currency == currency }.map { it.id to it.name })
  Text("Conserva el grupo visual que usas. La apertura registra principal pendiente y las cuotas aparecen en el calendario.")
  return {
   val id = f.draft.request()
   val rate = f.value("rate").trim().ifBlank { null }
   require(amortization == DebtAmortization.MANUAL || rate != null) { "Completa la tasa anual confirmada." }
   F07Command.CreateDebt(id, Debt(id, f.required("name", "nombre"), f.required("group", "grupo visual"), currency, DebtKind.valueOf(kind),
    f.amount("opening", "principal"), f.date("openingDate"), f.date("due"), f.amount("installment", "cuota"),
    f.positiveInt("months", "plazo"), rate,
    f.optionalAmount("final", "última cuota"), ExtraPaymentPolicy.valueOf(policy), account.ifBlank { null }, amortization))
  }
 }
 val installmentMonth = subject.substringBefore("/").takeIf { action == "f07installment" }
 val status = plan.debts.find { it.debt.id == subject.substringAfter("/") } ?: return null
 val debt = status.debt
 Text("${debt.name} · ${debt.currency} · Principal registrado ${money(status.principalMinor, debt.currency)}")
 if (action == "f07verify") {
  Text("El principal registrado se actualizará al saldo que confirmes. Se conservará la referencia de la verificación.")
  f.text("observed", "Principal informado por el acreedor", numeric = true)
  f.text("date", "Fecha del saldo · AAAA-MM-DD", today())
  f.text("reference", "Referencia del acreedor")
  val confirmed = f.check("confirmed", "Confirmo el saldo informado por el acreedor")
  return {
   require(confirmed) { "Confirma el saldo del acreedor." }
   val id = f.draft.request()
   F07Command.VerifyBalance(id, CreditorBalance(id, debt.id, f.date(), f.amount("observed", "principal", allowZero = true), f.required("reference", "referencia")))
  }
 }
 val occurrence = plan.installments.find { it.nominalMonth == installmentMonth && it.templateId == "term-debt:${debt.id}" }
 if (installmentMonth != null) {
  Text("Cuota de $installmentMonth${occurrence?.let { " · Pendiente ${money(it.pendingMinor, debt.currency)}" }.orEmpty()}")
 } else Text("Abono extraordinario · ${extraPolicyName(debt.extraPaymentPolicy)}")
 val account = f.account("account", "Cuenta de origen", cash.filter { it.currency == debt.currency }, debt.suggestedAccountId.orEmpty())
 f.text("date", "Fecha real del pago · AAAA-MM-DD", today())
 f.text("principal", "Principal pagado", "0", numeric = true)
 f.text("interest", "Intereses pagados", "0", numeric = true)
 f.text("charges", "Cargos pagados", "0", numeric = true)
 if (installmentMonth == null) {
  f.text("nextInstallment", "Nueva cuota confirmada (opcional)", numeric = true)
  f.text("newTerm", "Nuevo plazo confirmado en meses (opcional)", numeric = true)
 }
 val confirmed = f.check("confirmed", "Confirmo que este pago realmente ocurrió")
 return {
  require(confirmed) { "Confirma el pago real." }
  require(account.isNotEmpty()) { "Elige una cuenta de origen." }
  val id = f.draft.request()
  F07Command.PayDebt(id, DebtPayment(id, debt.id, account, f.date(), installmentMonth,
   f.amount("principal", "principal", "0", allowZero = true), f.amount("interest", "intereses", "0", allowZero = true),
   f.amount("charges", "cargos", "0", allowZero = true), f.optionalAmount("nextInstallment", "nueva cuota"),
   f.value("newTerm").takeIf { it.isNotBlank() }?.let { f.positiveInt("newTerm", "nuevo plazo") }))
 }
}

@Composable private fun SavingFields(
 action: String, subject: String, month: String, plan: F07Snapshot, cash: List<AccountDetail>, f: Fields,
): (() -> F07Command)? {
 val status = plan.savings.find { it.goal.id == subject }
 val goal = status?.goal
 if (action == "f07goal") {
  f.text("name", "Nombre de la meta")
  f.text("group", "Grupo visual", "Ahorro")
  val currency = f.currency()
  f.text("target", "Monto objetivo", numeric = true)
  f.text("targetDate", "Fecha objetivo · AAAA-MM-DD", today())
  f.text("priority", "Prioridad (1 es la mayor)", "1", numeric = true)
  f.text("monthly", "Aporte mensual previsto", "0", numeric = true)
  return {
   val id = f.draft.request()
   F07Command.SaveGoal(id, SavingGoal(id, f.required("name", "nombre"), f.required("group", "grupo visual"),
    currency, f.amount("target", "objetivo"), f.date("targetDate"), f.positiveInt("priority", "prioridad"),
    f.amount("monthly", "aporte mensual", "0", allowZero = true)))
  }
 }
 if (status == null || goal == null) return null
 val type = when (action) {
  "f07contribute" -> SavingAction.CONTRIBUTE
  "f07withdraw" -> SavingAction.WITHDRAW
  "f07reallocate" -> SavingAction.REALLOCATE
  "f07transfer" -> SavingAction.TRANSFER
  else -> SavingAction.EXTERNAL
 }
 val accounts = cash.filter { it.currency == goal.currency }
 val origin = when (type) {
  SavingAction.CONTRIBUTE, SavingAction.EXTERNAL -> accounts
  else -> accounts.filter { account -> status.accounts.any { it.accountId == account.id && it.minor > 0 } }
 }
 Text("${goal.name} · ${goal.group} · ${goal.currency}")
 when (type) {
  SavingAction.CONTRIBUTE -> Text("Aparta dinero disponible. Aporte adicional estimado para $month: ${money(status.attainableThisMonthMinor, goal.currency)}.")
  SavingAction.WITHDRAW -> Text("Libera dinero de la meta al saldo disponible de la misma cuenta.")
  SavingAction.REALLOCATE -> Text("Mueve la reserva a otra meta en la misma cuenta.")
  SavingAction.TRANSFER -> Text("Confirma un traslado de dinero y su reserva entre cuentas propias.")
  SavingAction.EXTERNAL -> Text("Registra un gasto a favor de otra persona o entidad. El aporte conserva el grupo visual de la meta.")
 }
 val account = f.account("account", "Cuenta de origen", origin)
 val destination = if (type == SavingAction.TRANSFER) f.account("destination", "Cuenta propia de destino", accounts.filter { it.id != account }) else null
 val target = if (type == SavingAction.REALLOCATE) f.choice("goal", "Meta de destino",
  plan.savings.filter { it.goal.id != goal.id && it.goal.currency == goal.currency }.map { it.goal.id to it.goal.name }) else null
 if (type == SavingAction.EXTERNAL) f.text("recipient", "Beneficiario externo")
 f.text("amount", "Importe", numeric = true)
 f.text("date", "Fecha del aporte o movimiento · AAAA-MM-DD", today())
 val confirmed = when (type) {
  SavingAction.TRANSFER -> f.check("confirmed", "Confirmo la transferencia real entre mis cuentas")
  SavingAction.EXTERNAL -> f.check("confirmed", "Confirmo el aporte externo real")
  else -> true
 }
 return {
  require(confirmed) { "Confirma el movimiento real." }
  require(account.isNotEmpty()) { "Elige una cuenta de origen." }
  require(destination != "" && target != "") { "Elige el destino." }
  val id = f.draft.request()
  F07Command.MoveSaving(id, SavingEvent(id, type, goal.id, account, f.date(), f.amount(),
   destination, target, if (type == SavingAction.EXTERNAL) f.required("recipient", "beneficiario") else null))
 }
}

@Composable private fun Fields.currency() =
 Currency.valueOf(choice("currency", "Moneda", Currency.entries.map { it.name to it.name }))

private fun Fields.positiveInt(key: String, label: String) =
 value(key).trim().toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("Revisa $label. Usa un número entero positivo.")

private fun Fields.optionalAmount(key: String, label: String): Long? =
 if (value(key).isBlank()) null else amount(key, label)

@Composable private fun ProjectFields(
 action: String, subject: String, plan: F07Snapshot, ledger: F05Snapshot, cash: List<AccountDetail>, f: Fields,
): (() -> F07Command)? {
 if (action == "f07project") {
  f.text("name", "Nombre del proyecto")
  f.text("group", "Grupo visual", "Proyectos")
  val currency = f.currency()
  f.text("target", "Objetivo de financiación", numeric = true)
  val goal = f.choice("goal", "Meta de ahorro vinculada", listOf("" to "Sin meta vinculada") +
   plan.savings.filter { it.goal.currency == currency }.map { it.goal.id to it.goal.name })
  return {
   val id = f.draft.request()
   F07Command.SaveProject(id, Project(id, f.required("name", "nombre"), f.required("group", "grupo visual"),
    currency, f.amount("target", "objetivo"), goal.ifBlank { null }))
  }
 }
 val owner = plan.projects.find { if (action == "f07item") it.project.id == subject else it.items.any { item -> item.item.id == subject } } ?: return null
 val item = owner.items.find { it.item.id == subject }?.item
 val project = owner.project
 Text("${project.name} · ${project.currency}")
 if (action == "f07item") {
  f.text("name", "Nombre de la partida")
  f.text("estimated", "Importe estimado", numeric = true)
  return {
   val id = f.draft.request()
   F07Command.SaveProjectItem(id, ProjectItem(id, project.id, f.required("name", "nombre"), f.amount("estimated", "estimación", allowZero = true)))
  }
 }
 if (item == null) return null
 Text(item.name)
 if (action == "f07projectlink") {
  val alreadyLinked = plan.projects.flatMap { it.items }.flatMap { it.payments }.map { it.operationId }.toSet()
  val accounts = ledger.accounts.filter { it.currency == project.currency }.map { it.id }.toSet()
  val operations = ledger.operations.filter {
   it.accountId in accounts && it.type in setOf("EXPENSE", "CREDIT_PURCHASE") &&
    !it.voided && it.originalId == null && it.id !in alreadyLinked && !it.id.endsWith(":fee")
  }
  Text("Vincula el gasto registrado para que cuente en la partida.")
  val operation = f.choice("operation", "Pago registrado", operations.reversed().map { it.id to operationChoice(it, project.currency) })
  return {
   require(operation.isNotBlank()) { "Elige un pago registrado." }
   val id = f.draft.request()
   F07Command.LinkProjectPayment(id, ProjectPayment(id, item.id, operation))
  }
 }
 Text("Registra el pago una sola vez. Si ya aparece en Movimientos, usa Vincular pago existente.")
 val account = f.account("account", "Cuenta de origen", cash.filter { it.currency == project.currency })
 f.text("date", "Fecha real del pago · AAAA-MM-DD", today())
 f.text("amount", "Importe pagado", numeric = true)
 val confirmed = f.check("confirmed", "Confirmo que este pago realmente ocurrió")
 return {
  require(confirmed) { "Confirma el pago real." }
  require(account.isNotBlank()) { "Elige una cuenta de origen." }
  F07Command.RecordProjectPayment(f.draft.request(), item.id, account, f.date(), f.amount())
 }
}

@Composable private fun CreditFields(
 action: String, subject: String, plan: F07Snapshot, ledger: F05Snapshot, f: Fields,
): (() -> F07Command)? {
 val old = plan.statements.find { it.statement.id == subject }?.statement
 val accountId = if (action == "f07statementpay") old?.accountId ?: return null else subject
 val account = ledger.accounts.find { it.id == accountId && it.kind == "LIABILITY" && !it.archived } ?: return null
 val purchases = ledger.operations.filter { it.type == "CREDIT_PURCHASE" && it.accountId == account.id }
 val payments = ledger.operations.filter { it.type in setOf("CREDIT_PAYMENT", "FX_CREDIT_PAYMENT") && it.destinationId == account.id }
 Text("${account.name} · ${account.currency}")
 val linked = plan.statements.flatMap { it.statement.purchaseIds + it.statement.paymentIds }.toSet()
 if (action == "f07statementpay" && old != null) {
  Text("Corte ${old.cutoffDate} · Vence ${old.dueDate}")
  val candidates = payments.filter { (it.bookedDate ?: it.date) >= old.cutoffDate && it.id !in linked }
  val operation = f.choice("operation", "Pago registrado", candidates.reversed().map { it.id to operationChoice(it, account.currency) })
  return {
   require(operation.isNotBlank()) { "Elige un pago registrado." }
   F07Command.LinkStatementPayment(f.draft.request(), old.id, operation)
  }
 }
 if (action == "f07evidence") {
  Text("Pega una referencia del correo o comprobante y elige el movimiento que respalda.")
  val kind = CreditEvidenceKind.valueOf(f.choice("kind", "Qué documenta", CreditEvidenceKind.entries.map { it.name to creditEvidenceName(it) }))
  val operations = if (kind == CreditEvidenceKind.PURCHASE) purchases else payments
  val operation = f.choice("operation", "Movimiento de la tarjeta", operations.reversed().map { it.id to operationChoice(it, account.currency) })
  f.text("reference", "Referencia del correo o comprobante")
  return {
   require(operation.isNotBlank()) { "Elige el movimiento de la tarjeta." }
   val id = f.draft.request()
   F07Command.LinkCreditEvidence(id, CreditEvidence(id, account.id, operation, kind, f.required("reference", "referencia")))
  }
 }
 f.text("cutoff", "Fecha de corte · AAAA-MM-DD", today())
 f.text("due", "Fecha de vencimiento · AAAA-MM-DD", today())
 f.text("amount", "Saldo informado al corte", numeric = true)
 Text("Marca los movimientos incluidos por el emisor en este corte.")
 val cutoff = f.value("cutoff", today())
 val purchaseIds = StatementOperations(purchases.filter { (it.bookedDate ?: it.date) <= cutoff && it.id !in linked }, account.currency, "Compra", f)
 val paymentIds = StatementOperations(payments.filter { (it.bookedDate ?: it.date) >= cutoff && it.id !in linked }, account.currency, "Pago", f)
 return {
  val id = f.draft.request()
  F07Command.SaveStatement(id, CreditStatement(id, account.id, f.date("cutoff"), f.date("due"),
   f.amount("amount", "saldo", allowZero = true, signed = true), purchaseIds, paymentIds))
 }
}

@Composable private fun StatementOperations(
 operations: List<OperationDetail>, currency: Currency, label: String, f: Fields,
): List<String> {
 val selected = mutableListOf<String>()
 operations.reversed().forEach { operation ->
  if (f.check("include-${operation.id}", "$label · ${operationChoice(operation, currency)}")) {
   selected.add(operation.id)
  }
 }
 return selected
}

private fun operationChoice(operation: OperationDetail, currency: Currency): String {
 val amount = if (operation.type == "FX_CREDIT_PAYMENT") operation.destinationMinor ?: operation.amountMinor else operation.amountMinor
 return "${operation.date} · ${operationLabel(operation)} · ${money(amount, currency)}${if (operation.voided) " · Anulado" else ""}"
}
