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
 fun required(key: String, label: String, default: String = ""): String = value(key, default).trim().also { require(it.isNotBlank()) { "Completa $label." } }
 fun amount(key: String = "amount", label: String = "importe", default: String = "", allowZero: Boolean = false, signed: Boolean = false): Long {
  val raw = required(key, label, default)
  val minor = try { BigDecimal(raw).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact() } catch (_: Exception) { throw IllegalArgumentException("Revisa $label: usa números y como máximo dos decimales, con punto decimal.") }
  require(signed || if (allowZero) minor >= 0 else minor > 0) { "El $label debe ser ${if (allowZero) "cero o positivo" else "positivo"}." }
  return minor
 }
 fun date(key: String = "date", default: String = today()): String = required(key, "fecha", default).also { try { LocalDate.parse(it) } catch (_: Exception) { throw IllegalArgumentException("Fecha inválida. Usa AAAA-MM-DD.") } }
 fun optionalDate(key: String, default: String = ""): String? = value(key, default).trim().ifBlank { null }?.also { try { LocalDate.parse(it) } catch (_: Exception) { throw IllegalArgumentException("Fecha inválida. Usa AAAA-MM-DD.") } }
 @Composable fun text(key: String, label: String, default: String = "", numeric: Boolean = false, lines: Int = 1) {
  val storageKey = "${draft.scope}:$key"
  LaunchedEffect(storageKey) { if (!draft.values.containsKey(storageKey)) draft.values[storageKey] = default }
  OutlinedTextField(value(key, default), { draft.set(key, it) }, label = { Text(label) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = label }, singleLine = lines == 1, minLines = lines, keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
 }
 @Composable fun choice(key: String, label: String, choices: List<Pair<String, String>>, default: String = ""): String {
  val validDefault = default.takeIf { id -> choices.any { it.first == id } } ?: choices.firstOrNull()?.first.orEmpty()
  val selected = value(key, validDefault).takeIf { id -> choices.any { it.first == id } } ?: validDefault
  val storageKey = "${draft.scope}:$key"
  LaunchedEffect(storageKey, selected) { if (draft.values[storageKey] != selected) draft.values[storageKey] = selected }
  var expanded by remember { mutableStateOf(false) }
  Column {
   Text(label, style = MaterialTheme.typography.labelLarge)
   Box {
    OutlinedButton(onClick = { expanded = true }, enabled = !busy && choices.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label }) { Text(choices.find { it.first == selected }?.second ?: "Sin opciones elegibles") }
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
 @Composable fun account(key: String, label: String, accounts: List<AccountDetail>, default: String = ""): String = choice(key, label, accounts.map { it.id to "${it.name} · ${it.currency}${if (it.archived) " · Archivada" else ""}" }, default)
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
 var saveLabel = "Guardar"
 when (action) {
  "account" -> {
   f.text("name", "Nombre de la cuenta")
   val kind = f.choice("kind", "Tipo", listOf("CASH" to "Depósito propio", "PHYSICAL_CASH" to "Efectivo físico", "LIABILITY" to "Tarjeta de crédito · deuda"))
   val currency = f.choice("currency", "Moneda nativa", Currency.entries.map { it.name to it.name })
   val eligibleProducts = snapshot.accounts.groupBy { it.productId }.filter { (_, accounts) -> accounts.all { it.kind == if (kind == "LIABILITY") "LIABILITY" else "CASH" } && accounts.none { it.currency.name == currency } && !accounts.first().productId.startsWith("cash") }
   val product = if (kind != "PHYSICAL_CASH") f.choice("product", "Agrupar en producto", listOf("new" to "Crear producto separado") + eligibleProducts.map { (id, accounts) -> id to accounts.first().name.substringBefore(" · ") }) else "new"
   f.text("opening", if (kind == "LIABILITY") "Deuda inicial confirmada (positiva)" else "Saldo inicial confirmado", "0.00", true)
   f.text("date", "Fecha de apertura · AAAA-MM-DD", today())
   if (kind == "LIABILITY") f.text("limit", "Límite de crédito (no es efectivo)", "0.00", true)
   Text("La moneda y el tipo quedan protegidos al existir historial. El nombre puede cambiar sin alterar los movimientos.")
   command = { val id = draft.request(); F05Command.CreateAccount(id, "account-$id", if (kind == "PHYSICAL_CASH") "cash-$id" else if (product == "new") "product-$id" else product, f.required("name", "nombre"), Currency.valueOf(currency), if (kind == "PHYSICAL_CASH") "CASH" else kind, f.amount("opening", "saldo de apertura", "0.00", true).let { if (kind == "LIABILITY") -it else it }, f.date(), if (kind == "LIABILITY") f.amount("limit", "límite", "0.00", true) else 0) }
  }
  "rename" -> {
   Text("${selectedAccount?.currency} · ${selectedAccount?.kind}. La moneda y el tipo se conservan.")
   f.text("name", "Nombre de la cuenta", selectedAccount?.name.orEmpty())
   command = { F05Command.RenameAccount(draft.request(), subject, f.required("name", "nombre", selectedAccount?.name.orEmpty())) }
  }
  "archive" -> {
   Text("${selectedAccount?.name}\n${if (selectedAccount?.archived == true) "Volverá a los selectores de nuevas operaciones." else "Se conserva todo el historial. La cuenta dejará de aparecer en nuevas operaciones."}")
   val confirmed = f.check("confirmed", "Confirmo este cambio")
   command = { require(confirmed) { "Confirma el cambio de estado." }; F05Command.ArchiveAccount(draft.request(), subject, selectedAccount?.archived != true) }
  }
  "expense", "income", "credit", "correct" -> {
   val type = when (action) { "income" -> "INCOME"; "credit" -> "CREDIT_PURCHASE"; "correct" -> operation?.type ?: "EXPENSE"; else -> "EXPENSE" }
   val account = f.account("account", if (type == "CREDIT_PURCHASE") "Tarjeta y moneda de la compra" else "Cuenta y moneda", if (type == "CREDIT_PURCHASE") credit else cash, operation?.accountId ?: selectedAccount?.id.orEmpty())
   val current = active.find { it.id == account }
   val funding = if (type == "CREDIT_PURCHASE") f.choice("fundingAccount", "Origen previsto del pago (opcional)", listOf("" to "Sin origen confirmado") + cash.filter { it.currency == current?.currency }.map { it.id to "${it.name} · ${it.currency}" }, operation?.fundingAccountId.orEmpty()) else ""
   val fundingPurpose = if (type == "CREDIT_PURCHASE" && funding.isNotBlank()) f.choice("fundingPurpose", "Propósito previsto (no se consume al comprar)", listOf("" to "Sin reserva prevista") + cash.find { it.id == funding }?.reserves.orEmpty().map { it.purposeId to it.name }, operation?.fundingPurposeId.orEmpty()) else ""
   Text("Moneda: ${current?.currency ?: "Selecciona una cuenta"}" + if (type == "CREDIT_PURCHASE") " · La compra aumenta la deuda. El pago se registra por separado." else "")
   f.text("amount", "Importe", operation?.let { decimal(it.amountMinor) }.orEmpty(), true)
   f.text("date", "Fecha · AAAA-MM-DD", operation?.date ?: today())
   f.text("bookedDate", "Fecha contabilizada (si difiere) · AAAA-MM-DD", operation?.bookedDate?.takeIf { it != operation.date }.orEmpty())
   f.text("merchant", if (type == "INCOME") "Origen del ingreso" else "Comercio", operation?.merchant.orEmpty())
   f.text("category", "Categoría", operation?.category.orEmpty())
   f.text("notes", "Notas (opcional)", operation?.notes.orEmpty(), lines = 2)
   val count = f.value("splitCount", operation?.splits?.size?.toString() ?: "0").toIntOrNull() ?: 0
   Text("Desglose por categoría · suma exacta al importe", style = MaterialTheme.typography.titleMedium)
   repeat(count) { index ->
    val initial = operation?.splits?.getOrNull(index)
    f.text("splitCategory$index", "Categoría del desglose ${index + 1}", initial?.category.orEmpty())
    f.text("splitAmount$index", "Importe del desglose ${index + 1}", initial?.let { decimal(it.minor) }.orEmpty(), true)
    f.choice("splitPurpose$index", "Propósito del desglose ${index + 1}", listOf("" to "Sin reserva") + (current?.reserves.orEmpty().map { it.purposeId to it.name }), initial?.purposeId.orEmpty())
    Action("Quitar desglose ${index + 1}", !busy) {
     for (j in index until count - 1) listOf("splitCategory", "splitAmount", "splitPurpose").forEach { key -> draft.set("$key$j", f.value("$key${j + 1}", operation?.splits?.getOrNull(j + 1)?.let { next -> when (key) { "splitCategory" -> next.category; "splitAmount" -> decimal(next.minor); else -> next.purposeId.orEmpty() } }.orEmpty())) }
     draft.set("splitCount", (count - 1).toString())
    }
   }
   Action("Añadir desglose", !busy) { draft.set("splitCount", (count + 1).toString()) }
   if (count > 0) Text("Total del desglose: " + runCatching { (0 until count).fold(0L) { sum, index -> Math.addExact(sum, f.amount("splitAmount$index", "desglose", operation?.splits?.getOrNull(index)?.let { p -> decimal(p.minor) }.orEmpty())) } }.getOrNull()?.let { decimal(it) }.orEmpty())
   if (action == "correct") { InfoCard("El original se conserva", "Se registra una corrección con trazabilidad. Las conciliaciones afectadas deberán revisarse otra vez.", 3); f.text("reason", "Motivo de la corrección", lines = 2) }
   command = {
    require(account.isNotBlank()) { "Selecciona una cuenta elegible." }
    val amount = f.amount(default = operation?.let { decimal(it.amountMinor) }.orEmpty())
    val splits = (0 until count).map { i -> val initial = operation?.splits?.getOrNull(i); ExpenseSplit(f.required("splitCategory$i", "categoría del desglose", initial?.category.orEmpty()), f.amount("splitAmount$i", "desglose", initial?.let { decimal(it.minor) }.orEmpty()), f.value("splitPurpose$i", initial?.purposeId.orEmpty()).ifBlank { null }) }
    if (splits.isNotEmpty()) require(splits.fold(0L) { sum, split -> Math.addExact(sum, split.minor) } == amount) { "La suma del desglose debe coincidir exactamente con el importe." }
    val request = F05Command.Record(if (action == "correct") draft.request() + ":replacement" else draft.request(), account, type, amount, f.date(default = operation?.date ?: today()), f.required("merchant", "comercio u origen", operation?.merchant.orEmpty()), f.required("category", "categoría", operation?.category.orEmpty()), f.value("notes", operation?.notes.orEmpty()), splits, f.optionalDate("bookedDate", operation?.bookedDate?.takeIf { it != operation.date }.orEmpty()), funding.ifBlank { null }, fundingPurpose.ifBlank { null })
    if (action == "correct") F05Command.Correct(draft.request(), subject, request, f.required("reason", "motivo")) else request
   }
  }
  "void", "refund" -> {
   InfoCard("Movimiento original", "${operation?.date} · ${operation?.merchant}\nImporte ${operation?.let { decimal(it.amountMinor) }}\nReembolsado ${operation?.let { decimal(it.refundedMinor) }}\nEl original y su auditoría se conservan.", 3)
   if (action == "refund") { f.text("amount", "Importe del reembolso parcial", numeric = true); f.text("date", "Fecha · AAAA-MM-DD", today()); f.text("bookedDate", "Fecha contabilizada (si difiere) · AAAA-MM-DD") }
   f.text("reason", "Motivo obligatorio", lines = 2)
   val confirmed = f.check("confirmed", if (action == "void") "Confirmo la anulación y revisión de conciliaciones afectadas" else "Confirmo el reembolso y su restitución de reserva")
   command = { require(confirmed) { "Revisa la vista previa y confirma." }; if (action == "void") F05Command.Void(draft.request(), subject, f.required("reason", "motivo")) else F05Command.Refund(draft.request(), subject, f.amount(), f.date(), f.required("reason", "motivo"), f.optionalDate("bookedDate")) }
  }
  "transfer", "withdraw", "fx", "payment", "moveReserve" -> {
   val source = f.account("source", "Cuenta de origen", cash, selectedAccount?.id.orEmpty())
   val origin = cash.find { it.id == source }
   val destinations = (if (action == "payment") credit else cash).filter { it.id != source && (if (action == "fx") it.currency != origin?.currency else it.currency == origin?.currency) }.filter { if (action == "withdraw") it.productId.startsWith("cash") || it.name.contains("efectivo", true) else true }
   val destination = f.account("destination", if (action == "payment") "Tarjeta y moneda a pagar" else "Cuenta de destino", destinations)
   val target = destinations.find { it.id == destination }
   if (destinations.isEmpty()) Text(if (action == "withdraw") "Primero crea una cuenta de tipo Efectivo físico en Cuentas, con la moneda de origen." else "No hay destino elegible. Crea o restaura una cuenta de la moneda y tipo necesarios.", color = MaterialTheme.colorScheme.error)
   Text("Salida ${origin?.currency} → Entrada ${target?.currency}")
   if (action == "payment") Text("Confirma un pago real. Reduce dinero propio y deuda; no registra un segundo gasto.")
   f.text("amount", "Importe real de salida", numeric = true)
   if (action == "fx") { f.text("received", "Importe real recibido", numeric = true); f.text("rate", "Tasa registrada (obligatoria)", numeric = true); f.text("rateSource", "Fuente de tasa (obligatoria)"); Text("La fecha de la operación también fecha el registro de conversión. No se calcula ningún importe ni tasa por ti.") }
   f.text("fee", "Comisión en moneda de origen", "0.00", true)
   f.text("date", "Fecha · AAAA-MM-DD", today())
   f.text("bookedDate", "Fecha contabilizada (si difiere) · AAAA-MM-DD")
   if (action == "fx") f.text("rateDate", "Fecha de tasa (si difiere) · AAAA-MM-DD")
   f.text("notes", "Notas (opcional)", lines = 2)
   val purpose = if (action == "moveReserve") f.choice("purpose", "Reserva que viaja con el dinero", origin?.reserves.orEmpty().filter { it.minor > 0 }.map { it.purposeId to "${it.name} · ${decimal(it.minor)}" }) else ""
   val allocations = mutableListOf<() -> CreditPaymentAllocation>()
   if (action == "payment") {
    val purchases = snapshot.operations.filter { it.accountId == destination && it.type == "CREDIT_PURCHASE" && !it.voided && purchaseRemainder(it).signum() > 0 }
    val openingId = "opening:$destination"
    val openingSettled = snapshot.operations.filter { !it.voided }.flatMap { it.creditAllocations }.filter { it.purchaseId == openingId }.fold(BigDecimal.ZERO) { sum, entry -> sum.add(BigDecimal.valueOf(entry.minor, 2)) }
    val openingDebt = BigDecimal.valueOf(target?.openingMinor ?: 0, 2).negate().subtract(openingSettled)
    val purchaseOptions = purchases.map { it.id to "${it.date} · ${it.merchant} · Pendiente ${purchaseRemainder(it).toPlainString()}" } + if (openingDebt.signum() > 0) listOf(openingId to "Principal inicial · Pendiente ${openingDebt.toPlainString()}") else emptyList()
    val count = f.value("allocationCount", "1").toIntOrNull() ?: 1
    Text("Compras que liquida este pago", style = MaterialTheme.typography.titleMedium)
    if (purchaseOptions.isEmpty()) Text("Esta moneda de tarjeta no tiene compras ni principal inicial pendientes para enlazar. Registra primero la compra real.", color = MaterialTheme.colorScheme.error)
    repeat(count) { index ->
     val purchaseId = f.choice("allocationPurchase$index", "Compra a liquidar ${index + 1}", purchaseOptions)
     val total = if (count == 1) f.check("allocationUseTotal$index", "Aplicar todo el importe principal a esta compra", true) else false
     if (!total) f.text("allocationAmount$index", "Importe aplicado a compra ${index + 1}", numeric = true)
     val paymentPurpose = f.choice("allocationPurpose$index", "Financiación real de compra ${index + 1}", listOf("" to "Dinero sin asignar") + origin?.reserves.orEmpty().map { it.purposeId to "${it.name} · ${decimal(it.minor)}" })
     allocations.add { require(purchaseId.isNotBlank()) { "Selecciona una compra pendiente para este pago." }; CreditPaymentAllocation(purchaseId, if (total) f.amount() else f.amount("allocationAmount$index", "aplicación a compra ${index + 1}"), paymentPurpose.ifBlank { null }) }
    }
    Action("Añadir otra compra al pago", !busy) { draft.set("allocationCount", (count + 1).toString()) }
    if (count > 1) Action("Quitar última compra del pago", !busy) { draft.set("allocationCount", (count - 1).toString()) }
    Text("La suma de las aplicaciones debe coincidir con el principal. Las reservas elegidas se consumen al confirmar el pago, una sola vez.")
   }
   if (action == "moveReserve") Text("La reserva y el dinero se transfieren juntos; el destino conserva el mismo propósito. La comisión consume dinero sin asignar.")
   val confirmed = f.check("confirmed", "Confirmo que la transferencia o pago ocurrió")
   command = { require(confirmed) { "Confirma que esta operación ocurrió." }; require(source.isNotBlank() && destination.isNotBlank()) { "Selecciona origen y destino elegibles de la moneda correcta." }; if (action == "moveReserve") require(purpose.isNotBlank()) { "Selecciona una reserva financiada." }; val amount = f.amount(); if (action == "fx") { f.required("rate", "tasa registrada"); f.required("rateSource", "fuente de tasa") }; val paymentAllocations = allocations.map { it() }; if (action == "payment") require(paymentAllocations.fold(0L) { total, entry -> Math.addExact(total, entry.minor) } == amount) { "Las aplicaciones a compras deben sumar exactamente el principal del pago." }; F05Command.Transfer(draft.request(), source, destination, amount, if (action == "fx") f.amount("received", "importe recibido") else amount, f.date(), f.amount("fee", "comisión", "0.00", true), purpose.ifBlank { null }, f.value("rate").ifBlank { null }, f.value("rateSource").ifBlank { null }, f.value("notes"), f.optionalDate("bookedDate"), f.optionalDate("rateDate"), paymentAllocations) }
  }
  "reserve", "release", "redistribute" -> {
   val account = f.account("account", "Cuenta con dinero existente", cash, selectedAccount?.id.orEmpty())
   val current = cash.find { it.id == account }
   Text("Sin asignar ${current?.let { money(it.unassignedMinor, it.currency) }.orEmpty()}")
   val existing = current?.reserves.orEmpty().filter { it.minor > 0 }
   val from = if (action != "reserve") f.choice("from", "Propósito de origen", existing.map { it.purposeId to "${it.name} · ${decimal(it.minor)}" }) else ""
   val selected = if (action != "release") f.choice("purpose", "Propósito de destino", listOf("new" to "Crear propósito") + snapshot.accounts.flatMap { it.reserves }.distinctBy { it.purposeId }.filter { it.purposeId != from }.map { it.purposeId to it.name }) else from
   val target = snapshot.accounts.flatMap { it.reserves }.find { it.purposeId == selected }
   if (selected == "new") f.text("name", "Nombre del propósito")
   f.text("amount", if (action == "release") "Importe a liberar" else "Importe a reservar", numeric = true)
   Text("Asignar y liberar no son ingresos ni gastos. Solo se reserva dinero disponible real.")
   command = { require(account.isNotBlank()) { "Selecciona una cuenta." }; val amount = f.amount(); val id = if (selected == "new") "purpose-${draft.request()}" else selected; val name = if (selected == "new") f.required("name", "nombre del propósito") else target?.name ?: existing.find { it.purposeId == from }?.name.orEmpty(); require(id.isNotBlank()) { "Selecciona un propósito." }; if (action == "redistribute") F05Command.Redistribute(draft.request(), account, from, id, name, amount) else F05Command.FundReserve(draft.request(), account, id, name, if (action == "release") -amount else amount) }
  }
  "reconcile" -> {
   val account = selectedAccount
   Text("${account?.name} · ${account?.currency}")
   f.text("date", "Fecha de corte · AAAA-MM-DD", today())
   val balanceType = f.choice("balanceType", "Tipo de saldo observado", listOf("BOOKED" to "Contabilizado", "AVAILABLE" to "Disponible"))
   f.text("observed", "Saldo observado (puede ser negativo)", numeric = true)
   val known = f.check("holdsKnown", "Revisé todas las retenciones al corte; sus importes son conocidos")
   if (!known && balanceType == "AVAILABLE") Text("Comparación provisional: las retenciones desconocidas impiden cerrar una conciliación definitiva.")
   val cut = f.value("date", today())
   val reviewed = snapshot.operations.filter { !it.voided && (it.accountId == subject || it.destinationId == subject) && (it.bookedDate ?: it.date) <= cut }
   Text("Marcar movimientos revisados", style = MaterialTheme.typography.titleMedium)
   val ids = reviewed.filter { f.check("review-${it.id}", "${it.date} · ${it.merchant.ifBlank { it.type }} · ${decimal(it.amountMinor)}") }.map { it.id }
   Text("La comparación no cambia saldos. Un ajuste posterior requiere motivo y confirmación explícita.")
   command = { F05Command.Reconcile(draft.request(), subject, f.date(), balanceType, f.amount("observed", "saldo observado", signed = true, allowZero = true), ids, known) }
   saveLabel = "Comparar sin modificar saldo"
  }
  "resolve" -> {
   val reconciliation = snapshot.reconciliations.find { it.id == subject }
   InfoCard("Vista previa del ajuste", "Diferencia observado − registrado: ${reconciliation?.let { decimal(it.differenceMinor) }}\nSe creará un ajuste separado y auditable; no se clasificará como salario ni ahorro ganado.", 3)
   f.text("reason", "Explicación del ajuste", lines = 3)
   val confirmed = f.check("confirmed", "Confirmo que esta diferencia debe ajustar el libro")
   command = { require(confirmed) { "Confirma el ajuste después de revisarlo." }; F05Command.ResolveDifference(draft.request(), subject, f.required("reason", "explicación")) }
   saveLabel = "Crear ajuste explícito"
  }
  "hold" -> {
   val account = f.account("account", "Cuenta o tarjeta", active)
   f.text("merchant", "Comercio")
   f.text("amount", "Importe retenido", numeric = true)
   f.text("date", "Fecha de autorización · AAAA-MM-DD", today())
   Text("Una autorización queda pendiente y separada del saldo contabilizado. Confirma el cargo únicamente cuando esté contabilizado.")
   command = { require(account.isNotBlank()) { "Selecciona una cuenta." }; F05Command.CreateHold(draft.request(), account, f.amount(), f.required("merchant", "comercio"), f.date()) }
  }
  "confirmHold", "releaseHold" -> {
   Text("${hold?.merchant} · ${hold?.let { decimal(it.minor) }}\nEstado: ${hold?.status}")
   if (action == "confirmHold") { f.text("category", "Categoría del cargo"); f.text("date", "Fecha del cargo · AAAA-MM-DD", today()); f.text("bookedDate", "Fecha contabilizada (si difiere) · AAAA-MM-DD"); f.text("notes", "Notas (opcional)", lines = 2) }
   val confirmed = f.check("confirmed", if (action == "confirmHold") "Verifiqué que el cargo está contabilizado; sustituye la retención" else "Verifiqué que la autorización fue liberada sin cargo")
   command = { require(confirmed) { "Confirma el estado real de la retención." }; if (action == "confirmHold") F05Command.ConfirmHold(draft.request(), subject, f.required("category", "categoría"), f.date(), f.value("notes"), f.optionalDate("bookedDate")) else F05Command.ReleaseHold(draft.request(), subject) }
  }
  "import" -> ImportForm(snapshot, draft, busy, onError, onSubmit, preview)
 }
 if (command != null) Button(onClick = { try { onSubmit(command()) } catch (e: Exception) { onError(userError(e)) } }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (busy) "Guardando…" else saveLabel) }
 Action("Cancelar · conservar borrador", !busy, onCancel)
}


