package io.github.kannoncoding.wealthward.domain

import java.time.LocalDate

data class ExpenseSplit(val category: String, val minor: Long, val purposeId: String? = null)
data class CreditPaymentAllocation(val purchaseId: String, val minor: Long, val purposeId: String? = null, val sourceMinor: Long? = null)
data class AccountDetail(val id: String, val productId: String, val name: String, val currency: Currency, val kind: String, val openingDate: String?, val archived: Boolean, val balanceMinor: Long, val reservedMinor: Long, val unassignedMinor: Long, val heldMinor: Long, val reserves: List<ReserveBalance>, val lastReconciliation: ReconciliationDetail?, val creditLimitMinor: Long = 0, val openingMinor: Long = 0)
data class ReserveBalance(val accountId: String, val purposeId: String, val name: String, val minor: Long)
data class OperationDetail(val id: String, val date: String, val type: String, val accountId: String, val destinationId: String?, val amountMinor: Long, val destinationMinor: Long?, val merchant: String, val category: String, val notes: String, val splits: List<ExpenseSplit>, val originalId: String?, val voided: Boolean, val refundedMinor: Long, val expenseMinor: Long, val feeMinor: Long = 0, val rate: String? = null, val rateSource: String? = null, val bookedDate: String? = null, val rateDate: String? = null, val fundingAccountId: String? = null, val fundingPurposeId: String? = null, val settledMinor: Long = 0, val creditAllocations: List<CreditPaymentAllocation> = emptyList())
data class HoldDetail(val id: String, val accountId: String, val minor: Long, val merchant: String, val date: String, val status: String)
data class ReconciliationDetail(val id: String, val accountId: String, val cutDate: String, val balanceType: String, val observedMinor: Long, val recordedMinor: Long, val differenceMinor: Long, val reviewedIds: List<String>, val status: String, val reason: String)
data class AuditDetail(val requestId: String, val action: String, val subjectId: String, val reason: String)
data class F05Snapshot(val accounts: List<AccountDetail> = emptyList(), val operations: List<OperationDetail> = emptyList(), val holds: List<HoldDetail> = emptyList(), val reconciliations: List<ReconciliationDetail> = emptyList(), val audit: List<AuditDetail> = emptyList())
data class ImportRow(val rowId: String, val rawAccount: String, val rawContent: String, val rawNotes: String, val accountId: String?, val confirmedOpeningMinor: Long?, val openingDate: String?, val confirmedReserveMinor: Long? = null, val purposeId: String? = null, val purposeName: String? = null, val confirmed: Boolean = false, val editedContent: String? = null, val editedNotes: String? = null)
data class ImportIssue(val rowId: String, val message: String)
sealed interface F05Command {
 val requestId: String
 data class CreateAccount(override val requestId: String, val id: String, val productId: String, val name: String, val currency: Currency, val kind: String, val openingMinor: Long, val openingDate: String, val creditLimitMinor: Long = 0): F05Command
 data class RenameAccount(override val requestId: String, val accountId: String, val name: String): F05Command
 data class ArchiveAccount(override val requestId: String, val accountId: String, val archived: Boolean = true): F05Command
 data class Record(override val requestId: String, val accountId: String, val type: String, val amountMinor: Long, val date: String, val merchant: String, val category: String, val notes: String = "", val splits: List<ExpenseSplit> = emptyList(), val bookedDate: String? = null, val fundingAccountId: String? = null, val fundingPurposeId: String? = null): F05Command
 data class Correct(override val requestId: String, val operationId: String, val replacement: Record, val reason: String): F05Command
 data class Void(override val requestId: String, val operationId: String, val reason: String): F05Command
 data class Refund(override val requestId: String, val operationId: String, val amountMinor: Long, val date: String, val reason: String, val bookedDate: String? = null): F05Command
 data class Transfer(override val requestId: String, val sourceId: String, val destinationId: String, val amountMinor: Long, val destinationMinor: Long, val date: String, val feeMinor: Long = 0, val purposeId: String? = null, val rate: String? = null, val rateSource: String? = null, val notes: String = "", val bookedDate: String? = null, val rateDate: String? = null, val creditAllocations: List<CreditPaymentAllocation> = emptyList()): F05Command
 data class FundReserve(override val requestId: String, val accountId: String, val purposeId: String, val name: String, val amountMinor: Long): F05Command
 data class Redistribute(override val requestId: String, val accountId: String, val fromPurposeId: String, val toPurposeId: String, val toName: String, val amountMinor: Long): F05Command
 data class Reconcile(override val requestId: String, val accountId: String, val cutDate: String, val balanceType: String, val observedMinor: Long, val reviewedIds: List<String>, val holdsKnown: Boolean = false): F05Command
 data class ResolveDifference(override val requestId: String, val reconciliationId: String, val reason: String): F05Command
 data class CreateHold(override val requestId: String, val accountId: String, val amountMinor: Long, val merchant: String, val date: String): F05Command
 data class ReleaseHold(override val requestId: String, val holdId: String): F05Command
 data class ConfirmHold(override val requestId: String, val holdId: String, val category: String, val date: String, val notes: String = "", val bookedDate: String? = null): F05Command
 data class ImportBatch(override val requestId: String, val rows: List<ImportRow>): F05Command
}






