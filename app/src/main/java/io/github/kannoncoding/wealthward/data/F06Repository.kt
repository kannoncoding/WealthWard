package io.github.kannoncoding.wealthward.data

import androidx.room.withTransaction
import io.github.kannoncoding.wealthward.domain.*
import kotlinx.coroutines.flow.map
import org.json.*
import java.time.*

/** Planning shares the ledger transaction and workspace; expected events never create postings. */
class F06Repository(private val db:WealthDatabase) {
 private val dao=db.ledger()
 private val ledger=F05Repository(db)
 val observe=dao.observeWorkspace().map { snapshot() }
 suspend fun snapshot(month:String=YearMonth.now(BusinessTime.zone).toString()):F06Snapshot=db.withTransaction {
  val state=load(); val p=planning(state); val ops=ledger.snapshot().operations
  val occurrences=p.rows("occurrences").filter { !it.optBoolean("suppressed") }.map { occurrence(p,it,ops) } + commitmentInstallments(state,month)
  val plans=p.rows("plans").map(::plan)
  val latest=plans.filter { it.month==month }.maxByOrNull { it.version }
  val planGroups=latest?.rows?.groupBy { Triple(it.group,it.nature,it.currency) } ?: emptyMap()
  val occurrenceGroups=occurrences.filter { it.financingMonth==month }.groupBy { Triple(it.group,it.nature,it.currency) }
  val actualGroups=occurrences.filter { cashActual(it.paymentIds,ops,month)!=0L }.map { Triple(it.group,it.nature,it.currency) }.toSet()
  val externalSavingIds=state.commitments().f07Rows("savingEvents").filter { it.getString("action")==SavingAction.EXTERNAL.name }.mapNotNull { it.f07Text("operationId") }.toSet()
  val savings=commitmentSavings(state,month)
  val external=commitmentExternalSpending(state,month)
  val extras=state.commitments().f07Rows("payments").filter { !it.optBoolean("voided") }.map { it.debtPayment() }
      .filter { it.extra && it.date.startsWith(month) }.groupBy { payment -> state.commitments().f07Row("debts",payment.debtId).debt().let { Triple(it.group,PlanNature.DEBT,it.currency) } }
      .mapValues { (_,payments) -> checkedSum(payments.map { it.totalMinor }) }
  val projects=state.commitments()
  val projectGroups=projects.f07Rows("projectPayments").groupBy { payment ->
   val item=projects.f07Row("items",payment.getString("itemId")).projectItem()
   projects.f07Row("projects",item.projectId).project().let { Triple(it.group,PlanNature.FIXED,it.currency) }
  }.mapValues { (_,links) -> links.map { it.getString("operationId") } }
  val classifiedProjects=projectGroups.values.flatten()
  val comparison=(planGroups.keys+occurrenceGroups.keys+actualGroups+savings.keys+external.keys+extras.keys+projectGroups.keys).map { key ->
   val rows=planGroups[key] ?: emptyList()
   val categoryMatches = budgetCategoryMatcher(rows, latest?.rows.orEmpty(), key.third)
   val plannedSavings=savings[key]
   var budget=when {
    plannedSavings!=null -> plannedSavings.first
    rows.isEmpty() || (key.second==PlanNature.DEBT && key in occurrenceGroups) -> checkedSum(occurrenceGroups[key].orEmpty().map { it.expectedMinor })
    else -> checkedSum(rows.map { it.minor })
   }
   if(rows.any { it.accumulate }) {
    fun unused(previous:String,depth:Int):Long {
     if(depth>1200) fail("Accumulation history exceeds supported range")
     val old=plans.filter { it.month==previous }.maxByOrNull { it.version } ?: return 0
     val oldRows=old.rows.filter { Triple(it.group,it.nature,it.currency)==key && it.accumulate }
     if(oldRows.isEmpty()) return 0
     val carry=unused(YearMonth.parse(previous).minusMonths(1).toString(),depth+1)
     return maxOf(0,Math.subtractExact(Math.addExact(checkedSum(oldRows.map { it.minor }),carry),variableActual(ops.filter { it.id !in externalSavingIds },occurrences,previous,key,budgetCategoryMatcher(oldRows,old.rows,key.third),classifiedProjects=classifiedProjects)))
    }
    budget=Math.addExact(budget,unused(YearMonth.parse(month).minusMonths(1).toString(),0))
   }
   val actual=if(plannedSavings!=null) 0L else if(key.second in setOf(PlanNature.VARIABLE_NEED,PlanNature.FREE)) variableActual(ops.filter { it.id !in externalSavingIds },occurrences,month,key,categoryMatches,cashOnly=true,classifiedProjects=classifiedProjects) else checkedSum(occurrences.filter { Triple(it.group,it.nature,it.currency)==key }.map { ownShareOfPayment(cashActual(it.paymentIds,ops,month,key.third), it.confirmedMinor ?: it.expectedMinor, it.paymentTotalMinor) })
   val consumed=if(plannedSavings!=null) plannedSavings.second else if(key.second in setOf(PlanNature.VARIABLE_NEED,PlanNature.FREE)) variableActual(ops.filter { it.id !in externalSavingIds },occurrences,month,key,categoryMatches,classifiedProjects=classifiedProjects) else checkedSum(occurrenceGroups[key].orEmpty().map { it.paidMinor })
   val supplementaryActual=external[key] ?: extras[key] ?: 0L
   val projectPayments=projectGroups[key].orEmpty()
   val projectCash=cashActual(projectPayments,ops,month,key.third)
   val projectSpending=checkedSum(ops.filter { !it.voided && (it.bookedDate ?: it.date).startsWith(month) }.map {
    if(it.id in projectPayments || (it.type=="REFUND" && it.originalId in projectPayments)) it.expenseMinor else 0L
   })
   val cashTotal=checkedSum(listOf(actual,supplementaryActual,projectCash))
   BudgetComparison(key.first,key.second,key.third,budget,cashTotal,Math.subtractExact(budget,cashTotal),
    checkedSum(listOf(consumed,supplementaryActual,projectSpending)))
  }
  val eligible=ops.filter { it.type in setOf("INCOME","EXPENSE","REFUND","CREDIT_PAYMENT","FX_CREDIT_PAYMENT","DEBT_PAYMENT") }.map { it.id }.toSet()
  val feeParents=ops.filter { it.feeMinor>0 }.map { it.id }.toSet()
  val reversals=ops.filter { it.type=="REVERSAL" && (it.originalId in eligible || it.originalId in feeParents) }.map { it.id }.toSet()
  val tx=dao.transactions().filter { it.confirmed && it.businessDate.startsWith(month) && (it.id in eligible || it.id in reversals) }.map { it.id }.toSet()
  val cash=dao.accounts().filter { it.kind=="CASH" }.map { it.id }.toSet()
  val post=dao.postings()
  val accountedFeeReversals=mutableSetOf<String>()
  val targetBase=post.filter { it.transactionId in tx && it.accountId in cash }.groupBy { Currency.valueOf(it.currency) }.mapValues { entry ->
   checkedSum(entry.value.map { posting ->
    val reversal=ops.find { it.id==posting.transactionId && it.type=="REVERSAL" }
    val original=ops.find { it.id==reversal?.originalId }
    if(original?.type in setOf("TRANSFER","FX_TRANSFER")) {
     if(posting.accountId==original?.accountId && accountedFeeReversals.add(posting.transactionId)) (original?.feeMinor ?: 0L) else 0L
    } else posting.minor
   })
  }
  val rem=p.getJSONObject("reminders")
  F06Snapshot(plans,p.rows("templates").map(::template),p.rows("salaries").map(::salary),occurrences,comparison,ReminderPreference(rem.getBoolean("enabled"),rem.getInt("daysBefore"),rem.getInt("hour"), reminderExtras(rem)),targetBase,p.readExtraIncomes())
 }
 private fun descendants(ids:List<String>,ops:List<OperationDetail>):Set<String> {
  val result=ids.toMutableSet(); var changed=true
  while(changed) { changed=false; ops.filter { it.originalId in result && it.type in setOf("EXPENSE","INCOME","CREDIT_PURCHASE") }.forEach { if(result.add(it.id)) changed=true } }
  return result
 }
 private fun cashActual(ids:List<String>,ops:List<OperationDetail>,month:String?,currency:Currency?=null):Long {
  val owned=descendants(ids,ops)
  return checkedSum(ops.filter { !it.voided && (month==null || (it.bookedDate ?: it.date).startsWith(month)) && (currency==null || currencyCache[it.accountId]==currency.name) }.map { op ->
   when {
    op.id in owned && (op.type in setOf("EXPENSE","INCOME","DEBT_PAYMENT") || op.type == "CREDIT_PURCHASE" && op.previouslyDeducted) -> op.amountMinor
    op.type=="REFUND" && op.originalId in owned && ops.find { it.id==op.originalId }?.type=="EXPENSE" -> -op.amountMinor
    op.type in setOf("CREDIT_PAYMENT","FX_CREDIT_PAYMENT") -> checkedSum(op.creditAllocations.filter { it.purchaseId in owned }.map { it.sourceMinor ?: it.minor })
    else -> 0
   }
  })
 }
 private fun variableActual(ops:List<OperationDetail>,occ:List<PlannedOccurrence>,month:String,key:Triple<String,PlanNature,Currency>,categoryMatches:(String)->Boolean,cashOnly:Boolean=false,classifiedProjects:List<String> = emptyList()):Long {
  val linked=descendants(occ.flatMap { it.paymentIds } + classifiedProjects,ops)
  fun amount(o:OperationDetail,minor:Long):Long {
   if(currencyCache[o.accountId]!=key.third.name || o.id in linked) return 0
   if(o.splits.isEmpty()) return if(categoryMatches(o.category)) minor else 0
   var cumulative=0L
   var previous=0L
   val total=java.math.BigInteger.valueOf(o.amountMinor)
   return checkedSum(o.splits.map { split ->
    cumulative=Math.addExact(cumulative,split.minor)
    val end=java.math.BigInteger.valueOf(cumulative).multiply(java.math.BigInteger.valueOf(minor)).divide(total).longValueExact()
    val part=end-previous; previous=end; if(categoryMatches(split.category)) part else 0
   })
  }
  return checkedSum(ops.filter { !it.voided && (it.bookedDate ?: it.date).startsWith(month) }.map { o ->
   when(o.type) {
    "EXPENSE" -> amount(o,o.amountMinor)
    "CREDIT_PURCHASE" -> if(cashOnly && !o.previouslyDeducted) 0 else amount(o,o.amountMinor)
    "REFUND" -> ops.find { it.id==o.originalId }?.let { original ->
     if(cashOnly && original.type=="CREDIT_PURCHASE") 0 else {
      val prior=checkedSum(ops.takeWhile { it.id!=o.id }.filter { it.type=="REFUND" && it.originalId==original.id && !it.voided }.map { it.amountMinor })
      -(amount(original,Math.addExact(prior,o.amountMinor))-amount(original,prior))
     }
    } ?: 0
    "CREDIT_PAYMENT","FX_CREDIT_PAYMENT" -> if(!cashOnly || currencyCache[o.accountId]!=key.third.name) 0 else checkedSum(o.creditAllocations.map { a ->
     val purchase=ops.find { it.id==a.purchaseId } ?: return@map 0L
     if(purchase.id in linked || currencyCache[purchase.accountId]!=key.third.name) 0 else {
      val prior=checkedSum(ops.takeWhile { it.id!=o.id }.filter { !it.voided && it.type in setOf("CREDIT_PAYMENT","FX_CREDIT_PAYMENT") }.flatMap { it.creditAllocations }.filter { it.purchaseId==purchase.id }.map { it.minor })
      amount(purchase,Math.addExact(prior,a.minor))-amount(purchase,prior)
     }
    })
    else -> 0
   }
  })
 }
 private val currencyCache=mutableMapOf<String,String>()
 private suspend fun load():JSONObject { currencyCache.clear(); dao.accounts().forEach { currencyCache[it.id]=it.currency }; return JSONObject(dao.workspaceState()?.payload ?: F05Repository.EMPTY_STATE) }
 private fun planning(state:JSONObject):JSONObject { if(!state.has("f06")) state.put("f06",empty()); return state.getJSONObject("f06") }
 suspend fun confirmSalary(occurrenceId: String, accountId: String, shownMinor: Long) = db.withTransaction {
  val p = planning(load())
  val salary = occurrence(p, findOccurrence(p, occurrenceId), ledger.snapshot().operations)
  val received = LocalDate.now(BusinessTime.zone)
  if (salary.templateId != "salary" || LocalDate.parse(salary.dueDate) > received) fail("Salary is not due")
  if (salary.paymentPendingMinor == 0L) return@withTransaction
  if (salary.suggestedAccountId != accountId || salary.paymentPendingMinor != shownMinor) fail("Salary details changed")
  execute(F06Command.Pay("salary-receipt:$occurrenceId:${salary.paymentIds.size}", occurrenceId, accountId, shownMinor, received.toString()))
  Unit
 }
 suspend fun execute(command:F06Command):String=db.withTransaction {
  if(command.requestId.isBlank()) fail("A stable request key is required")
  val fingerprint=canonicalPlanning(command).toString()
  dao.request(command.requestId)?.let { if(it.fingerprint!=fingerprint) fail("Request key already used for different content"); return@withTransaction it.result }
  var state=load(); var p=planning(state)
  when(val c=command) {
   is F06Command.SavePlan -> { validatePlan(c.plan); if(c.plan.version!=(p.rows("plans").filter { it.getString("month")==c.plan.month }.maxOfOrNull { it.getInt("version") } ?: 0)+1) fail("Plan versions must advance sequentially"); if(p.rows("plans").any { it.getString("month")==c.plan.month && it.getInt("version")==c.plan.version }) fail("Plan version already exists"); p.appendRow("plans",json(c.plan)) }
   is F06Command.SaveTemplate -> { validateTemplate(c.template); val history=p.rows("templates").filter { it.getString("id")==c.template.id }; if(history.any { it.getInt("version")==c.template.version } || (history.maxOfOrNull { it.getInt("version") } ?: 0)+1!=c.template.version) fail("Template versions must advance sequentially"); val anchored=c.template.copy(anchorMonth=history.firstOrNull()?.getString("anchorMonth") ?: c.template.anchorMonth); p.appendRow("templates",json(anchored)); p.rows("opened").map { it.getString("month") }.forEach { generate(p,it) } }
   is F06Command.SaveSalary -> {
    val salary = c.salary
    validateSalary(salary)
    account(salary.accountId,salary.currency)
    if (p.rows("salaries").any { it.getString("id") == salary.id }) fail("Salary revision identity must be unique")
    p.appendRow("salaries",json(salary))
    p.rows("opened").forEach { generate(p,it.getString("month")) }
   }
   is F06Command.SaveExtraIncome -> {
    val income = c.income
    validateExtraIncome(income)
    account(income.accountId,income.currency)
    val previous = p.readExtraIncomes().filter { it.id == income.id }.maxByOrNull { it.version }
    if (income.version != (previous?.version ?: 0) + 1 ||
     previous != null && income.effectiveDate < previous.effectiveDate) fail("Income revisions must advance sequentially")
    val revisions = p.optJSONArray("extraIncomes") ?: JSONArray().also { p.put("extraIncomes",it) }
    revisions.put(json(income))
    val months = p.rows("opened").map { it.getString("month") } + income.effectiveDate.take(7)
    months.distinct().forEach { generate(p,it) }
   }
   is F06Command.OpenPeriod -> {
    val target = YearMonth.parse(c.month)
    val opened = p.rows("opened").map { YearMonth.parse(it.getString("month")) }.toSet()
    var next = opened.filter { it < target }.maxOrNull()?.plusMonths(1) ?: target
    while (next <= target) {
     if (next !in opened) p.appendRow("opened",obj("month" to next.toString()))
     generate(p,next.toString())
     next = next.plusMonths(1)
    }
   }
   is F06Command.CopyPeriod -> { YearMonth.parse(c.targetMonth); if(c.sourceMonth==c.targetMonth) fail("Choose a different period"); if(p.rows("plans").any { it.getString("month")==c.targetMonth }) fail("Target period already prepared"); val source=p.rows("plans").filter { it.getString("month")==c.sourceMonth }.maxByOrNull { it.getInt("version") } ?: fail("Unknown source period"); p.appendRow("plans",json(plan(source).copy(month=c.targetMonth,version=1,effectiveDate=c.targetMonth+"-01",reason="Copied from "+c.sourceMonth))); if(p.rows("opened").none { it.getString("month")==c.targetMonth }) p.appendRow("opened",obj("month" to c.targetMonth)); generate(p,c.targetMonth) }
   is F06Command.ConfirmAmount -> { if(c.minor<0) fail("Confirmed amount cannot be negative"); val o=findOccurrence(p,c.occurrenceId); val item=occurrence(p,o,ledger.snapshot().operations); if(c.minor<item.paymentTotalMinor-item.paymentPendingMinor) fail("Confirmed amount is below paid amount"); val t=occurrenceTemplate(p,o); if(t?.householdTotalMinor!=null) { o.put("confirmedGrossMinor",c.minor); o.put("confirmedMinor",PlanningMath.ownShare(c.minor,t.ownPercent,t.ownFixedMinor?.coerceAtMost(c.minor))) } else o.put("confirmedMinor",c.minor) }
   is F06Command.ConfirmPreviouslyDeducted -> {
    val row = findOccurrence(p,c.occurrenceId)
    val item = occurrence(p,row,ledger.snapshot().operations)
    if(item.nature !in setOf(PlanNature.FIXED,PlanNature.SUBSCRIPTION) || item.paymentPendingMinor <= 0)
     fail("No hay un pago pendiente que se pueda confirmar de esta forma")
    account(c.accountId,item.currency,allowCard = true)
    val date = LocalDate.parse(c.date)
    if(date > LocalDate.now(BusinessTime.zone)) fail("La fecha del pago no puede estar en el futuro")
    val confirmation = obj("accountId" to c.accountId, "date" to date.toString(), "minor" to item.paymentPendingMinor)
    if (row.isNull("previouslyDeducted")) row.put("previouslyDeducted", confirmation)
    else {
     val additional = row.optJSONArray("additionalPreviouslyDeducted") ?: JSONArray().also { row.put("additionalPreviouslyDeducted", it) }
     additional.put(confirmation)
    }
   }
   is F06Command.Pay -> {
    positive(c.minor); val o=findOccurrence(p,c.occurrenceId); val v=occurrence(p,o,ledger.snapshot().operations); if(c.minor>v.paymentPendingMinor) fail("Payment exceeds outstanding amount"); val canUseCard = v.nature in setOf(PlanNature.FIXED, PlanNature.SUBSCRIPTION)
    account(c.accountId,v.currency,canUseCard)
    val paymentAccount = ledger.snapshot().accounts.first { it.id == c.accountId }
    val operationId=c.requestId+":ledger"
    val category = occurrenceTemplate(p,o)?.category ?: v.name
    val splits=if(paymentAccount.kind=="CASH" && v.purposeId!=null && v.nature!=PlanNature.INCOME && paymentAccount.reserves.any { it.purposeId==v.purposeId }) listOf(ExpenseSplit(category,c.minor,v.purposeId)) else emptyList()
    ledger.execute(F05Command.Record(operationId,c.accountId,when { v.nature==PlanNature.INCOME -> "INCOME"; paymentAccount.kind=="LIABILITY" -> "CREDIT_PURCHASE"; else -> "EXPENSE" },c.minor,c.date,v.name,category,splits=splits))
    // F05 has saved the shared document: reload before adding the link, never overwrite it with stale JSON.
    state=load(); p=planning(state); findOccurrence(p,c.occurrenceId).getJSONArray("paymentIds").put(operationId)
   }
   is F06Command.LinkPayment -> { val o=findOccurrence(p,c.occurrenceId); val ops=ledger.snapshot().operations; val v=occurrence(p,o,ops); val op=ops.find { it.id==c.operationId } ?: fail("Unknown payment"); if(state.commitments().f07Rows("savingEvents").any { it.f07Text("operationId")==op.id } || state.commitments().f07Rows("projectPayments").any { it.getString("operationId")==op.id }) fail("This movement already belongs to a saving or project"); val a=dao.accounts().first { it.id==op.accountId }; if(op.voided || op.type !in (if(v.nature==PlanNature.INCOME) setOf("INCOME") else setOf("EXPENSE","CREDIT_PURCHASE")) || a.currency!=v.currency.name || descendants(p.rows("occurrences").flatMap { it.getJSONArray("paymentIds").strings() },ops).contains(op.id)) fail("Movement is not eligible or is already allocated"); if(net(op.id,ops)>v.paymentPendingMinor) fail("Movement exceeds pending amount"); o.getJSONArray("paymentIds").put(op.id) }
   is F06Command.ExtraordinaryIncome -> { YearMonth.parse(c.month); LocalDate.parse(c.dueDate); positive(c.expectedMinor); account(c.accountId,c.currency); val id="extra:"+c.id; if(p.rows("occurrences").any { it.getString("id")==id }) fail("Income already exists"); p.appendRow("occurrences",newOccurrence(id,c.id,1,c.month,0,c.name,"Income",PlanNature.INCOME,c.currency,c.dueDate,c.expectedMinor,c.accountId,null)) }
   is F06Command.FundAnnual -> { positive(c.minor); val o=findOccurrence(p,c.occurrenceId); val purpose=o.nullText("purposeId") ?: fail("This occurrence has no annual purpose"); val v=occurrence(p,o,ledger.snapshot().operations); account(c.accountId,v.currency); ledger.execute(F05Command.FundReserve(c.requestId+":ledger",c.accountId,purpose,v.name,c.minor)); state=load(); p=planning(state) }
   is F06Command.AssignFundingPeriod -> { YearMonth.parse(c.month); findOccurrence(p,c.occurrenceId).put("financingMonth",c.month) }
   is F06Command.FundPurpose -> { positive(c.minor); val t=p.rows("templates").map(::template).filter { it.id==c.templateId }.maxByOrNull { it.version } ?: fail("Unknown template"); val purpose=t.purposeId ?: fail("Template has no purpose"); account(c.accountId,t.currency); ledger.execute(F05Command.FundReserve(c.requestId+":ledger",c.accountId,purpose,t.name,c.minor)); state=load(); p=planning(state) }
   is F06Command.SetReminders -> { val r=c.preference; if(r.daysBefore !in -1..31 || (r.daysBefore == -1 && r.additionalDays.isNotEmpty()) || r.leads.any { it !in 0..31 } || r.leads.distinct().size != r.leads.size || r.hour !in 0..23) fail("Invalid reminder time"); p.put("reminders",obj("enabled" to r.enabled,"daysBefore" to r.daysBefore,"hour" to r.hour, "additionalDays" to JSONArray(r.additionalDays))) }
  }
  state.put("f06",p); state.saveCategoryCatalog(); state.getJSONArray("audit").put(obj("requestId" to command.requestId,"action" to command.javaClass.simpleName,"subjectId" to command.requestId,"reason" to when(command) { is F06Command.SavePlan -> command.plan.reason; is F06Command.SaveSalary -> command.salary.reason; is F06Command.SaveTemplate -> command.template.reason; else -> "" })); validatePlanningRestore(state,dao.accounts()); dao.saveWorkspace(WorkspaceStateEntity("main",state.toString())); dao.insertRequest(WorkspaceRequestEntity(command.requestId,fingerprint,command.requestId)); (p.rows("plans").map { it.getString("month") }+p.rows("opened").map { it.getString("month") }).distinct().forEach { snapshot(it) }; command.requestId
 }
 private suspend fun account(id:String?,currency:Currency,allowCard:Boolean=false) { if(id==null) return; val a=ledger.snapshot().accounts.find { it.id==id } ?: fail("Unknown account"); if(a.currency!=currency || (a.kind!="CASH" && (!allowCard || a.kind!="LIABILITY")) || a.archived) fail("Choose an active cash account in the native currency") }
 private suspend fun validatePlan(v:PeriodPlan) { validateCategories(v, normalized = true); YearMonth.parse(v.month); LocalDate.parse(v.effectiveDate); if(v.version<1 || v.targetMinor<0 || v.rows.map { it.id }.distinct().size!=v.rows.size) fail("Invalid plan"); v.rows.forEach { if(it.id.isBlank() || it.name.isBlank() || it.group.isBlank() || it.minor<0) fail("Invalid plan row"); account(it.suggestedAccountId,it.currency) } }
 private suspend fun validateTemplate(t:RecurringTemplate) { YearMonth.parse(t.effectiveMonth); YearMonth.parse(t.anchorMonth); if(t.nature !in setOf(PlanNature.FIXED,PlanNature.SUBSCRIPTION,PlanNature.INCOME)) fail("Use reserve financing or account settlement for this financial nature"); if(t.category?.isBlank() == true || t.id.isBlank() || t.name.isBlank() || t.group.isBlank() || t.version<1 || t.minor<0 || t.day !in 1..31 || t.secondDay !in 1..31 || t.intervalMonths<1 || t.monthlyContributionMinor<0) fail("Invalid recurrence"); PlanningMath.ownShare(t.householdTotalMinor ?: t.minor,t.ownPercent,t.ownFixedMinor); account(t.suggestedAccountId,t.currency,t.nature!=PlanNature.INCOME) }
 private fun generate(p:JSONObject,month:String) {
  val ym=YearMonth.parse(month)
  val today = LocalDate.now(BusinessTime.zone)
  val currentOrFuture = ym >= YearMonth.from(today)
  val versions = p.rows("templates").map(::template).filter { it.effectiveMonth <= month }.groupBy { it.id }
  val scheduleOrder = compareBy<RecurringTemplate> { it.effectiveMonth }.thenBy { it.version }
  val occurrences = p.rows("occurrences").filter { it.getString("nominalMonth") == month }.groupBy { it.getString("templateId") }
  fun canRevise(old: JSONObject) = old.isNull("previouslyDeducted") && old.isNull("confirmedMinor") &&
   old.getJSONArray("paymentIds").length() == 0 &&
   (currentOrFuture || LocalDate.parse(old.getString("dueDate")) >= today)

  for ((id, history) in versions) {
   val revision = history.maxWith(scheduleOrder)
   val dates = PlanningMath.slots(revision, ym)
   val existing = occurrences[id].orEmpty().associateBy { it.getInt("slot") }
   existing.values.filter { it.getInt("slot") >= dates.size && canRevise(it) }.forEach { it.put("suppressed", true) }
   dates.forEachIndexed { slot, day ->
    val old = existing[slot]
    val row = newOccurrence("$id:$month:$slot", id, revision.version, month, slot, revision.name, revision.group,
     revision.nature, revision.currency, day.toString(), PlanningMath.ownShare(revision.householdTotalMinor ?: revision.minor,
      revision.ownPercent, revision.ownFixedMinor), revision.suggestedAccountId, revision.purposeId)
    if (old == null) p.appendRow("occurrences", row)
    else if (canRevise(old) && (old.getInt("templateVersion") != revision.version || old.optBoolean("suppressed"))) {
     row.put("financingMonth", old.getString("financingMonth"))
     row.keys().forEach { old.put(it, row.get(it)) }
    }
   }
  }
  fun syncIncome(existing: List<JSONObject>, scheduled: List<JSONObject>) {
   val byId = existing.associateBy { it.getString("id") }
   val ids = scheduled.map { it.getString("id") }.toSet()
   val lockedDates = existing.filter { !it.optBoolean("suppressed") && !canReviseIncome(it,today) }
    .map { it.getString("dueDate") }.toSet()
   existing.filter { it.getString("id") !in ids && canReviseIncome(it,today) }.forEach { it.put("suppressed",true) }
   for (row in scheduled) {
    val old = byId[row.getString("id")]
    if (old != null && !canReviseIncome(old,today)) continue
    if (row.getString("dueDate") in lockedDates) old?.put("suppressed",true)
    else if (old == null) p.appendRow("occurrences",row)
    else row.keys().forEach { old.put(it,row.get(it)) }
   }
  }
  val salary = p.rows("salaries").map(::salary).filter { it.effectiveMonth <= month }
   .withIndex().maxWithOrNull(compareBy({ it.value.effectiveMonth },{ it.index }))?.value
  if (salary != null) {
   val rows = PlanningMath.salaryDates(ym,salary).map { date ->
    val slot = if (salary.frequency == IncomeFrequency.MONTHLY) 0 else date.dayOfMonth
    newOccurrence("salary:$month:$slot","salary",1,month,slot,"Salary","Income",PlanNature.INCOME,
     salary.currency,date.toString(),salary.netMinor,salary.accountId,null).put("salaryRevisionId",salary.id)
   }
   syncIncome(occurrences["salary"].orEmpty(),rows)
  }
  p.readExtraIncomes().groupBy { it.id }.forEach { (id,revisions) ->
   val templateId = "recurring-extra:$id"
   val eligible = revisions.filter { it.effectiveDate <= ym.atEndOfMonth().toString() }
   val rows = eligible.filterNot { it.paused }.flatMap { revision ->
    PlanningMath.incomeDates(ym,revision.frequency,LocalDate.parse(revision.anchorDate))
     .filter { date ->
      eligible.filter { it.effectiveDate <= date.toString() }.maxByOrNull { it.version } == revision
     }.map { date ->
      val slot = date.dayOfMonth
      newOccurrence("$templateId:$month:$slot",templateId,revision.version,month,slot,revision.name,"Income",
       PlanNature.INCOME,revision.currency,date.toString(),revision.expectedMinor,revision.accountId,null)
     }
   }
   syncIncome(occurrences[templateId].orEmpty(),rows)
  }
 }
 private fun findOccurrence(p:JSONObject,id:String)=p.rows("occurrences").find { it.getString("id")==id && !it.optBoolean("suppressed") } ?: fail("Unknown occurrence")
 private fun net(id:String,ops:List<OperationDetail>):Long { val o=ops.find { it.id==id } ?: return 0; if(o.voided) { return ops.filter { it.originalId==id && it.type in setOf("INCOME","EXPENSE","CREDIT_PURCHASE") }.map { net(it.id,ops) }.let(::checkedSum) }; return o.amountMinor-o.refundedMinor }
 private fun occurrence(p: JSONObject, o: JSONObject, ops: List<OperationDetail>): PlannedOccurrence {
  val ids = o.getJSONArray("paymentIds").strings()
  val prior = previouslyDeductedPayments(o)
  val grossPaid = Math.addExact(checkedSum(ids.map { net(it, ops) }), checkedSum(prior.map { it.getLong("minor") }))
  val currency = Currency.valueOf(o.getString("currency"))
  val expected = o.getLong("expectedMinor")
  val confirmed = o.f07OptionalLong("confirmedMinor")
  val ownTotal = confirmed ?: expected
  val template = occurrenceTemplate(p, o)
  val grossTotal = paymentTotal(o, template)
  val ownPaid = ownShareOfPayment(grossPaid, ownTotal, grossTotal)
  val pending = (grossTotal - grossPaid).coerceAtLeast(0)
  val due = o.getString("dueDate")
  return PlannedOccurrence(o.getString("id"), o.getString("templateId"), o.getInt("templateVersion"),
   o.getString("nominalMonth"), o.getInt("slot"), o.getString("name"), o.getString("group"),
   PlanNature.valueOf(o.getString("nature")), currency, due, expected, confirmed, ownPaid,
   (ownTotal - ownPaid).coerceAtLeast(0), o.nullText("suggestedAccountId"), o.nullText("purposeId"),
   pending > 0 && LocalDate.parse(due) < LocalDate.now(BusinessTime.zone), ids, o.getString("financingMonth"),
   ownShareOfPayment(cashActual(ids, ops, null, currency), ownTotal, grossTotal), prior.isNotEmpty(),
   grossTotal, pending, template?.householdTotalMinor != null)
 }
 companion object {
  internal fun empty()=obj("payloadVersion" to 1,"plans" to JSONArray(),"templates" to JSONArray(),"salaries" to JSONArray(),"opened" to JSONArray(),"occurrences" to JSONArray(),"reminders" to obj("enabled" to false,"daysBefore" to 1,"hour" to 9))
 }
}
private fun fail(message:String):Nothing=throw DomainFailure.Invalid(message)
private fun positive(v:Long) { if(v<=0) fail("Amount must be positive") }
private fun obj(vararg fields:Pair<String,Any?>)=JSONObject().apply { fields.forEach { put(it.first,it.second ?: JSONObject.NULL) } }
private fun JSONObject.rows(key:String)=getJSONArray(key).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
private fun JSONObject.appendRow(key:String,row:JSONObject) { getJSONArray(key).put(row) }
private fun JSONArray.strings()=(0 until length()).map { getString(it) }
private fun JSONObject.nullText(key:String)=if(isNull(key)) null else getString(key)
private fun json(r:PlanRow)=obj("id" to r.id,"name" to r.name,"group" to r.group,"nature" to r.nature.name,"minor" to r.minor,"currency" to r.currency.name,"suggestedAccountId" to r.suggestedAccountId,"accumulate" to r.accumulate)
private fun json(p:PeriodPlan)=obj("month" to p.month,"version" to p.version,"targetMinor" to p.targetMinor,"targetCurrency" to p.targetCurrency.name,"reason" to p.reason,"effectiveDate" to p.effectiveDate,"rows" to JSONArray(p.rows.map(::json)))
internal fun plan(p:JSONObject)=PeriodPlan(p.getString("month"),p.getInt("version"),p.rows("rows").map { PlanRow(it.getString("id"),it.getString("name"),it.getString("group"),PlanNature.valueOf(it.getString("nature")),it.getLong("minor"),Currency.valueOf(it.getString("currency")),it.nullText("suggestedAccountId"),it.getBoolean("accumulate")) },p.getLong("targetMinor"),Currency.valueOf(p.getString("targetCurrency")),p.getString("reason"),p.getString("effectiveDate"))
private fun json(s:SalaryVersion)=obj("id" to s.id,"effectiveMonth" to s.effectiveMonth,"netMinor" to s.netMinor,"currency" to s.currency.name,"accountId" to s.accountId,"reason" to s.reason,"scheduleDay" to s.scheduleDay).apply {
 if (s.frequency != IncomeFrequency.MONTHLY) put("frequency",s.frequency.name)
 s.anchorDate?.let { put("anchorDate",it) }
}
private fun salary(s:JSONObject)=SalaryVersion(s.getString("id"),s.getString("effectiveMonth"),s.getLong("netMinor"),Currency.valueOf(s.getString("currency")),s.nullText("accountId"),s.getString("reason"),if(s.isNull("scheduleDay")) null else s.getInt("scheduleDay"),IncomeFrequency.valueOf(s.optString("frequency",IncomeFrequency.MONTHLY.name)),s.nullText("anchorDate"))
private fun validateSalary(s: SalaryVersion) {
 YearMonth.parse(s.effectiveMonth)
 if (s.id.isBlank() || s.netMinor <= 0 || (s.scheduleDay != null && s.scheduleDay !in 1..31)) fail("Invalid salary")
 if (s.frequency.intervalDays == null) {
  if (s.anchorDate != null || s.frequency != IncomeFrequency.MONTHLY && s.scheduleDay != null) fail("Invalid salary anchor")
 } else {
  if (s.anchorDate == null || s.scheduleDay != null) fail("A weekly or 14-day salary needs an anchor date")
  LocalDate.parse(s.anchorDate)
 }
}
private fun json(i: ExtraIncomeVersion) = obj(
 "id" to i.id,"version" to i.version,"name" to i.name,"effectiveDate" to i.effectiveDate,
 "anchorDate" to i.anchorDate,"frequency" to i.frequency.name,"expectedMinor" to i.expectedMinor,
 "currency" to i.currency.name,"accountId" to i.accountId,"paused" to i.paused,
)
private fun extraIncome(i: JSONObject) = ExtraIncomeVersion(
 i.getString("id"),i.getInt("version"),i.getString("name"),i.getString("effectiveDate"),
 i.getString("anchorDate"),IncomeFrequency.valueOf(i.getString("frequency")),i.getLong("expectedMinor"),
 Currency.valueOf(i.getString("currency")),i.nullText("accountId"),i.getBoolean("paused"),
)
private fun JSONObject.readExtraIncomes() = if (has("extraIncomes")) rows("extraIncomes").map(::extraIncome) else emptyList()
private fun validateExtraIncome(i: ExtraIncomeVersion) {
 LocalDate.parse(i.effectiveDate)
 LocalDate.parse(i.anchorDate)
 if (i.id.isBlank() || i.name.isBlank() || i.version < 1 || i.expectedMinor <= 0) fail("Invalid recurring income")
}
private fun canReviseIncome(row: JSONObject,today: LocalDate) =
 row.isNull("confirmedMinor") && row.getJSONArray("paymentIds").length() == 0 &&
  (row.optBoolean("suppressed") || LocalDate.parse(row.getString("dueDate")) >= today)
private fun json(t:RecurringTemplate)=obj("id" to t.id,"version" to t.version,"name" to t.name,"group" to t.group,"nature" to t.nature.name,"minor" to t.minor,"currency" to t.currency.name,"effectiveMonth" to t.effectiveMonth,"frequency" to t.frequency.name,"day" to t.day,"secondDay" to t.secondDay,"intervalMonths" to t.intervalMonths,"suggestedAccountId" to t.suggestedAccountId,"purposeId" to t.purposeId,"paused" to t.paused,"cancelled" to t.cancelled,"householdTotalMinor" to t.householdTotalMinor,"ownPercent" to t.ownPercent,"ownFixedMinor" to t.ownFixedMinor,"monthlyContributionMinor" to t.monthlyContributionMinor,"reason" to t.reason,"anchorMonth" to t.anchorMonth).apply { t.category?.let { put("category", it) } }
private fun template(t:JSONObject)=RecurringTemplate(t.getString("id"),t.getInt("version"),t.getString("name"),t.getString("group"),PlanNature.valueOf(t.getString("nature")),t.getLong("minor"),Currency.valueOf(t.getString("currency")),t.getString("effectiveMonth"),Recurrence.valueOf(t.getString("frequency")),t.getInt("day"),t.getInt("secondDay"),t.getInt("intervalMonths"),t.nullText("suggestedAccountId"),t.nullText("purposeId"),t.getBoolean("paused"),t.getBoolean("cancelled"),if(t.isNull("householdTotalMinor")) null else t.getLong("householdTotalMinor"),t.nullText("ownPercent"),if(t.isNull("ownFixedMinor")) null else t.getLong("ownFixedMinor"),t.getLong("monthlyContributionMinor"),t.getString("reason"),t.getString("anchorMonth"),t.nullText("category"))
private fun newOccurrence(id:String,template:String,version:Int,month:String,slot:Int,name:String,group:String,nature:PlanNature,currency:Currency,due:String,minor:Long,account:String?,purpose:String?)=obj("id" to id,"templateId" to template,"templateVersion" to version,"nominalMonth" to month,"slot" to slot,"name" to name,"group" to group,"nature" to nature.name,"currency" to currency.name,"dueDate" to due,"expectedMinor" to minor,"confirmedMinor" to null,"suggestedAccountId" to account,"purposeId" to purpose,"financingMonth" to month,"paymentIds" to JSONArray(),"suppressed" to false)

/** Strict graph validation is called both before command commit and before fixture replacement. */
internal fun validatePlanningRestore(state:JSONObject,accounts:List<AccountEntity>) {
 if(!state.has("f06")) return
 val p=state.getJSONObject("f06")
 fun integer(o:JSONObject,k:String):Long { val v=o.get(k); if(v !is Int && v !is Long) fail("Planning integers must be exact"); return (v as Number).toLong() }
 if(integer(p,"payloadVersion")!=1L) throw DomainFailure.UnsupportedVersion()
 val plans=p.rows("plans").map { raw -> integer(raw,"version"); integer(raw,"targetMinor"); raw.rows("rows").forEach { integer(it,"minor"); if(it.get("accumulate") !is Boolean) fail("Invalid accumulation flag") }; plan(raw) }
 val templates=p.rows("templates").map { raw -> listOf("version","minor","day","secondDay","intervalMonths","monthlyContributionMinor").forEach { integer(raw,it) }; listOf("householdTotalMinor","ownFixedMinor").forEach { if(!raw.isNull(it)) integer(raw,it) }; listOf("paused","cancelled").forEach { if(raw.get(it) !is Boolean) fail("Invalid template status") }; template(raw) }
 val salaries=p.rows("salaries").map { raw -> integer(raw,"netMinor"); if(!raw.isNull("scheduleDay")) integer(raw,"scheduleDay"); salary(raw) }
 val extraIncomes = p.readExtraIncomes()
 if (p.has("extraIncomes")) p.rows("extraIncomes").forEach {
  integer(it,"version"); integer(it,"expectedMinor")
  if (it.get("paused") !is Boolean) fail("Invalid recurring income status")
 }
 fun ref(id:String?,currency:Currency,allowCard:Boolean=false) { if(id!=null && accounts.none { it.id==id && it.currency==currency.name && (it.kind=="CASH" || allowCard && it.kind=="LIABILITY") }) fail("Invalid planning account reference") }
 if(plans.map { it.month to it.version }.distinct().size!=plans.size || templates.map { it.id to it.version }.distinct().size!=templates.size || salaries.map { it.id }.distinct().size!=salaries.size) fail("Duplicate planning revisions")
 plans.forEach { v -> validateCategories(v); YearMonth.parse(v.month); LocalDate.parse(v.effectiveDate); if(v.version<1 || v.targetMinor<0 || v.rows.map { it.id }.distinct().size!=v.rows.size) fail("Invalid plan"); v.rows.forEach { if(it.id.isBlank() || it.name.isBlank() || it.group.isBlank() || it.minor<0) fail("Invalid plan row"); ref(it.suggestedAccountId,it.currency) } }
 templates.forEach { t -> YearMonth.parse(t.effectiveMonth); YearMonth.parse(t.anchorMonth); if(t.nature !in setOf(PlanNature.FIXED,PlanNature.SUBSCRIPTION,PlanNature.INCOME)) fail("Use reserve financing or account settlement for this financial nature"); if(t.category?.isBlank() == true || t.id.isBlank() || t.name.isBlank() || t.group.isBlank() || t.version<1 || t.minor<0 || t.day !in 1..31 || t.secondDay !in 1..31 || t.intervalMonths<1 || t.monthlyContributionMinor<0) fail("Invalid template"); PlanningMath.ownShare(t.householdTotalMinor ?: t.minor,t.ownPercent,t.ownFixedMinor); ref(t.suggestedAccountId,t.currency,t.nature!=PlanNature.INCOME) }
 salaries.forEach { validateSalary(it); ref(it.accountId,it.currency) }
 extraIncomes.groupBy { it.id }.values.forEach { history ->
  val versions = history.sortedBy { it.version }
  if (versions.withIndex().any { it.value.version != it.index + 1 } ||
   versions.zipWithNext().any { (a,b) -> a.effectiveDate > b.effectiveDate }) fail("Invalid recurring income revisions")
  versions.forEach { validateExtraIncome(it); ref(it.accountId,it.currency) }
 }
 val opened=p.rows("opened").map { it.getString("month").also(YearMonth::parse) }; if(opened.distinct().size!=opened.size) fail("Duplicate opened month")
 val operations=state.rows("operations"); val byId=operations.associateBy { it.getString("id") }; operations.forEach { origin -> val visited=mutableSetOf<String>(); var current:JSONObject?=origin; while(current!=null && !current.isNull("originalId")) { if(!visited.add(current.getString("id"))) fail("Cyclic operation graph"); current=byId[current.getString("originalId")] } }
 val occurrences=p.rows("occurrences"); if(occurrences.map { it.getString("id") }.distinct().size!=occurrences.size) fail("Duplicate occurrence")
 val logicalKeys=occurrences.map { Triple(it.getString("templateId"),it.getString("nominalMonth"),it.getInt("slot")) }; if(logicalKeys.distinct().size!=logicalKeys.size) fail("Duplicate logical occurrence")
 val links=mutableSetOf<String>()
 occurrences.forEach { o ->
  val id=o.getString("id"); val month=o.getString("nominalMonth"); YearMonth.parse(month); YearMonth.parse(o.getString("financingMonth")); LocalDate.parse(o.getString("dueDate")); val nature=PlanNature.valueOf(o.getString("nature")); val currency=Currency.valueOf(o.getString("currency")); val templateId=o.getString("templateId"); val version=integer(o,"templateVersion"); val slot=integer(o,"slot"); if(o.get("suppressed") !is Boolean) fail("Invalid occurrence state")
  if(id.isBlank() || o.getString("name").isBlank() || o.getString("group").isBlank() || slot !in 0..31 || integer(o,"expectedMinor")<0 || (!o.isNull("confirmedMinor") && integer(o,"confirmedMinor")<0)) fail("Invalid occurrence")
  when {
   templateId == "salary" -> {
    val s = salaries.find { it.id==o.getString("salaryRevisionId") && it.effectiveMonth<=month && it.currency==currency }
     ?: fail("Invalid salary revision")
    val date = LocalDate.parse(o.getString("dueDate"))
    val validSlot = if (s.frequency == IncomeFrequency.MONTHLY) slot == 0L else slot == date.dayOfMonth.toLong()
    if (!validSlot || id!="salary:$month:$slot" || nature!=PlanNature.INCOME || version!=1L ||
     date !in PlanningMath.salaryDates(YearMonth.parse(month),s)) fail("Invalid salary occurrence")
   }
   id.startsWith("extra:") -> if(id!="extra:$templateId" || nature!=PlanNature.INCOME || version!=1L || slot!=0L) fail("Invalid extraordinary income identity")
   templateId.startsWith("recurring-extra:") -> {
    val revision = extraIncomes.find { it.id == templateId.removePrefix("recurring-extra:") && it.version.toLong() == version }
     ?: fail("Invalid recurring income revision")
    val date = LocalDate.parse(o.getString("dueDate"))
    if (revision.paused || date.toString() < revision.effectiveDate || slot != date.dayOfMonth.toLong() ||
     id!="$templateId:$month:$slot" || nature!=PlanNature.INCOME || revision.currency!=currency ||
     date !in PlanningMath.incomeDates(YearMonth.parse(month),revision.frequency,LocalDate.parse(revision.anchorDate))) fail("Invalid recurring income occurrence")
   }
   else -> {
    val t=templates.find { it.id==templateId && it.version.toLong()==version } ?: fail("Unknown occurrence revision")
    if(slot !in 0..1 || t.currency!=currency || t.nature!=nature || id!="$templateId:$month:$slot") fail("Invalid occurrence identity")
   }
  }
  ref(o.nullText("suggestedAccountId"),currency,nature in setOf(PlanNature.FIXED,PlanNature.SUBSCRIPTION))
  var netPaid=java.math.BigInteger.ZERO
  o.getJSONArray("paymentIds").strings().forEach { payment ->
   if(!links.add(payment)) fail("Payment belongs to more than one obligation")
   val op=byId[payment] ?: fail("Missing linked payment")
   val expected=if(nature==PlanNature.INCOME) "INCOME" else "EXPENSE"
   if(op.getString("type") !in (if(expected=="INCOME") setOf("INCOME") else setOf("EXPENSE","CREDIT_PURCHASE")) || accounts.none { it.id==op.getString("accountId") && it.currency==currency.name }) fail("Ineligible linked payment")
   // A corrected payment must retain currency and financial nature throughout its replacement chain.
   fun replacements(parent:String):List<JSONObject> = operations.filter { !it.isNull("originalId") && it.getString("originalId")==parent && it.getString("type") in setOf("INCOME","EXPENSE","CREDIT_PURCHASE") }.flatMap { listOf(it)+replacements(it.getString("id")) }
   val family=listOf(op)+replacements(payment); family.drop(1).forEach { if(!links.add(it.getString("id"))) fail("Replacement allocated more than once") }
   family.forEach { r -> if(!r.getBoolean("voided")) netPaid=netPaid.add(java.math.BigInteger.valueOf(integer(r,"amountMinor"))).subtract(java.math.BigInteger.valueOf(integer(r,"refundedMinor"))) }
   replacements(payment).forEach { r -> if(r.getString("type")!=op.getString("type") || accounts.none { it.id==r.getString("accountId") && it.currency==currency.name }) fail("Correction changes linked payment nature or currency") }
  }
  if (!o.isNull("additionalPreviouslyDeducted") && o.isNull("previouslyDeducted")) fail("Missing original confirmation")
  previouslyDeductedPayments(o).forEach { prior ->
   val amount = integer(prior,"minor")
   if(nature !in setOf(PlanNature.FIXED,PlanNature.SUBSCRIPTION) || amount <= 0) fail("Invalid previously deducted payment")
   ref(prior.getString("accountId"),currency,allowCard = true)
   LocalDate.parse(prior.getString("date"))
   netPaid = netPaid.add(java.math.BigInteger.valueOf(amount))
  }
  val revision = templates.find { it.id == templateId && it.version.toLong() == version }
  if (!o.isNull("confirmedGrossMinor") && (revision?.householdTotalMinor == null ||
   o.isNull("confirmedMinor") || integer(o,"confirmedGrossMinor") < integer(o,"confirmedMinor"))) fail("Invalid shared payment total")
  val cap=paymentTotal(o, revision); if(netPaid>java.math.BigInteger.valueOf(cap)) fail("Linked payments exceed obligation")
 }
 val r=p.getJSONObject("reminders"); if(r.get("enabled") !is Boolean) fail("Invalid reminder state"); if(integer(r,"daysBefore") !in -1..31 || (r.getInt("daysBefore") == -1 && reminderExtras(r).isNotEmpty()) || integer(r,"hour") !in 0..23 || reminderExtras(r).any { it !in 0..31 || it == r.getInt("daysBefore") } || reminderExtras(r).distinct().size != reminderExtras(r).size) fail("Invalid reminders")
}


internal fun previouslyDeductedPayments(row: JSONObject): List<JSONObject> =
 listOfNotNull(if (row.isNull("previouslyDeducted")) null else row.getJSONObject("previouslyDeducted")) +
  if (row.isNull("additionalPreviouslyDeducted")) emptyList() else row.getJSONArray("additionalPreviouslyDeducted").let { entries ->
   List(entries.length()) { entries.getJSONObject(it) }
  }

internal fun occurrenceTemplate(plan: JSONObject, row: JSONObject): RecurringTemplate? =
 plan.rows("templates").firstOrNull {
  it.getString("id") == row.getString("templateId") && it.getInt("version") == row.getInt("templateVersion")
 }?.let(::template)

internal fun paymentTotal(row: JSONObject, template: RecurringTemplate?, historical: Boolean = false): Long {
 val own = row.getLong("expectedMinor")
 val confirmed = if (historical) null else row.f07OptionalLong("confirmedMinor")
 val household = template?.householdTotalMinor ?: return confirmed ?: own
 val gross = if (historical) null else row.f07OptionalLong("confirmedGrossMinor")
 return gross ?: maxOf(household, confirmed ?: own)
}

internal fun ownShareOfPayment(amount: Long, own: Long, gross: Long): Long {
 if (own == gross || gross == 0L) return amount
 return java.math.BigDecimal.valueOf(amount).multiply(java.math.BigDecimal.valueOf(own))
  .divide(java.math.BigDecimal.valueOf(gross), 0, java.math.RoundingMode.HALF_UP).longValueExact()
}

private fun checkedSum(values:Iterable<Long>):Long = try { values.fold(java.math.BigInteger.ZERO) { n,v -> n.add(java.math.BigInteger.valueOf(v)) }.longValueExact() } catch (_:ArithmeticException) { fail("Amount exceeds integer minor-unit range") }

private fun reminderExtras(row: JSONObject): List<Int> {
 if (!row.has("additionalDays")) return emptyList()
 val values = row.getJSONArray("additionalDays")
 return List(values.length()) { index ->
  val value = values.get(index)
  if (value !is Number || value.toDouble() != value.toInt().toDouble()) fail("Invalid reminder lead")
  value.toInt()
 }
}

internal fun canonicalPlanning(value:Any?):Any = when(value) {
 null -> JSONObject.NULL
 is String,is Boolean,is Number -> value
 is Enum<*> -> value.name
 is Iterable<*> -> JSONArray(value.map(::canonicalPlanning))
 else -> JSONObject().apply {
  put("commandType",value.javaClass.name)
  value.javaClass.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.sortedBy { it.name }.forEach { field ->
   field.isAccessible = true
   val omit = when (value) {
    is Debt -> when (field.name) {
     "intervalMonths" -> value.intervalMonths == 1
     "maturityDate" -> field.get(value) == null
     "estimateTermFromBalance" -> field.get(value) == false
     "estimatedOpeningPrincipal" -> field.get(value) == false
     "installmentChargesMinor" -> field.get(value) == null
     else -> false
    }
    is SalaryVersion -> when (field.name) {
     "frequency" -> value.frequency == IncomeFrequency.MONTHLY
     "anchorDate" -> value.anchorDate == null
     else -> false
    }
    is ReminderPreference -> field.name == "additionalDays" && value.additionalDays.isEmpty()
    is RecurringTemplate -> field.name == "category" && value.category == null
    else -> false
   }
   if (!omit) put(field.name,canonicalPlanning(field.get(value)))
  }
 }
}
private fun validateCategories(plan:PeriodPlan, normalized: Boolean = false) {
 val rows=plan.rows.filter { it.nature in setOf(PlanNature.VARIABLE_NEED,PlanNature.FREE) }
 if(rows.map { (if (normalized) categoryKey(it.name) else it.name) to it.currency }.distinct().size!=rows.size) fail("A variable category can belong to only one budget group")
}

internal fun budgetCategoryMatcher(rows: List<PlanRow>, planRows: List<PlanRow>, currency: Currency): (String) -> Boolean {
 val aliases = planRows.filter { it.currency == currency && it.nature in setOf(PlanNature.VARIABLE_NEED, PlanNature.FREE) }
  .groupingBy { categoryKey(it.name) }.eachCount().filterValues { it > 1 }.keys
 val exact = rows.map { it.name }.filter { categoryKey(it) in aliases }.toSet()
 val normalized = rows.map { categoryKey(it.name) }.toSet() - aliases
 // Older plans could assign case variants to different groups. Preserve their exact attribution.
 return { label -> label in exact || categoryKey(label) in normalized }
}

