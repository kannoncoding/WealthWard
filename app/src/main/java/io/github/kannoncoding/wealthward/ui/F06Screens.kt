package io.github.kannoncoding.wealthward.ui

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
 PlanNature.INCOME -> "Ingresos"; PlanNature.FIXED -> "Gastos fijos"; PlanNature.SUBSCRIPTION -> "Suscripciones / apps"; PlanNature.VARIABLE_NEED -> "Necesidades variables"; PlanNature.FREE -> "Gasto libre"; PlanNature.SAVING -> "Ahorro financiado"; PlanNature.DEBT -> "Deudas"; PlanNature.TARGET -> "Meta T"
}
internal fun f06Title(action:String) = mapOf("f06salary" to "Configurar ingreso neto", "f06extra" to "Ingreso extraordinario esperado", "f06template" to "Obligación recurrente", "f06budget" to "Presupuesto por periodo", "f06pay" to "Confirmar pago o ingreso real", "f06confirm" to "Confirmar importe real", "f06fund" to "Financiar reserva anual", "f06purpose" to "Aportar a reserva anual", "f06financing" to "Periodo de financiación", "f06copy" to "Preparar otro periodo", "f06reminders" to "Recordatorios locales", "f06link" to "Vincular movimiento existente")[action] ?: action

@Composable internal fun PlanningDashboard(month:String, changeMonth:(String)->Unit, plan:F06Snapshot, ledger:F05Snapshot, open:(String,String)->Unit, execute:(F06Command)->Unit) {
 var monthDraft by androidx.compose.runtime.saveable.rememberSaveable(month) { mutableStateOf(month) }
 var monthError by remember { mutableStateOf("") }
 OutlinedTextField(monthDraft, { monthDraft=it; monthError="" }, label={ Text("Periodo · AAAA-MM") })
 Action("Ver periodo") { runCatching { YearMonth.parse(monthDraft).toString() }.onSuccess { changeMonth(it) }.onFailure { monthError="Mes inválido. Usa AAAA-MM." } }
 if(monthError.isNotEmpty()) Text(monthError,color=MaterialTheme.colorScheme.error)
 Text("Periodo mostrado: $month")
 Action("Abrir / sincronizar periodo") { execute(F06Command.OpenPeriod(newId(),month)) }
 Action("Editar presupuesto y meta T") { open("f06budget","") }
 Action("Preparar / copiar periodo") { open("f06copy","") }
 Text("Presupuesto / Real / Diferencia", style=MaterialTheme.typography.titleLarge)
 Text("Diferencia = presupuesto menos real. Un valor negativo indica exceso. Las reservas financiadas se muestran separadas del gasto; transferencias propias no son gasto.")
 plan.comparisons.forEach { row ->
  val usage = when(row.nature) {
   PlanNature.SAVING -> "Aporte financiado"
   PlanNature.DEBT -> "Aplicado a deuda"
   else -> "Consumo económico"
  }
  InfoCard("${row.group} · ${natureName(row.nature)} · ${row.currency}", "Presupuesto ${money(row.budgetMinor,row.currency)}\nReal pagado ${money(row.actualMinor,row.currency)}\n$usage ${money(row.consumptionMinor,row.currency)}\nDiferencia de caja ${money(row.differenceMinor,row.currency)}", 2)
  if(row.nature in setOf(PlanNature.VARIABLE_NEED,PlanNature.FREE)) {
   val remaining=BigDecimal.valueOf(row.budgetMinor,2).subtract(BigDecimal.valueOf(row.consumptionMinor,2))
   Text("Restante por consumo: ${row.currency} ${remaining.toPlainString()}${if(remaining.signum()<0) " · Exceso de presupuesto" else ""}")
   Text("El consumo incluye compras a crédito pendientes. El restante por consumo indica lo que queda en el sobre; la diferencia de caja compara solo pagos reales.")
  }
 }
 if(plan.comparisons.isEmpty()) Text("Sin líneas para este periodo. Crea un presupuesto o una obligación recurrente.")
 plan.plans.filter { it.month==month }.maxByOrNull { it.version }?.let { version ->
  InfoCard("Versión ${version.version} · Meta T", if(version.targetMinor==0L) "Sin meta" else "Meta ${money(version.targetMinor,version.targetCurrency)}. Es un objetivo separado: no genera un gasto ni una reserva.", 1)
  if(version.targetMinor>0) {
   val base=plan.targetBaseMinor[version.targetCurrency] ?: 0L
   val percent=BigDecimal.valueOf(base).multiply(BigDecimal(100)).divide(BigDecimal.valueOf(version.targetMinor),2,java.math.RoundingMode.HALF_UP)
   Text("Base Real sin apertura: ${money(base,version.targetCurrency)}\nProgreso de la meta: ${percent.toPlainString()} %")
   Text("Base = ingresos y otros movimientos reales de caja menos pagos externos netos del periodo. Excluye apertura, transferencias propias, reservas y compras a crédito aún sin pagar. No es saldo bancario ni pronóstico.")
  }
 }
 plan.plans.filter { it.month==month }.sortedBy { it.version }.forEach { version ->
  var expanded by remember(version.month,version.version) { mutableStateOf(false) }
  Action("${if(version.version==1) "Presupuesto inicial" else "Revisión"} · versión ${version.version} · ver historial") { expanded=!expanded }
  if(expanded) {
   InfoCard("Versión ${version.version} · solo lectura", "Vigente ${version.effectiveDate}\nMotivo: ${version.reason}\nMeta T ${money(version.targetMinor,version.targetCurrency)}",0)
   version.rows.forEach { row -> Text("${row.group} · ${natureName(row.nature)} · ${row.name}: ${money(row.minor,row.currency)}${if(row.accumulate) " · Acumula sobrante" else " · Reinicia cada mes"}") }
  }
 }
 Text("Próximos y vencidos · calendario", style=MaterialTheme.typography.titleLarge)
 Text("Cada fecha conserva su obligación original. Los vencidos pendientes siguen visibles al cambiar de mes.")
 plan.occurrences.filter { it.nominalMonth==month || (it.nominalMonth<month && it.pendingMinor>0) }.sortedWith(compareBy({it.dueDate},{it.id})).forEach { occurrence ->
  val account=ledger.accounts.find { it.id==occurrence.suggestedAccountId }
  val termDebt = occurrence.templateId.startsWith("term-debt:")
  val applied = if(termDebt) "Aplicado a cuota" else "Consumo vinculado"
  InfoCard("${occurrence.dueDate} · ${occurrence.name}", "${natureName(occurrence.nature)} · ${occurrence.group}\nCuenta sugerida: ${account?.name ?: "Sin elegir"}\nEstimado ${money(occurrence.expectedMinor,occurrence.currency)}\nConfirmado ${occurrence.confirmedMinor?.let { money(it,occurrence.currency) } ?: "Por confirmar"}\nReal pagado ${money(occurrence.cashPaidMinor,occurrence.currency)}\n$applied ${money(occurrence.paidMinor,occurrence.currency)}\nTu pendiente ${money(occurrence.pendingMinor,occurrence.currency)} · ${if(occurrence.pendingMinor==0L && occurrence.paidMinor==0L) "Sin saldo pendiente" else if(occurrence.pendingMinor==0L) if(occurrence.nature==PlanNature.INCOME) "Recibido" else "Pagado" else if(occurrence.overdue) "Vencido" else if(occurrence.paidMinor>0) "Parcial" else "Pendiente"}", if(occurrence.overdue) 1 else 3)
  if(occurrence.pendingMinor>0) {
   if(termDebt) {
    Action("Pagar cuota · ${occurrence.name}") { open("f07installment", "${occurrence.nominalMonth}/${occurrence.templateId.removePrefix("term-debt:")}") }
   } else {
   Action(if(occurrence.nature==PlanNature.INCOME) "Registrar recepción · ${occurrence.name}" else "Pagar · ${occurrence.name}") { open("f06pay",occurrence.id) }
   Action("Confirmar importe · ${occurrence.name}") { open("f06confirm",occurrence.id) }
   Action("Vincular movimiento · ${occurrence.name}") { open("f06link",occurrence.id) }
  Action("Cambiar periodo de financiación · ${occurrence.name}") { open("f06financing",occurrence.id) }
   if(occurrence.purposeId!=null) Action("Financiar reserva · ${occurrence.name}") { open("f06fund",occurrence.id) }
   }
  }
 }
 if(plan.occurrences.isEmpty()) Text("Sin vencimientos generados. Abre el periodo después de configurar ingresos y obligaciones.")
}

@Composable internal fun PlanningConfiguration(plan:F06Snapshot, open:(String,String)->Unit) {
 Action("Ingresos · salario neto y extraordinarios") { open("f06salary","") }
 Action("Gastos fijos · configurar") { open("f06template","FIXED") }
 Action("Suscripciones / apps · configurar") { open("f06template","SUBSCRIPTION") }
 Action("Deudas · cuentas de crédito") { open("payment","") }
 Action("Ahorros · propósitos y reservas") { open("reserve","") }
 Action("Cuentas · crear producto") { open("account","") }
 Action("Ingreso extraordinario · configurar") { open("f06extra","") }
 Action("Recordatorios · preferencias y permiso") { open("f06reminders","") }
 plan.templates.groupBy { it.id }.values.forEach { versions -> val item=versions.maxBy { it.version }
  InfoCard(item.name, "${natureName(item.nature)} · ${money(item.minor,item.currency)}\nDesde ${item.effectiveMonth} · versión ${item.version} · ${if(item.cancelled) "Cancelada" else if(item.paused) "Pausada" else "Activa"}" + if(item.frequency==Recurrence.ANNUAL) "\nAporte mensual previsto ${decimal(item.monthlyContributionMinor)} ${item.currency}; financiar requiere dinero existente." else "", 0)
  Action("Revisar / pausar · ${item.name}") { open("f06template",item.id) }
  if(item.frequency==Recurrence.ANNUAL && item.purposeId!=null) Action("Aportar a reserva anual · ${item.name}") { open("f06purpose",item.id) }
  var showHistory by remember(item.id) { mutableStateOf(false) }
  Action("Historial de revisiones · ${item.name}") { showHistory=!showHistory }
  if(showHistory) versions.sortedBy { it.version }.forEach { revision ->
   InfoCard("Versión ${revision.version} · desde ${revision.effectiveMonth}", "${revision.name} · ${money(revision.minor,revision.currency)}\nGrupo ${revision.group} · ${natureName(revision.nature)}\nDías nominales ${revision.day}${if(revision.frequency==Recurrence.TWO_SLOTS) " y ${revision.secondDay}" else ""}\n${revision.frequency} · ${if(revision.cancelled) "Cancelada" else if(revision.paused) "Pausada" else "Activa"}\nMotivo: ${revision.reason}" + if(revision.householdTotalMinor!=null) "\nTotal hogar ${money(revision.householdTotalMinor,revision.currency)} · mi porcentaje ${revision.ownPercent ?: "Importe fijo"}" else "",4)
  }
 }
 plan.salaries.sortedBy { it.effectiveMonth }.forEach { Text("Salario desde ${it.effectiveMonth}: ${money(it.netMinor,it.currency)}\n${it.reason} · ${it.scheduleDay?.let { day -> "Día nominal $day" } ?: "Último lunes–viernes"}") }
}

@Composable internal fun F06Form(action:String, subject:String, month:String, snapshot:F06Snapshot, ledger:F05Snapshot, draft:Drafts, busy:Boolean, onError:(String)->Unit, onSubmit:(F06Command)->Unit, onCancel:()->Unit) {
 val f=Fields(draft,busy)
 val cash=ledger.accounts.filter { !it.archived && it.kind!="LIABILITY" }
 var command:(()->F06Command)?=null
 val occurrence=snapshot.occurrences.find { it.id==subject }
 when(action) {
  "f06salary" -> {
   Text("Salario neto esperado. Último lunes–viernes del mes civil, 09:00 America/Costa_Rica; no se calculan feriados. Solo una recepción confirmada modifica el saldo.")
   val latest=snapshot.salaries.withIndex().maxWithOrNull(compareBy({ it.value.effectiveMonth }, { it.index }))?.value
   f.text("effective","Vigente desde · AAAA-MM",month); f.text("amount","Salario neto",latest?.netMinor?.let(::decimal).orEmpty(),true)
   val currency=f.choice("currency","Moneda",Currency.entries.map { it.name to it.name },latest?.currency?.name ?: "CRC")
   val account=f.account("account","Cuenta de recepción sugerida",cash.filter { it.currency.name==currency },latest?.accountId.orEmpty())
   val schedule=f.choice("schedule","Fecha nominal",listOf("LAST" to "Último lunes–viernes","DAY" to "Día fijo del mes"),if(latest?.scheduleDay==null) "LAST" else "DAY")
   if(schedule=="DAY") f.text("day","Día nominal de recepción (1–31)",(latest?.scheduleDay ?: 1).toString(),true)
   f.text("reason","Motivo de la versión")
   command={ F06Command.SaveSalary(draft.request(),SalaryVersion(draft.get("entity").ifBlank { newId().also { draft.set("entity",it) } },YearMonth.parse(f.required("effective","mes",month)).toString(),f.amount(label="salario neto"),Currency.valueOf(currency),account.ifBlank { null },f.required("reason","motivo"),if(schedule=="DAY") f.required("day","día","1").toInt() else null)) }
  }
  "f06extra" -> {
   f.text("name","Nombre del ingreso"); f.text("date","Fecha esperada",today()); f.text("amount","Importe esperado",numeric=true)
   val currency=f.choice("currency","Moneda",Currency.entries.map { it.name to it.name })
   val account=f.account("account","Cuenta de recepción sugerida",cash.filter { it.currency.name==currency })
   Text("Este registro es una expectativa. Confirma la recepción en el calendario cuando recibas el dinero.")
   command={ val date=f.date(); F06Command.ExtraordinaryIncome(draft.request(),draft.get("entity").ifBlank { newId().also { draft.set("entity",it) } },f.required("name","nombre"),date.take(7),date,f.amount(),Currency.valueOf(currency),account.ifBlank { null }) }
  }
  "f06template" -> {
   val old=snapshot.templates.filter { it.id==subject }.maxByOrNull { it.version }
   f.text("name","Nombre de la obligación",old?.name.orEmpty()); f.text("group","Grupo visual personalizado",old?.group ?: "Hogar")
   val nature=f.choice("nature","Tipo financiero",listOf(PlanNature.FIXED,PlanNature.SUBSCRIPTION).map { it.name to natureName(it) },old?.nature?.name ?: subject.takeIf { it in listOf("FIXED","SUBSCRIPTION") }.orEmpty())
   f.text("effective","Vigente desde · AAAA-MM",month)
   f.text("amount","Importe propio estimado",old?.minor?.let(::decimal).orEmpty(),true)
   val currency=f.choice("currency","Moneda",Currency.entries.map { it.name to it.name },old?.currency?.name ?: "CRC")
   val recurrence=f.choice("recurrence","Frecuencia",listOf("MONTHLY" to "Mensual","ANNUAL" to "Anual","EVERY_N_MONTHS" to "Cada N meses","TWO_SLOTS" to "Dos fechas fijas cada mes"),old?.frequency?.name ?: "MONTHLY")
   f.text("day","Día nominal (1–31)",(old?.day ?: 1).toString(),true)
   if(recurrence=="TWO_SLOTS") f.text("second","Segunda fecha fija (1–31)",(old?.secondDay ?: 15).toString(),true)
   if(recurrence=="EVERY_N_MONTHS") f.text("interval","Intervalo en meses",(old?.intervalMonths ?: 1).toString(),true)
   Text("Un día que no existe se ajusta al último día del mes; el siguiente mes conserva el día nominal. Dos fechas fijas no equivalen a cada 15 días.")
   val account=f.account("account","Cuenta de pago sugerida",cash.filter { it.currency.name==currency },old?.suggestedAccountId.orEmpty())
   val purposes=ledger.accounts.filter { it.currency.name==currency }.flatMap { it.reserves }.distinctBy { it.purposeId }
   val purpose=if(recurrence=="ANNUAL") f.choice("purpose","Reserva anual existente",listOf("" to "Sin reserva vinculada")+purposes.map { it.purposeId to it.name },old?.purposeId.orEmpty()) else ""
   if(recurrence=="ANNUAL") Text("Crea y financia un propósito desde Ahorros para vincularlo aquí. La contribución prevista no crea fondos.")
   if(recurrence=="ANNUAL") f.text("contribution","Aporte mensual previsto",decimal(old?.monthlyContributionMinor ?: 0),true)
   val household=f.check("household","Es un gasto compartido del hogar",old?.householdTotalMinor!=null)
   var share="FIXED"
   if(household) { f.text("total","Total del hogar",old?.householdTotalMinor?.let(::decimal).orEmpty(),true); share=f.choice("share","Cómo calcular mi parte",listOf("FIXED" to "Importe fijo propio","PERCENT" to "Porcentaje propio"),if(old?.ownPercent!=null) "PERCENT" else "FIXED"); if(share=="PERCENT") f.text("percent","Mi porcentaje (0–100)",old?.ownPercent ?: "50",true) else f.text("own","Mi importe fijo",old?.ownFixedMinor?.let(::decimal).orEmpty(),true); Text("Solo tu parte se presupuesta y paga. Porcentajes se redondean HALF_UP al centavo.") }
   val paused=f.check("paused","Pausar desde la vigencia elegida",old?.paused ?: false)
   val cancelled=f.check("cancelled","Cancelar desde la vigencia elegida",old?.cancelled ?: false)
   f.text("reason","Motivo de esta revisión")
   command={
    f.required("reason","motivo")
    val total=if(household) f.amount("total","total hogar") else null
    val percent=if(household && share=="PERCENT") f.required("percent","porcentaje") else null
    val fixed=if(household && share=="FIXED") f.amount("own","parte propia",allowZero=true) else null
    val own=if(total!=null) PlanningMath.ownShare(total,percent,fixed) else f.amount()
    F06Command.SaveTemplate(draft.request(),RecurringTemplate(old?.id ?: draft.get("entity").ifBlank { newId().also { draft.set("entity",it) } },(old?.version ?: 0)+1,f.required("name","nombre"),f.required("group","grupo","Hogar"),PlanNature.valueOf(nature),own,Currency.valueOf(currency),YearMonth.parse(f.required("effective","vigencia",month)).toString(),Recurrence.valueOf(recurrence),f.required("day","día","1").toInt(),f.value("second","15").toInt(),f.value("interval","1").toInt(),account.ifBlank { null },purpose.ifBlank { null },paused,cancelled,total,percent,fixed,if(recurrence=="ANNUAL") f.amount("contribution","aporte mensual",allowZero=true) else 0,f.required("reason","motivo")))
   }
  }
  "f06pay", "f06confirm", "f06fund", "f06link" -> {
   if(occurrence==null) { Text("Vuelve al calendario y sincroniza el periodo para elegir la obligación."); return }
   InfoCard(occurrence.name,"Fecha ${occurrence.dueDate}\nTu pendiente ${money(occurrence.pendingMinor,occurrence.currency)}",2)
   when(action) {
    "f06confirm" -> { f.text("amount","Importe real total",decimal(occurrence.confirmedMinor ?: occurrence.expectedMinor),true); Text("Confirma el total real, conservando el estimado y los pagos ya registrados."); command={ F06Command.ConfirmAmount(draft.request(),subject,f.amount(allowZero=true)) } }
    "f06link" -> {
     val operations=ledger.operations.filter { !it.voided && it.type in (if(occurrence.nature==PlanNature.INCOME) setOf("INCOME") else setOf("EXPENSE","CREDIT_PURCHASE")) && ledger.accounts.any { account -> account.id==it.accountId && account.currency==occurrence.currency } }
     val operation=f.choice("operation","Movimiento ya registrado",operations.map { it.id to "${it.date} · ${it.merchant} · ${decimal(it.amountMinor)}" })
     Text("Vincular reconoce un movimiento existente; no registra otro pago.")
     command={ F06Command.LinkPayment(draft.request(),subject,operation) }
    }
    else -> {
     val account=f.account("account",if(action=="f06fund") "Cuenta de financiación" else "Cuenta del movimiento real",cash.filter { it.currency==occurrence.currency },occurrence.suggestedAccountId.orEmpty())
     f.text("amount",if(action=="f06fund") "Importe a reservar" else "Importe real recibido o pagado",decimal(occurrence.pendingMinor),true)
     if(action!="f06fund") f.text("date","Fecha real del movimiento",today())
     val confirmed=f.check("confirmed",if(action=="f06fund") "Confirmo asignar dinero existente a la reserva" else "Confirmo que este movimiento realmente ocurrió")
     Text(if(action=="f06fund") "Financiar reserva protege dinero existente y no paga la obligación. Puedes liberarlo desde Metas." else "Puedes registrar una parte. El pendiente se reduce con este único movimiento vinculado.")
     command={ require(confirmed) { "Confirma el movimiento real antes de guardar." }; if(action=="f06fund") F06Command.FundAnnual(draft.request(),subject,account,f.amount()) else F06Command.Pay(draft.request(),subject,account,f.amount(),f.date()) }
    }
   }
  }
  "f06financing" -> {
   f.text("month","Periodo que financia esta obligación",occurrence?.financingMonth ?: month)
   Text("Cambia el periodo de planificación, conservando la fecha de vencimiento y los pagos originales.")
   command={ F06Command.AssignFundingPeriod(draft.request(),subject,YearMonth.parse(f.required("month","periodo",month)).toString()) }
  }
  "f06purpose" -> {
   val template=snapshot.templates.filter { it.id==subject }.maxByOrNull { it.version }
   val account=f.account("account","Cuenta de financiación",cash.filter { it.currency==template?.currency },template?.suggestedAccountId.orEmpty())
   f.text("amount","Aporte real a reserva",template?.monthlyContributionMinor?.let(::decimal).orEmpty(),true)
   val confirmed=f.check("confirmed","Confirmo asignar dinero existente a la reserva")
   command={ require(confirmed) { "Confirma la asignación de dinero existente." }; F06Command.FundPurpose(draft.request(),subject,account,f.amount()) }
  }
  "f06copy" -> {
   f.text("source","Periodo de origen",month); f.text("target","Periodo a preparar",runCatching { YearMonth.parse(month).plusMonths(1).toString() }.getOrDefault(month))
   Text("Copia constantes, meta y cuentas sugeridas. No copia pagos, saldos ni reservas financiadas; regenerar el mismo periodo no duplica obligaciones.")
   command={ F06Command.CopyPeriod(draft.request(),YearMonth.parse(f.required("source","origen",month)).toString(),YearMonth.parse(f.required("target","destino")).toString()) }
  }
  "f06budget" -> {
   BudgetEditor(f,month,snapshot,ledger,draft,onSubmit,onError)
  }
  "f06reminders" -> ReminderEditor(f,snapshot,onSubmit,onError)
 }
 command?.let { build -> Action("Guardar",!busy) { try { onSubmit(build()) } catch(e:Exception) { onError(userError(e)) } } }
 Action("Cancelar · conservar borrador",!busy,onCancel)
}

@Composable private fun BudgetEditor(f:Fields,month:String,snapshot:F06Snapshot,ledger:F05Snapshot,draft:Drafts,onSubmit:(F06Command)->Unit,onError:(String)->Unit) {
 val old=snapshot.plans.filter { it.month==month }.maxByOrNull { it.version }
 Text("Versión inicial conservada; guardar crea una revisión para $month. Las necesidades y el gasto libre tienen sobres separados por categoría y moneda.")
 f.text("targetT","Meta T (objetivo, no gasto)",decimal(old?.targetMinor ?: 0),true)
 val targetCurrency=f.choice("targetCurrency","Moneda de la meta T",Currency.entries.map { it.name to it.name },old?.targetCurrency?.name ?: "CRC")
 f.text("effective","Fecha efectiva de la versión",old?.effectiveDate ?: "$month-01")
 f.text("reason","Motivo de la versión")
 val count=f.value("count",(old?.rows?.size ?: 1).coerceAtLeast(1).toString()).toIntOrNull() ?: 1
 val rows=mutableListOf<()->PlanRow>()
 repeat(count) { index ->
  val row=old?.rows?.getOrNull(index); Text("Línea ${index+1}",style=MaterialTheme.typography.titleMedium)
  f.text("name$index","Categoría ${index+1}",row?.name ?: "")
  f.text("group$index","Grupo visual ${index+1}",row?.group ?: "Variables")
  val nature=f.choice("nature$index","Tipo financiero ${index+1}",listOf(PlanNature.VARIABLE_NEED,PlanNature.FREE,PlanNature.INCOME,PlanNature.FIXED,PlanNature.SUBSCRIPTION,PlanNature.SAVING,PlanNature.DEBT).map { it.name to natureName(it) },row?.nature?.name ?: "VARIABLE_NEED")
  f.text("amount$index","Presupuesto ${index+1}",row?.minor?.let(::decimal).orEmpty(),true)
  val currency=f.choice("currency$index","Moneda ${index+1}",Currency.entries.map { it.name to it.name },row?.currency?.name ?: "CRC")
  val account=f.account("account$index","Cuenta sugerida ${index+1}",ledger.accounts.filter { !it.archived && it.currency.name==currency },row?.suggestedAccountId.orEmpty())
  val accumulate=f.check("accumulate$index","Acumular sobrante positivo de esta categoría",row?.accumulate ?: false)
  rows.add { PlanRow(row?.id ?: "line-$index",f.required("name$index","categoría"),f.required("group$index","grupo","Variables"),PlanNature.valueOf(nature),f.amount("amount$index","presupuesto",allowZero=true),Currency.valueOf(currency),account.ifBlank { null },accumulate) }
 }
 Action("Añadir categoría") { draft.set("count",(count+1).toString()) }
 if(count>1) Action("Quitar última categoría") { draft.set("count",(count-1).toString()) }
 Action("Guardar presupuesto",!f.busy) { try { onSubmit(F06Command.SavePlan(draft.request(),PeriodPlan(month,(old?.version ?: 0)+1,rows.map { it() },f.amount("targetT","meta T",allowZero=true),Currency.valueOf(targetCurrency),f.required("reason","motivo"),f.date("effective","$month-01")))) } catch(e:Exception) { onError(userError(e)) } }
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
 Text(if(permission) "Permiso del sistema: disponible" else "Permiso del sistema: denegado o desactivado. El calendario y los pagos siguen disponibles.")
 val enabled=f.check("enabled","Activar recordatorios locales",snapshot.reminders.enabled)
 f.text("days","Días de anticipación (0–31)",snapshot.reminders.daysBefore.toString(),true)
 f.text("hour","Hora local (0–23)",snapshot.reminders.hour.toString(),true)
 Text("America/Costa_Rica. Android puede retrasar la entrega para ahorrar batería; no se garantiza la hora exacta.")
 if(Build.VERSION.SDK_INT>=33) Action("Solicitar permiso de notificaciones") { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
 Action("Abrir ajustes de notificaciones del sistema") { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName)) }
 Action("Enviar notificación de prueba") { permission=reminders.allowed(); testStatus=if(reminders.test()) "Notificación de prueba enviada al sistema." else "No se envió: permiso denegado o notificaciones desactivadas." }
 if(testStatus.isNotEmpty()) Text(testStatus)
 Action("Guardar preferencias") { try { val days=f.required("days","anticipación", "1").toInt(); val hour=f.required("hour","hora","9").toInt(); require(days in 0..31 && hour in 0..23) { "Revisa anticipación y hora." }; onSubmit(F06Command.SetReminders(f.draft.request(),ReminderPreference(enabled,days,hour))) } catch(e:Exception) { onError(userError(e)) } }
}




