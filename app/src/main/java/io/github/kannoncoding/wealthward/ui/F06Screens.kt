package io.github.kannoncoding.wealthward.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import io.github.kannoncoding.wealthward.domain.*
import io.github.kannoncoding.wealthward.notifications.*
import java.time.YearMonth
import java.math.BigDecimal

internal fun natureName(value: PlanNature) = when(value) {
 PlanNature.INCOME -> localized("Ingresos"); PlanNature.FIXED -> localized("Gastos fijos"); PlanNature.SUBSCRIPTION -> localized("Suscripciones / apps"); PlanNature.VARIABLE_NEED -> localized("Necesidades variables"); PlanNature.FREE -> localized("Gasto libre"); PlanNature.SAVING -> localized("Ahorro financiado"); PlanNature.DEBT -> localized("Deudas"); PlanNature.TARGET -> localized("Meta Sobrante")
}
internal fun f06Title(action:String) = mapOf("f06salary" to localized("Configurar ingreso neto"), "f06extra" to localized("Ingreso extraordinario esperado"), "f06template" to localized("Obligación recurrente"), "f06budget" to localized("Presupuesto por periodo"), "f06target" to localized("Meta Sobrante"), "f06pay" to localized("Confirmar pago o ingreso real"), "f06confirm" to localized("Confirmar importe real"), "f06fund" to localized("Financiar reserva anual"), "f06purpose" to localized("Aportar a reserva anual"), "f06financing" to localized("Periodo de financiación"), "f06copy" to localized("Preparar otro periodo"), "f06reminders" to localized("Recordatorios locales"), "f06link" to localized("Vincular movimiento existente"))[action] ?: action

@Composable internal fun PlanningDashboard(month:String, changeMonth:(String)->Unit, plan:F06Snapshot, ledger:F05Snapshot, open:(String,String)->Unit, execute:(F06Command)->Unit, projection: @Composable () -> Unit = {}) {
 var monthDraft by androidx.compose.runtime.saveable.rememberSaveable(month) { mutableStateOf(month) }
 var monthError by remember { mutableStateOf("") }
 Text(localized("Tu mes en un vistazo: ingresos previstos, gastos, próximos pagos y cuánto podría quedarte al cierre."))
 OutlinedTextField(monthDraft, { monthDraft=it; monthError="" }, label={ Text(localized("Periodo · AAAA-MM")) })
 Action(localized("Ver periodo")) { runCatching { YearMonth.parse(monthDraft).toString() }.onSuccess { changeMonth(it) }.onFailure { monthError=localized("Mes inválido. Usa AAAA-MM.") } }
 if(monthError.isNotEmpty()) Text(monthError,color=MaterialTheme.colorScheme.error)
 Text(localized("Periodo mostrado: {0}", month))
 projection()
 Action(localized("Cargar ingresos y pagos del mes")) { execute(F06Command.OpenPeriod(newId(),month)) }
 Action(localized("Configurar Meta Sobrante")) { open("f06target","") }
 Action(localized("Editar presupuesto por categoría")) { open("f06budget","") }
 Action(localized("Preparar / copiar periodo")) { open("f06copy","") }
 Text(localized("Presupuesto / Real / Diferencia"), style=MaterialTheme.typography.titleLarge)
 Text(localized("Diferencia = presupuesto menos real. Un valor negativo indica exceso. Las reservas financiadas se muestran separadas del gasto; transferencias propias no son gasto."))
 plan.comparisons.forEach { row ->
  val usage = when(row.nature) {
   PlanNature.SAVING -> localized("Aporte financiado")
   PlanNature.DEBT -> localized("Aplicado a deuda")
   else -> localized("Consumo económico")
  }
  InfoCard("${row.group} · ${natureName(row.nature)} · ${row.currency}", localized("Presupuesto {0}\nReal pagado {1}\n{2} {3}\nDiferencia de caja {4}", money(row.budgetMinor,row.currency), money(row.actualMinor,row.currency), usage, money(row.consumptionMinor,row.currency), money(row.differenceMinor,row.currency)), 2)
  if(row.nature in setOf(PlanNature.VARIABLE_NEED,PlanNature.FREE)) {
   val remaining=BigDecimal.valueOf(row.budgetMinor,2).subtract(BigDecimal.valueOf(row.consumptionMinor,2))
   Text(localized("Restante por consumo: {0} {1}{2}", row.currency, remaining.toPlainString(), if(remaining.signum()<0) localized(" · Exceso de presupuesto") else ""))
   Text(localized("El consumo incluye compras a crédito pendientes. El restante por consumo indica lo que queda en el sobre; la diferencia de caja compara solo pagos reales."))
  }
 }
 if(plan.comparisons.isEmpty()) Text(localized("Sin líneas para este periodo. Crea un presupuesto o una obligación recurrente."))
 plan.plans.filter { it.month==month }.maxByOrNull { it.version }?.let { version ->
  InfoCard(localized("Versión {0} · Meta Sobrante", version.version), if(version.targetMinor==0L) localized("Sin meta") else localized("Meta {0}. Es un objetivo separado: no genera un gasto ni una reserva.", money(version.targetMinor,version.targetCurrency)), 1)

 }
 plan.plans.filter { it.month==month }.sortedBy { it.version }.forEach { version ->
  var expanded by remember(version.month,version.version) { mutableStateOf(false) }
  Action(localized("{0} · versión {1} · ver historial", if(version.version==1) localized("Presupuesto inicial") else localized("Revisión"), version.version)) { expanded=!expanded }
  if(expanded) {
   InfoCard(localized("Versión {0} · solo lectura", version.version), localized("Vigente {0}\nMotivo: {1}\nMeta Sobrante {2}", version.effectiveDate, version.reason, money(version.targetMinor,version.targetCurrency)),0)
   version.rows.forEach { row -> Text("${row.group} · ${natureName(row.nature)} · ${row.name}: ${money(row.minor,row.currency)}${if(row.accumulate) localized(" · Acumula sobrante") else localized(" · Reinicia cada mes")}") }
  }
 }
 Text(localized("Próximos y vencidos · calendario"), style=MaterialTheme.typography.titleLarge)
 Text(localized("Cada fecha conserva su obligación original. Los vencidos pendientes siguen visibles al cambiar de mes."))
 plan.occurrences.filter { it.nominalMonth==month || (it.nominalMonth<month && it.pendingMinor>0) }.sortedWith(compareBy({it.dueDate},{it.id})).forEach { occurrence ->
  val account=ledger.accounts.find { it.id==occurrence.suggestedAccountId }
  val termDebt = occurrence.templateId.startsWith("term-debt:")
  val applied = if(termDebt) localized("Aplicado a cuota") else localized("Consumo vinculado")
  InfoCard("${occurrence.dueDate} · ${occurrence.displayName()}", localized("{0} · {1}\nCuenta sugerida: {2}\nEstimado {3}\nConfirmado {4}\nReal pagado {5}\n{6} {7}\nTu pendiente {8} · {9}", natureName(occurrence.nature), if (occurrence.templateId == "salary") localized("Ingresos") else occurrence.group, account?.name ?: localized("Sin elegir"), money(occurrence.expectedMinor,occurrence.currency), occurrence.confirmedMinor?.let { money(it,occurrence.currency) } ?: localized("Por confirmar"), money(occurrence.cashPaidMinor,occurrence.currency), applied, money(occurrence.paidMinor,occurrence.currency), money(occurrence.pendingMinor,occurrence.currency), if(occurrence.pendingMinor==0L && occurrence.paidMinor==0L) localized("Sin saldo pendiente") else if(occurrence.pendingMinor==0L) if(occurrence.nature==PlanNature.INCOME) localized("Recibido") else localized("Pagado") else if(occurrence.overdue) localized("Vencido") else if(occurrence.paidMinor>0) localized("Parcial") else localized("Pendiente")), if(occurrence.overdue) 1 else 3)
  if(occurrence.paymentPendingMinor>0) {
   if(termDebt) {
    Action(localized("Pagar cuota · {0}", occurrence.displayName())) { open("f07installment", "${occurrence.nominalMonth}/${occurrence.templateId.removePrefix("term-debt:")}") }
   } else {
   Action(if(occurrence.nature==PlanNature.INCOME) localized("Registrar recepción · {0}", occurrence.displayName()) else localized("Pagar · {0}", occurrence.displayName())) { open("f06pay",occurrence.id) }
   Action(localized("Confirmar importe · {0}", occurrence.displayName())) { open("f06confirm",occurrence.id) }
   Action(localized("Vincular movimiento · {0}", occurrence.displayName())) { open("f06link",occurrence.id) }
  Action(localized("Cambiar periodo de financiación · {0}", occurrence.displayName())) { open("f06financing",occurrence.id) }
   if(occurrence.purposeId!=null) Action(localized("Financiar reserva · {0}", occurrence.displayName())) { open("f06fund",occurrence.id) }
   }
  }
 }
 if(plan.occurrences.isEmpty()) Text(localized("Sin vencimientos generados. Abre el periodo después de configurar ingresos y obligaciones."))
}

private val configSections get() = linkedMapOf(
 "sectionIncome" to localized("Ingresos"),
 "sectionBills" to localized("Gastos y Suscripciones"),
 "sectionDebt" to localized("Deudas"),
 "f06reminders" to localized("Recordatorios - Preferencias y Permisos"),
 "sectionAccounts" to localized("Cuentas"),
 "sectionBackup" to localized("Respaldo y exportar"),
 "sectionAppearance" to localized("Configuración"),
)
internal fun configurationTitle(screen: String) =
 if (screen == "sectionLanguage") "Idioma / Language" else configSections[screen].orEmpty()

@Composable internal fun ConfigurationMenu(open: (String,String) -> Unit) {
 configSections.forEach { (id,label) -> Action(label, menu = true) { open(id, "") } }
}

@Composable internal fun ConfigurationSection(screen: String, plan: F06Snapshot, commitments: F07Snapshot, ledger: F05Snapshot, open: (String,String) -> Unit) {
 when (screen) {
  "sectionIncome" -> {
   Action(localized("Configurar salario neto")) { open("f06salary", "") }
   Action(localized("Crear ingreso extraordinario")) { open("f06extra", "") }
   plan.salaries.sortedByDescending { it.effectiveMonth }.forEach {
    InfoCard(localized("Salario neto · {0}", it.effectiveMonth), money(it.netMinor,it.currency), 0)
   }
   PendingPayments(plan.occurrences.filter { it.nature==PlanNature.INCOME }, open)
  }
  "sectionBills" -> {
   Action(localized("Crear un nuevo gasto")) { open("f06template", "FIXED") }
   Action(localized("Crear una suscripción")) { open("f06template", "SUBSCRIPTION") }
 plan.templates.groupBy { it.id }.values.forEach { versions -> val item=versions.maxBy { it.version }
  InfoCard(item.name, localized("{0} · {1}\nDesde {2} · versión {3} · {4}", natureName(item.nature), money(item.minor,item.currency), item.effectiveMonth, item.version, if(item.cancelled) localized("Cancelada") else if(item.paused) localized("Pausada") else localized("Activa")) + if(item.frequency==Recurrence.ANNUAL) localized("\nAporte mensual previsto {0} {1}; financiar requiere dinero existente.", decimal(item.monthlyContributionMinor), item.currency) else "", 0)
  Action(localized("Revisar / pausar · {0}", item.name)) { open("f06template",item.id) }
  if(item.frequency==Recurrence.ANNUAL && item.purposeId!=null) Action(localized("Aportar a reserva anual · {0}", item.name)) { open("f06purpose",item.id) }
  var showHistory by remember(item.id) { mutableStateOf(false) }
  Action(localized("Historial de revisiones · {0}", item.name)) { showHistory=!showHistory }
  if(showHistory) versions.sortedBy { it.version }.forEach { revision ->
   InfoCard(localized("Versión {0} · desde {1}", revision.version, revision.effectiveMonth), localized("{0} · {1}\nGrupo {2} · {3}\nDías nominales {4}{5}\n{6} · {7}\nMotivo: {8}", revision.name, money(revision.minor,revision.currency), revision.group, natureName(revision.nature), revision.day, if(revision.frequency==Recurrence.TWO_SLOTS) localized(" y {0}", revision.secondDay) else "", revision.frequency, if(revision.cancelled) localized("Cancelada") else if(revision.paused) localized("Pausada") else localized("Activa"), revision.reason) + if(revision.householdTotalMinor!=null) localized("\nTotal hogar {0} · mi porcentaje {1}", money(revision.householdTotalMinor,revision.currency), revision.ownPercent ?: localized("Importe fijo")) else "",4)
  }
 }
   PendingPayments(plan.occurrences.filter { it.nature in setOf(PlanNature.FIXED,PlanNature.SUBSCRIPTION) }, open)
   Action(localized("Editar presupuesto por categoría")) { open("f06budget", "") }
   Action(localized("Preparar otro mes")) { open("f06copy", "") }
   Action(localized("Ajustar pronóstico")) { open("f08", "") }
  }
  "sectionDebt" -> {
   TermDebtCards(commitments, ledger, open)
   PendingPayments(commitments.installments, open)
  }
  "sectionAccounts" -> Action(localized("Importar cuentas")) { open("import", "") }
  "sectionBackup" -> F10Configuration { open(it, "") }
 }
}

@Composable private fun PendingPayments(occurrences: List<PlannedOccurrence>, open: (String,String) -> Unit) {
 occurrences.sortedBy { it.dueDate }.forEach { item ->
  val term = item.templateId.startsWith("term-debt:")
  InfoCard(item.displayName(), "${item.dueDate} · " +
   if(item.pendingMinor == 0L) if(item.previouslyDeducted) localized("Pagado · ya descontado") else localized("Registrado") else localized("Pendiente {0}", money(item.pendingMinor,item.currency)), 2)
  if(item.pendingMinor > 0) {
   if(term) Action(localized("Pagar cuota · {0}", item.displayName())) {
    open("f07installment", "${item.nominalMonth}/${item.templateId.removePrefix("term-debt:")}")
   } else {
    Action(if(item.nature == PlanNature.INCOME) localized("Registrar recepción · {0}", item.displayName()) else localized("Registrar pago · {0}", item.displayName())) { open("f06pay",item.id) }
    Action(localized("Confirmar importe · {0}", item.displayName())) { open("f06confirm",item.id) }
    Action(localized("Vincular movimiento · {0}", item.displayName())) { open("f06link",item.id) }
    Action(localized("Cambiar periodo de financiación · {0}", item.displayName())) { open("f06financing",item.id) }
   }
  }
 }
}

@Composable internal fun F06Form(action:String, subject:String, month:String, snapshot:F06Snapshot, ledger:F05Snapshot, draft:Drafts, busy:Boolean, onError:(String)->Unit, onSubmit:(F06Command)->Unit, onCancel:()->Unit) {
 val f=Fields(draft,busy)
 val cash=ledger.accounts.filter { !it.archived && it.kind!="LIABILITY" }
 var command:(()->F06Command)?=null
 val occurrence=snapshot.occurrences.find { it.id==subject }
 when(action) {
  "f06salary" -> {
   Text(localized("Salario neto esperado. Último lunes–viernes del mes civil, 09:00 America/Costa_Rica; no se calculan feriados. Solo una recepción confirmada modifica el saldo."))
   val latest=snapshot.salaries.withIndex().maxWithOrNull(compareBy({ it.value.effectiveMonth }, { it.index }))?.value
   f.text("effective",localized("Vigente desde · AAAA-MM"),month); f.text("amount",localized("Salario neto"),latest?.netMinor?.let(::decimal).orEmpty(),true)
   val currency=f.choice("currency",localized("Moneda"),Currency.entries.map { it.name to it.name },latest?.currency?.name ?: "CRC")
   val account=f.account("account",localized("Cuenta de recepción sugerida"),cash.filter { it.currency.name==currency },latest?.accountId.orEmpty())
   val schedule=f.choice("schedule",localized("Fecha nominal"),listOf("LAST" to localized("Último lunes–viernes"),"DAY" to localized("Día fijo del mes")),if(latest?.scheduleDay==null) "LAST" else "DAY")
   if(schedule=="DAY") f.text("day",localized("Día nominal de recepción (1–31)"),(latest?.scheduleDay ?: 1).toString(),true)
   f.text("reason",localized("Motivo de la versión"))
   command={ F06Command.SaveSalary(draft.request(),SalaryVersion(draft.get("entity").ifBlank { newId().also { draft.set("entity",it) } },YearMonth.parse(f.required("effective",localized("mes"),month)).toString(),f.amount(label=localized("salario neto")),Currency.valueOf(currency),account.ifBlank { null },f.required("reason",localized("motivo")),if(schedule=="DAY") f.required("day",localized("día"),"1").toInt() else null)) }
  }
  "f06extra" -> {
   f.text("name",localized("Nombre del ingreso")); f.calendar("date",localized("Fecha esperada"),today()); f.text("amount",localized("Importe esperado"),numeric=true)
   val currency=f.choice("currency",localized("Moneda"),Currency.entries.map { it.name to it.name })
   val account=f.account("account",localized("Cuenta de recepción sugerida"),cash.filter { it.currency.name==currency })
   Text(localized("Este registro es una expectativa. Confirma la recepción en el calendario cuando recibas el dinero."))
   command={ val date=f.date(); F06Command.ExtraordinaryIncome(draft.request(),draft.get("entity").ifBlank { newId().also { draft.set("entity",it) } },f.required("name",localized("nombre")),date.take(7),date,f.amount(),Currency.valueOf(currency),account.ifBlank { null }) }
  }
  "f06template" -> {
   val old=snapshot.templates.filter { it.id==subject }.maxByOrNull { it.version }
   f.text("name",localized("Nombre de la obligación"),old?.name.orEmpty()); f.text("group",localized("Grupo visual personalizado"),old?.group ?: "Hogar")
   val nature=f.choice("nature",localized("Tipo financiero"),listOf(PlanNature.FIXED,PlanNature.SUBSCRIPTION).map { it.name to natureName(it) },old?.nature?.name ?: subject.takeIf { it in listOf("FIXED","SUBSCRIPTION") }.orEmpty())
   val categoryDefault = old?.category.orEmpty()
   f.category("category", localized("Categoría (opcional)"), ledger.categories, categoryDefault)
   f.text("effective",localized("Vigente desde · AAAA-MM"),month)
   f.text("amount",localized("Importe propio estimado"),old?.minor?.let(::decimal).orEmpty(),true)
   val currency=f.choice("currency",localized("Moneda"),Currency.entries.map { it.name to it.name },old?.currency?.name ?: "CRC")
   val recurrence=f.choice("recurrence",localized("Frecuencia"),listOf("MONTHLY" to localized("Mensual"),"ANNUAL" to localized("Anual"),"EVERY_N_MONTHS" to localized("Cada N meses"),"TWO_SLOTS" to localized("Dos fechas fijas cada mes")),old?.frequency?.name ?: "MONTHLY")
   f.text("day",localized("Día nominal (1–31)"),(old?.day ?: 1).toString(),true)
   if(recurrence=="TWO_SLOTS") f.text("second",localized("Segunda fecha fija (1–31)"),(old?.secondDay ?: 15).toString(),true)
   if(recurrence=="EVERY_N_MONTHS") f.text("interval",localized("Intervalo en meses"),(old?.intervalMonths ?: 1).toString(),true)
   Text(localized("Un día que no existe se ajusta al último día del mes; el siguiente mes conserva el día nominal. Dos fechas fijas no equivalen a cada 15 días."))
   val account=f.account("account",localized("Cuenta o tarjeta de pago sugerida"),ledger.accounts.filter { !it.archived && it.currency.name==currency },old?.suggestedAccountId.orEmpty())
   val purposes=ledger.accounts.filter { it.currency.name==currency }.flatMap { it.reserves }.distinctBy { it.purposeId }
   val purpose=if(recurrence=="ANNUAL") f.choice("purpose",localized("Reserva anual existente"),listOf("" to localized("Sin reserva vinculada"))+purposes.map { it.purposeId to it.name },old?.purposeId.orEmpty()) else ""
   if(recurrence=="ANNUAL") Text(localized("Crea y financia un propósito desde Ahorros para vincularlo aquí. La contribución prevista no crea fondos."))
   if(recurrence=="ANNUAL") f.text("contribution",localized("Aporte mensual previsto"),decimal(old?.monthlyContributionMinor ?: 0),true)
   val household=f.check("household",localized("Es un gasto compartido del hogar"),old?.householdTotalMinor!=null)
   var share="FIXED"
   if(household) { f.text("total",localized("Total del hogar"),old?.householdTotalMinor?.let(::decimal).orEmpty(),true); share=f.choice("share",localized("Cómo calcular mi parte"),listOf("FIXED" to localized("Importe fijo propio"),"PERCENT" to localized("Porcentaje propio")),if(old?.ownPercent!=null) "PERCENT" else "FIXED"); if(share=="PERCENT") f.text("percent",localized("Mi porcentaje (0–100)"),old?.ownPercent ?: "50",true) else f.text("own",localized("Mi importe fijo"),old?.ownFixedMinor?.let(::decimal).orEmpty(),true); Text(localized("Solo tu parte se presupuesta y paga. Porcentajes se redondean HALF_UP al centavo.")) }
   val paused=f.check("paused",localized("Pausar desde la vigencia elegida"),old?.paused ?: false)
   val cancelled=f.check("cancelled",localized("Cancelar desde la vigencia elegida"),old?.cancelled ?: false)
   f.text("reason",localized("Motivo de esta revisión"))
   command={
    f.required("reason",localized("motivo"))
    val total=if(household) f.amount("total",localized("total hogar")) else null
    val percent=if(household && share=="PERCENT") f.required("percent",localized("porcentaje")) else null
    val fixed=if(household && share=="FIXED") f.amount("own",localized("parte propia"),allowZero=true) else null
    val own=if(total!=null) PlanningMath.ownShare(total,percent,fixed) else f.amount()
    F06Command.SaveTemplate(draft.request(),RecurringTemplate(old?.id ?: draft.get("entity").ifBlank { newId().also { draft.set("entity",it) } },(old?.version ?: 0)+1,f.required("name",localized("nombre")),f.required("group",localized("grupo"),"Hogar"),PlanNature.valueOf(nature),own,Currency.valueOf(currency),YearMonth.parse(f.required("effective",localized("vigencia"),month)).toString(),Recurrence.valueOf(recurrence),f.required("day",localized("día"),"1").toInt(),f.value("second","15").toInt(),f.value("interval","1").toInt(),account.ifBlank { null },purpose.ifBlank { null },paused,cancelled,total,percent,fixed,if(recurrence=="ANNUAL") f.amount("contribution",localized("aporte mensual"),allowZero=true) else 0,f.required("reason",localized("motivo")),category=f.value("category",categoryDefault).trim().ifBlank { null }))
   }
  }
  "f06pay", "f06confirm", "f06fund", "f06link" -> {
   if(occurrence==null) { Text(localized("Vuelve al calendario y sincroniza el periodo para elegir la obligación.")); return }
   InfoCard(occurrence.displayName(),localized("Fecha {0}\nTu pendiente {1}", occurrence.dueDate, money(occurrence.pendingMinor,occurrence.currency)) + if (occurrence.shared) localized("\nTotal pendiente del hogar {0}", money(occurrence.paymentPendingMinor, occurrence.currency)) else "",2)
   when(action) {
    "f06confirm" -> { f.text("amount",localized("Importe real total"),decimal(occurrence.paymentTotalMinor),true); Text(localized("Confirma el total real, conservando el estimado y los pagos ya registrados.")); command={ F06Command.ConfirmAmount(draft.request(),subject,f.amount(allowZero=true)) } }
    "f06link" -> {
     val operations=ledger.operations.filter { !it.voided && it.type in (if(occurrence.nature==PlanNature.INCOME) setOf("INCOME") else setOf("EXPENSE","CREDIT_PURCHASE")) && ledger.accounts.any { account -> account.id==it.accountId && account.currency==occurrence.currency } }
     val operation=f.choice("operation",localized("Movimiento ya registrado"),operations.map { it.id to "${it.date} · ${it.merchant} · ${decimal(it.amountMinor)}" })
     Text(localized("Vincular reconoce un movimiento existente; no registra otro pago."))
     command={ F06Command.LinkPayment(draft.request(),subject,operation) }
    }
    else -> {
     val canUseCard = action=="f06pay" && occurrence.nature in setOf(PlanNature.FIXED, PlanNature.SUBSCRIPTION)
     val eligible = if (canUseCard) ledger.accounts.filter { !it.archived } else cash
     val account=f.account("account",if (canUseCard) localized("Cuenta o tarjeta usada") else if(action=="f06fund") localized("Cuenta de financiación") else localized("Cuenta del movimiento real"),eligible.filter { it.currency==occurrence.currency },occurrence.suggestedAccountId.orEmpty())
     val alreadyDeducted = canUseCard && occurrence.paymentPendingMinor > 0 && f.check("alreadyDeducted", localized("Este pago ya se había descontado de la cuenta seleccionada"))
     if (!alreadyDeducted && eligible.any { it.id == account && it.kind == "LIABILITY" }) Text(localized("Se registra el cargo en la tarjeta. Pagá la tarjeta por separado desde tu cuenta de débito."))
     if (alreadyDeducted) Text(localized("Importe a descontar: 0. Se marcará como pagado sin cambiar el saldo."))
     else f.text("amount",if(action=="f06fund") localized("Importe a reservar") else localized("Importe real recibido o pagado"),decimal(if (action == "f06pay") occurrence.paymentPendingMinor else occurrence.pendingMinor),true)
     if(action!="f06fund") f.calendar("date",localized("Fecha real del movimiento"),today())
     val confirmed=f.check("confirmed",if(action=="f06fund") localized("Confirmo asignar dinero existente a la reserva") else localized("Confirmo que este movimiento realmente ocurrió"))
     if (!alreadyDeducted) Text(if(action=="f06fund") localized("Financiar reserva protege dinero existente y no paga la obligación. Puedes liberarlo desde Metas.") else localized("Puedes registrar una parte. El pendiente se reduce con este único movimiento vinculado."))
     command={ require(confirmed) { localized("Confirma el movimiento real antes de guardar.") }; if(action=="f06fund") F06Command.FundAnnual(draft.request(),subject,account,f.amount()) else if (alreadyDeducted) F06Command.ConfirmPreviouslyDeducted(draft.request(),subject,account,f.date()) else F06Command.Pay(draft.request(),subject,account,f.amount(),f.date()) }
    }
   }
  }
  "f06financing" -> {
   f.text("month",localized("Periodo que financia esta obligación"),occurrence?.financingMonth ?: month)
   Text(localized("Cambia el periodo de planificación, conservando la fecha de vencimiento y los pagos originales."))
   command={ F06Command.AssignFundingPeriod(draft.request(),subject,YearMonth.parse(f.required("month",localized("periodo"),month)).toString()) }
  }
  "f06purpose" -> {
   val template=snapshot.templates.filter { it.id==subject }.maxByOrNull { it.version }
   val account=f.account("account",localized("Cuenta de financiación"),cash.filter { it.currency==template?.currency },template?.suggestedAccountId.orEmpty())
   f.text("amount",localized("Aporte real a reserva"),template?.monthlyContributionMinor?.let(::decimal).orEmpty(),true)
   val confirmed=f.check("confirmed",localized("Confirmo asignar dinero existente a la reserva"))
   command={ require(confirmed) { localized("Confirma la asignación de dinero existente.") }; F06Command.FundPurpose(draft.request(),subject,account,f.amount()) }
  }
  "f06copy" -> {
   f.text("source",localized("Periodo de origen"),month); f.text("target",localized("Periodo a preparar"),runCatching { YearMonth.parse(month).plusMonths(1).toString() }.getOrDefault(month))
   Text(localized("Copia constantes, meta y cuentas sugeridas. No copia pagos, saldos ni reservas financiadas; regenerar el mismo periodo no duplica obligaciones."))
   command={ F06Command.CopyPeriod(draft.request(),YearMonth.parse(f.required("source",localized("origen"),month)).toString(),YearMonth.parse(f.required("target",localized("destino"))).toString()) }
  }
  "f06target" -> {
   val old = snapshot.plans.filter { it.month == month }.maxByOrNull { it.version }
   Text(localized("¿Cuánto querés que te sobre al cierre de {0}? Usá 0 para quitar la meta.", month))
   f.text("target",localized("Monto de la meta"),decimal(old?.targetMinor ?: 0),true)
   val currency = f.choice("currency",localized("Moneda"),Currency.entries.map { it.name to it.name },old?.targetCurrency?.name ?: "CRC")
   command = {
    F06Command.SavePlan(draft.request(), PeriodPlan(month,(old?.version ?: 0)+1,old?.rows.orEmpty(),
     f.amount("target",localized("monto de la meta"),allowZero=true),Currency.valueOf(currency),
     localized("Actualización de Meta Sobrante"),today()))
   }
  }
  "f06budget" -> {
   BudgetEditor(f,month,snapshot,ledger,draft,onSubmit,onError)
  }
  "f06reminders" -> ReminderEditor(f,snapshot,onSubmit,onError)
 }
 command?.let { build -> Action(localized("Guardar"),!busy) { try { onSubmit(build()) } catch(e:Exception) { onError(userError(e)) } } }
 Action(localized("Cancelar"),!busy, onClick = onCancel)
}

@Composable private fun BudgetEditor(f:Fields,month:String,snapshot:F06Snapshot,ledger:F05Snapshot,draft:Drafts,onSubmit:(F06Command)->Unit,onError:(String)->Unit) {
 val old=snapshot.plans.filter { it.month==month }.maxByOrNull { it.version }
 Text(localized("Versión inicial conservada; guardar crea una revisión para {0}. Las necesidades y el gasto libre tienen sobres separados por categoría y moneda.", month))
 f.calendar("effective",localized("Fecha efectiva de la versión"),old?.effectiveDate ?: "$month-01")
 f.text("reason",localized("Motivo de la versión"))
 val count=f.value("count",(old?.rows?.size ?: 1).coerceAtLeast(1).toString()).toIntOrNull() ?: 1
 val rows=mutableListOf<()->PlanRow>()
 repeat(count) { index ->
  val row=old?.rows?.getOrNull(index); Text(localized("Línea {0}", index+1),style=MaterialTheme.typography.titleMedium)
  f.category("name$index",localized("Categoría {0}", index+1),ledger.categories,row?.name.orEmpty())
  f.text("group$index",localized("Grupo visual {0}", index+1),row?.group ?: "Variables")
  val nature=f.choice("nature$index",localized("Tipo financiero {0}", index+1),listOf(PlanNature.VARIABLE_NEED,PlanNature.FREE,PlanNature.INCOME,PlanNature.FIXED,PlanNature.SUBSCRIPTION,PlanNature.SAVING,PlanNature.DEBT).map { it.name to natureName(it) },row?.nature?.name ?: "VARIABLE_NEED")
  f.text("amount$index",localized("Presupuesto {0}", index+1),row?.minor?.let(::decimal).orEmpty(),true)
  val currency=f.choice("currency$index",localized("Moneda {0}", index+1),Currency.entries.map { it.name to it.name },row?.currency?.name ?: "CRC")
  val account=f.account("account$index",localized("Cuenta sugerida {0}", index+1),ledger.accounts.filter { !it.archived && it.currency.name==currency },row?.suggestedAccountId.orEmpty())
  val accumulate=f.check("accumulate$index",localized("Acumular sobrante positivo de esta categoría"),row?.accumulate ?: false)
  rows.add { PlanRow(row?.id ?: "line-$index",f.required("name$index",localized("categoría")),f.required("group$index",localized("grupo"),"Variables"),PlanNature.valueOf(nature),f.amount("amount$index",localized("presupuesto"),allowZero=true),Currency.valueOf(currency),account.ifBlank { null },accumulate) }
 }
 Action(localized("Añadir categoría")) { draft.set("count",(count+1).toString()) }
 if(count>1) Action(localized("Quitar última categoría")) { draft.set("count",(count-1).toString()) }
 Action(localized("Guardar presupuesto"),!f.busy) { try { onSubmit(F06Command.SavePlan(draft.request(),PeriodPlan(month,(old?.version ?: 0)+1,rows.map { it() },old?.targetMinor ?: 0,old?.targetCurrency ?: Currency.CRC,f.required("reason",localized("motivo")),f.date("effective","$month-01")))) } catch(e:Exception) { onError(userError(e)) } }
}

@Composable private fun ReminderEditor(f:Fields,snapshot:F06Snapshot,onSubmit:(F06Command)->Unit,onError:(String)->Unit) {
 val context=LocalContext.current
 val reminders=remember { LocalReminders(context) }
 var permission by remember { mutableStateOf(reminders.allowed()) }
 var testStatus by remember { mutableStateOf("") }
 val owner=androidx.lifecycle.compose.LocalLifecycleOwner.current
 DisposableEffect(owner) {
  val observer=androidx.lifecycle.LifecycleEventObserver { _, event -> if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME) { permission=reminders.allowed(); reminders.reconcile() } }
  owner.lifecycle.addObserver(observer)
  onDispose { owner.lifecycle.removeObserver(observer) }
 }
 val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission=reminders.allowed(); reminders.reconcile() }
 Text(if(permission) localized("Permiso del sistema: disponible") else localized("Permiso del sistema: denegado o desactivado. El calendario y los pagos siguen disponibles."))
 val enabled=f.check("enabled",localized("Activar recordatorios locales"),snapshot.reminders.enabled)
 val leadsDefault = snapshot.reminders.leads.joinToString(", ")
 f.text("days",localized("Días de anticipación (0–31, separados por coma)"),leadsDefault)
 Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
  listOf(7 to localized("Una semana"), 1 to localized("Un día"), 0 to localized("Mismo día")).forEach { (days, label) ->
   val current = f.value("days", leadsDefault).split(",").map { it.trim() }.filter { it.isNotBlank() }
   FilterChip(days.toString() in current, onClick = {
    f.draft.set("days", (if (days.toString() in current) current - days.toString() else current + days.toString()).joinToString(", "))
   }, label = { Text(label) }, modifier = Modifier.weight(1f))
  }
 }
 f.text("hour",localized("Hora local (0–23)"),snapshot.reminders.hour.toString(),true)
 Text(localized("America/Costa_Rica. Android puede retrasar la entrega para ahorrar batería; no se garantiza la hora exacta."))
 if(Build.VERSION.SDK_INT>=33) Action(localized("Solicitar permiso de notificaciones")) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
 Action(localized("Abrir ajustes de notificaciones del sistema")) { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName)) }
 Action(localized("Enviar notificación de prueba")) { permission=reminders.allowed(); testStatus=if(reminders.test()) localized("Notificación de prueba enviada al sistema.") else localized("No se envió: permiso denegado o notificaciones desactivadas.") }
 if(testStatus.isNotEmpty()) Text(testStatus)
 Action(localized("Guardar preferencias")) { try { val days=f.value("days",leadsDefault).split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }; val hour=f.required("hour",localized("hora"),"9").toInt(); require(days.all { it in 0..31 } && days.distinct().size == days.size && hour in 0..23) { localized("Revisa anticipación y hora.") }; onSubmit(F06Command.SetReminders(f.draft.request(),ReminderPreference(enabled,days.firstOrNull() ?: -1,hour,days.drop(1)))) } catch(e:Exception) { onError(userError(e)) } }
}




