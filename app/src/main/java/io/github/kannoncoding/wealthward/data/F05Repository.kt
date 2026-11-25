package io.github.kannoncoding.wealthward.data

import androidx.room.withTransaction
import io.github.kannoncoding.wealthward.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.Instant
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Durable command boundary. Every command, its audit event, postings and request key commit together. */
class F05Repository(private val db: WealthDatabase) {
 private val dao = db.ledger()
 val observe: Flow<F05Snapshot> = combine(dao.observeAccounts(), dao.observePostingCount(), dao.observeWorkspace()) { _, _, _ -> snapshot() }
 suspend fun snapshot(): F05Snapshot = db.withTransaction { snapshot(load(), includeTermDebts = false) }
 private suspend fun load() = JSONObject(dao.workspaceState()?.payload ?: EMPTY_STATE)
 private fun invalid(message: String): Nothing = throw DomainFailure.Invalid(message)
 private fun date(value: String): LocalDate = try { LocalDate.parse(value) } catch (_: Exception) { invalid("Use an ISO calendar date") }
 private fun add(a: Long,b: Long): Long = exact(BigInteger.valueOf(a).add(BigInteger.valueOf(b)))
 private fun sub(a: Long,b: Long): Long = exact(BigInteger.valueOf(a).subtract(BigInteger.valueOf(b)))
 private fun exact(value: BigInteger): Long = try { value.longValueExact() } catch (_: ArithmeticException) { invalid("Amount exceeds integer minor-unit range") }
 private fun positive(value: Long) { if(value <= 0) invalid("Amount must be positive") }
 private fun JSONObject.list(key: String) = getJSONArray(key).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
 private fun JSONObject.find(key: String,id: String) = list(key).find { it.getString("id") == id }
 private fun JSONObject.appendRow(key: String,value: JSONObject) { getJSONArray(key).put(value) }
 private fun obj(vararg fields: Pair<String,Any?>) = JSONObject().apply { fields.forEach { put(it.first,it.second ?: JSONObject.NULL) } }
 private fun JSONObject.text(key: String): String? = if(isNull(key)) null else getString(key)
 private fun JSONObject.longOrNull(key: String): Long? = if(isNull(key)) null else getLong(key)
 private fun meta(state: JSONObject, account: AccountEntity): JSONObject = state.find("accounts",account.id) ?: obj("id" to account.id,"productId" to account.id,"openingDate" to null,"archived" to false)
 private suspend fun account(id: String, state: JSONObject, active: Boolean = true): AccountEntity {
  val value = dao.accounts().find { it.id == id } ?: invalid("Unknown account")
  if(value.kind=="TERM_DEBT") invalid("Use the term-debt workspace")
  if(active && meta(state,value).getBoolean("archived")) invalid("Archived accounts cannot receive new operations")
  return value
 }
 private suspend fun snapshot(state: JSONObject, includeTermDebts: Boolean = true): F05Snapshot {
  val operations = state.list("operations").map { operation(it) }
  val recs = state.list("reconciliations").map { reconciliation(it) }
  val reserves = state.list("reserves").map { ReserveBalance(it.getString("accountId"),it.getString("purposeId"),it.getString("name"),it.getLong("minor")) }
  val holds = state.list("holds").map { HoldDetail(it.getString("id"),it.getString("accountId"),it.getLong("minor"),it.getString("merchant"),it.getString("date"),it.getString("status")) }
  val transactions = dao.transactions().filter { it.confirmed }.map { it.id }.toSet()
  val postings = dao.postings()
  val accounts = dao.accounts().filter { includeTermDebts || it.kind != "TERM_DEBT" }.map { a ->
   val m = meta(state,a)
   val balance = exact(postings.filter { it.accountId == a.id && it.transactionId in transactions }.fold(BigInteger.valueOf(a.openingMinor)) { n,p -> n.add(BigInteger.valueOf(p.minor)) })
   val funded = reserves.filter { it.accountId == a.id }
   val reserved = exact(funded.fold(BigInteger.ZERO) { n,r -> n.add(BigInteger.valueOf(r.minor)) })
   val held = exact(holds.filter { it.accountId == a.id && it.status == "PENDING" }.fold(BigInteger.ZERO) { n,h -> n.add(BigInteger.valueOf(h.minor)) })
   AccountDetail(a.id,m.getString("productId"),a.name,Currency.valueOf(a.currency),a.kind,m.text("openingDate"),m.getBoolean("archived"),balance,reserved,sub(balance,reserved),held,funded,recs.lastOrNull { it.accountId == a.id },a.creditLimitMinor,a.openingMinor,
    m.text("cashSubtype")?.let(CashAccountSubtype::valueOf),m.text("customTypeName"))
  }
  return F05Snapshot(accounts,operations,holds,recs,state.list("audit").map { AuditDetail(it.getString("requestId"),it.getString("action"),it.getString("subjectId"),it.getString("reason")) },state.categoryCatalog())
 }
 internal fun operation(o: JSONObject) = OperationDetail(o.getString("id"),o.getString("date"),o.getString("type"),o.getString("accountId"),o.text("destinationId"),o.getLong("amountMinor"),o.longOrNull("destinationMinor"),o.getString("merchant"),o.getString("category"),o.getString("notes"),o.list("splits").map { ExpenseSplit(it.getString("category"),it.getLong("minor"),it.text("purposeId")) },o.text("originalId"),o.getBoolean("voided"),o.getLong("refundedMinor"),o.getLong("expenseMinor"),o.optLong("feeMinor"),o.text("rate"),o.text("rateSource"),o.optString("bookedDate",o.getString("date")),o.optString("rateDate",o.getString("date")),o.optString("fundingAccountId").takeIf { it.isNotBlank() && it!="null" },o.optString("fundingPurposeId").takeIf { it.isNotBlank() && it!="null" },o.optLong("settledMinor"),o.optJSONArray("creditAllocations")?.let { a -> (0 until a.length()).map { i -> val e=a.getJSONObject(i); CreditPaymentAllocation(e.getString("purchaseId"),e.getLong("minor"),e.text("purposeId"),e.getLong("sourceMinor")) } } ?: emptyList(),o.optBoolean("previouslyDeducted"))
 private fun reconciliation(o: JSONObject) = ReconciliationDetail(o.getString("id"),o.getString("accountId"),o.getString("cutDate"),o.getString("balanceType"),o.getLong("observedMinor"),o.getLong("recordedMinor"),o.getLong("differenceMinor"),(0 until o.getJSONArray("reviewedIds").length()).map { o.getJSONArray("reviewedIds").getString(it) },o.getString("status"),o.getString("reason"))
 suspend fun execute(command: F05Command): String = execute(command, false)

 internal suspend fun executeCommitment(command: F05Command): String {
  if(dao.request(command.requestId)!=null) invalid("Ledger link already exists")
  return execute(command, true)
 }

 private suspend fun execute(command: F05Command, commitment: Boolean): String = db.withTransaction {
  if(command.requestId.isBlank()) invalid("A stable request key is required")
  val fingerprint = canonicalLedgerCommand(command).toString()
  dao.request(command.requestId)?.let { if(it.fingerprint != fingerprint) invalid("Request key already used for different content"); return@withTransaction it.result }
  val state = load()
  if(!commitment) validateCommitmentBoundary(command,state)
  val accountEditReason = apply(command,state)
  state.saveCategoryCatalog()
  validatePlanningRestore(state,dao.accounts())
  snapshot(state) // Reconstruct all balances before any commit, with checked integer arithmetic.
  val auditReason = when (command) {
   is F05Command.Void -> command.reason
   is F05Command.Correct -> command.reason
   is F05Command.Refund -> command.reason
   is F05Command.ResolveDifference -> command.reason
   else -> accountEditReason.orEmpty()
  }
  val subjectId = (command as? F05Command.EditAccount)?.accountId ?: command.requestId
  state.appendRow("audit",obj("requestId" to command.requestId,"action" to command.javaClass.simpleName,"subjectId" to subjectId,"reason" to auditReason))
  dao.saveWorkspace(WorkspaceStateEntity("main",state.toString()))
  dao.insertRequest(WorkspaceRequestEntity(command.requestId,fingerprint,command.requestId))
  command.requestId
 }
 private suspend fun editAccount(c: F05Command.EditAccount, state: JSONObject): String {
  val previous = account(c.accountId, state, false)
  val metadata = meta(state, previous)
  val previousOpeningDate = metadata.text("openingDate")
  val productId = metadata.getString("productId")
  val wasPhysicalCash = previous.kind == "CASH" && productId.startsWith("cash")
  val isPhysicalCash = c.kind == "PHYSICAL_CASH"
  val kind = if (isPhysicalCash) "CASH" else c.kind
  val openingDate = c.openingDate?.let(::date)
  if (c.name.isBlank() || kind !in setOf("CASH", "LIABILITY")) invalid("Completa el nombre y el tipo de cuenta.")
  val customTypeName = c.customTypeName?.trim()
  if (!validCashSubtype(kind, isPhysicalCash, c.cashSubtype, customTypeName)) invalid("Completa un tipo válido para la cuenta.")
  if (c.creditLimitMinor < 0 || kind == "CASH" && c.creditLimitMinor != 0L) invalid("El límite debe ser cero o positivo y corresponde a una tarjeta de crédito.")
  if (c.expectedCurrency.name != previous.currency) invalid("La moneda de la cuenta cambió. Vuelve a abrir el formulario.")
  val currencyChanged = previous.currency != c.currency.name
  val physicalCashChanged = wasPhysicalCash != isPhysicalCash
  val identityChanged = currencyChanged || previous.kind != kind || physicalCashChanged
  val operations = state.list("operations").filter { row ->
   listOf("accountId", "destinationId", "fundingAccountId").any { row.text(it) == previous.id }
  }
  fun accountRows(table: String) = state.list(table).filter { it.text("accountId") == previous.id }
  val accountPostings = if (identityChanged || c.openingDate != previousOpeningDate) {
   dao.postings().filter { it.accountId == previous.id }
  } else emptyList()
  if (identityChanged) {
   if (accountPostings.isNotEmpty() || operations.isNotEmpty()) invalid("Esta cuenta tiene movimientos que dependen de su moneda o tipo original.")
   if (listOf("holds", "reserves", "reconciliations", "imports").any { accountRows(it).isNotEmpty() } ||
    dao.creditAssociations().any { it.accountId == previous.id }) {
    invalid("Esta cuenta tiene autorizaciones, reservas, conciliaciones, importaciones o asociaciones de crédito que dependen de su moneda o tipo original.")
   }
   if (referencesAccount(state.optJSONObject("f06"), previous.id) ||
    referencesAccount(state.optJSONObject("f07"), previous.id)) {
    invalid("Esta cuenta está vinculada a un plan o compromiso que depende de su moneda o tipo original.")
   }
   val productAccounts = dao.accounts().filter { it.id != previous.id && meta(state, it).getString("productId") == productId }
   if (productAccounts.any { physicalCashChanged || it.kind != kind || it.currency == c.currency.name }) {
    invalid("El producto requiere cuentas del mismo tipo y una sola cuenta por moneda.")
   }
  }
  if (c.openingDate != previousOpeningDate) {
   if (openingDate == null) invalid("Completa la fecha de apertura.")
   val postingIds = accountPostings.mapTo(mutableSetOf()) { it.transactionId }
   val transactions = if (postingIds.isEmpty()) emptyList() else dao.transactions().filter { it.id in postingIds }
   val historyDates = operations.flatMap { listOfNotNull(it.text("date"), it.text("bookedDate")) } +
    accountRows("holds").map { it.getString("date") } +
    accountRows("reconciliations").map { it.getString("cutDate") } +
    accountRows("imports").mapNotNull { it.text("openingDate") } +
    transactions.flatMap { listOf(it.businessDate, BusinessTime.date(Instant.parse(it.occurredAt)).toString()) }
   if (historyDates.any { date(it) < openingDate }) invalid("La fecha de apertura debe ser igual o anterior al registro más antiguo de la cuenta.")
  }
  val updatedOpening = if (currencyChanged) {
   if (previous.openingMinor != c.expectedBalanceMinor) invalid("El saldo cambió. Vuelve a abrir el formulario para revisar la conversión.")
   val rate = c.conversionRate?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    ?: invalid("Ingresa un tipo de cambio manual positivo.")
   Money(previous.openingMinor, c.expectedCurrency).convert(c.currency, rate).minor
  } else {
   if (c.conversionRate != null) invalid("El tipo de cambio se aplica solo al cambiar de moneda.")
   previous.openingMinor
  }
  val updatedProduct = when {
   !physicalCashChanged -> productId
   isPhysicalCash -> "cash-${c.requestId}"
   else -> "product-${c.requestId}"
  }
  dao.updateAccount(previous.copy(name = c.name.trim(), currency = c.currency.name, kind = kind,
   openingMinor = updatedOpening, creditLimitMinor = c.creditLimitMinor))
  if (state.find("accounts", previous.id) == null) state.appendRow("accounts", metadata)
  val previousSubtype = metadata.text("cashSubtype")
  val previousCustomTypeName = metadata.text("customTypeName")
  metadata.put("openingDate", c.openingDate ?: JSONObject.NULL).put("productId", updatedProduct)
  metadata.put("cashSubtype", c.cashSubtype?.name).put("customTypeName", customTypeName)
  return obj("previousName" to previous.name,"name" to c.name.trim(),
   "previousKind" to (if (wasPhysicalCash) "PHYSICAL_CASH" else previous.kind),"kind" to c.kind,
   "previousCashSubtype" to previousSubtype,"cashSubtype" to c.cashSubtype?.name,
   "previousCustomTypeName" to previousCustomTypeName,"customTypeName" to customTypeName,
   "previousProductId" to productId,"productId" to updatedProduct,
   "previousCurrency" to previous.currency,"currency" to c.currency.name,"manualRate" to c.conversionRate,
   "previousOpeningMinor" to previous.openingMinor,"openingMinor" to updatedOpening,
   "previousOpeningDate" to previousOpeningDate,"openingDate" to c.openingDate,
   "previousCreditLimitMinor" to previous.creditLimitMinor,"creditLimitMinor" to c.creditLimitMinor).toString()
 }
 private fun referencesAccount(node: Any?, id: String): Boolean = when (node) {
  is JSONObject -> node.keys().asSequence().any { key ->
   val value = node.get(key)
   (key.endsWith("AccountId", ignoreCase = true) && value == id) || referencesAccount(value, id)
  }
  is JSONArray -> (0 until node.length()).any { referencesAccount(node.get(it), id) }
  else -> false
 }
 private suspend fun apply(command: F05Command, state: JSONObject): String? {
  when(val c = command) {
   is F05Command.CreateAccount -> {
    date(c.openingDate)
    if(c.id.isBlank() || c.productId.isBlank() || c.name.isBlank() || c.kind !in setOf("CASH","LIABILITY") || c.creditLimitMinor < 0) invalid("Account name, product, currency and valid type are required")
    val customTypeName = c.customTypeName?.trim()
    if (!validCashSubtype(c.kind, c.productId.startsWith("cash"), c.cashSubtype, customTypeName)) invalid("Completa un tipo válido para la cuenta.")
    val accounts = dao.accounts()
    if (accounts.any { it.id == c.id }) invalid("Account already exists")
    val productAccounts = accounts.filter { meta(state, it).getString("productId") == c.productId }
    if (productAccounts.any { c.productId.startsWith("cash") || it.kind != c.kind || it.currency == c.currency.name }) {
     invalid("El producto requiere cuentas del mismo tipo y una sola cuenta por moneda.")
    }
    dao.insertAccount(AccountEntity(c.id,c.name,c.currency.name,c.kind,c.openingMinor,c.creditLimitMinor))
    val metadata = obj("id" to c.id,"productId" to c.productId,"openingDate" to c.openingDate,"archived" to false)
    metadata.put("cashSubtype", c.cashSubtype?.name).put("customTypeName", customTypeName)
    state.appendRow("accounts",metadata)
   }
   is F05Command.RenameAccount -> {
    val a=account(c.accountId,state,false); if(c.name.isBlank()) invalid("Account name is required")
    dao.renameAccount(a.id,c.name)
   }
   is F05Command.EditAccount -> return editAccount(c,state)
   is F05Command.ArchiveAccount -> {
    val a=account(c.accountId,state,false); val m=meta(state,a)
    if(state.find("accounts",a.id)==null) state.appendRow("accounts",m)
    m.put("archived",c.archived)
   }
   is F05Command.Record -> record(c,state)
   is F05Command.Transfer -> transfer(c,state)
   is F05Command.FundReserve -> fund(state,c.accountId,c.purposeId,c.name,c.amountMinor)
   is F05Command.Redistribute -> {
    positive(c.amountMinor)
    fund(state,c.accountId,c.fromPurposeId,"",-c.amountMinor)
    // The destination allocation replaces the released reserve within the same cash account.
    fund(state,c.accountId,c.toPurposeId,c.toName,c.amountMinor,reallocated=true)
   }
   is F05Command.Void -> reverse(state,c.operationId,c.requestId,c.reason)
   is F05Command.Correct -> {
    if(c.replacement.requestId != c.requestId + ":replacement") invalid("Replacement request key must be correction key plus :replacement")
    reverse(state,c.operationId,c.requestId+":reversal",c.reason)
    record(c.replacement,state,c.operationId)
   }
   is F05Command.Refund -> refund(c,state)
   is F05Command.CreateHold -> {
    val holdAccount=account(c.accountId,state); positive(c.amountMinor); date(c.date)
    meta(state,holdAccount).text("openingDate")?.let { if(date(c.date)<date(it)) invalid("Authorization predates account opening") }
    if(c.merchant.isBlank()) invalid("Merchant is required")
    state.appendRow("holds",obj("id" to c.requestId,"accountId" to c.accountId,"minor" to c.amountMinor,"merchant" to c.merchant,"date" to c.date,"status" to "PENDING"))
   }
   is F05Command.ReleaseHold -> {
    val h=state.find("holds",c.holdId) ?: invalid("Unknown hold")
    if(h.getString("status")!="PENDING") invalid("Hold already resolved")
    h.put("status","RELEASED").put("resolvedDate",LocalDate.now(BusinessTime.zone).toString())
   }
   is F05Command.ConfirmHold -> {
    val h=state.find("holds",c.holdId) ?: invalid("Unknown hold")
    if(h.getString("status")!="PENDING") invalid("Hold already resolved")
    if(date(c.date)<date(h.getString("date"))) invalid("Confirmed charge cannot predate authorization")
    val a=account(h.getString("accountId"),state)
    record(F05Command.Record(c.requestId,a.id,if(a.kind=="LIABILITY") "CREDIT_PURCHASE" else "EXPENSE",h.getLong("minor"),c.date,h.getString("merchant"),c.category,c.notes,bookedDate=c.bookedDate),state)
    h.put("status","CONFIRMED").put("operationId",c.requestId).put("resolvedDate",c.bookedDate ?: c.date)
   }
   is F05Command.Reconcile -> reconcile(c,state)
   is F05Command.ResolveDifference -> resolve(c,state)
   is F05Command.ImportBatch -> import(c,state)
  }
  return null
 }
 private fun validateCommitmentBoundary(command: F05Command, state: JSONObject) {
  val purposes=when(command) {
   is F05Command.FundReserve -> listOf(command.purposeId)
   is F05Command.Redistribute -> listOf(command.fromPurposeId,command.toPurposeId)
   is F05Command.Transfer -> listOfNotNull(command.purposeId)
   is F05Command.ImportBatch -> command.rows.mapNotNull { it.purposeId }
   else -> emptyList()
  }
  if(purposes.any { it.startsWith("saving-goal:") }) invalid("Use the savings workspace for this reserve")
  val changedOperation = when(command) {
   is F05Command.Void -> command.operationId
   is F05Command.Correct -> command.operationId
   is F05Command.Refund -> command.operationId
   else -> null
  }
  if(changedOperation!=null) {
   val commitments=state.commitments()
   if(commitments.f07Rows("savingEvents").any { it.f07Text("operationId")==changedOperation })
    invalid("This movement is linked to a dated savings event")
   if(command is F05Command.Correct && commitments.f07Rows("projectPayments").any { it.getString("operationId")==changedOperation })
    invalid("Reverse the project payment and record the correction through the project")
  }
 }

 internal suspend fun postDebtPayment(state:JSONObject, debt:Debt, payment:DebtPayment, id:String) {
  val cash=account(payment.accountId,state)
  val liability=dao.accounts().firstOrNull { it.id==debtAccount(debt.id) } ?: invalid("Unknown term debt")
  val day=date(payment.date)
  if(cash.kind!="CASH" || cash.currency!=liability.currency || day<date(debt.openingDate) ||
     meta(state,cash).text("openingDate")?.let { day<date(it) }==true) invalid("Choose eligible cash in the native currency")
  val legs=listOf(cash to -payment.totalMinor) +
      listOfNotNull((liability to payment.principalMinor).takeIf { payment.principalMinor>0 })
  val expense=add(payment.interestMinor,payment.chargesMinor)
  post(id,payment.date,"DEBT_PAYMENT",legs,expense)
  state.appendRow("operations",op(id,payment.date,"DEBT_PAYMENT",cash.id,payment.totalMinor,
    debt.name,debt.name,expense=expense,destination=liability.id,destinationMinor=payment.principalMinor))
  invalidateReconciliations(state,id)
 }

 internal suspend fun postDebtVerification(state:JSONObject, debt:Debt, verification:CreditorBalance, delta:Long, id:String) {
  if(delta==0L) return
  val liability=dao.accounts().first { it.id==debtAccount(debt.id) }
  post(id,verification.date,"DEBT_ADJUSTMENT",listOf(liability to delta),0)
  state.appendRow("operations",op(id,verification.date,"DEBT_ADJUSTMENT",liability.id,delta,
    debt.name,debt.name,notes=verification.reference))
 }

 private suspend fun post(id: String, day: String, type: String, legs: List<Pair<AccountEntity,Long>>, expense: Long, bookedDate: String = day) {
  date(day); date(bookedDate)
  if(legs.isEmpty() || legs.any { it.second==0L }) invalid("Nonzero postings required")
  dao.insertTransaction(TransactionEntity(id,date(day).atTime(12,0).atZone(BusinessTime.zone).toInstant().toString(),bookedDate,type,true,null,expense))
  dao.insertPostings(legs.mapIndexed { i,p -> PostingEntity("$id:$i",id,p.first.id,p.second,p.first.currency) })
 }
 private suspend fun recordWithoutPosting(id: String, day: String, type: String, expense: Long, bookedDate: String) {
  dao.insertTransaction(TransactionEntity(id,date(day).atTime(12,0).atZone(BusinessTime.zone).toInstant().toString(),
   bookedDate,type,true,null,expense))
 }
 private fun op(id:String,day:String,type:String,a:String,minor:Long,merchant:String="",category:String="",notes:String="",original:String?=null,splits:List<ExpenseSplit> = emptyList(),expense:Long=0,destination:String?=null,destinationMinor:Long?=null,fee:Long=0,rate:String?=null,rateSource:String?=null,bookedDate:String=day,rateDate:String?=null) = obj("id" to id,"date" to day,"type" to type,"accountId" to a,"amountMinor" to minor,"merchant" to merchant,"category" to category,"notes" to notes,"originalId" to original,"splits" to JSONArray(splits.map { obj("category" to it.category,"minor" to it.minor,"purposeId" to it.purposeId) }),"expenseMinor" to expense,"destinationId" to destination,"destinationMinor" to destinationMinor,"voided" to false,"refundedMinor" to 0,"feeMinor" to fee,"rate" to rate,"rateSource" to rateSource,"bookedDate" to bookedDate,"rateDate" to (rateDate ?: day))
 private suspend fun record(c:F05Command.Record,state:JSONObject,original:String?=null) {
  val a=account(c.accountId,state); if (!c.previouslyDeducted) positive(c.amountMinor) else if (c.amountMinor < 0 || c.type == "INCOME") invalid("Invalid already deducted expense"); val d=date(c.date); val booked=c.bookedDate ?: c.date; date(booked)
  meta(state,a).text("openingDate")?.let { if(d < date(it) || date(booked)<date(it)) invalid("Operation predates account opening") }
  if(c.merchant.isBlank() || c.category.isBlank()) invalid("Merchant/origin and category are required")
  if(c.type !in setOf("INCOME","EXPENSE","CREDIT_PURCHASE")) invalid("Unsupported entry type")
  if((c.type=="CREDIT_PURCHASE") != (a.kind=="LIABILITY")) invalid("Choose an eligible cash or credit account")
  if(c.splits.any { it.minor<=0 || it.category.isBlank() } || (c.splits.isNotEmpty() && exact(c.splits.fold(BigInteger.ZERO) { n,s -> n.add(BigInteger.valueOf(s.minor)) }) != c.amountMinor)) invalid("Splits must equal the exact total")
  if(c.type=="CREDIT_PURCHASE") {
   if(c.splits.any { it.purposeId!=null }) invalid("Credit purchase purpose funding is recorded separately; the deposit reserve is consumed at payment")
   if(c.fundingAccountId!=null) {
    val funding=account(c.fundingAccountId,state)
    if(funding.kind!="CASH" || funding.currency!=a.currency) invalid("Purchase funding must reference a cash account in the same native currency")
    if(c.fundingPurposeId!=null && reserve(state,funding.id,c.fundingPurposeId)==null) invalid("Unknown funding purpose")
   } else if(c.fundingPurposeId!=null) invalid("Funding purpose requires an explicit deposit account")
  } else if(c.fundingAccountId!=null || c.fundingPurposeId!=null) invalid("Funding reference is only valid for a credit purchase")
  val expense=if(c.type=="INCOME") 0L else c.amountMinor
  if (c.previouslyDeducted) {
   if (c.splits.any { it.purposeId != null } || c.fundingAccountId != null) invalid("Un gasto ya descontado no consume reservas")
   recordWithoutPosting(c.requestId,c.date,c.type,expense,booked)
  } else post(c.requestId,c.date,c.type,listOf(a to if(c.type=="INCOME") c.amountMinor else -c.amountMinor),expense,booked)
  val effects=JSONArray()
  c.splits.filter { it.purposeId!=null }.forEach {
   val before=reserve(state,a.id,it.purposeId!!)?.getLong("minor") ?: invalid("Unknown purpose")
   spendReserve(state,a.id,it.purposeId!!,if(c.type=="INCOME") -it.minor else it.minor)
   effects.put(obj("accountId" to a.id,"purposeId" to it.purposeId,"minor" to sub(before,reserve(state,a.id,it.purposeId!!)!!.getLong("minor"))))
  }
  state.appendRow("operations",op(c.requestId,c.date,c.type,a.id,c.amountMinor,c.merchant,c.category,c.notes,original,c.splits,expense,bookedDate=booked).put("reserveEffects",effects).put("fundingAccountId",c.fundingAccountId ?: JSONObject.NULL).put("fundingPurposeId",c.fundingPurposeId ?: JSONObject.NULL).put("settledMinor",0).put("previouslyDeducted",c.previouslyDeducted))
  invalidateReconciliations(state,c.requestId)
 }
 private suspend fun transfer(c:F05Command.Transfer,state:JSONObject) {
  val a=account(c.sourceId,state); val b=account(c.destinationId,state)
  val booked=c.bookedDate ?: c.date; date(booked)
  listOf(a,b).forEach { acc -> meta(state,acc).text("openingDate")?.let { if(date(c.date)<date(it) || date(booked)<date(it)) invalid("Transfer predates account opening") } }
  positive(c.amountMinor); positive(c.destinationMinor); date(c.date)
  if(a.id==b.id || a.kind!="CASH" || c.feeMinor<0) invalid("Choose distinct source cash and destination accounts")
  if(a.currency==b.currency && c.amountMinor!=c.destinationMinor) invalid("Same currency transfers must preserve principal")
  if(a.currency!=b.currency) {
   if(c.rate.isNullOrBlank() || c.rateSource.isNullOrBlank()) invalid("FX requires both actual amounts and an explicit rate source snapshot")
   date(c.rateDate ?: c.date)
   val rate=try { BigDecimal(c.rate) } catch (_: Exception) { invalid("Invalid rate") }
   if(rate.signum()<=0) invalid("Rate must be positive")
   Money(c.amountMinor,Currency.valueOf(a.currency)).convert(Currency.valueOf(b.currency),rate) // validate range; actual bank amounts are authoritative.
  }
  add(c.amountMinor,c.feeMinor)
  val type=if(b.kind=="LIABILITY" && a.currency!=b.currency) "FX_CREDIT_PAYMENT" else if(b.kind=="LIABILITY") "CREDIT_PAYMENT" else if(a.currency!=b.currency) "FX_TRANSFER" else "TRANSFER"
  post(c.requestId,c.date,type,listOf(a to -c.amountMinor,b to c.destinationMinor),0,booked)
  if(c.feeMinor>0) post(c.requestId+":fee",c.date,"EXPENSE",listOf(a to -c.feeMinor),c.feeMinor,booked)
  val reserveEffects=JSONArray()
  val allocations=JSONArray()
  if(b.kind=="LIABILITY") {
   if(c.creditAllocations.isEmpty() || c.creditAllocations.map { it.purchaseId }.distinct().size!=c.creditAllocations.size) invalid("Credit payment requires explicit distinct purchase allocations")
   if(exact(c.creditAllocations.fold(BigInteger.ZERO) { n,e -> n.add(BigInteger.valueOf(e.minor)) })!=c.destinationMinor) invalid("Purchase allocations must equal card principal settled")
   var sourceTotal=BigInteger.ZERO
   c.creditAllocations.forEach { e ->
    positive(e.minor)
    val opening=e.purchaseId=="opening:${b.id}"
    val purchase=if(opening) null else state.find("operations",e.purchaseId) ?: invalid("Unknown credit purchase")
    val outstanding=if(opening) {
     if(b.openingMinor>=0) invalid("This card has no opening principal debt")
     val allocated=state.list("operations").filter { !it.getBoolean("voided") }.flatMap { o -> o.optJSONArray("creditAllocations")?.let { ar -> (0 until ar.length()).map { ar.getJSONObject(it) } } ?: emptyList() }.filter { it.getString("purchaseId")==e.purchaseId }.fold(BigInteger.ZERO) { n,item -> n.add(BigInteger.valueOf(item.getLong("minor"))) }
     exact(BigInteger.valueOf(b.openingMinor).negate().subtract(allocated))
    } else {
     if(purchase!!.getString("type")!="CREDIT_PURCHASE" || purchase.getString("accountId")!=b.id || purchase.getBoolean("voided") || purchase.optBoolean("previouslyDeducted")) invalid("Payment allocation must target an active purchase on this card ledger")
     sub(sub(purchase.getLong("amountMinor"),purchase.getLong("refundedMinor")),purchase.optLong("settledMinor"))
    }
    if(e.minor>outstanding) invalid("Allocation exceeds outstanding principal")
    val source=e.sourceMinor ?: if(a.currency==b.currency) e.minor else invalid("FX allocation requires actual source amount")
    positive(source); sourceTotal=sourceTotal.add(BigInteger.valueOf(source))
    e.purposeId?.let { purpose ->
     val r=reserve(state,a.id,purpose) ?: invalid("Selected source has no such financed purpose")
     val consumed=minOf(source,r.getLong("minor")); r.put("minor",sub(r.getLong("minor"),consumed))
     reserveEffects.put(obj("accountId" to a.id,"purposeId" to purpose,"minor" to consumed))
    }
    purchase?.put("settledMinor",add(purchase.optLong("settledMinor"),e.minor))
    allocations.put(obj("purchaseId" to e.purchaseId,"minor" to e.minor,"purposeId" to e.purposeId,"sourceMinor" to source))
   }
   if(sourceTotal!=BigInteger.valueOf(c.amountMinor)) invalid("Allocation source amounts must equal actual deposit principal")
   if(c.purposeId!=null) invalid("Specify payment purpose per purchase allocation")
  } else if(c.creditAllocations.isNotEmpty()) invalid("Purchase allocations are only valid for credit payments")
  c.purposeId?.let {
   if(a.currency!=b.currency || b.kind!="CASH") invalid("Reserve movement requires same-currency cash transfer")
   val r=reserve(state,a.id,it) ?: invalid("Unknown funded purpose")
   if(r.getLong("minor")<c.amountMinor) invalid("Insufficient purpose reserve")
   r.put("minor",sub(r.getLong("minor"),c.amountMinor)); changeReserve(state,b.id,it,r.getString("name"),c.destinationMinor)
   reserveEffects.put(obj("accountId" to a.id,"purposeId" to it,"minor" to c.amountMinor)); reserveEffects.put(obj("accountId" to b.id,"purposeId" to it,"minor" to -c.destinationMinor))
  }
  state.appendRow("operations",op(c.requestId,c.date,type,a.id,c.amountMinor,notes=c.notes,destination=b.id,destinationMinor=c.destinationMinor,fee=c.feeMinor,rate=c.rate,rateSource=c.rateSource,bookedDate=booked,rateDate=c.rateDate).put("reserveEffects",reserveEffects).put("creditAllocations",allocations))
  if(c.feeMinor>0) state.appendRow("operations",op(c.requestId+":fee",c.date,"EXPENSE",a.id,c.feeMinor,"Transfer fee","Fees",original=c.requestId,expense=c.feeMinor,bookedDate=booked).put("feeParent",c.requestId))
  invalidateReconciliations(state,c.requestId)
 }
 private fun reserve(state:JSONObject,a:String,p:String)=state.list("reserves").find { it.getString("accountId")==a && it.getString("purposeId")==p }
 private fun changeReserve(state:JSONObject,a:String,p:String,name:String,delta:Long) {
  val r=reserve(state,a,p)
  if(r==null) { if(name.isBlank() || p.isBlank()) invalid("Purpose name and identity required"); state.appendRow("reserves",obj("id" to "$a:$p","accountId" to a,"purposeId" to p,"name" to name,"minor" to delta)) }
  else r.put("minor",add(r.getLong("minor"),delta))
 }
 private suspend fun fund(state:JSONObject,a:String,p:String,name:String,delta:Long,reallocated:Boolean=false) {
  val account=account(a,state); if(account.kind!="CASH" || delta==0L || p.isBlank()) invalid("Reserve funding requires a cash account and nonzero amount")
  val detail=snapshot(state).accounts.first { it.id==a }
  if(delta>0 && !reallocated && detail.unassignedMinor<delta) invalid("Insufficient unassigned money for a new reserve")
  val current=reserve(state,a,p)?.getLong("minor") ?: 0
  if(add(current,delta)<0) invalid("Cannot release more than the funded reserve")
  changeReserve(state,a,p,name,delta)
 }
 private fun spendReserve(state:JSONObject,a:String,p:String,amount:Long) {
  val r=reserve(state,a,p) ?: invalid("Unknown purpose")
  // Real expense is never rejected for a planning deficit. Consume only the financed part.
  val consumed=if(amount>0) minOf(amount,r.getLong("minor")) else amount
  r.put("minor",sub(r.getLong("minor"),consumed))
 }
 private suspend fun invalidateReconciliations(state:JSONObject,operationId:String) {
  val o=state.find("operations",operationId) ?: return
  state.list("reconciliations").filter { r ->
   (r.getString("accountId")==o.getString("accountId") || r.getString("accountId")==o.text("destinationId")) && date(r.getString("cutDate"))>=date(o.optString("bookedDate",o.getString("date")))
  }.forEach { it.put("status","INVALIDATED") }
 }
 internal suspend fun reverseDebtPayment(state:JSONObject, id:String, key:String, reason:String) =
  reverse(state,id,key,reason,allowDebt=true)

 private suspend fun reverse(state:JSONObject,id:String,key:String,reason:String,allowDebt:Boolean=false) {
  if(reason.isBlank()) invalid("A correction reason is required")
  val o=state.find("operations",id) ?: invalid("Unknown operation")
  if(o.optLong("settledMinor")>0) invalid("Reverse linked payments before correcting the purchase")
  if(o.has("feeParent")) invalid("Correct the parent transfer to change its separate fee")
  if(o.getBoolean("voided") || o.getLong("refundedMinor")>0) invalid("Already voided or refunded; cannot reverse")
  if(o.getString("type") !in setOf("EXPENSE","INCOME","CREDIT_PURCHASE","TRANSFER","FX_TRANSFER","CREDIT_PAYMENT","FX_CREDIT_PAYMENT") &&
     !(allowDebt && o.getString("type")=="DEBT_PAYMENT")) invalid("This operation cannot be reversed")
  val p=dao.postings().filter { it.transactionId==id || it.transactionId=="$id:fee" }
  val accounts=dao.accounts().associateBy { it.id }
  if (o.optBoolean("previouslyDeducted"))
   recordWithoutPosting(key,o.getString("date"),"REVERSAL",-o.getLong("expenseMinor"),o.optString("bookedDate",o.getString("date")))
  else post(key,o.getString("date"),"REVERSAL",p.map { accounts.getValue(it.accountId) to sub(0,it.minor) },-add(o.getLong("expenseMinor"),o.optLong("feeMinor")),o.optString("bookedDate",o.getString("date")))
  val effects=o.optJSONArray("reserveEffects") ?: JSONArray()
  (0 until effects.length()).forEach { i -> val e=effects.getJSONObject(i)
   val r=reserve(state,e.getString("accountId"),e.getString("purposeId")) ?: invalid("Missing reserve history")
   val restored=add(r.getLong("minor"),e.getLong("minor")); if(restored<0) invalid("Transferred reserve has since been used; redistribute before reversal")
   r.put("minor",restored)
  }
  val allocations=o.optJSONArray("creditAllocations") ?: JSONArray()
  (0 until allocations.length()).forEach { i -> val e=allocations.getJSONObject(i)
   val purchase=state.find("operations",e.getString("purchaseId"))
   if(purchase==null && !e.getString("purchaseId").startsWith("opening:")) invalid("Missing linked purchase")
   purchase?.put("settledMinor",sub(purchase!!.optLong("settledMinor"),e.getLong("minor")))
  }
  o.put("voided",true)
  state.find("operations","$id:fee")?.put("voided",true)
  state.appendRow("operations",op(key,o.getString("date"),"REVERSAL",o.getString("accountId"),o.getLong("amountMinor"),notes=reason,original=id,expense=-add(o.getLong("expenseMinor"),o.optLong("feeMinor")),destination=o.text("destinationId"),destinationMinor=o.longOrNull("destinationMinor"),bookedDate=o.optString("bookedDate",o.getString("date"))).put("previouslyDeducted",o.optBoolean("previouslyDeducted")))
  invalidateReconciliations(state,id)
 }
 private suspend fun refund(c:F05Command.Refund,state:JSONObject) {
  positive(c.amountMinor); date(c.date); val booked=c.bookedDate ?: c.date; date(booked); if(c.reason.isBlank()) invalid("Refund reason is required")
  val o=state.find("operations",c.operationId) ?: invalid("Unknown purchase")
  if(o.has("feeParent")) invalid("Fee refund requires a separate audited bank correction")
  if(o.getString("type") !in setOf("EXPENSE","CREDIT_PURCHASE") || o.getBoolean("voided") || o.optBoolean("previouslyDeducted")) invalid("Refund requires an active purchase posted to the account")
  if(date(c.date)<date(o.getString("date"))) invalid("Refund cannot predate purchase")
  val refunded=add(o.getLong("refundedMinor"),c.amountMinor)
  if(refunded>o.getLong("amountMinor")) invalid("Refunds cannot exceed original purchase")
  val a=account(o.getString("accountId"),state,false)
  meta(state,a).text("openingDate")?.let { if(date(c.date)<date(it) || date(booked)<date(it)) invalid("Refund predates account opening") }
  post(c.requestId,c.date,"REFUND",listOf(a to c.amountMinor),-c.amountMinor,booked)
  // Cumulative proportional allocation prevents repeated partial refunds from manufacturing reserve money.
  val effects=o.optJSONArray("reserveEffects") ?: JSONArray()
  (0 until if(a.kind=="LIABILITY") 0 else effects.length()).forEach { i -> val s=effects.getJSONObject(i)
   val total=BigInteger.valueOf(o.getLong("amountMinor")); val split=BigInteger.valueOf(s.getLong("minor"))
   val before=split.multiply(BigInteger.valueOf(o.getLong("refundedMinor"))).divide(total)
   val after=split.multiply(BigInteger.valueOf(refunded)).divide(total)
   spendReserve(state,s.getString("accountId"),s.getString("purposeId"),-exact(after.subtract(before)))
  }
  o.put("refundedMinor",refunded)
  state.appendRow("operations",op(c.requestId,c.date,"REFUND",a.id,c.amountMinor,notes=c.reason,original=c.operationId,expense=-c.amountMinor,bookedDate=booked))
  invalidateReconciliations(state,c.operationId)
 }
 private suspend fun reconcile(c:F05Command.Reconcile,state:JSONObject) {
  val a=account(c.accountId,state,false); val cut=date(c.cutDate)
  meta(state,a).text("openingDate")?.let { if(cut<date(it)) invalid("Cut predates account opening") }
  if(c.balanceType !in setOf("BOOKED","AVAILABLE")) invalid("Choose booked or available balance explicitly")
  val transactions=dao.transactions().filter { it.confirmed && date(it.businessDate)<=cut }.map { it.id }.toSet()
  val recorded=exact(dao.postings().filter { it.accountId==a.id && it.transactionId in transactions }.fold(BigInteger.valueOf(a.openingMinor)) { n,p -> n.add(BigInteger.valueOf(p.minor)) })
  val eligible=state.list("operations").filter { (it.getString("accountId")==a.id || it.text("destinationId")==a.id) && date(it.optString("bookedDate",it.getString("date")))<=cut }.map { it.getString("id") }.toSet()
  if(c.reviewedIds.distinct().size!=c.reviewedIds.size || c.reviewedIds.any { it !in eligible }) invalid("Reviewed records must belong to this account and cut")
  val pending=exact(state.list("holds").filter { it.getString("accountId")==a.id && (it.getString("status")=="PENDING" || (it.has("resolvedDate") && date(it.getString("resolvedDate"))>cut)) && date(it.getString("date"))<=cut }.fold(BigInteger.ZERO) { n,h -> n.add(BigInteger.valueOf(h.getLong("minor"))) })
  val comparable=if(c.balanceType=="AVAILABLE") sub(if(a.kind=="LIABILITY") add(a.creditLimitMinor,recorded) else recorded,pending) else recorded
  val diff=sub(c.observedMinor,comparable)
  val status=if(!c.holdsKnown && c.balanceType=="AVAILABLE") "PROVISIONAL" else if(diff==0L) "MATCHED" else "DIFFERENCE"
  state.appendRow("reconciliations",obj("id" to c.requestId,"accountId" to a.id,"cutDate" to c.cutDate,"balanceType" to c.balanceType,"observedMinor" to c.observedMinor,"recordedMinor" to comparable,"differenceMinor" to diff,"reviewedIds" to JSONArray(c.reviewedIds),"status" to status,"reason" to ""))
 }
 private suspend fun resolve(c:F05Command.ResolveDifference,state:JSONObject) {
  if(c.reason.isBlank()) invalid("An adjustment reason is required")
  val r=state.find("reconciliations",c.reconciliationId) ?: invalid("Unknown reconciliation")
  if(r.getString("status")!="DIFFERENCE" || r.getString("balanceType")!="BOOKED") invalid("Only an unresolved booked difference can be adjusted")
  val a=account(r.getString("accountId"),state,false); val delta=r.getLong("differenceMinor")
  if(delta==0L) invalid("No difference to adjust")
  post(c.requestId,r.getString("cutDate"),"ADJUSTMENT",listOf(a to delta),0)
  state.appendRow("operations",op(c.requestId,r.getString("cutDate"),"ADJUSTMENT",a.id,delta,category="Reconciliation adjustment",notes=c.reason,original=c.reconciliationId))
  r.put("status","ADJUSTED").put("reason",c.reason).put("adjustmentId",c.requestId)
 }
 suspend fun previewImport(rows:List<ImportRow>): List<ImportIssue> = db.withTransaction {
  val accounts=dao.accounts(); val state=load(); val postings=dao.postings(); val issues=mutableListOf<ImportIssue>()
  if(rows.isEmpty()) issues.add(ImportIssue("","Import has no rows"))
  rows.forEach { r ->
   if(r.rowId.isBlank() || rows.count { it.rowId==r.rowId }>1) issues.add(ImportIssue(r.rowId,"Duplicate or missing row identity"))
   if(r.accountId==null || accounts.none { it.id==r.accountId }) issues.add(ImportIssue(r.rowId,"Choose a known account; aliases are not inferred"))
   if(!r.confirmed) issues.add(ImportIssue(r.rowId,"Explicit confirmation is required"))
   if(r.confirmedOpeningMinor==null || r.openingDate==null) issues.add(ImportIssue(r.rowId,"Opening amount and date remain unresolved"))
   else try { date(r.openingDate) } catch (_: Exception) { issues.add(ImportIssue(r.rowId,"Invalid opening date")) }
   if(r.confirmedReserveMinor!=null && (r.confirmedReserveMinor<=0 || r.purposeId.isNullOrBlank() || r.purposeName.isNullOrBlank())) issues.add(ImportIssue(r.rowId,"Confirm a positive reserve, purpose and name"))
  }
  rows.filter { it.accountId!=null }.groupBy { it.accountId }.forEach { (id,group) ->
   val account=accounts.find { it.id==id }
   if(group.map { it.confirmedOpeningMinor to it.openingDate }.distinct().size>1) group.forEach { issues.add(ImportIssue(it.rowId,"Conflicting confirmed opening for the same account alias")) }
   if(postings.any { it.accountId==id } || state.list("imports").any { it.getString("accountId")==id }) group.forEach { issues.add(ImportIssue(it.rowId,"Account already has posted or imported history")) }
   if(account!=null && account.openingMinor!=0L && group.any { it.confirmedOpeningMinor!=account.openingMinor }) group.forEach { issues.add(ImportIssue(it.rowId,"Existing opening differs; use an audited adjustment")) }
   if(group.any { row -> state.list("imports").any { it.getString("rowId")==row.rowId } }) group.forEach { issues.add(ImportIssue(it.rowId,"Row was already imported")) }
   val total=group.fold(BigInteger.ZERO) { n,row -> n.add(BigInteger.valueOf(row.confirmedReserveMinor ?: 0)) }
   val opening=group.first().confirmedOpeningMinor
   if(opening!=null && total>BigInteger.valueOf(opening)) group.forEach { issues.add(ImportIssue(it.rowId,"Confirmed reserves exceed confirmed opening money")) }
  }
  issues
 }
 private suspend fun import(c:F05Command.ImportBatch,state:JSONObject) {
  val issues=previewImport(c.rows); if(issues.isNotEmpty()) invalid(issues.joinToString("; ") { it.message })
  c.rows.groupBy { it.accountId!! }.forEach { (id,rows) ->
   val first=rows.first(); val a=account(id,state)
   dao.setOpening(a.id,first.confirmedOpeningMinor!!)
   val m=meta(state,a); if(state.find("accounts",a.id)==null) state.appendRow("accounts",m); m.put("openingDate",first.openingDate)
   rows.forEach { r ->
    r.confirmedReserveMinor?.let { fund(state,a.id,r.purposeId!!,r.purposeName!!,it) }
    state.appendRow("imports",obj("id" to "${c.requestId}:${r.rowId}","rowId" to r.rowId,"accountId" to a.id,"rawAccount" to r.rawAccount,"rawContent" to r.rawContent,"rawNotes" to r.rawNotes,"editedContent" to r.editedContent,"editedNotes" to r.editedNotes,"confirmedOpeningMinor" to r.confirmedOpeningMinor,"openingDate" to r.openingDate))
   }
  }
 }
 companion object {
  const val EMPTY_STATE = "{\"accounts\":[],\"operations\":[],\"reserves\":[],\"holds\":[],\"reconciliations\":[],\"audit\":[],\"imports\":[]}"
  /** Validate the complete extension before replacing even one existing row. */
  fun validateRestore(payload:String, requests:List<WorkspaceRequestEntity>, accounts:List<AccountEntity>, transactions:List<TransactionEntity>, postings:List<PostingEntity>) {
   fun fail():Nothing = throw DomainFailure.Invalid("Invalid workspace fixture graph")
   fun JSONArray.rows() = (0 until length()).map { getJSONObject(it) }
   fun JSONObject.integer(key:String):Long { val n=get(key); if(n !is Int && n !is Long) fail(); return (n as Number).toLong() }
   val state=JSONObject(payload)
   state.validateCategoryCatalog()
   validatePlanningRestore(state,accounts)
   validateCommitmentRestore(state,requests,accounts,transactions,postings)
   val accountMap=accounts.associateBy { it.id }; val tx=transactions.associateBy { it.id }
   val postingsByTransaction=postings.groupBy { it.transactionId }
   val names=listOf("accounts","operations","reserves","holds","reconciliations","audit","imports")
   names.forEach { key ->
    val rows=state.getJSONArray(key).rows()
    if(key !in setOf("audit")) {
     val ids=rows.map { it.getString("id") }
     if(ids.any { it.isBlank() } || ids.distinct().size!=ids.size) fail()
    }
   }
   val operations=state.getJSONArray("operations").rows(); val operationIds=operations.map { it.getString("id") }.toSet()
   val reconciliations=state.getJSONArray("reconciliations").rows().associateBy { it.getString("id") }
   val refundsByOriginal=operations.filter { it.getString("type")=="REFUND" }.groupBy { it.getString("originalId") }
   state.getJSONArray("accounts").rows().forEach { m ->
    val account = accountMap[m.getString("id")] ?: fail()
    val productId = m.getString("productId")
    if (productId.isBlank()) fail()
    val subtype = if (m.isNull("cashSubtype")) null else
     CashAccountSubtype.entries.find { it.name == m.get("cashSubtype") } ?: fail()
    val customTypeName = if (m.isNull("customTypeName")) null else m.get("customTypeName") as? String ?: fail()
    if (!validCashSubtype(account.kind, productId.startsWith("cash"), subtype, customTypeName)) fail()
    m.getBoolean("archived"); if(!m.isNull("openingDate")) LocalDate.parse(m.getString("openingDate"))
   }
   operations.forEach { o ->
    val id=o.getString("id"); val a=accountMap[o.getString("accountId")] ?: fail()
    val t=tx[id] ?: fail(); if(!t.confirmed || t.type!=o.getString("type") || t.businessDate!=o.optString("bookedDate",o.getString("date")) || t.expenseMinor!=o.integer("expenseMinor")) fail()
    if(BusinessTime.date(Instant.parse(t.occurredAt))!=LocalDate.parse(o.getString("date"))) fail()
    LocalDate.parse(o.optString("bookedDate",o.getString("date"))); LocalDate.parse(o.getString("date")); o.getBoolean("voided")
    val amount=o.integer("amountMinor"); val refunded=o.integer("refundedMinor"); val fee=o.integer("feeMinor")
    if(refunded<0 || (refunded>0 && refunded>amount) || fee<0) fail()
    listOf("merchant","category","notes").forEach { o.getString(it) }
    if(!o.isNull("destinationId") && o.getString("destinationId") !in accountMap) fail()
    if(!o.isNull("originalId") && o.getString("originalId") !in operationIds && o.getString("originalId") !in reconciliations) fail()
    val splits=o.getJSONArray("splits").rows()
    if(splits.any { it.integer("minor")<=0 || it.getString("category").isBlank() }) fail()
    if(splits.isNotEmpty() && splits.fold(BigInteger.ZERO) { n,s -> n.add(BigInteger.valueOf(s.integer("minor"))) }!=BigInteger.valueOf(amount)) fail()
    val legs=postingsByTransaction[id].orEmpty()
    if (o.optBoolean("previouslyDeducted") != legs.isEmpty()) fail()
    if(t.type in setOf("FX_TRANSFER","FX_CREDIT_PAYMENT")) {
     if(legs.size!=2 || legs.map { it.currency }.distinct().size!=2 || legs.none { it.minor<0 && it.accountId==a.id } || o.getString("rateSource").isBlank() || BigDecimal(o.getString("rate")).signum()<=0) fail()
    }
   }
   val byId=operations.associateBy { it.getString("id") }
   val settled=mutableMapOf<String,BigInteger>()
   operations.forEach { o ->
    val id=o.getString("id");val type=o.getString("type");val amount=o.integer("amountMinor")
    val legs=postingsByTransaction[id].orEmpty()
    fun aggregate(ps:List<PostingEntity>)=ps.groupBy { it.accountId }.mapValues { (_,v) -> v.fold(BigInteger.ZERO) { n,p -> n.add(BigInteger.valueOf(p.minor)) } }.filterValues { it!=BigInteger.ZERO }
    when(type) {
     "INCOME","EXPENSE","CREDIT_PURCHASE" -> {
      if (o.optBoolean("previouslyDeducted")) {
       if (type == "INCOME" || amount < 0 || o.integer("expenseMinor") != amount ||
        o.integer("refundedMinor") != 0L || o.optLong("settledMinor") != 0L ||
        o.getJSONArray("splits").rows().any { !it.isNull("purposeId") } ||
        !o.isNull("fundingAccountId") || !o.isNull("fundingPurposeId")) fail()
       return@forEach
      }
      val posting=legs.singleOrNull() ?: fail()
      val income=type=="INCOME"
      if(amount<=0 || posting.accountId!=o.getString("accountId") ||
       posting.minor!=(if(income) amount else -amount) || o.integer("expenseMinor")!=(if(income) 0L else amount)) fail()
     }
     "TRANSFER","FX_TRANSFER","CREDIT_PAYMENT","FX_CREDIT_PAYMENT" -> {
      val source=accountMap[o.getString("accountId")] ?: fail();val destination=accountMap[o.getString("destinationId")] ?: fail()
      if(source.kind!="CASH" || amount<=0 || legs.size!=2 || legs.none { it.accountId==source.id && it.minor==-amount } || legs.none { it.accountId==destination.id && it.minor==o.integer("destinationMinor") }) fail()
      val payment=type in setOf("CREDIT_PAYMENT","FX_CREDIT_PAYMENT")
      if((destination.kind=="LIABILITY")!=payment) fail()
      val allocations=o.optJSONArray("creditAllocations")?.rows() ?: emptyList()
      if(payment) {
       if(allocations.isEmpty() || allocations.map { it.getString("purchaseId") }.distinct().size!=allocations.size) fail()
       if(allocations.fold(BigInteger.ZERO) { n,e -> n.add(BigInteger.valueOf(e.integer("minor"))) }!=BigInteger.valueOf(o.integer("destinationMinor"))) fail()
       if(allocations.fold(BigInteger.ZERO) { n,e -> n.add(BigInteger.valueOf(e.integer("sourceMinor"))) }!=BigInteger.valueOf(amount)) fail()
       allocations.forEach { e ->
        val key=e.getString("purchaseId"); val p=byId[key]
        if(key=="opening:${destination.id}") {
         if(destination.openingMinor>=0) fail()
        } else if(p==null || p.getString("type")!="CREDIT_PURCHASE" || p.getString("accountId")!=destination.id || p.optBoolean("previouslyDeducted")) fail()
        if(e.integer("minor")<=0 || e.integer("sourceMinor")<=0) fail()
        if(!o.getBoolean("voided")) settled[key]=(settled[key] ?: BigInteger.ZERO).add(BigInteger.valueOf(e.integer("minor")))
       }
      } else if(allocations.isNotEmpty()) fail()
     }
     "REFUND" -> {
      val original=byId[o.getString("originalId")] ?: fail()
      if(original.getString("type") !in setOf("EXPENSE","CREDIT_PURCHASE") || original.optBoolean("previouslyDeducted") || legs.size!=1 || amount<=0 || legs.single().accountId!=original.getString("accountId") || legs.single().minor!=amount || o.integer("expenseMinor")!=-amount) fail()
     }
     "REVERSAL" -> {
      val original=byId[o.getString("originalId")] ?: fail()
      val originalLegs=postingsByTransaction[original.getString("id")].orEmpty() +
          postingsByTransaction[original.getString("id")+":fee"].orEmpty()
      if(!original.getBoolean("voided") || o.optBoolean("previouslyDeducted") != original.optBoolean("previouslyDeducted") || o.integer("expenseMinor") != -Math.addExact(original.integer("expenseMinor"), original.optLong("feeMinor")) || amount!=original.integer("amountMinor") || aggregate(legs)!=aggregate(originalLegs).mapValues { it.value.negate() }) fail()
     }
     "ADJUSTMENT" -> {
      val rec=reconciliations[o.getString("originalId")] ?: fail()
      if(legs.size!=1 || legs.single().minor!=amount || rec.integer("differenceMinor")!=amount || o.getString("notes").isBlank() || o.integer("expenseMinor")!=0L) fail()
     }
    }
    val effects=o.optJSONArray("reserveEffects")?.rows() ?: emptyList()
    effects.forEach { e -> if(accountMap[e.getString("accountId")]?.kind!="CASH" || e.getString("purposeId").isBlank()) fail(); e.integer("minor") }
   }
   settled.filterKeys { it.startsWith("opening:") }.forEach { (key,total) ->
    val card=accountMap[key.removePrefix("opening:")] ?: fail()
    if(card.kind!="LIABILITY" || total>BigInteger.valueOf(card.openingMinor).negate()) fail()
   }
   operations.forEach { o ->
    if(o.getString("type")=="CREDIT_PURCHASE") {
     val expected=settled[o.getString("id")] ?: BigInteger.ZERO
     if(expected!=BigInteger.valueOf(o.optLong("settledMinor")) || expected>BigInteger.valueOf(o.integer("amountMinor"))) fail()
     if(!o.isNull("fundingAccountId") && o.has("fundingAccountId")) {
      val funding=accountMap[o.getString("fundingAccountId")] ?: fail()
      if(funding.kind!="CASH" || funding.currency!=accountMap.getValue(o.getString("accountId")).currency) fail()
     }
    }
    val refunds=refundsByOriginal[o.getString("id")].orEmpty().fold(BigInteger.ZERO) { n,r -> n.add(BigInteger.valueOf(r.integer("amountMinor"))) }
    if(refunds!=BigInteger.valueOf(o.integer("refundedMinor"))) fail()
   }
   transactions.filter { it.type in setOf("FX_TRANSFER","FX_CREDIT_PAYMENT","REFUND","REVERSAL","ADJUSTMENT","DEBT_PAYMENT","DEBT_ADJUSTMENT") }.forEach { if(it.id !in operationIds) fail() }
   val reserves=state.getJSONArray("reserves").rows()
   if(reserves.map { it.getString("accountId") to it.getString("purposeId") }.distinct().size!=reserves.size) fail()
   reserves.forEach { r -> if(accountMap[r.getString("accountId")]?.kind!="CASH" || r.getString("purposeId").isBlank() || r.getString("name").isBlank() || r.integer("minor")<0) fail() }
   state.getJSONArray("holds").rows().forEach { h ->
    if(h.getString("accountId") !in accountMap || h.integer("minor")<=0 || h.getString("status") !in setOf("PENDING","RELEASED","CONFIRMED")) fail()
    LocalDate.parse(h.getString("date")); h.getString("merchant")
    if(h.getString("status")=="CONFIRMED" && h.getString("operationId") !in operationIds) fail()
   }
   state.getJSONArray("reconciliations").rows().forEach { r ->
    if(r.getString("accountId") !in accountMap || r.getString("balanceType") !in setOf("BOOKED","AVAILABLE") || r.getString("status") !in setOf("PROVISIONAL","MATCHED","DIFFERENCE","ADJUSTED","INVALIDATED")) fail()
    LocalDate.parse(r.getString("cutDate")); r.getString("reason")
    if(BigInteger.valueOf(r.integer("observedMinor")).subtract(BigInteger.valueOf(r.integer("recordedMinor")))!=BigInteger.valueOf(r.integer("differenceMinor"))) fail()
    val reviewed=r.getJSONArray("reviewedIds"); val ids=(0 until reviewed.length()).map { reviewed.getString(it) }
    if(ids.distinct().size!=ids.size || ids.any { it !in operationIds }) fail()
    if(r.getString("status")=="ADJUSTED" && r.getString("adjustmentId") !in operationIds) fail()
   }
   state.getJSONArray("imports").rows().forEach { r ->
    if(r.getString("accountId") !in accountMap || r.getString("rowId").isBlank()) fail()
    r.integer("confirmedOpeningMinor"); LocalDate.parse(r.getString("openingDate"))
    listOf("rawAccount","rawContent","rawNotes").forEach { r.getString(it) }
   }
   val requestIds=requests.mapTo(mutableSetOf()) { it.id }
   if(requestIds.any { it.isBlank() } || requestIds.size!=requests.size || requests.any { it.result!=it.id || JSONObject(it.fingerprint).getString("requestId")!=it.id }) fail()
   state.getJSONArray("audit").rows().forEach { r -> if(r.getString("requestId") !in requestIds || r.getString("action").isBlank()) fail(); r.getString("subjectId"); r.getString("reason") }
  }
 }
}

private fun validCashSubtype(kind: String, physicalCash: Boolean, subtype: CashAccountSubtype?, customTypeName: String?): Boolean =
 (subtype == null || kind == "CASH" && !physicalCash) &&
  (if (subtype == CashAccountSubtype.OTHER) !customTypeName.isNullOrBlank() else customTypeName == null)

internal fun canonicalLedgerCommand(value: Any?): Any = when(value) {
  null -> JSONObject.NULL
  is String, is Number, is Boolean -> value
  is Enum<*> -> value.name
  is Iterable<*> -> JSONArray(value.map { canonicalLedgerCommand(it) })
  else -> JSONObject().apply {
   put("_type", value.javaClass.simpleName)
   value.javaClass.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.sortedBy { it.name }.forEach { field ->
    field.isAccessible = true
    val fieldValue = field.get(value)
    when (field.name) {
     "previouslyDeducted" -> if (fieldValue == true) put(field.name, canonicalLedgerCommand(fieldValue)) else Unit
     "cashSubtype", "customTypeName" -> if (fieldValue != null) put(field.name, canonicalLedgerCommand(fieldValue)) else Unit
     else -> put(field.name, canonicalLedgerCommand(fieldValue))
    }
   }
  }
 }
