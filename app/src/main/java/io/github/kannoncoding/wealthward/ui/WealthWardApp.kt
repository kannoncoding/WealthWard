package io.github.kannoncoding.wealthward.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kannoncoding.wealthward.data.AppContainer
import io.github.kannoncoding.wealthward.domain.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

private val Tabs get() = listOf(localized("Resumen"), localized("Movimientos"), localized("Cuentas"), localized("Metas"), localized("Config"))
internal fun money(minor: Long, currency: Currency) = "${currency.name} ${BigDecimal.valueOf(minor, 2).toPlainString()}"
internal fun decimal(minor: Long) = BigDecimal.valueOf(minor, 2).toPlainString()
internal fun total(values: Iterable<Long>) = values.fold(BigDecimal.ZERO) { sum, value -> sum.add(BigDecimal.valueOf(value, 2)) }.toPlainString()
internal fun debt(minor: Long) = BigDecimal.valueOf(minor, 2).negate().toPlainString()
internal fun purchaseRemainder(operation: OperationDetail): BigDecimal = BigDecimal.valueOf(operation.amountMinor, 2).subtract(BigDecimal.valueOf(operation.refundedMinor, 2)).subtract(BigDecimal.valueOf(operation.settledMinor, 2))
internal fun today() = LocalDate.now(BusinessTime.zone).toString()
internal fun newId() = UUID.randomUUID().toString()

/** Saveable drafts survive navigation and recreation; request identities survive retry. */
internal class Drafts(val values: MutableMap<String, String>, private val persistence: android.content.SharedPreferences? = null) {
 var scope: String = ""
 fun get(key: String, default: String = "") = values["$scope:$key"] ?: default
 fun set(key: String, value: String) { val path = "$scope:$key"; values[path] = value; persistence?.edit()?.putString(path, value)?.apply() }
 fun request(): String = get("request").ifBlank { newId().also { set("request", it) } }
 fun clear() {
  val edit = persistence?.edit()
  values.keys.filter { it.startsWith("$scope:") }.toList().forEach { values.remove(it); edit?.remove(it) }
  edit?.apply()
 }
}

@Composable fun WealthWardApp(container: AppContainer) {
 val context = LocalContext.current
 val preferences = remember(context) { context.getSharedPreferences("appearance", 0) }
 var appearance by remember { mutableStateOf(Appearance.entries.firstOrNull { it.name == preferences.getString("mode", null) } ?: Appearance.SYSTEM) }
 var uiSize by remember { mutableStateOf(UiSize.entries.firstOrNull { it.name == preferences.getString("ui_size", null) } ?: UiSize.STANDARD) }
 val isDark = appearance == Appearance.DARK || appearance == Appearance.SYSTEM && isSystemInDarkTheme()
 SideEffect {
  (context as? android.app.Activity)?.window?.let { window ->
   androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
    isAppearanceLightStatusBars = !isDark
    isAppearanceLightNavigationBars = !isDark
   }
  }
 }
 WealthTheme(isDark, uiSize) {
  val palette = LocalPalette.current
  val scope = rememberCoroutineScope()
  val repository = container.repository.workspace
  val ledgerSnapshot by repository.observe.collectAsStateWithLifecycle(initialValue = F05Snapshot())
  val snapshot = remember(ledgerSnapshot) {
   ledgerSnapshot.copy(accounts = ledgerSnapshot.accounts.filter { it.kind != "TERM_DEBT" })
  }
  val planning = container.repository.planning
  val planningEvent by planning.observe.collectAsStateWithLifecycle(initialValue = F06Snapshot())
  val commitments = container.repository.commitments
  val commitmentEvent by commitments.observe.collectAsStateWithLifecycle(initialValue = null)
  var currentDay by remember { mutableStateOf(LocalDate.now(BusinessTime.zone)) }
  val currentMonth = java.time.YearMonth.from(currentDay).toString()
  val reminderHorizon = java.time.YearMonth.from(currentDay.plusDays(31)).toString()
  val planningMonth = currentMonth
  var loadedPlan by remember { mutableStateOf<F06Snapshot?>(null) }
  val plan = loadedPlan ?: F06Snapshot()
  var commitmentPlan by remember { mutableStateOf(F07Snapshot()) }
  var commitmentError by remember { mutableStateOf("") }
  val reminders = remember(context) { io.github.kannoncoding.wealthward.notifications.LocalReminders(context) }
  LaunchedEffect(planningEvent, planningMonth, snapshot) { runCatching { planning.snapshot(planningMonth) }.onSuccess { loadedPlan = it } }
  LaunchedEffect(commitmentEvent, planningMonth, snapshot) {
   try {
    commitmentPlan = commitments.snapshot(planningMonth)
    commitmentError = ""
   } catch (e: kotlinx.coroutines.CancellationException) {
    throw e
   } catch (e: Exception) {
    commitmentError = localized("No se pudieron cargar las deudas, metas y proyectos.")
   }
  }
  LaunchedEffect(loadedPlan, reminderHorizon, AppLanguage.current) {
   if (loadedPlan == null) return@LaunchedEffect
   val reminderPlan = try {
    planning.snapshot(reminderHorizon)
   } catch (e: CancellationException) {
    throw e
   } catch (_: Exception) {
    return@LaunchedEffect
   }
   reminders.save(io.github.kannoncoding.wealthward.notifications.ReminderPreferences(reminderPlan.reminders.enabled, reminderPlan.reminders.daysBefore, reminderPlan.reminders.hour, reminderPlan.reminders.additionalDays))
   reminders.update(reminderPlan.occurrences.filter { it.paymentPendingMinor > 0 }.map { io.github.kannoncoding.wealthward.notifications.ReminderItem(it.id, it.displayName(), it.dueDate, localized("{0} · Pendiente {1}", it.dueDate, money(it.paymentPendingMinor,it.currency))) })
  }
  val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
   val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if(event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) reminders.reconcile() }
   lifecycleOwner.lifecycle.addObserver(observer)
   onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  var selected by rememberSaveable { mutableIntStateOf(0) }
  var screen by rememberSaveable { mutableStateOf("") }
  var backStack by rememberSaveable { mutableStateOf(arrayListOf<ArrayList<String>>()) }
  var formMonth by rememberSaveable { mutableStateOf(currentMonth) }
  var subject by rememberSaveable { mutableStateOf("") }
  var accountId by rememberSaveable { mutableStateOf("") }
  var filter by rememberSaveable { mutableStateOf("") }
  var movementCategory by rememberSaveable { mutableStateOf("") }
  var accountMovementCategory by rememberSaveable(accountId) { mutableStateOf("") }
  var failure by rememberSaveable { mutableStateOf("") }
  var success by rememberSaveable { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var ready by remember { mutableStateOf(false) }
  var deferredSalaries by rememberSaveable(currentDay.toString()) { mutableStateOf(arrayListOf<String>()) }
  var salarySaving by remember { mutableStateOf(false) }
  var salaryError by remember { mutableStateOf("") }
  val projectionLimits by container.files.projectionLimits.collectAsStateWithLifecycle(emptyMap())
  LaunchedEffect(Unit) {
   while (true) {
    currentDay = java.time.LocalDate.now(BusinessTime.zone)
    kotlinx.coroutines.delay(30_000)
   }
  }
  var projectionDraft by rememberProjectionSettings(planningMonth)
  val projectionSettings = projectionDraft.copy(limits = projectionLimits)
  val summary = selected == 0 && (screen.isEmpty() || screen == "upcoming")
  val projection = if (ready && (summary || screen == "f08")) {
   loadProjection(container.repository.projections, if (summary) currentMonth else planningMonth,
    if (summary) projectionSettings.copy(cutoff = currentDay) else projectionSettings, listOf(ledgerSnapshot, planningEvent, commitmentEvent))
  } else ProjectionLoading()
  val draftPreferences = remember(context) { context.getSharedPreferences("form-drafts", 0) }
  val saved = remember(draftPreferences) { mutableStateMapOf<String, String>().apply {
   draftPreferences.all.forEach { (key, value) -> if (value is String) put(key, value) }
  } }
  val encodedSubject = android.net.Uri.encode(subject)
  val draftScope = when {
   screen == "f08" -> "$screen:$encodedSubject:$planningMonth"
   screen.startsWith("f06") || screen.startsWith("f07") -> "$screen:$encodedSubject:$formMonth"
   else -> "$screen:$encodedSubject"
  }
  val drafts = remember(saved, draftScope) { Drafts(saved, draftPreferences).apply { this.scope = draftScope } }
  var confirmClear by remember(draftScope) { mutableStateOf(false) }
  var formRevision by remember(draftScope) { mutableIntStateOf(0) }
  val hasDraftForm = screen.isNotEmpty() && !screen.startsWith("section") &&
   !screen.startsWith("f10") && screen !in setOf("upcoming", "f08")
  val errorFocus = remember { FocusRequester() }
  val exportMessages = remember { SnackbarHostState() }
  LaunchedEffect(container) { try { container.initialize(); ready = true } catch (e: Exception) { failure = localized("No se pudieron abrir los datos: {0}", e.message) } }
  LaunchedEffect(ready, currentMonth, reminderHorizon) {
   if (ready) {
    try {
     planning.execute(F06Command.OpenPeriod("summary-period-v3:$currentMonth", currentMonth))
     planning.execute(F06Command.OpenPeriod("summary-period-v3:$reminderHorizon", reminderHorizon))
    }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { failure = localized("No se pudo preparar el mes.") }
   }
  }
  LaunchedEffect(failure) { if (failure.isNotEmpty()) runCatching { errorFocus.requestFocus() } }
  LaunchedEffect(screen, failure, success) {
   val message = failure.ifEmpty { success }
   if (screen == "f10Summary" && message.isNotEmpty()) {
    exportMessages.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
   }
  }
  fun rememberPage() {
   backStack = ArrayList(backStack + listOf(arrayListOf(selected.toString(), screen, subject, accountId, filter, formMonth)))
  }
  fun previousPage() {
   if (backStack.isNotEmpty()) {
    val page = backStack.last()
    backStack = ArrayList(backStack.dropLast(1))
    selected = page[0].toInt()
    screen = page[1]
    subject = page[2]
    accountId = page[3]
    filter = page[4]
    formMonth = page[5]
   } else {
    selected = 0
    screen = ""
    subject = ""
    accountId = ""
    filter = ""
   }
   failure = ""
  }
  fun open(action: String, id: String = "") {
   rememberPage()
   screen = action
   subject = id
   formMonth = currentMonth
   failure = ""
   success = ""
  }
  val dueSalary = if (ready && screen.isEmpty()) {
   plan.occurrences.asSequence().filter {
    it.templateId == "salary" && it.id !in deferredSalaries && it.paymentPendingMinor > 0 && it.dueDate <= currentDay.toString()
   }.minByOrNull { it.dueDate }
  } else null
  dueSalary?.let { salary ->
   val eligible = snapshot.accounts.filter { !it.archived && it.kind == "CASH" && it.currency == salary.currency }
   val account = eligible.firstOrNull { it.id == salary.suggestedAccountId }
   AlertDialog(
    onDismissRequest = { if (!salarySaving) { deferredSalaries = ArrayList(deferredSalaries + salary.id); salaryError = "" } },
    title = { Text(localized("Confirmación de salario")) },
    text = {
     Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(localized("¿Recibiste este salario programado para el {0}?", salary.dueDate))
      Text(money(salary.paymentPendingMinor, salary.currency), style = MaterialTheme.typography.titleLarge)
      if (account != null) Text(localized("En la cuenta: {0}", account.name))
      else if (eligible.isEmpty()) Text(localized("Crea una cuenta de recepción en {0}.", salary.currency))
      if (salaryError.isNotEmpty()) Text(salaryError, color = MaterialTheme.colorScheme.error)
     }
    },
    dismissButton = { TextButton(enabled = !salarySaving, onClick = { deferredSalaries = ArrayList(deferredSalaries + salary.id); salaryError = "" }) { Text(localized("Ahora no")) } },
    confirmButton = {
     if (account == null) {
      CompactRecordAction(localized(if (eligible.isEmpty()) "Crear cuenta" else "Elegir cuenta de recepción")) {
       deferredSalaries = ArrayList(deferredSalaries + salary.id)
       if (eligible.isEmpty()) open("account") else open("f06pay", salary.id)
      }
     } else TextButton(enabled = !salarySaving, onClick = {
      salarySaving = true
      salaryError = ""
      scope.launch {
       try {
        planning.confirmSalary(salary.id, account.id, salary.paymentPendingMinor)
        loadedPlan = planning.snapshot(planningMonth)
       } catch (e: CancellationException) {
        throw e
       } catch (e: Exception) {
        salaryError = userError(e)
       } finally {
        salarySaving = false
       }
      }
     }) { Text(localized("Confirmar ingreso")) }
    },
   )
  }
  fun selectTab(index: Int) {
   if (index == selected) {
    if (accountId.isNotEmpty()) previousPage()
    return
   }
   if (index == 0) backStack = arrayListOf() else rememberPage()
   selected = index
   accountId = ""
   filter = ""
   failure = ""
   success = ""
  }
  fun back() {
   if (busy) return
   if (selected == 0 && screen.isEmpty()) (context as? android.app.Activity)?.finish()
   else {
    previousPage()
    success = ""
   }
  }
  BackHandler { back() }
  fun submit(command: F05Command) {
   if (busy) return
   busy = true; failure = ""
   scope.launch {
    try { repository.execute(command); drafts.clear(); success = localized("Guardado. Los saldos están actualizados."); previousPage() }
    catch (e: Exception) { failure = userError(e) }
    finally { busy = false }
   }
  }
  fun submitPlan(command: F06Command) {
   if(busy) return
   busy = true; failure = ""
   scope.launch {
    try { planning.execute(command); loadedPlan = planning.snapshot(planningMonth); drafts.clear(); success = localized("Guardado. Plan y movimientos actualizados."); previousPage() }
    catch(e:Exception) { failure = userError(e) }
    finally { busy = false }
   }
  }
  fun submitCommitment(command: F07Command) {
   if (busy) return
   busy = true; failure = ""
   scope.launch {
    try {
     commitments.execute(command)
     drafts.clear()
     success = localized("Guardado.")
     previousPage()
    } catch (e: kotlinx.coroutines.CancellationException) {
     throw e
    } catch (e: Exception) {
     failure = userError(e)
    } finally {
     busy = false
    }
   }
  }
  val scroll = key(selected, screen, subject, accountId) { rememberScrollState() }
  Scaffold(containerColor = palette.gradientEnd, contentColor = palette.ink, snackbarHost = { SnackbarHost(exportMessages) }, bottomBar = {
   if (screen.isEmpty()) WealthNavigationBar(Tabs, selected, ::selectTab)
  }) { padding ->
   Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(palette.base, palette.gradientEnd))).padding(padding), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 800.dp).fillMaxWidth().verticalScroll(scroll).imePadding().padding(16.dp * uiSize.spacingScale), verticalArrangement = Arrangement.spacedBy(12.dp * uiSize.spacingScale)) {
     val summaryHome = screen.isEmpty() && selected == 0
     if (screen.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      CompactRecordAction(localized("Volver"), enabled = !busy) { back() }
      if (hasDraftForm) CompactRecordAction(localized("Limpiar datos"), enabled = !busy) { confirmClear = true }
     }
     if (confirmClear) AlertDialog(
      onDismissRequest = { confirmClear = false },
      text = { Text(localized("¿Seguro que desea borrar los datos ingresados en esta pantalla?")) },
      confirmButton = { TextButton(onClick = { drafts.clear(); formRevision++; failure = ""; confirmClear = false }) { Text(localized("Limpiar datos")) } },
      dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(localized("Cancelar")) } },
     )
     if (!summaryHome) Text(when {
      screen.startsWith("section") -> configurationTitle(screen)
      screen.isEmpty() -> Tabs[selected]
      screen == "upcoming" -> localized("Pagos pendientes del mes")
      screen == "f08" -> localized("Pronóstico de cierre")
      screen == "f10Backup" -> localized("Crear respaldo")
      screen == "f10Restore" -> localized("Restaurar desde respaldo")
      screen == "f10Summary" -> localized("Exportar resumen")
      screen.startsWith("f06") -> f06Title(screen)
      screen.startsWith("f07") -> f07Title(screen)
      else -> actionTitle(screen)
     }, modifier = Modifier.padding(horizontal = 5.dp, vertical = 6.dp), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
     if (!summaryHome && (!ready || busy)) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = localized("Procesando") })
     if (!summaryHome && screen != "f10Summary" && failure.isNotEmpty()) Text(failure, color = MaterialTheme.colorScheme.error, modifier = Modifier.focusRequester(errorFocus).focusable().semantics { liveRegion = LiveRegionMode.Assertive })
     if (!summaryHome && screen != "f10Summary" && success.isNotEmpty()) Text(success, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
     if (!summaryHome && commitmentError.isNotEmpty() && (screen.startsWith("f07") || screen.isEmpty() && selected in listOf(0, 3, 4))) {
      Text(commitmentError, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
     }
     if (screen.isNotEmpty()) key(formRevision) {
      if (screen == "upcoming") {
       MonthlySummary(currentMonth, currentDay, snapshot, plan, commitmentPlan, projection.result, showAllPayments = true, open = ::open)
      } else if (screen == "sectionAppearance") {
       AppearanceSettings(
        current = appearance,
        uiSize = uiSize,
        select = { mode ->
         preferences.edit().putString("mode", mode.name).apply()
         appearance = mode
        },
        selectSize = { size ->
         preferences.edit().putString("ui_size", size.name).apply()
         uiSize = size
        },
       )
       Action("Idioma / Language", menu = true) { open("sectionLanguage") }
      } else if (screen == "sectionLanguage") {
       LanguageSettings(context)
      } else if (screen.startsWith("section")) {
       ConfigurationSection(screen, plan, commitmentPlan, snapshot, ::open)
      } else if (screen == "f08") {
       ProjectionScreen(planningMonth, projection, projectionSettings, {
        val updated = it
        val submitted = drafts.values.filterKeys { key -> key.startsWith("${drafts.scope}:") }
        scope.launch {
         try {
          container.files.saveProjectionLimits(updated.base, updated.limits.getValue(updated.base))
          projectionDraft = updated
          if (drafts.values.filterKeys { key -> key.startsWith("${drafts.scope}:") } == submitted) drafts.clear()
         } catch (e: CancellationException) {
          throw e
         } catch (_: Exception) {
          failure = localized("No se pudieron guardar los ajustes del pronóstico.")
         }
        }
       }, plan, commitmentPlan, snapshot, ::open, draft = drafts)
      } else if (screen.startsWith("f10")) {
       if (ready) F10Screen(screen, container.files, busy, { busy = it }, { failure = it }, { success = it })
      } else if(screen.startsWith("f06")) F06Form(screen,subject,formMonth,plan,snapshot,drafts,busy,{ failure=it },::submitPlan,{ back() })
      else if(screen.startsWith("f07")) F07Form(screen,subject,formMonth,commitmentPlan,snapshot,drafts,busy,{ failure=it },::submitCommitment,{ back() })
      else F05Form(screen, subject, snapshot, drafts, busy, onError = { failure = it }, onSubmit = ::submit, onCancel = { back() }, preview = { rows -> repository.previewImport(rows) })
     } else when (selected) {
      0 -> MonthlySummary(currentMonth,currentDay,snapshot,plan,commitmentPlan,projection.result, open = ::open)
      1 -> {
       Text(localized("Registrar movimiento"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
       MovementActions(::open)
       GoldClasp()
       Text(localized("Movimientos recientes"), modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
       OutlinedTextField(filter, { filter = it }, label = { Text(localized("Filtrar historial: comercio, fecha o categoría")) }, modifier = Modifier.fillMaxWidth())
       MovementCategoryFilter(snapshot.categories, movementCategory) { movementCategory = it }
       val movements = snapshot.operations.filter { operation ->
        operation.inCategory(movementCategory) && (listOf(operation.historyTitle(), operation.date, operation.notes).any { it.contains(filter, true) } ||
         operation.matchesCategory(filter))
       }
       OperationCards(snapshot, movements, ::open)
       if (movements.isNotEmpty() && snapshot.holds.isNotEmpty()) GoldClasp()
       HoldCards(snapshot, ::open)
      }
      2 -> {
       val monthlyBills = monthlyAccountBills(plan, currentMonth)
       Action(localized("Crear cuenta"), accent = true) { open("account") }
       if (accountId.isNotEmpty()) {
        CompactRecordAction(localized("← Todos los productos")) { back() }
        snapshot.accounts.find { it.id == accountId }?.let { account ->
         val bills = monthlyBills[account.id].orEmpty()
         AccountCard(account, bills, detailed = true)
         GoldClasp()
         AccountMonthlyBills(bills, plan.templates, ::open)
         GoldClasp()
         AccountMonthlySavings(account, commitmentPlan, currentMonth)
         GoldClasp()
         Text(localized("Última conciliación: ") + (account.lastReconciliation?.let { localized("{0} · {1} · {2}\nDiferencia {3}", it.cutDate, it.balanceType, it.status, money(it.differenceMinor, account.currency)) } ?: localized("Sin comparar")))
         AccountActions(buildList {
          add(CompactAction(localized("Editar cuenta"), localized("Editar cuenta")) { open("editAccount", account.id) })
          add(CompactAction(if (account.archived) localized("Restaurar cuenta archivada") else localized("Archivar cuenta"), if (account.archived) localized("Restaurar") else localized("Archivar")) { open("archive", account.id) })
          if (!account.archived) {
           add(CompactAction(localized("Comparar con el banco"), localized("Conciliar")) { open("reconcile", account.id) })
           if (account.kind == "LIABILITY") add(CompactAction(localized("Registrar compra en esta tarjeta"), localized("Compra")) { open("credit", account.id) })
           else {
            add(CompactAction(localized("Registrar gasto en esta cuenta"), localized("Gasto")) { open("expense", account.id) })
            add(CompactAction(localized("Registrar ingreso en esta cuenta"), localized("Ingreso")) { open("income", account.id) })
            add(CompactAction(localized("Transferir desde esta cuenta"), localized("Transferir")) { open("transfer", account.id) })
            add(CompactAction(localized("Gestionar reserva de esta cuenta"), localized("Reservas")) { open("reserve", account.id) })
           }
          }
         })
         if (account.kind == "LIABILITY") CreditStatementCards(commitmentPlan, snapshot, account.id, ::open)
         snapshot.reconciliations.filter { it.accountId == account.id }.forEach { comparison ->
          InfoCard(localized("Corte {0} · {1}", comparison.cutDate, comparison.status), localized("{0}\nObservado {1}\nRegistrado {2}\nDiferencia {3}\n{4}", comparison.balanceType, money(comparison.observedMinor, account.currency), money(comparison.recordedMinor, account.currency), money(comparison.differenceMinor, account.currency), comparison.reason), 3)
          if (comparison.balanceType == "BOOKED" && comparison.status == "DIFFERENCE") Action(localized("Resolver diferencia con ajuste explícito")) { open("resolve", comparison.id) }
         }
         GoldClasp()
         Text(localized("Movimientos del mes"), style = MaterialTheme.typography.titleLarge)
         OutlinedTextField(filter, { filter = it }, label = { Text(localized("Filtrar historial de esta cuenta")) }, modifier = Modifier.fillMaxWidth())
         MovementCategoryFilter(snapshot.categories, accountMovementCategory) { accountMovementCategory = it }
         OperationCards(snapshot, snapshot.operations.filter {
          (it.bookedDate ?: it.date).startsWith(currentMonth) && (it.accountId == account.id || it.destinationId == account.id) &&
           it.inCategory(accountMovementCategory) && (it.matchesCategory(filter) || listOf(it.merchant, it.date, it.type).any { part -> part.contains(filter, true) })
         }, ::open)
         HoldCards(snapshot.copy(holds = snapshot.holds.filter { it.accountId == account.id && it.date.startsWith(currentMonth) }), ::open)
        }
       } else {
        snapshot.accounts.groupBy { it.productId }.values.flatten().forEachIndexed { index, account ->
         if (index > 0) GoldClasp()
         AccountCard(account, monthlyBills[account.id].orEmpty()) { rememberPage(); accountId = account.id; filter = ""; success = "" }
        }
       }
      }
      3 -> {
       Panel {
        val target = plan.plans.filter { it.month == currentMonth }.maxByOrNull { it.version }
        Text(localized("Meta Sobrante"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(target?.takeIf { it.targetMinor > 0 }?.let { money(it.targetMinor, it.targetCurrency) } ?: localized("Sin meta"),
         style = MaterialTheme.typography.headlineSmall)
        Action(localized("Configurar Meta Sobrante"), accent = true) { open("f06target") }
       }
       GoldClasp()
       SavingAndProjectCards(commitmentPlan, snapshot, ::open)
       Text(localized("Propósitos con dinero existente"), style = MaterialTheme.typography.titleMedium)
       Action(localized("Asignar o crear propósito")) { open("reserve") }; Action(localized("Liberar reserva")) { open("release") }
       Action(localized("Redistribuir entre propósitos")) { open("redistribute") }; Action(localized("Mover reserva a otra cuenta")) { open("moveReserve") }
       GoldClasp()
       snapshot.accounts.filter { it.reserves.isNotEmpty() }.forEachIndexed { index, account ->
        if (index > 0) GoldClasp()
        Panel {
         Text(account.name, style = MaterialTheme.typography.titleMedium)
         Text(localized("{0} · Sin asignar {1}", account.currency, decimal(account.unassignedMinor)))
        }
        account.reserves.forEach { reserve ->
         GoldClasp()
         Panel {
          Text(reserve.name, style = MaterialTheme.typography.titleMedium)
          Text(money(reserve.minor, account.currency), style = MaterialTheme.typography.bodyMedium)
         }
        }
       }
       if (snapshot.accounts.none { it.reserves.isNotEmpty() }) Text(localized("Aún no hay reservas. Asigna dinero disponible para comenzar."))
      }
      4 -> ConfigurationMenu(::open)
     }
     Spacer(Modifier.height(24.dp))
    }
   }
  }
 }
}

@Composable internal fun InfoCard(title: String, detail: String, tint: Int) {
 Panel {
  Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
  Text(detail, style = MaterialTheme.typography.bodyMedium)
 }
}
@Composable internal fun Action(title: String, enabled: Boolean = true, accent: Boolean = false, menu: Boolean = false, accentText: Boolean = false, onClick: () -> Unit) {
 val p = LocalPalette.current
 Row(Modifier.fillMaxWidth().heightIn(min = if (menu) 55.dp else 48.dp)
  .actionSurface(accent = accent, enabled = enabled, radius = 11)
  .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
  verticalAlignment = Alignment.CenterVertically,
  horizontalArrangement = if (menu) Arrangement.SpaceBetween else Arrangement.Center) {
  Text(title, modifier = if (menu) Modifier.weight(1f) else Modifier,
   color = (if (accent) p.selection.text else if (accentText) p.accent else p.ink).copy(alpha = if (enabled) 1f else .5f),
   fontWeight = FontWeight.SemiBold)
  if (menu) Text("›", modifier = Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.titleLarge)
 }
}
@Composable private fun AccountCard(account: AccountDetail, bills: List<PlannedOccurrence>, detailed: Boolean = false, onClick: (() -> Unit)? = null) {
 Column(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
  verticalArrangement = Arrangement.spacedBy(6.dp)) {
 Text(account.name + if (account.archived) localized(" · Archivada") else "", style = MaterialTheme.typography.titleLarge)
 Column(Modifier.fillMaxWidth().panelShape()
  .padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
   Text(account.accountTypeLabel(), style = MaterialTheme.typography.titleMedium)
   val palette = LocalPalette.current
   val label = when {
    account.kind != "LIABILITY" -> localized("Saldo contabilizado")
    account.balanceMinor >= 0 -> localized("Saldo a favor en tarjeta")
    else -> localized("Deuda")
   }
   val amount = if (account.kind == "LIABILITY" && account.balanceMinor < 0) {
    account.currency.name + " " + debt(account.balanceMinor)
   } else money(account.balanceMinor, account.currency)
   Text(androidx.compose.ui.text.buildAnnotatedString {
    append(label)
    append(" ")
    pushStyle(androidx.compose.ui.text.SpanStyle(color = palette.balanceColor(account.balanceMinor.compareTo(0L))))
    append(amount)
    pop()
   }, style = MaterialTheme.typography.titleLarge)
   if (account.kind != "LIABILITY") {
    val assigned = pendingOutsideReserves(account, bills)
    val unassigned = BigDecimal.valueOf(account.unassignedMinor, 2) - assigned
    Text(localized("Reservado para Ahorros ") + money(account.reservedMinor, account.currency))
    if (assigned.signum() > 0) Text(localized("Asignado a gastos y apps ") + account.currency + " " + assigned.toPlainString())
    Text(localized("Sin Asignar - Sobrante ") + account.currency + " " + unassigned.toPlainString())
   }
   else if (detailed) { Text(localized("Límite {0} · No es dinero propio", money(account.creditLimitMinor, account.currency))); Text(localized("Crédito disponible tras retenciones {0} {1}", account.currency, BigDecimal.valueOf(account.creditLimitMinor, 2).add(BigDecimal.valueOf(account.balanceMinor, 2)).subtract(BigDecimal.valueOf(account.heldMinor, 2)).toPlainString())) }
   if (detailed) Text(localized("Retenciones pendientes {0}", money(account.heldMinor, account.currency)))
   if (detailed && account.kind != "LIABILITY") Text(localized("Disponible tras retenciones {0} {1}", account.currency, BigDecimal.valueOf(account.balanceMinor, 2).subtract(BigDecimal.valueOf(account.heldMinor, 2)).toPlainString()))
 }
 }
}
private fun OperationDetail.inCategory(id: String) =
 id.isEmpty() || categoryId(category) == id || splits.any { categoryId(it.category) == id }

private fun OperationDetail.historyTitle() =
 if (id.startsWith("salary-receipt:")) localized("Ingreso de salario") else merchant.ifBlank { operationLabel(this) }

@Composable private fun MovementCategoryFilter(categories: List<LedgerCategory>, selected: String, select: (String) -> Unit) {
 if (categories.isEmpty()) return
 var expanded by remember { mutableStateOf(false) }
 val all = localized("Todas las categorías")
 Box {
  OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = outlineActionColors()) {
   Text(categories.firstOrNull { it.id == selected }?.name ?: all)
  }
  DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
   DropdownMenuItem(text = { Text(all) }, onClick = { select(""); expanded = false })
   categories.forEach { category ->
    DropdownMenuItem(text = { Text(category.name) }, onClick = { select(category.id); expanded = false })
   }
  }
 }
}

@Composable private fun OperationCards(snapshot: F05Snapshot, operations: List<OperationDetail>, open: (String, String) -> Unit) {
 if (operations.isEmpty()) Text(localized("No hay movimientos con este filtro."))
 val categoryNames = snapshot.categories.associate { it.id to it.name }
 fun categoryLabel(value: String) = categoryNames[categoryId(value)] ?: value
 Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
 operations.asReversed().forEachIndexed { index, operation ->
  if (index > 0) GoldClasp()
  val account = snapshot.accounts.find { it.id == operation.accountId }
  Panel {
   RecordHeading("${operation.date} · ${operation.historyTitle()}", "${account?.name.orEmpty()}\n${listOf(categoryLabel(operation.category), operationLabel(operation)).filter { it.isNotBlank() }.distinct().joinToString(" · ")}${if (operation.voided) localized(" · ANULADO") else ""}\n${operation.notes}" + if (operation.refundedMinor > 0) localized("\nReembolsado {0}", decimal(operation.refundedMinor)) else "", amount = money(operation.amountMinor, account?.currency ?: Currency.CRC))
   if (operation.bookedDate != null && operation.bookedDate != operation.date) Text(localized("Contabilizado: {0}", operation.bookedDate))
   operation.destinationId?.let { id -> snapshot.accounts.find { it.id == id }?.let { target -> Text(localized("Destino {0} · {1} {2}", target.name, target.currency, operation.destinationMinor?.let { decimal(it) }.orEmpty())) } }
   if (operation.rate != null) Text(localized("Tasa registrada {0} · {1} · {2}", operation.rate, operation.rateSource.orEmpty(), operation.rateDate ?: operation.date))
   if (operation.previouslyDeducted && operation.type != "REVERSAL") Text(localized("Ya descontado de la cuenta"))
   if (operation.type == "CREDIT_PURCHASE" && !operation.previouslyDeducted) {
    val remainder = purchaseRemainder(operation)
    Text(localized("Pagado {0} · Pendiente {1}", decimal(operation.settledMinor), remainder.max(BigDecimal.ZERO).toPlainString()))
    if (remainder.signum() < 0) Text(localized("Reembolso de compra pagada: {0} a favor en tarjeta; no aumenta dinero propio.", remainder.negate().toPlainString()))
    Text(localized("Origen previsto: ") + (snapshot.accounts.find { it.id == operation.fundingAccountId }?.name ?: localized("Sin confirmar")) + (operation.fundingPurposeId?.let { purpose -> " · " + (snapshot.accounts.flatMap { it.reserves }.find { it.purposeId == purpose }?.name ?: localized("Reserva prevista")) } ?: ""))
   }
   operation.creditAllocations.forEach { allocation -> Text(localized("Aplicado a {0}: {1} · ", snapshot.operations.find { it.id == allocation.purchaseId }?.merchant ?: localized("Compra"), decimal(allocation.minor)) + if (allocation.purposeId == null) localized("Sin asignar") else localized("Reserva financiada")) }
   operation.splits.forEach { Text("${categoryLabel(it.category)}: ${decimal(it.minor)}", style = MaterialTheme.typography.bodySmall) }
   val feeChild = operation.id.endsWith(":fee")
   if (!operation.voided && !feeChild && operation.type !in listOf("REFUND", "REVERSAL", "ADJUSTMENT", "DEBT_PAYMENT", "DEBT_ADJUSTMENT", "DEBT_REVERSAL")) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
     if (operation.type in listOf("EXPENSE", "INCOME", "CREDIT_PURCHASE")) CompactRecordAction(localized("Editar")) { open("correct", operation.id) }
     CompactRecordAction(localized("Anular")) { open("void", operation.id) }
     if (!operation.previouslyDeducted && operation.type in listOf("EXPENSE", "CREDIT_PURCHASE")) CompactRecordAction(localized("Reembolsar")) { open("refund", operation.id) }
    }
   }
  }
 }
 }
}
@Composable private fun HoldCards(snapshot: F05Snapshot, open: (String, String) -> Unit) {
 snapshot.holds.forEachIndexed { index, hold ->
  if (index > 0) GoldClasp()
  val account = snapshot.accounts.find { it.id == hold.accountId }
  Panel {
   val status = when (hold.status) {
    "PENDING", "AUTHORIZED", "ACTIVE" -> localized("Pendiente")
    "CONFIRMED" -> localized("Confirmada")
    else -> localized("Liberada")
   }
   RecordHeading(localized("Retención · {0}", status), "${hold.merchant}\n${account?.name.orEmpty()} · ${money(hold.minor, account?.currency ?: Currency.CRC)}\n${hold.date} · " + when (hold.status) { "PENDING", "AUTHORIZED", "ACTIVE" -> localized("Autorización pendiente, separada del libro"); "CONFIRMED" -> localized("Sustituida por un único cargo contabilizado"); else -> localized("Liberada sin cargo") })
   if (hold.status in listOf("PENDING", "AUTHORIZED", "ACTIVE")) { Action(localized("Confirmar cargo contabilizado"), onClick = { open("confirmHold", hold.id) }); Action(localized("Liberar retención"), onClick = { open("releaseHold", hold.id) }) }
  }
 }
}
@Composable private fun RecordHeading(title: String, detail: String, amount: String? = null) {
 Text(title, color = LocalPalette.current.accent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
 if (amount != null) Text(amount, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
 Text(detail.trimEnd(), style = MaterialTheme.typography.bodyMedium)
}
internal fun userError(error: Exception): String = localized(
 localized("No se guardó. {0}"),
 error.message?.let { AppLanguage.current.backendMessage(it) } ?: localized("Revisa los datos e inténtalo de nuevo."),
)
internal fun actionTitle(action: String): String = mapOf("account" to localized("Crear cuenta"), "editAccount" to localized("Editar cuenta"), "rename" to localized("Editar nombre"), "archive" to localized("Archivar / restaurar"), "expense" to localized("Registrar gasto"), "income" to localized("Registrar ingreso"), "credit" to localized("Compra con crédito"), "payment" to localized("Pago de tarjeta"), "transfer" to localized("Transferencia propia"), "withdraw" to localized("Retiro de efectivo"), "fx" to localized("Conversión real"), "correct" to localized("Corrección auditada"), "void" to localized("Anular con motivo"), "refund" to localized("Reembolso parcial"), "reserve" to localized("Asignar reserva"), "release" to localized("Liberar reserva"), "redistribute" to localized("Redistribuir reserva"), "moveReserve" to localized("Mover reserva y dinero"), "reconcile" to localized("Comparar con el banco"), "resolve" to localized("Ajuste explícito"), "hold" to localized("Retención pendiente"), "confirmHold" to localized("Confirmar cargo"), "releaseHold" to localized("Liberar retención"), "import" to localized("Importación asistida"))[action] ?: action
