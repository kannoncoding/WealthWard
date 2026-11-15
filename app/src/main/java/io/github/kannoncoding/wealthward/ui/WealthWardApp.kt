package io.github.kannoncoding.wealthward.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kannoncoding.wealthward.data.AppContainer
import io.github.kannoncoding.wealthward.domain.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

internal val Pastels = listOf(Color(0xFFE2DCFC), Color(0xFFE7DFFC), Color(0xFFDFE9FC), Color(0xFFD9EDF8), Color(0xFFE2E5F8))
private val Scheme = lightColorScheme(primary = Color(0xFF54427D), onPrimary = Color.White, background = Color(0xFFFAF9FF), surface = Color(0xFFFAF9FF), onSurface = Color(0xFF272238), secondaryContainer = Color(0xFFE2DCFC))
private val Tabs = listOf("Plan", "Movimientos", "Cuentas", "Metas", "Config")
internal fun money(minor: Long, currency: Currency) = "${currency.name} ${BigDecimal.valueOf(minor, 2).toPlainString()}"
internal fun decimal(minor: Long) = BigDecimal.valueOf(minor, 2).toPlainString()
internal fun total(values: Iterable<Long>) = values.fold(BigDecimal.ZERO) { sum, value -> sum.add(BigDecimal.valueOf(value, 2)) }.toPlainString()
internal fun debt(minor: Long) = BigDecimal.valueOf(minor, 2).negate().toPlainString()
internal fun purchaseRemainder(operation: OperationDetail): BigDecimal = BigDecimal.valueOf(operation.amountMinor, 2).subtract(BigDecimal.valueOf(operation.refundedMinor, 2)).subtract(BigDecimal.valueOf(operation.settledMinor, 2))
internal fun today() = LocalDate.now(BusinessTime.zone).toString()
internal fun newId() = UUID.randomUUID().toString()

/** Saveable drafts survive navigation and recreation; request identities survive retry. */
internal class Drafts(val values: MutableMap<String, String>) {
 var scope: String = ""
 fun get(key: String, default: String = "") = values["$scope:$key"] ?: default
 fun set(key: String, value: String) { values["$scope:$key"] = value }
 fun request(): String = get("request").ifBlank { newId().also { set("request", it) } }
 fun clear() { values.keys.filter { it.startsWith("$scope:") }.toList().forEach { values.remove(it) } }
}

@Composable fun WealthWardApp(container: AppContainer) {
 MaterialTheme(colorScheme = Scheme) {
  val scope = rememberCoroutineScope()
  val repository = container.repository.workspace
  val snapshot by repository.observe.collectAsStateWithLifecycle(initialValue = F05Snapshot())
  var selected by rememberSaveable { mutableIntStateOf(0) }
  var screen by rememberSaveable { mutableStateOf("") }
  var subject by rememberSaveable { mutableStateOf("") }
  var accountId by rememberSaveable { mutableStateOf("") }
  var filter by rememberSaveable { mutableStateOf("") }
  var failure by rememberSaveable { mutableStateOf("") }
  var success by rememberSaveable { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var ready by remember { mutableStateOf(false) }
  val saved = rememberSaveable(saver = mapSaver(save = { it.toMap() }, restore = { mutableStateMapOf<String, String>().apply { putAll(it.mapValues { e -> e.value as String }) } })) { mutableStateMapOf<String, String>() }
  val drafts = remember(saved) { Drafts(saved) }
  drafts.scope = "$screen:$subject"
  val errorFocus = remember { FocusRequester() }
  val largeText = LocalDensity.current.fontScale > 1.3f
  LaunchedEffect(container) { try { container.initializeDemo(); ready = true } catch (e: Exception) { failure = "No se pudieron abrir los datos: ${e.message}" } }
  LaunchedEffect(failure) { if (failure.isNotEmpty()) runCatching { errorFocus.requestFocus() } }
  fun open(action: String, id: String = "") { screen = action; subject = id; failure = ""; success = "" }
  fun back() { if (!busy) { screen = ""; subject = ""; failure = "" } }
  BackHandler(screen.isNotEmpty()) { back() }
  fun submit(command: F05Command) {
   if (busy) return
   busy = true; failure = ""
   scope.launch {
    try { repository.execute(command); drafts.clear(); success = "Guardado. Los saldos están actualizados."; screen = ""; subject = "" }
    catch (e: Exception) { failure = userError(e) }
    finally { busy = false }
   }
  }
  Scaffold(bottomBar = {
   if (screen.isEmpty() && largeText) Row(Modifier.fillMaxWidth().background(Scheme.surface).navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Tabs.forEachIndexed { index, title -> FilterChip(selected = selected == index, onClick = { selected = index; accountId = ""; failure = "" }, label = { Text(title) }, modifier = Modifier.heightIn(min = 48.dp)) }
   }
   else if (screen.isEmpty()) NavigationBar { Tabs.forEachIndexed { index, title ->
    NavigationBarItem(modifier = Modifier.semantics { contentDescription = title }, selected = selected == index, onClick = { selected = index; accountId = ""; failure = "" }, icon = { Text(listOf("◫", "⇄", "▤", "◎", "⚙")[index]) }, label = { Text(if (index == 1) "Movs." else title, maxLines = 1, style = MaterialTheme.typography.labelSmall) })
   } }
  }) { padding ->
   Box(Modifier.fillMaxSize().background(Scheme.background).padding(padding), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 800.dp).fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
     Text("WealthWard", style = MaterialTheme.typography.labelLarge)
     if (screen.isNotEmpty()) TextButton(onClick = { back() }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("← Volver · conservar borrador") }
     Text(if (screen.isEmpty()) Tabs[selected] else actionTitle(screen), style = MaterialTheme.typography.headlineMedium)
     if (!ready || busy) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Procesando" })
     if (failure.isNotEmpty()) Text(failure, color = Scheme.error, modifier = Modifier.focusRequester(errorFocus).focusable().semantics { liveRegion = LiveRegionMode.Assertive })
     if (success.isNotEmpty()) Text(success, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
     if (screen.isNotEmpty()) {
      F05Form(screen, subject, snapshot, drafts, busy, onError = { failure = it }, onSubmit = ::submit, onCancel = { back() }, preview = { rows -> repository.previewImport(rows) })
     } else when (selected) {
      0 -> {
       InfoCard("Tu dinero por moneda", "El saldo propio, las reservas y las deudas se muestran por separado. Los importes son ficticios hasta que captures tus propios datos.", 0)
       Currency.entries.forEach { currency ->
        val cash = snapshot.accounts.filter { !it.archived && it.currency == currency && it.kind != "LIABILITY" }
        val debt = snapshot.accounts.filter { !it.archived && it.currency == currency && it.kind == "LIABILITY" }
        InfoCard(currency.name, "Dinero propio $currency ${total(cash.map { it.balanceMinor })}\nReservas $currency ${total(cash.map { it.reservedMinor })}\nSin asignar $currency ${total(cash.map { it.unassignedMinor })}\nDeuda $currency ${BigDecimal(total(debt.filter { it.balanceMinor < 0 }.map { it.balanceMinor })).negate().toPlainString()}\nSaldo a favor en tarjetas $currency ${total(debt.filter { it.balanceMinor > 0 }.map { it.balanceMinor })}", 2)
       }
       Action("Asignar dinero a un propósito") { open("reserve") }
       Action("Registrar gasto") { open("expense") }
       InfoCard("Presupuesto por periodo", "El editor del presupuesto está previsto para F06. Puedes gestionar tus reservas financiadas en Metas.", 3)
      }
      1 -> {
       Action("Registrar gasto") { open("expense") }; Action("Registrar ingreso") { open("income") }
       Action("Transferir entre cuentas") { open("transfer") }; Action("Retirar efectivo") { open("withdraw") }
       Action("Conversión CRC / USD") { open("fx") }
       Action("Compra con tarjeta de crédito") { open("credit") }; Action("Pagar tarjeta de crédito") { open("payment") }
       Action("Autorizar retención pendiente") { open("hold") }
       OutlinedTextField(filter, { filter = it }, label = { Text("Filtrar historial: comercio, fecha o categoría") }, modifier = Modifier.fillMaxWidth())
       OperationCards(snapshot, snapshot.operations.filter { listOf(it.merchant, it.category, it.date, it.notes).any { part -> part.contains(filter, true) } }, ::open)
       HoldCards(snapshot, ::open)
      }
      2 -> {
       Action("Crear cuenta") { open("account") }
       if (accountId.isNotEmpty()) {
        TextButton(onClick = { accountId = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("← Todos los productos") }
        snapshot.accounts.find { it.id == accountId }?.let { account ->
         AccountCard(account, onClick = {})
         Text("Reservas en esta cuenta", style = MaterialTheme.typography.titleMedium)
         account.reserves.forEach { Text("${it.name}: ${money(it.minor, account.currency)}") }
         if (account.reserves.isEmpty()) Text("Sin reservas financiadas.")
         Text("Última conciliación: " + (account.lastReconciliation?.let { "${it.cutDate} · ${it.balanceType} · ${it.status}\nDiferencia ${money(it.differenceMinor, account.currency)}" } ?: "Sin comparar"))
         Action("Editar nombre") { open("rename", account.id) }
         Action(if (account.archived) "Restaurar cuenta archivada" else "Archivar cuenta") { open("archive", account.id) }
         if (!account.archived) Action("Comparar con el banco") { open("reconcile", account.id) }
         if (!account.archived && account.kind != "LIABILITY") {
          Action("Registrar gasto en esta cuenta") { open("expense", account.id) }
          Action("Registrar ingreso en esta cuenta") { open("income", account.id) }
          Action("Transferir desde esta cuenta") { open("transfer", account.id) }
          Action("Gestionar reserva de esta cuenta") { open("reserve", account.id) }
         }
         if (!account.archived && account.kind == "LIABILITY") Action("Registrar compra en esta tarjeta") { open("credit", account.id) }
         snapshot.reconciliations.filter { it.accountId == account.id }.forEach { comparison ->
          InfoCard("Corte ${comparison.cutDate} · ${comparison.status}", "${comparison.balanceType}\nObservado ${money(comparison.observedMinor, account.currency)}\nRegistrado ${money(comparison.recordedMinor, account.currency)}\nDiferencia ${money(comparison.differenceMinor, account.currency)}\n${comparison.reason}", 3)
          if (comparison.balanceType == "BOOKED" && comparison.status == "DIFFERENCE") Action("Resolver diferencia con ajuste explícito") { open("resolve", comparison.id) }
         }
         OutlinedTextField(filter, { filter = it }, label = { Text("Filtrar historial de esta cuenta") }, modifier = Modifier.fillMaxWidth())
         OperationCards(snapshot, snapshot.operations.filter { (it.accountId == account.id || it.destinationId == account.id) && listOf(it.merchant, it.category, it.date, it.type).any { part -> part.contains(filter, true) } }, ::open)
         HoldCards(snapshot.copy(holds = snapshot.holds.filter { it.accountId == account.id }), ::open)
        }
       } else {
        Text("Productos · subcuentas nativas", style = MaterialTheme.typography.titleMedium)
        snapshot.accounts.groupBy { it.productId }.forEach { (product, accounts) ->
         Text(if (product.startsWith("cash")) "Efectivo" else accounts.first().name.substringBefore(" · "), style = MaterialTheme.typography.titleLarge)
         accounts.forEach { account -> AccountCard(account) { accountId = account.id; filter = "" } }
        }
       }
      }
      3 -> {
       Text("Propósitos con dinero existente", style = MaterialTheme.typography.titleMedium)
       Action("Asignar o crear propósito") { open("reserve") }; Action("Liberar reserva") { open("release") }
       Action("Redistribuir entre propósitos") { open("redistribute") }; Action("Mover reserva a otra cuenta") { open("moveReserve") }
       snapshot.accounts.filter { it.reserves.isNotEmpty() }.forEach { account ->
        InfoCard(account.name, "${account.currency} · Sin asignar ${decimal(account.unassignedMinor)}", 1)
        account.reserves.forEach { Text("${it.name}: ${money(it.minor, account.currency)}") }
       }
       if (snapshot.accounts.none { it.reserves.isNotEmpty() }) Text("Aún no hay reservas. Asigna dinero disponible para comenzar.")
      }
      4 -> {
       Action("Importación asistida · pegar tabla") { open("import") }
       InfoCard("Datos locales", "La app funciona sin conexión bancaria. La importación solicita cuenta, importe y confirmación; las notas no se convierten en dinero.", 3)
       InfoCard("Diagnóstico", "Versión ${io.github.kannoncoding.wealthward.BuildConfig.VERSION_NAME}\nAndroid ${android.os.Build.VERSION.RELEASE}\n${io.github.kannoncoding.wealthward.BuildConfig.APPLICATION_ID}", 4)
       Text("Auditoría reciente", style = MaterialTheme.typography.titleMedium)
       snapshot.audit.takeLast(30).reversed().forEach { Text("${it.action} · ${it.reason.ifBlank { "Registro confirmado" }}\nReferencia ${it.subjectId}", style = MaterialTheme.typography.bodySmall) }
      }
     }
     Spacer(Modifier.height(24.dp))
    }
   }
  }
 }
}

@Composable internal fun InfoCard(title: String, detail: String, tint: Int) {
 Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Pastels[tint])) {
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail, style = MaterialTheme.typography.bodyLarge) }
 }
}
@Composable internal fun Action(title: String, enabled: Boolean = true, onClick: () -> Unit) { OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(title) } }
@Composable private fun AccountCard(account: AccountDetail, onClick: () -> Unit) {
 Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = Pastels[if (account.kind == "LIABILITY") 1 else 2])) {
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
   Text(account.name + if (account.archived) " · Archivada" else "", style = MaterialTheme.typography.titleMedium)
   Text(if (account.kind == "LIABILITY") if (account.balanceMinor > 0) "Saldo a favor en tarjeta ${money(account.balanceMinor, account.currency)}" else "Deuda ${account.currency} ${debt(account.balanceMinor)}" else "Saldo contabilizado ${money(account.balanceMinor, account.currency)}", style = MaterialTheme.typography.titleLarge)
   if (account.kind != "LIABILITY") { Text("Reservas ${money(account.reservedMinor, account.currency)}"); Text("Sin asignar ${money(account.unassignedMinor, account.currency)}") }
   else { Text("Límite ${money(account.creditLimitMinor, account.currency)} · No es dinero propio"); Text("Crédito disponible tras retenciones ${account.currency} ${BigDecimal.valueOf(account.creditLimitMinor, 2).add(BigDecimal.valueOf(account.balanceMinor, 2)).subtract(BigDecimal.valueOf(account.heldMinor, 2)).toPlainString()}") }
   Text("Retenciones pendientes ${money(account.heldMinor, account.currency)}")
   if (account.kind != "LIABILITY") Text("Disponible tras retenciones ${account.currency} ${BigDecimal.valueOf(account.balanceMinor, 2).subtract(BigDecimal.valueOf(account.heldMinor, 2)).toPlainString()}")
   Text("Apertura: ${account.openingDate ?: "Fecha heredada desconocida"}", style = MaterialTheme.typography.bodySmall)
  }
 }
}
@Composable private fun OperationCards(snapshot: F05Snapshot, operations: List<OperationDetail>, open: (String, String) -> Unit) {
 if (operations.isEmpty()) Text("No hay movimientos con este filtro.")
 operations.reversed().forEach { operation ->
  val account = snapshot.accounts.find { it.id == operation.accountId }
  InfoCard("${operation.date} · ${operation.merchant.ifBlank { operation.type }}", "${account?.name.orEmpty()} · ${money(operation.amountMinor, account?.currency ?: Currency.CRC)}\n${operation.category} · ${operation.type}${if (operation.voided) " · ANULADO" else ""}\n${operation.notes}" + if (operation.refundedMinor > 0) "\nReembolsado ${decimal(operation.refundedMinor)}" else "", 2)
  if (operation.bookedDate != null && operation.bookedDate != operation.date) Text("Contabilizado: ${operation.bookedDate}")
  operation.destinationId?.let { id -> snapshot.accounts.find { it.id == id }?.let { target -> Text("Destino ${target.name} · ${target.currency} ${operation.destinationMinor?.let { decimal(it) }.orEmpty()}") } }
  if (operation.rate != null) Text("Tasa registrada ${operation.rate} · ${operation.rateSource.orEmpty()} · ${operation.rateDate ?: operation.date}")
  if (operation.type == "CREDIT_PURCHASE") {
   val remainder = purchaseRemainder(operation)
   Text("Pagado ${decimal(operation.settledMinor)} · Pendiente ${remainder.max(BigDecimal.ZERO).toPlainString()}")
   if (remainder.signum() < 0) Text("Reembolso de compra pagada: ${remainder.negate().toPlainString()} a favor en tarjeta; no aumenta dinero propio.")
   Text("Origen previsto: " + (snapshot.accounts.find { it.id == operation.fundingAccountId }?.name ?: "Sin confirmar") + (operation.fundingPurposeId?.let { purpose -> " · " + (snapshot.accounts.flatMap { it.reserves }.find { it.purposeId == purpose }?.name ?: "Reserva prevista") } ?: ""))
  }
  operation.creditAllocations.forEach { allocation -> Text("Aplicado a ${snapshot.operations.find { it.id == allocation.purchaseId }?.merchant ?: "Compra"}: ${decimal(allocation.minor)} · " + if (allocation.purposeId == null) "Sin asignar" else "Reserva financiada") }
  if (operation.splits.isNotEmpty()) operation.splits.forEach { Text("${it.category}: ${decimal(it.minor)}", style = MaterialTheme.typography.bodySmall) }
  val feeChild = operation.id.endsWith(":fee")
  if (!operation.voided && !feeChild && operation.type !in listOf("REFUND", "REVERSAL", "ADJUSTMENT")) {
   if (operation.type in listOf("EXPENSE", "INCOME", "CREDIT_PURCHASE")) Action("Editar movimiento", onClick = { open("correct", operation.id) })
   Action("Anular movimiento", onClick = { open("void", operation.id) })
   if (operation.type in listOf("EXPENSE", "CREDIT_PURCHASE")) Action("Reembolso parcial", onClick = { open("refund", operation.id) })
  }
 }
}
@Composable private fun HoldCards(snapshot: F05Snapshot, open: (String, String) -> Unit) {
 snapshot.holds.forEach { hold ->
  val account = snapshot.accounts.find { it.id == hold.accountId }
  InfoCard("Retención · ${hold.status}", "${hold.merchant}\n${account?.name.orEmpty()} · ${money(hold.minor, account?.currency ?: Currency.CRC)}\n${hold.date} · " + when (hold.status) { "PENDING" -> "Autorización pendiente, separada del libro"; "CONFIRMED" -> "Sustituida por un único cargo contabilizado"; else -> "Liberada sin cargo" }, 3)
  if (hold.status in listOf("PENDING", "AUTHORIZED", "ACTIVE")) { Action("Confirmar cargo contabilizado", onClick = { open("confirmHold", hold.id) }); Action("Liberar retención", onClick = { open("releaseHold", hold.id) }) }
 }
}
internal fun userError(error: Exception): String = "No se guardó. ${error.message ?: "Revisa los datos e inténtalo de nuevo."}"
internal fun actionTitle(action: String): String = mapOf("account" to "Crear cuenta", "rename" to "Editar nombre", "archive" to "Archivar / restaurar", "expense" to "Registrar gasto", "income" to "Registrar ingreso", "credit" to "Compra con crédito", "payment" to "Pago de tarjeta", "transfer" to "Transferencia propia", "withdraw" to "Retiro de efectivo", "fx" to "Conversión real", "correct" to "Corrección auditada", "void" to "Anular con motivo", "refund" to "Reembolso parcial", "reserve" to "Asignar reserva", "release" to "Liberar reserva", "redistribute" to "Redistribuir reserva", "moveReserve" to "Mover reserva y dinero", "reconcile" to "Comparar con el banco", "resolve" to "Ajuste explícito", "hold" to "Retención pendiente", "confirmHold" to "Confirmar cargo", "releaseHold" to "Liberar retención", "import" to "Importación asistida")[action] ?: action
