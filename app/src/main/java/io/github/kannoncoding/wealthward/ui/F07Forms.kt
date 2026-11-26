package io.github.kannoncoding.wealthward.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.kannoncoding.wealthward.domain.*
import java.math.BigDecimal
import java.time.LocalDate

internal fun f07Title(action: String) = when (action) {
 "f07debt" -> localized("Crear deuda a plazo")
 "f07maturity" -> localized("Editar vencimiento")
 "f07editLoan" -> localized("Editar préstamo")
 "f07verify" -> localized("Verificar saldo del acreedor")
 "f07installment" -> localized("Registrar cuota de deuda")
 "f07extra" -> localized("Registrar abono extraordinario")
 "f07goal" -> localized("Meta de ahorro")
 "f07contribute" -> localized("Aportar a ahorro propio")
 "f07withdraw" -> localized("Retirar ahorro")
 "f07reallocate" -> localized("Reasignar ahorro")
 "f07transfer" -> localized("Transferir ahorro entre cuentas propias")
 "f07external" -> localized("Registrar aporte externo")
 "f07project" -> localized("Proyecto")
 "f07item" -> localized("Partida del proyecto")
 "f07projectlink" -> localized("Vincular pago al proyecto")
 "f07projectpay" -> localized("Registrar pago del proyecto")
 "f07statement" -> localized("Corte de tarjeta")
 "f07statementpay" -> localized("Vincular pago al corte")
 "f07evidence" -> localized("Vincular correo o comprobante")
 else -> localized("Deudas, metas y proyectos")
}

@Composable internal fun F07Form(
 action: String, subject: String, month: String, plan: F07Snapshot, ledger: F05Snapshot,
 draft: Drafts, busy: Boolean, onError: (String) -> Unit, submit: (F07Command) -> Unit, cancel: () -> Unit,
) {
 val f = Fields(draft, busy)
 val cash = ledger.accounts.filter { !it.archived && it.kind == "CASH" }
 val command = when (action) {
  "f07debt", "f07maturity", "f07verify", "f07installment", "f07extra" -> DebtFields(action, subject, plan, cash, f)
  "f07editLoan" -> plan.debts.find { it.debt.id == subject && it.debt.kind == DebtKind.PERSONAL_LOAN }
   ?.let { EditLoanFields(it, ledger.accounts.filter { account -> account.kind == "CASH" }, f) }
  "f07goal", "f07contribute", "f07withdraw", "f07reallocate", "f07transfer", "f07external" -> SavingFields(action, subject, month, plan, cash, f)
  "f07project", "f07item", "f07projectlink", "f07projectpay" -> ProjectFields(action, subject, plan, ledger, cash, f)
  "f07statement", "f07statementpay", "f07evidence" -> CreditFields(action, subject, plan, ledger, f)
  else -> null
 }
 if (command == null) Text(localized("El registro solicitado no está disponible."))
 else Action(localized("Guardar"), enabled = !busy) {
  runCatching(command).onSuccess(submit).onFailure { onError(it.message ?: localized("Revisa los datos.")) }
 }
 Action(localized("Volver"), enabled = !busy, onClick = cancel)
}

@Composable private fun EditLoanFields(status: DebtStatus, accounts: List<AccountDetail>, f: Fields): () -> F07Command {
 val debt = status.debt
 fun Long.amountText() = BigDecimal.valueOf(this, 2).toPlainString()
 val opening = debt.openingMinor.amountText()
 val installment = debt.installmentMinor.amountText()
 val final = debt.finalInstallmentMinor?.amountText().orEmpty()
 val charges = debt.installmentChargesMinor?.amountText().orEmpty()
 val rate = debt.annualRatePercent.orEmpty()
 val count = debt.remainingMonths.toString()
 f.text("name", localized("Nombre de la deuda"), debt.name)
 f.text("group", localized("Grupo visual"), debt.group)
 if (status.hasHistory) {
  Text(localized("Apertura del préstamo · {0} · {1}", debt.openingDate, money(debt.openingMinor, debt.currency)))
  Text(localized("Para corregir el principal registrado, usa Verificar saldo del acreedor."))
 } else {
  f.text("opening", localized("Saldo total pendiente de la deuda"), opening, numeric = true)
  f.calendar("openingDate", localized("Fecha de apertura"), debt.openingDate)
 }
 val frequency = listOf("1" to localized("Mensual"), "2" to localized("Cada dos meses"), "3" to localized("Trimestral"),
  "6" to localized("Semestral"), "12" to localized("Anual"))
 val interval = if (status.hasScheduledPayments) {
  Text(localized("Primera cuota · {0}", debt.firstDueDate))
  debt.intervalMonths
 } else {
  f.calendar("due", localized("Primera cuota"), debt.firstDueDate)
  val current = debt.intervalMonths.toString()
  val options = if (frequency.any { it.first == current }) frequency else frequency + (current to localized("Cada {0} meses", current))
  f.choice("frequency", localized("Frecuencia de cobro"), options, current).toInt()
 }
 val amortization = if (interval != 1) DebtAmortization.MANUAL else DebtAmortization.valueOf(
  f.choice("amortization", localized("Modo de amortización"),
   listOf(DebtAmortization.MANUAL.name to localized("Seguimiento manual"), DebtAmortization.FIXED_MONTHLY.name to localized("Tasa fija mensual")),
   debt.amortization.name))
 f.text("rate", if (amortization == DebtAmortization.MANUAL) localized("Interés anual aproximado % (opcional)")
  else localized("Tasa anual confirmada en %"), rate, numeric = true)
 f.text("installment", localized("Monto por cuota"), installment, numeric = true)
 f.text("installmentCharges", localized("Cargos incluidos en cada cuota (opcional)"), charges, numeric = true)
 f.text("final", localized("Última cuota si es distinta (opcional)"), final, numeric = true)
 val modes = listOf("COUNT" to localized("Cantidad de cuotas"), "DATE" to localized("Fecha de fin"))
 val endMode = f.choice("endMode", localized("Cómo termina la deuda"),
  if (debt.estimateTermFromBalance) modes + ("BALANCE" to localized("Estimar con saldo y cuota")) else modes,
  when {
   debt.maturityDate != null -> "DATE"
   debt.estimateTermFromBalance -> "BALANCE"
   else -> "COUNT"
  })
 if (endMode == "COUNT") f.text("months", localized("Cantidad de cuotas"), count, numeric = true)
 if (endMode == "DATE") f.calendar("endDate", localized("Fecha de fin"), debt.maturityDate.orEmpty())
 debtEstimateExplanation(endMode, amortization)?.let { Text(it) }
 val policy = f.choice("policy", localized("Condiciones para abonos extras"),
  ExtraPaymentPolicy.entries.map { it.name to extraPolicyName(it) }, debt.extraPaymentPolicy.name)
 val account = f.choice("account", localized("Cuenta de pago sugerida"),
  listOf("" to localized("Elegir al pagar")) + accounts.filter {
   it.currency == debt.currency && (!it.archived || it.id == debt.suggestedAccountId)
  }.map { it.id to it.name }, debt.suggestedAccountId.orEmpty())
 return {
  val openingMinor = if (status.hasHistory) debt.openingMinor else f.amount("opening", localized("principal"), opening)
  val due = if (status.hasScheduledPayments) debt.firstDueDate else f.date("due", debt.firstDueDate)
  val maturity = if (endMode == "DATE") f.date("endDate", debt.maturityDate.orEmpty()) else null
  val regularPayment = f.amount("installment", localized("cuota"), installment)
  val recurringCharges = f.value("installmentCharges", charges).takeIf { it.isNotBlank() }
   ?.let { f.amount("installmentCharges", localized("cargos"), charges, allowZero = true) }
  val annualRate = f.value("rate", rate).trim().takeIf { it.isNotEmpty() }?.let {
   if (it == rate) rate else normalizedDebtRate(it)?.toPlainString()
    ?: throw IllegalArgumentException(AppLanguage.current.backendMessage(DEBT_RATE_ERROR))
  }
  val remaining = when (endMode) {
   "DATE" -> {
    val firstDue = LocalDate.parse(due)
    val lastDue = LocalDate.parse(checkNotNull(maturity))
    require(lastDue >= firstDue) { AppLanguage.current.backendMessage(DEBT_DATE_ERROR) }
    val installments = installmentsThrough(firstDue, lastDue, interval)
    require(installments in 1L..debtInstallmentLimit(interval).toLong()) { AppLanguage.current.backendMessage(DEBT_TERM_LIMIT) }
    installments.toInt()
   }
   "BALANCE" -> {
    val netPayment = regularPayment - (recurringCharges ?: 0)
    val appliedRate = annualRate?.let(::normalizedDebtRate).takeIf { amortization == DebtAmortization.FIXED_MONTHLY }
    require(netPayment > 0) { AppLanguage.current.backendMessage("Recurring charges must be less than the installment and final installment.") }
    require(appliedRate == null || debtMonthInterest(openingMinor, appliedRate) < BigDecimal.valueOf(netPayment)) {
     AppLanguage.current.backendMessage(DEBT_PAYMENT_INTEREST)
    }
    debtPayoff(openingMinor, netPayment, appliedRate, debtInstallmentLimit(interval))?.installments
     ?: throw IllegalArgumentException(AppLanguage.current.backendMessage(DEBT_TERM_LIMIT))
   }
   else -> f.positiveInt("months", localized("cantidad de cuotas"), count)
  }
  val updated = debt.copy(
   name = f.required("name", localized("nombre"), debt.name),
   group = f.required("group", localized("grupo visual"), debt.group),
   openingMinor = openingMinor,
   openingDate = if (status.hasHistory) debt.openingDate else f.date("openingDate", debt.openingDate),
   estimatedOpeningPrincipal = debt.estimatedOpeningPrincipal && openingMinor == debt.openingMinor,
   firstDueDate = due,
   installmentMinor = regularPayment,
   installmentChargesMinor = recurringCharges,
   finalInstallmentMinor = f.value("final", final).takeIf { it.isNotBlank() }
    ?.let { f.amount("final", localized("última cuota"), final) },
   maturityDate = maturity,
   estimateTermFromBalance = endMode == "BALANCE",
   remainingMonths = remaining,
   annualRatePercent = annualRate,
   extraPaymentPolicy = ExtraPaymentPolicy.valueOf(policy),
   suggestedAccountId = account.ifBlank { null },
   amortization = amortization,
   intervalMonths = interval,
  )
  F07Command.EditLoan(f.draft.request(), updated)
 }
}

@Composable private fun DebtFields(
 action: String, subject: String, plan: F07Snapshot, cash: List<AccountDetail>, f: Fields,
): (() -> F07Command)? {
 if (action == "f07debt") {
  f.text("name", localized("Nombre de la deuda"))
  f.text("group", localized("Grupo visual"), "Deudas")
  val currency = f.currency()
  val kind = f.choice("kind", localized("Tipo financiero"), DebtKind.entries.map { it.name to debtKindName(it) })
  val mortgage = kind == DebtKind.MORTGAGE.name
  val endModes = listOf("COUNT" to localized("Cantidad de cuotas"), "DATE" to localized("Fecha de fin"))
  val endMode = f.choice("endMode", localized("Cómo termina la deuda"),
   if (mortgage) endModes + ("BALANCE" to localized("Estimar con saldo y cuota")) else endModes,
   if (mortgage) "DATE" else "COUNT")
  val canEstimateOpening = mortgage && endMode == "DATE"
  f.text("opening", if (canEstimateOpening) localized("Saldo pendiente (opcional si deseas estimarlo)") else localized("Saldo total pendiente de la deuda"), numeric = true)
  f.calendar("openingDate", localized("Fecha de apertura"), today())
  f.calendar("due", localized("Primera cuota"), today())
  val interval = f.choice("frequency", localized("Frecuencia de cobro"), listOf("1" to localized("Mensual"), "2" to localized("Cada dos meses"), "3" to localized("Trimestral"), "6" to localized("Semestral"), "12" to localized("Anual"))).toInt()
  f.text("installment", localized("Monto por cuota"), numeric = true)
  if (mortgage) f.text("installmentCharges", localized("Cargos incluidos en cada cuota (opcional)"), numeric = true)
  if (endMode == "COUNT") f.text("months", localized("Cuotas restantes"), numeric = true)
  if (endMode == "DATE") f.calendar("endDate", localized("Fecha de fin"))
  val amortization = if (interval != 1) DebtAmortization.MANUAL else DebtAmortization.valueOf(f.choice("amortization", localized("Modo de amortización"),
   listOf(DebtAmortization.MANUAL.name to localized("Seguimiento manual"), DebtAmortization.FIXED_MONTHLY.name to localized("Tasa fija mensual"))))
  f.text("rate", if (amortization == DebtAmortization.MANUAL) localized("Interés anual aproximado % (opcional)") else localized("Tasa anual confirmada en %"), numeric = true)
  if (endMode != "BALANCE") f.text("final", localized("Última cuota si es distinta (opcional)"), numeric = true)
  val policy = f.choice("policy", localized("Condiciones para abonos extras"), ExtraPaymentPolicy.entries.map { it.name to extraPolicyName(it) }, ExtraPaymentPolicy.MANUAL.name)
  val account = f.choice("account", localized("Cuenta de pago sugerida"),
   listOf("" to localized("Elegir al pagar")) + cash.filter { it.currency == currency }.map { it.id to it.name })
  debtEstimateExplanation(endMode, amortization)?.let { Text(it) }
  if (canEstimateOpening) {
   Text(localized("Si dejas el saldo pendiente vacío, se guardará un saldo de apertura estimado según las cuotas, la amortización y los cargos indicados."))
   Text(debtOpeningAssumption(amortization))
  }
  Text(localized("Conserva el grupo visual que usas. La apertura registra principal pendiente y las cuotas aparecen en el calendario."))
  return {
   val id = f.draft.request()
   val enteredOpening = if (canEstimateOpening && f.value("opening").isBlank()) null else f.amount("opening", localized("principal"))
   val payment = f.amount("installment", localized("cuota"))
   val finalPayment = if (endMode == "BALANCE") null else f.optionalAmount("final", localized("última cuota"))
   val charges = if (mortgage && f.value("installmentCharges").isNotBlank())
    f.amount("installmentCharges", localized("cargos"), allowZero = true) else null
   val chargeAmount = charges ?: 0L
   require(chargeAmount < payment && (finalPayment == null || chargeAmount < finalPayment)) {
    AppLanguage.current.backendMessage("Recurring charges must be less than the installment and final installment.")
   }
   val netPayment = payment - chargeAmount
   val due = LocalDate.parse(f.date("due"))
   val maturity = if (endMode == "DATE") f.date("endDate", "") else null
   val rate = f.value("rate").trim().takeIf { it.isNotEmpty() }?.let {
    normalizedDebtRate(it) ?: throw IllegalArgumentException(AppLanguage.current.backendMessage(DEBT_RATE_ERROR))
   }
   require(amortization == DebtAmortization.MANUAL || rate != null) { localized("Completa la tasa anual confirmada.") }
   val appliedRate = rate.takeIf { amortization == DebtAmortization.FIXED_MONTHLY }
   val limit = debtInstallmentLimit(interval)
   val count = when (endMode) {
    "DATE" -> {
     val end = LocalDate.parse(maturity!!)
     require(end >= due) { AppLanguage.current.backendMessage(DEBT_DATE_ERROR) }
     val installments = installmentsThrough(due, end, interval)
     require(installments in 1L..limit.toLong()) { AppLanguage.current.backendMessage(DEBT_TERM_LIMIT) }
     installments.toInt()
    }
    "BALANCE" -> {
     val opening = checkNotNull(enteredOpening)
     require(appliedRate == null || debtMonthInterest(opening, appliedRate) < BigDecimal.valueOf(netPayment)) {
      AppLanguage.current.backendMessage(DEBT_PAYMENT_INTEREST)
     }
     debtPayoff(opening, netPayment, appliedRate, limit)?.installments
      ?: throw IllegalArgumentException(AppLanguage.current.backendMessage(DEBT_TERM_LIMIT))
    }
    else -> f.positiveInt("months", localized("cantidad de cuotas"))
   }
   val opening = enteredOpening ?: try {
    debtPrincipalFromPayments(netPayment, (finalPayment ?: payment) - chargeAmount, count, appliedRate)
   } catch (_: ArithmeticException) {
    throw IllegalArgumentException(localized("El saldo estimado excede el importe admitido. Revisa la cuota y la fecha de fin."))
   }
   require(opening > 0) { localized("El saldo estimado debe ser positivo. Revisa la cuota, la tasa y los cargos.") }
   F07Command.CreateDebt(id, Debt(id, f.required("name", localized("nombre")), f.required("group", localized("grupo visual")), currency, DebtKind.valueOf(kind),
    opening, f.date("openingDate"), due.toString(), payment,
    count, rate?.toPlainString(),
    finalPayment, ExtraPaymentPolicy.valueOf(policy), account.ifBlank { null },
    amortization, interval, maturity, endMode == "BALANCE", enteredOpening == null, charges))
  }
 }
 val installmentMonth = subject.substringBefore("/").takeIf { action == "f07installment" }
 val status = plan.debts.find { it.debt.id == subject.substringAfter("/") } ?: return null
 val debt = status.debt
 Text(localized("{0} · {1} · Principal registrado {2}", debt.name, debt.currency, money(status.principalMinor, debt.currency)))
 if (action == "f07maturity") {
  f.calendar("endDate", localized("Fecha de fin"), debt.maturityDate.orEmpty())
  debtEstimateExplanation("DATE", debt.amortization)?.let { Text(it) }
  return { F07Command.UpdateDebtMaturity(f.draft.request(), debt.id, f.date("endDate", debt.maturityDate.orEmpty())) }
 }
 if (action == "f07verify") {
  Text(localized("El principal registrado se actualizará al saldo que confirmes. Se conservará la referencia de la verificación."))
  f.text("observed", localized("Principal informado por el acreedor"), numeric = true)
  f.calendar("date", localized("Fecha del saldo"), today())
  f.text("reference", localized("Referencia del acreedor"))
  val confirmed = f.check("confirmed", localized("Confirmo el saldo informado por el acreedor"))
  return {
   require(confirmed) { localized("Confirma el saldo del acreedor.") }
   val id = f.draft.request()
   F07Command.VerifyBalance(id, CreditorBalance(id, debt.id, f.date(), f.amount("observed", "principal", allowZero = true), f.required("reference", localized("referencia"))))
  }
 }
 val occurrence = plan.installments.find { it.nominalMonth == installmentMonth && it.templateId == "term-debt:${debt.id}" }
 if (installmentMonth != null) {
  Text(localized("Cuota de {0}{1}", installmentMonth, occurrence?.let { localized(" · Pendiente {0}", money(it.pendingMinor, debt.currency)) }.orEmpty()))
 } else Text(localized("Abono extraordinario · {0}", extraPolicyName(debt.extraPaymentPolicy)))
 val account = f.account("account", localized("Cuenta de origen"), cash.filter { it.currency == debt.currency }, debt.suggestedAccountId.orEmpty())
 f.calendar("date", localized("Fecha real del pago"), today())
 f.text("principal", localized("Principal pagado"), "0", numeric = true)
 f.text("interest", localized("Intereses pagados"), "0", numeric = true)
 f.text("charges", localized("Cargos pagados"), "0", numeric = true)
 if (installmentMonth == null) {
  f.text("nextInstallment", localized("Nueva cuota confirmada (opcional)"), numeric = true)
  f.text("newTerm", localized("Nueva cantidad de cuotas confirmada (opcional)"), numeric = true)
 }
 val confirmed = f.check("confirmed", localized("Confirmo que este pago realmente ocurrió"))
 return {
  require(confirmed) { localized("Confirma el pago real.") }
  require(account.isNotEmpty()) { localized("Elige una cuenta de origen.") }
  val id = f.draft.request()
  F07Command.PayDebt(id, DebtPayment(id, debt.id, account, f.date(), installmentMonth,
   f.amount("principal", "principal", "0", allowZero = true), f.amount("interest", localized("intereses"), "0", allowZero = true),
   f.amount("charges", localized("cargos"), "0", allowZero = true), f.optionalAmount("nextInstallment", localized("nueva cuota")),
   f.value("newTerm").takeIf { it.isNotBlank() }?.let { f.positiveInt("newTerm", localized("nuevo plazo")) }))
 }
}

@Composable private fun SavingFields(
 action: String, subject: String, month: String, plan: F07Snapshot, cash: List<AccountDetail>, f: Fields,
): (() -> F07Command)? {
 val status = plan.savings.find { it.goal.id == subject }
 val goal = status?.goal
 if (action == "f07goal") {
  f.text("name", localized("Nombre de la meta"))
  f.text("group", localized("Grupo visual"), "Ahorro")
  val currency = f.currency()
  f.text("target", localized("Monto objetivo"), numeric = true)
  f.calendar("targetDate", localized("Fecha objetivo"), today())
  f.text("priority", localized("Prioridad (1 es la mayor)"), "1", numeric = true)
  f.text("monthly", localized("Aporte mensual previsto"), "0", numeric = true)
  return {
   val id = f.draft.request()
   F07Command.SaveGoal(id, SavingGoal(id, f.required("name", localized("nombre")), f.required("group", localized("grupo visual")),
    currency, f.amount("target", localized("objetivo")), f.date("targetDate"), f.positiveInt("priority", localized("prioridad")),
    f.amount("monthly", localized("aporte mensual"), "0", allowZero = true)))
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
  SavingAction.CONTRIBUTE -> Text(localized("Aparta dinero disponible. Aporte adicional estimado para {0}: {1}.", month, money(status.attainableThisMonthMinor, goal.currency)))
  SavingAction.WITHDRAW -> Text(localized("Libera dinero de la meta al saldo disponible de la misma cuenta."))
  SavingAction.REALLOCATE -> Text(localized("Mueve la reserva a otra meta en la misma cuenta."))
  SavingAction.TRANSFER -> Text(localized("Confirma un traslado de dinero y su reserva entre cuentas propias."))
  SavingAction.EXTERNAL -> Text(localized("Registra un gasto a favor de otra persona o entidad. El aporte conserva el grupo visual de la meta."))
 }
 val account = f.account("account", localized("Cuenta de origen"), origin)
 val destination = if (type == SavingAction.TRANSFER) f.account("destination", localized("Cuenta propia de destino"), accounts.filter { it.id != account }) else null
 val target = if (type == SavingAction.REALLOCATE) f.choice("goal", localized("Meta de destino"),
  plan.savings.filter { it.goal.id != goal.id && it.goal.currency == goal.currency }.map { it.goal.id to it.goal.name }) else null
 if (type == SavingAction.EXTERNAL) f.text("recipient", localized("Beneficiario externo"))
 f.text("amount", localized("Monto/Importe"), numeric = true)
 f.calendar("date", localized("Fecha del aporte o movimiento"), today())
 val confirmed = when (type) {
  SavingAction.TRANSFER -> f.check("confirmed", localized("Confirmo la transferencia real entre mis cuentas"))
  SavingAction.EXTERNAL -> f.check("confirmed", localized("Confirmo el aporte externo real"))
  else -> true
 }
 return {
  require(confirmed) { localized("Confirma el movimiento real.") }
  require(account.isNotEmpty()) { localized("Elige una cuenta de origen.") }
  require(destination != "" && target != "") { localized("Elige el destino.") }
  val id = f.draft.request()
  F07Command.MoveSaving(id, SavingEvent(id, type, goal.id, account, f.date(), f.amount(),
   destination, target, if (type == SavingAction.EXTERNAL) f.required("recipient", localized("beneficiario")) else null))
 }
}

@Composable private fun Fields.currency() =
 Currency.valueOf(choice("currency", localized("Moneda"), Currency.entries.map { it.name to it.name }))

private fun Fields.positiveInt(key: String, label: String, default: String = "") =
 value(key, default).trim().toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException(localized("Revisa {0}. Usa un número entero positivo.", label))

private fun Fields.optionalAmount(key: String, label: String): Long? =
 if (value(key).isBlank()) null else amount(key, label)

@Composable private fun ProjectFields(
 action: String, subject: String, plan: F07Snapshot, ledger: F05Snapshot, cash: List<AccountDetail>, f: Fields,
): (() -> F07Command)? {
 if (action == "f07project") {
  f.text("name", localized("Nombre del proyecto"))
  f.text("group", localized("Grupo visual"), "Proyectos")
  val currency = f.currency()
  f.text("target", localized("Objetivo de financiación"), numeric = true)
  val goal = f.choice("goal", localized("Meta de ahorro vinculada"), listOf("" to localized("Sin meta vinculada")) +
   plan.savings.filter { it.goal.currency == currency }.map { it.goal.id to it.goal.name })
  return {
   val id = f.draft.request()
   F07Command.SaveProject(id, Project(id, f.required("name", localized("nombre")), f.required("group", localized("grupo visual")),
    currency, f.amount("target", localized("objetivo")), goal.ifBlank { null }))
  }
 }
 val owner = plan.projects.find { if (action == "f07item") it.project.id == subject else it.items.any { item -> item.item.id == subject } } ?: return null
 val item = owner.items.find { it.item.id == subject }?.item
 val project = owner.project
 Text("${project.name} · ${project.currency}")
 if (action == "f07item") {
  f.text("name", localized("Nombre de la partida"))
  f.text("estimated", localized("Importe estimado"), numeric = true)
  return {
   val id = f.draft.request()
   F07Command.SaveProjectItem(id, ProjectItem(id, project.id, f.required("name", localized("nombre")), f.amount("estimated", localized("estimación"), allowZero = true)))
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
  Text(localized("Vincula el gasto registrado para que cuente en la partida."))
  val operation = f.choice("operation", localized("Pago registrado"), operations.reversed().map { it.id to operationChoice(it, project.currency) })
  return {
   require(operation.isNotBlank()) { localized("Elige un pago registrado.") }
   val id = f.draft.request()
   F07Command.LinkProjectPayment(id, ProjectPayment(id, item.id, operation))
  }
 }
 Text(localized("Registra el pago una sola vez. Si ya aparece en Movimientos, usa Vincular pago existente."))
 val account = f.account("account", localized("Cuenta de origen"), cash.filter { it.currency == project.currency })
 f.calendar("date", localized("Fecha real del pago"), today())
 f.text("amount", localized("Importe pagado"), numeric = true)
 val confirmed = f.check("confirmed", localized("Confirmo que este pago realmente ocurrió"))
 return {
  require(confirmed) { localized("Confirma el pago real.") }
  require(account.isNotBlank()) { localized("Elige una cuenta de origen.") }
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
  Text(localized("Corte {0} · Vence {1}", old.cutoffDate, old.dueDate))
  val candidates = payments.filter { (it.bookedDate ?: it.date) >= old.cutoffDate && it.id !in linked }
  val operation = f.choice("operation", localized("Pago registrado"), candidates.reversed().map { it.id to operationChoice(it, account.currency) })
  return {
   require(operation.isNotBlank()) { localized("Elige un pago registrado.") }
   F07Command.LinkStatementPayment(f.draft.request(), old.id, operation)
  }
 }
 if (action == "f07evidence") {
  Text(localized("Pega una referencia del correo o comprobante y elige el movimiento que respalda."))
  val kind = CreditEvidenceKind.valueOf(f.choice("kind", localized("Qué documenta"), CreditEvidenceKind.entries.map { it.name to creditEvidenceName(it) }))
  val operations = if (kind == CreditEvidenceKind.PURCHASE) purchases else payments
  val operation = f.choice("operation", localized("Movimiento de la tarjeta"), operations.reversed().map { it.id to operationChoice(it, account.currency) })
  f.text("reference", localized("Referencia del correo o comprobante"))
  return {
   require(operation.isNotBlank()) { localized("Elige el movimiento de la tarjeta.") }
   val id = f.draft.request()
   F07Command.LinkCreditEvidence(id, CreditEvidence(id, account.id, operation, kind, f.required("reference", localized("referencia"))))
  }
 }
 f.calendar("cutoff", localized("Fecha de corte"), today())
 f.calendar("due", localized("Fecha de vencimiento"), today())
 f.text("amount", localized("Saldo informado al corte"), numeric = true)
 Text(localized("Marca los movimientos incluidos por el emisor en este corte."))
 val cutoff = f.value("cutoff", today())
 val purchaseIds = StatementOperations(purchases.filter { (it.bookedDate ?: it.date) <= cutoff && it.id !in linked }, account.currency, localized("Compra"), f)
 val paymentIds = StatementOperations(payments.filter { (it.bookedDate ?: it.date) >= cutoff && it.id !in linked }, account.currency, localized("Pago"), f)
 return {
  val id = f.draft.request()
  F07Command.SaveStatement(id, CreditStatement(id, account.id, f.date("cutoff"), f.date("due"),
   f.amount("amount", localized("saldo"), allowZero = true, signed = true), purchaseIds, paymentIds))
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
 return "${operation.date} · ${operationLabel(operation)} · ${money(amount, currency)}${if (operation.voided) localized(" · Anulado") else ""}"
}
