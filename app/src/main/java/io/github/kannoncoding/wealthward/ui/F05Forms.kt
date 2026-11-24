package io.github.kannoncoding.wealthward.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.kannoncoding.wealthward.domain.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

internal class Fields(val draft: Drafts, val busy: Boolean) {
 fun value(key: String, default: String = "") = draft.get(key, default)
 fun required(key: String, label: String, default: String = ""): String = value(key, default).trim().also { require(it.isNotBlank()) { localized("Completa {0}.", label) } }
 fun amount(key: String = "amount", label: String = localized("importe"), default: String = "", allowZero: Boolean = false, signed: Boolean = false): Long {
  val raw = required(key, label, default)
  val minor = try { BigDecimal(raw).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact() } catch (_: Exception) { throw IllegalArgumentException(localized("Revisa {0}: usa números y como máximo dos decimales, con punto decimal.", label)) }
  require(signed || if (allowZero) minor >= 0 else minor > 0) { localized("El {0} debe ser {1}.", label, if (allowZero) localized("cero o positivo") else localized("positivo")) }
  return minor
 }
 fun date(key: String = "date", default: String = today()): String = required(key, localized("fecha"), default).also { try { LocalDate.parse(it) } catch (_: Exception) { throw IllegalArgumentException(localized("Fecha inválida. Usa AAAA-MM-DD.")) } }
 fun optionalDate(key: String, default: String = ""): String? = value(key, default).trim().ifBlank { null }?.also { try { LocalDate.parse(it) } catch (_: Exception) { throw IllegalArgumentException(localized("Fecha inválida. Usa AAAA-MM-DD.")) } }
 @Composable fun text(key: String, label: String, default: String = "", numeric: Boolean = false, lines: Int = 1) {
  val storageKey = "${draft.scope}:$key"
  LaunchedEffect(storageKey) { if (!draft.values.containsKey(storageKey)) draft.values[storageKey] = default }
  OutlinedTextField(value(key, default), { draft.set(key, it) }, label = { Text(label) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = label }, singleLine = lines == 1, minLines = lines, keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
 }
 @Composable fun choice(key: String, label: String, choices: List<Pair<String, String>>, default: String = ""): String {
  val validDefault = default.takeIf { id -> choices.any { it.first == id } } ?: choices.firstOrNull()?.first.orEmpty()
  val selected = value(key, validDefault).takeIf { id -> choices.any { it.first == id } } ?: validDefault
  var expanded by remember { mutableStateOf(false) }
  Column {
   Text(label, style = MaterialTheme.typography.labelLarge)
   Box {
    OutlinedButton(onClick = { expanded = true }, enabled = !busy && choices.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label }) { Text(choices.find { it.first == selected }?.second ?: localized("Sin opciones elegibles")) }
    DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
     choices.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { draft.set(key, id); expanded = false }, modifier = Modifier.heightIn(min = 48.dp)) }
    }
   }
  }
  return selected
 }
 @Composable fun check(key: String, label: String, default: Boolean = false): Boolean {
  val checked = value(key, default.toString()) == "true"
  Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = !busy, role = Role.Checkbox, onValueChange = { draft.set(key, it.toString()) }), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Checkbox(checked, null, enabled = !busy); Text(label, modifier = Modifier.weight(1f)) }
  return checked
 }
 @Composable fun account(key: String, label: String, accounts: List<AccountDetail>, default: String = ""): String = choice(key, label, accounts.map { it.id to "${it.name} · ${it.currency}${if (it.kind == "LIABILITY") localized(" · Crédito") else ""}${if (it.archived) localized(" · Archivada") else ""}" }, default)
}

@Composable internal fun F05Form(action: String, subject: String, snapshot: F05Snapshot, draft: Drafts, busy: Boolean, onError: (String) -> Unit, onSubmit: (F05Command) -> Unit, onCancel: () -> Unit, preview: suspend (List<ImportRow>) -> List<ImportIssue>) {
 val f = Fields(draft, busy)
 val active = snapshot.accounts.filter { !it.archived }
 val cash = active.filter { it.kind != "LIABILITY" }
 val credit = active.filter { it.kind == "LIABILITY" }
 val operation = snapshot.operations.find { it.id == subject }
 val selectedAccount = snapshot.accounts.find { it.id == subject }
 val hold = snapshot.holds.find { it.id == subject }
 var command: (() -> F05Command)? = null
 var saveLabel = localized("Guardar")
 when (action) {
  "account" -> {
   f.text("name", localized("Nombre de la cuenta"))
   val kind = f.choice("kind", localized("Tipo"), listOf("CASH" to localized("Depósito propio"), "PHYSICAL_CASH" to localized("Efectivo físico"), "LIABILITY" to localized("Tarjeta de crédito · deuda")))
   val currency = f.choice("currency", localized("Moneda nativa"), Currency.entries.map { it.name to it.name })
   val eligibleProducts = snapshot.accounts.groupBy { it.productId }.filter { (_, accounts) -> accounts.all { it.kind == if (kind == "LIABILITY") "LIABILITY" else "CASH" } && accounts.none { it.currency.name == currency } && !accounts.first().productId.startsWith("cash") }
   val product = if (kind != "PHYSICAL_CASH") f.choice("product", localized("Agrupar en producto"), listOf("new" to localized("Crear producto separado")) + eligibleProducts.map { (id, accounts) -> id to accounts.first().name.substringBefore(" · ") }) else "new"
   f.text("opening", if (kind == "LIABILITY") localized("Deuda inicial confirmada (positiva)") else localized("Saldo inicial confirmado"), "0.00", true)
   f.text("date", localized("Fecha de apertura · AAAA-MM-DD"), today())
   if (kind == "LIABILITY") f.text("limit", localized("Límite de crédito (no es efectivo)"), "0.00", true)
   Text(localized("La moneda y el tipo quedan protegidos al existir historial. El nombre puede cambiar sin alterar los movimientos."))
   command = { val id = draft.request(); F05Command.CreateAccount(id, "account-$id", if (kind == "PHYSICAL_CASH") "cash-$id" else if (product == "new") "product-$id" else product, f.required("name", localized("nombre")), Currency.valueOf(currency), if (kind == "PHYSICAL_CASH") "CASH" else kind, f.amount("opening", localized("saldo de apertura"), "0.00", true).let { if (kind == "LIABILITY") -it else it }, f.date(), if (kind == "LIABILITY") f.amount("limit", localized("límite"), "0.00", true) else 0) }
  }
  "editAccount" -> {
   val current = selectedAccount
   if (current == null) {
    Text(localized("Cuenta no disponible."))
   } else {
    f.text("name", localized("Nombre de la cuenta"), current.name)
    val currentKind = if (current.kind == "CASH" && current.productId.startsWith("cash")) "PHYSICAL_CASH" else current.kind
    val kind = f.choice("kind", localized("Tipo"), listOf("CASH" to localized("Depósito propio"), "PHYSICAL_CASH" to localized("Efectivo físico"),
     "LIABILITY" to localized("Tarjeta de crédito · deuda")), currentKind)
    val currency = Currency.valueOf(f.choice("currency", localized("Moneda nativa"), Currency.entries.map { it.name to it.name }, current.currency.name))
    val currencyChanged = currency != current.currency
    f.text("date", localized("Fecha de apertura · AAAA-MM-DD"), current.openingDate.orEmpty())
    val limitDefault = if (currencyChanged) "" else decimal(current.creditLimitMinor)
    val limitKey = "limit-${currency.name}"
    if (kind == "LIABILITY") f.text(limitKey, localized("Límite de crédito · {0}", currency.name), limitDefault, true)
    val rateKey = "rate-${current.currency.name}-${currency.name}"
    if (currencyChanged) {
     Text(localized("Saldo actual: {0}", money(current.balanceMinor, current.currency)))
     f.text(rateKey, localized("Tipo de cambio manual · {0} por 1 {1}", currency.name, current.currency.name), numeric = true)
     val converted = f.value(rateKey).trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.let { rate ->
      runCatching { Money(current.balanceMinor, current.currency).convert(currency, rate) }.getOrNull()
     }
     if (converted != null) Text(localized("Saldo convertido: {0}", money(converted.minor, converted.currency)))
    }
    if (currencyChanged || kind != currentKind) Text(localized("El cambio de moneda o tipo está disponible para cuentas sin movimientos ni referencias dependientes."))
    command = {
     val rate = if (currencyChanged) f.required(rateKey, localized("el tipo de cambio")).also { raw ->
      val value = raw.toBigDecimalOrNull()
      require(value != null && value.signum() > 0) { localized("Ingresa un tipo de cambio manual positivo.") }
      Money(current.balanceMinor, current.currency).convert(currency, value)
     } else null
     val openingDate = if (current.openingDate == null) f.optionalDate("date") else f.date("date", current.openingDate)
     val limit = if (kind == "LIABILITY") f.amount(limitKey, localized("límite"), limitDefault, allowZero = true) else 0L
     F05Command.EditAccount(draft.request(), subject, f.required("name", localized("nombre"), current.name), currency, kind,
      openingDate, limit, rate, current.currency, current.balanceMinor)
    }
   }
  }
  "rename" -> {
   Text(localized("{0} · {1}. La moneda y el tipo se conservan.", selectedAccount?.currency, selectedAccount?.kind))
   f.text("name", localized("Nombre de la cuenta"), selectedAccount?.name.orEmpty())
   command = { F05Command.RenameAccount(draft.request(), subject, f.required("name", localized("nombre"), selectedAccount?.name.orEmpty())) }
  }
  "archive" -> {
   Text("${selectedAccount?.name}\n${if (selectedAccount?.archived == true) localized("Volverá a los selectores de nuevas operaciones.") else localized("Se conserva todo el historial. La cuenta dejará de aparecer en nuevas operaciones.")}")
   val confirmed = f.check("confirmed", localized("Confirmo este cambio"))
   command = { require(confirmed) { localized("Confirma el cambio de estado.") }; F05Command.ArchiveAccount(draft.request(), subject, selectedAccount?.archived != true) }
  }
  "expense", "income", "credit", "correct" -> {
   val type = when (action) { "income" -> "INCOME"; "credit" -> "CREDIT_PURCHASE"; "correct" -> operation?.type ?: "EXPENSE"; else -> "EXPENSE" }
   val account = f.account("account", if (type == "CREDIT_PURCHASE") localized("Tarjeta y moneda de la compra") else localized("Cuenta y moneda"), if (type == "CREDIT_PURCHASE") credit else cash, operation?.accountId ?: selectedAccount?.id.orEmpty())
   val alreadyDeducted = type != "INCOME" && f.check("alreadyDeducted", localized("Este pago ya se había descontado de la cuenta seleccionada"), operation?.previouslyDeducted == true)
   if (alreadyDeducted) Text(localized("Se registrará el gasto sin volver a cambiar el saldo."))
   val current = active.find { it.id == account }
   val funding = if (type == "CREDIT_PURCHASE" && !alreadyDeducted) f.choice("fundingAccount", localized("Origen previsto del pago (opcional)"), listOf("" to localized("Sin origen confirmado")) + cash.filter { it.currency == current?.currency }.map { it.id to "${it.name} · ${it.currency}" }, operation?.fundingAccountId.orEmpty()) else ""
   val fundingPurpose = if (type == "CREDIT_PURCHASE" && funding.isNotBlank()) f.choice("fundingPurpose", localized("Propósito previsto (no se consume al comprar)"), listOf("" to localized("Sin reserva prevista")) + cash.find { it.id == funding }?.reserves.orEmpty().map { it.purposeId to it.name }, operation?.fundingPurposeId.orEmpty()) else ""
   Text(localized("Moneda: {0}", current?.currency ?: localized("Selecciona una cuenta")) + if (type == "CREDIT_PURCHASE" && !alreadyDeducted) localized(" · La compra aumenta la deuda. El pago se registra por separado.") else "")
   f.text("amount", localized("Importe"), operation?.let { decimal(it.amountMinor) }.orEmpty(), true)
   f.text("date", localized("Fecha · AAAA-MM-DD"), operation?.date ?: today())
   f.text("bookedDate", localized("Fecha contabilizada (si difiere) · AAAA-MM-DD"), operation?.bookedDate?.takeIf { it != operation.date }.orEmpty())
   f.text("merchant", if (type == "INCOME") localized("Origen del ingreso") else localized("Comercio"), operation?.merchant.orEmpty())
   f.text("category", localized("Categoría"), operation?.category.orEmpty())
   f.text("notes", localized("Notas (opcional)"), operation?.notes.orEmpty(), lines = 2)
   val count = f.value("splitCount", operation?.splits?.size?.toString() ?: "0").toIntOrNull() ?: 0
   Text(localized("Desglose por categoría · suma exacta al importe"), style = MaterialTheme.typography.titleMedium)
   val splitPurposes = mutableListOf<String?>()
   repeat(count) { index ->
    val initial = operation?.splits?.getOrNull(index)
    f.text("splitCategory$index", localized("Categoría del desglose {0}", index + 1), initial?.category.orEmpty())
    f.text("splitAmount$index", localized("Importe del desglose {0}", index + 1), initial?.let { decimal(it.minor) }.orEmpty(), true)
    val purpose = if (!alreadyDeducted) f.choice("splitPurpose$index", localized("Propósito del desglose {0}", index + 1), listOf("" to localized("Sin reserva")) + (current?.reserves.orEmpty().map { it.purposeId to it.name }), initial?.purposeId.orEmpty()).ifBlank { null } else null
    splitPurposes.add(purpose)
    Action(localized("Quitar desglose {0}", index + 1), !busy) {
     for (j in index until count - 1) listOf("splitCategory", "splitAmount", "splitPurpose").forEach { key -> draft.set("$key$j", f.value("$key${j + 1}", operation?.splits?.getOrNull(j + 1)?.let { next -> when (key) { "splitCategory" -> next.category; "splitAmount" -> decimal(next.minor); else -> next.purposeId.orEmpty() } }.orEmpty())) }
     draft.set("splitCount", (count - 1).toString())
    }
   }
   Action(localized("Añadir desglose"), !busy) { draft.set("splitCount", (count + 1).toString()) }
   if (count > 0) Text(localized("Total del desglose: ") + runCatching { (0 until count).fold(0L) { sum, index -> Math.addExact(sum, f.amount("splitAmount$index", localized("desglose"), operation?.splits?.getOrNull(index)?.let { p -> decimal(p.minor) }.orEmpty())) } }.getOrNull()?.let { decimal(it) }.orEmpty())
   if (action == "correct") { InfoCard(localized("El original se conserva"), localized("Se registra una corrección con trazabilidad. Las conciliaciones afectadas deberán revisarse otra vez."), 3); f.text("reason", localized("Motivo de la corrección"), lines = 2) }
   command = {
    require(account.isNotBlank()) { localized("Selecciona una cuenta elegible.") }
    val amount = f.amount(default = operation?.let { decimal(it.amountMinor) }.orEmpty(), allowZero = alreadyDeducted)
    val splits = (0 until count).map { i -> val initial = operation?.splits?.getOrNull(i); ExpenseSplit(f.required("splitCategory$i", localized("categoría del desglose"), initial?.category.orEmpty()), f.amount("splitAmount$i", localized("desglose"), initial?.let { decimal(it.minor) }.orEmpty()), splitPurposes[i]) }
    if (splits.isNotEmpty()) require(splits.fold(0L) { sum, split -> Math.addExact(sum, split.minor) } == amount) { localized("La suma del desglose debe coincidir exactamente con el importe.") }
    val request = F05Command.Record(if (action == "correct") draft.request() + ":replacement" else draft.request(), account, type, amount, f.date(default = operation?.date ?: today()), f.required("merchant", localized("comercio u origen"), operation?.merchant.orEmpty()), f.required("category", localized("categoría"), operation?.category.orEmpty()), f.value("notes", operation?.notes.orEmpty()), splits, f.optionalDate("bookedDate", operation?.bookedDate?.takeIf { it != operation.date }.orEmpty()), funding.ifBlank { null }, fundingPurpose.ifBlank { null }, previouslyDeducted = alreadyDeducted)
    if (action == "correct") F05Command.Correct(draft.request(), subject, request, f.required("reason", localized("motivo"))) else request
   }
  }
  "void", "refund" -> {
   InfoCard(localized("Movimiento original"), localized("{0} · {1}\nImporte {2}\nReembolsado {3}\nEl original y su auditoría se conservan.", operation?.date, operation?.merchant, operation?.let { decimal(it.amountMinor) }, operation?.let { decimal(it.refundedMinor) }), 3)
   if (action == "refund") { f.text("amount", localized("Importe del reembolso parcial"), numeric = true); f.text("date", localized("Fecha · AAAA-MM-DD"), today()); f.text("bookedDate", localized("Fecha contabilizada (si difiere) · AAAA-MM-DD")) }
   f.text("reason", localized("Motivo obligatorio"), lines = 2)
   val confirmed = f.check("confirmed", if (action == "void") localized("Confirmo la anulación y revisión de conciliaciones afectadas") else localized("Confirmo el reembolso y su restitución de reserva"))
   command = { require(confirmed) { localized("Revisa la vista previa y confirma.") }; if (action == "void") F05Command.Void(draft.request(), subject, f.required("reason", localized("motivo"))) else F05Command.Refund(draft.request(), subject, f.amount(), f.date(), f.required("reason", localized("motivo")), f.optionalDate("bookedDate")) }
  }
  "transfer", "withdraw", "fx", "payment", "moveReserve" -> {
   val source = f.account("source", localized("Cuenta de origen"), cash, selectedAccount?.id.orEmpty())
   val origin = cash.find { it.id == source }
   val destinations = (if (action == "payment") credit else cash).filter { it.id != source && (if (action == "fx") it.currency != origin?.currency else it.currency == origin?.currency) }.filter { if (action == "withdraw") it.productId.startsWith("cash") || it.name.contains(localized("efectivo"), true) else true }
   val destination = f.account("destination", if (action == "payment") localized("Tarjeta y moneda a pagar") else localized("Cuenta de destino"), destinations, if (action == "payment") selectedAccount?.id.orEmpty() else "")
   val target = destinations.find { it.id == destination }
   if (destinations.isEmpty()) Text(if (action == "withdraw") localized("Primero crea una cuenta de tipo Efectivo físico en Cuentas, con la moneda de origen.") else localized("No hay destino elegible. Crea o restaura una cuenta de la moneda y tipo necesarios."), color = MaterialTheme.colorScheme.error)
   Text(localized("Salida {0} → Entrada {1}", origin?.currency, target?.currency))
   if (action == "payment") Text(localized("Confirma un pago real. Reduce dinero propio y deuda; no registra un segundo gasto."))
   f.text("amount", localized("Importe real de salida"), numeric = true)
   if (action == "fx") { f.text("received", localized("Importe real recibido"), numeric = true); f.text("rate", localized("Tasa registrada (obligatoria)"), numeric = true); f.text("rateSource", localized("Fuente de tasa (obligatoria)")); Text(localized("La fecha de la operación también fecha el registro de conversión. No se calcula ningún importe ni tasa por ti.")) }
   f.text("fee", localized("Comisión en moneda de origen"), "0.00", true)
   f.text("date", localized("Fecha · AAAA-MM-DD"), today())
   f.text("bookedDate", localized("Fecha contabilizada (si difiere) · AAAA-MM-DD"))
   if (action == "fx") f.text("rateDate", localized("Fecha de tasa (si difiere) · AAAA-MM-DD"))
   f.text("notes", localized("Notas (opcional)"), lines = 2)
   val purpose = if (action == "moveReserve") f.choice("purpose", localized("Reserva que viaja con el dinero"), origin?.reserves.orEmpty().filter { it.minor > 0 }.map { it.purposeId to "${it.name} · ${decimal(it.minor)}" }) else ""
   val allocations = mutableListOf<() -> CreditPaymentAllocation>()
   if (action == "payment") {
    val purchases = snapshot.operations.filter { it.accountId == destination && it.type == "CREDIT_PURCHASE" && !it.previouslyDeducted && !it.voided && purchaseRemainder(it).signum() > 0 }
    val openingId = "opening:$destination"
    val openingSettled = snapshot.operations.filter { !it.voided }.flatMap { it.creditAllocations }.filter { it.purchaseId == openingId }.fold(BigDecimal.ZERO) { sum, entry -> sum.add(BigDecimal.valueOf(entry.minor, 2)) }
    val openingDebt = BigDecimal.valueOf(target?.openingMinor ?: 0, 2).negate().subtract(openingSettled)
    val purchaseOptions = purchases.map { it.id to localized("{0} · {1} · Pendiente {2}", it.date, it.merchant, purchaseRemainder(it).toPlainString()) } + if (openingDebt.signum() > 0) listOf(openingId to localized("Principal inicial · Pendiente {0}", openingDebt.toPlainString())) else emptyList()
    val count = f.value("allocationCount", "1").toIntOrNull() ?: 1
    Text(localized("Compras que liquida este pago"), style = MaterialTheme.typography.titleMedium)
    if (purchaseOptions.isEmpty()) Text(localized("Esta moneda de tarjeta no tiene compras ni principal inicial pendientes para enlazar. Registra primero la compra real."), color = MaterialTheme.colorScheme.error)
    repeat(count) { index ->
     val purchaseId = f.choice("allocationPurchase$index", localized("Compra a liquidar {0}", index + 1), purchaseOptions)
     val total = if (count == 1) f.check("allocationUseTotal$index", localized("Aplicar todo el importe principal a esta compra"), true) else false
     if (!total) f.text("allocationAmount$index", localized("Importe aplicado a compra {0}", index + 1), numeric = true)
     val paymentPurpose = f.choice("allocationPurpose$index", localized("Financiación real de compra {0}", index + 1), listOf("" to localized("Dinero sin asignar")) + origin?.reserves.orEmpty().map { it.purposeId to "${it.name} · ${decimal(it.minor)}" })
     allocations.add { require(purchaseId.isNotBlank()) { localized("Selecciona una compra pendiente para este pago.") }; CreditPaymentAllocation(purchaseId, if (total) f.amount() else f.amount("allocationAmount$index", localized("aplicación a compra {0}", index + 1)), paymentPurpose.ifBlank { null }) }
    }
    Action(localized("Añadir otra compra al pago"), !busy) { draft.set("allocationCount", (count + 1).toString()) }
    if (count > 1) Action(localized("Quitar última compra del pago"), !busy) { draft.set("allocationCount", (count - 1).toString()) }
    Text(localized("La suma de las aplicaciones debe coincidir con el principal. Las reservas elegidas se consumen al confirmar el pago, una sola vez."))
   }
   if (action == "moveReserve") Text(localized("La reserva y el dinero se transfieren juntos; el destino conserva el mismo propósito. La comisión consume dinero sin asignar."))
   val confirmed = f.check("confirmed", localized("Confirmo que la transferencia o pago ocurrió"))
   command = { require(confirmed) { localized("Confirma que esta operación ocurrió.") }; require(source.isNotBlank() && destination.isNotBlank()) { localized("Selecciona origen y destino elegibles de la moneda correcta.") }; if (action == "moveReserve") require(purpose.isNotBlank()) { localized("Selecciona una reserva financiada.") }; val amount = f.amount(); if (action == "fx") { f.required("rate", localized("tasa registrada")); f.required("rateSource", localized("fuente de tasa")) }; val paymentAllocations = allocations.map { it() }; if (action == "payment") require(paymentAllocations.fold(0L) { total, entry -> Math.addExact(total, entry.minor) } == amount) { localized("Las aplicaciones a compras deben sumar exactamente el principal del pago.") }; F05Command.Transfer(draft.request(), source, destination, amount, if (action == "fx") f.amount("received", localized("importe recibido")) else amount, f.date(), f.amount("fee", localized("comisión"), "0.00", true), purpose.ifBlank { null }, f.value("rate").ifBlank { null }, f.value("rateSource").ifBlank { null }, f.value("notes"), f.optionalDate("bookedDate"), f.optionalDate("rateDate"), paymentAllocations) }
  }
  "reserve", "release", "redistribute" -> {
   val account = f.account("account", localized("Cuenta con dinero existente"), cash, selectedAccount?.id.orEmpty())
   val current = cash.find { it.id == account }
   Text(localized("Sin asignar {0}", current?.let { money(it.unassignedMinor, it.currency) }.orEmpty()))
   val existing = current?.reserves.orEmpty().filter { it.minor > 0 }
   val from = if (action != "reserve") f.choice("from", localized("Propósito de origen"), existing.map { it.purposeId to "${it.name} · ${decimal(it.minor)}" }) else ""
   val selected = if (action != "release") f.choice("purpose", localized("Propósito de destino"), listOf("new" to localized("Crear propósito")) + snapshot.accounts.flatMap { it.reserves }.distinctBy { it.purposeId }.filter { it.purposeId != from }.map { it.purposeId to it.name }) else from
   val target = snapshot.accounts.flatMap { it.reserves }.find { it.purposeId == selected }
   if (selected == "new") f.text("name", localized("Nombre del propósito"))
   f.text("amount", if (action == "release") localized("Importe a liberar") else localized("Importe a reservar"), numeric = true)
   Text(localized("Asignar y liberar no son ingresos ni gastos. Solo se reserva dinero disponible real."))
   command = { require(account.isNotBlank()) { localized("Selecciona una cuenta.") }; val amount = f.amount(); val id = if (selected == "new") "purpose-${draft.request()}" else selected; val name = if (selected == "new") f.required("name", localized("nombre del propósito")) else target?.name ?: existing.find { it.purposeId == from }?.name.orEmpty(); require(id.isNotBlank()) { localized("Selecciona un propósito.") }; if (action == "redistribute") F05Command.Redistribute(draft.request(), account, from, id, name, amount) else F05Command.FundReserve(draft.request(), account, id, name, if (action == "release") -amount else amount) }
  }
  "reconcile" -> {
   val account = selectedAccount
   Text("${account?.name} · ${account?.currency}")
   f.text("date", localized("Fecha de corte · AAAA-MM-DD"), today())
   val balanceType = f.choice("balanceType", localized("Tipo de saldo observado"), listOf("BOOKED" to localized("Contabilizado"), "AVAILABLE" to localized("Disponible")))
   f.text("observed", localized("Saldo observado (puede ser negativo)"), numeric = true)
   val known = f.check("holdsKnown", localized("Revisé todas las retenciones al corte; sus importes son conocidos"))
   if (!known && balanceType == "AVAILABLE") Text(localized("Comparación provisional: las retenciones desconocidas impiden cerrar una conciliación definitiva."))
   val cut = f.value("date", today())
   val reviewed = snapshot.operations.filter { !it.voided && (it.accountId == subject || it.destinationId == subject) && (it.bookedDate ?: it.date) <= cut }
   Text(localized("Marcar movimientos revisados"), style = MaterialTheme.typography.titleMedium)
   val ids = reviewed.filter { f.check("review-${it.id}", "${it.date} · ${it.merchant.ifBlank { it.type }} · ${decimal(it.amountMinor)}") }.map { it.id }
   Text(localized("La comparación no cambia saldos. Un ajuste posterior requiere motivo y confirmación explícita."))
   command = { F05Command.Reconcile(draft.request(), subject, f.date(), balanceType, f.amount("observed", localized("saldo observado"), signed = true, allowZero = true), ids, known) }
   saveLabel = localized("Comparar sin modificar saldo")
  }
  "resolve" -> {
   val reconciliation = snapshot.reconciliations.find { it.id == subject }
   InfoCard(localized("Vista previa del ajuste"), localized("Diferencia observado − registrado: {0}\nSe creará un ajuste separado y auditable; no se clasificará como salario ni ahorro ganado.", reconciliation?.let { decimal(it.differenceMinor) }), 3)
   f.text("reason", localized("Explicación del ajuste"), lines = 3)
   val confirmed = f.check("confirmed", localized("Confirmo que esta diferencia debe ajustar el libro"))
   command = { require(confirmed) { localized("Confirma el ajuste después de revisarlo.") }; F05Command.ResolveDifference(draft.request(), subject, f.required("reason", localized("explicación"))) }
   saveLabel = localized("Crear ajuste explícito")
  }
  "hold" -> {
   val account = f.account("account", localized("Cuenta o tarjeta"), active)
   f.text("merchant", localized("Comercio"))
   f.text("amount", localized("Importe retenido"), numeric = true)
   f.text("date", localized("Fecha de autorización · AAAA-MM-DD"), today())
   Text(localized("Una autorización queda pendiente y separada del saldo contabilizado. Confirma el cargo únicamente cuando esté contabilizado."))
   command = { require(account.isNotBlank()) { localized("Selecciona una cuenta.") }; F05Command.CreateHold(draft.request(), account, f.amount(), f.required("merchant", localized("comercio")), f.date()) }
  }
  "confirmHold", "releaseHold" -> {
   val holdStatus = when (hold?.status) {
    "PENDING", "AUTHORIZED", "ACTIVE" -> localized("Pendiente")
    "CONFIRMED" -> localized("Confirmada")
    "RELEASED" -> localized("Liberada")
    else -> hold?.status.orEmpty()
   }
   Text(localized("{0} · {1}\nEstado: {2}", hold?.merchant, hold?.let { decimal(it.minor) }, holdStatus))
   if (action == "confirmHold") { f.text("category", localized("Categoría del cargo")); f.text("date", localized("Fecha del cargo · AAAA-MM-DD"), today()); f.text("bookedDate", localized("Fecha contabilizada (si difiere) · AAAA-MM-DD")); f.text("notes", localized("Notas (opcional)"), lines = 2) }
   val confirmed = f.check("confirmed", if (action == "confirmHold") localized("Verifiqué que el cargo está contabilizado; sustituye la retención") else localized("Verifiqué que la autorización fue liberada sin cargo"))
   command = { require(confirmed) { localized("Confirma el estado real de la retención.") }; if (action == "confirmHold") F05Command.ConfirmHold(draft.request(), subject, f.required("category", localized("categoría")), f.date(), f.value("notes"), f.optionalDate("bookedDate")) else F05Command.ReleaseHold(draft.request(), subject) }
  }
  "import" -> ImportForm(snapshot, draft, busy, onError, onSubmit, preview)
 }
 if (command != null) Button(onClick = { try { onSubmit(command()) } catch (e: Exception) { onError(userError(e)) } }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (busy) localized("Guardando…") else saveLabel) }
 Action(localized("Cancelar"), !busy, onClick = onCancel)
}


