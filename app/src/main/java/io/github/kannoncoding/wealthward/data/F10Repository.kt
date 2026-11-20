package io.github.kannoncoding.wealthward.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import io.github.kannoncoding.wealthward.domain.BusinessTime
import io.github.kannoncoding.wealthward.domain.Currency
import io.github.kannoncoding.wealthward.domain.DomainFailure
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.json.JSONObject

internal data class ProjectionLimits(val buffer: Long = 0, val green: Long? = null) {
    init {
        require(buffer >= 0 && (green == null || green > 0))
    }
}

internal class BackupCandidate internal constructor(
    val createdAt: Instant,
    val summary: FinancialSummary,
    val accountCount: Int,
    val movementCount: Int,
    val planCount: Int,
    internal val fixture: CoreFixture,
    internal val limits: Map<Currency, ProjectionLimits>,
)

internal const val BACKUP_VERSION = 1
private const val BACKUP_FORMAT = "wealthward-backup"
private const val PROJECTION_LIMITS = "projection_limits"

internal class F10Repository(
    private val context: Context,
    private val db: WealthDatabase,
    private val finance: FinanceRepository,
) {
    private val dao = db.ledger()
    val projectionLimits = dao.observeProjectionLimits().distinctUntilChanged().map { payload ->
        if (payload == null) emptyMap() else decodeLimits(JSONObject(payload))
    }

    suspend fun migrateProjectionLimits() = db.withTransaction {
        if (dao.projectionLimits() == null) {
            val previous = context.getSharedPreferences(PROJECTION_LIMITS, Context.MODE_PRIVATE)
            saveLimits(Currency.entries.associateWith { currency ->
                ProjectionLimits(
                    previous.getLong("${currency}_buffer", 0).coerceAtLeast(0),
                    previous.getLong("${currency}_green", 0).takeIf { it > 0 },
                )
            })
        }
    }

    suspend fun saveProjectionLimits(currency: Currency, value: ProjectionLimits) = db.withTransaction {
        saveLimits(currentLimits() + (currency to value))
    }

    suspend fun backup(): String = db.withTransaction {
        JSONObject()
            .put("format", BACKUP_FORMAT)
            .put("version", BACKUP_VERSION)
            .put("createdAt", Instant.now().toString())
            .put("ledger", JSONObject(finance.exportFixture()))
            .put("projectionLimits", encodeLimits(currentLimits()))
            .toString()
    }

    suspend fun inspectBackup(document: String): BackupCandidate {
        // Require a complete JSON document before using JSONObject and the legacy fixture parser.
        Json.parseToJsonElement(document)
        val root = JSONObject(document)
        require(root.get("format") == BACKUP_FORMAT)
        if (root.get("version") != BACKUP_VERSION) throw DomainFailure.UnsupportedVersion()
        val createdAt = Instant.parse(root.getString("createdAt"))
        createdAt.atZone(BusinessTime.zone)
        val limits = decodeLimits(root.getJSONObject("projectionLimits"))
        val ledger = root.getJSONObject("ledger")
        if (ledger.get("version") != 3) throw DomainFailure.UnsupportedVersion()
        val fixture = finance.parseCoreFixture(ledger.toString())
        val scratch = Room.inMemoryDatabaseBuilder(context, WealthDatabase::class.java).build()
        try {
            val candidate = FinanceRepository(scratch)
            candidate.restoreCoreFixture(fixture)
            val day = LocalDate.now(BusinessTime.zone)
            val summary = buildSummary(candidate, day)
            val plans = JSONObject(fixture.workspace).optJSONObject("f06")?.getJSONArray("plans")?.length() ?: 0
            return BackupCandidate(createdAt, summary, fixture.accounts.size, fixture.transactions.size,
                plans + fixture.versions.size, fixture, limits)
        } finally {
            scratch.close()
        }
    }

    suspend fun restore(candidate: BackupCandidate) = db.withTransaction {
        finance.restoreCoreFixture(candidate.fixture)
        saveLimits(candidate.limits)
    }

    suspend fun summary(): FinancialSummary = db.withTransaction {
        buildSummary(finance, LocalDate.now(BusinessTime.zone))
    }

    private suspend fun currentLimits() = dao.projectionLimits()?.let { decodeLimits(JSONObject(it)) }.orEmpty()

    private suspend fun saveLimits(limits: Map<Currency, ProjectionLimits>) {
        dao.saveWorkspace(WorkspaceStateEntity(PROJECTION_LIMITS, encodeLimits(limits).toString()))
    }
}

private fun encodeLimits(limits: Map<Currency, ProjectionLimits>) = JSONObject().apply {
    Currency.entries.forEach { currency ->
        val limit = limits[currency] ?: ProjectionLimits()
        put(currency.name, JSONObject()
            .put("bufferMinor", limit.buffer)
            .put("greenMinor", limit.green ?: JSONObject.NULL))
    }
}

private fun decodeLimits(root: JSONObject) = Currency.entries.associateWith { currency ->
    val row = root.getJSONObject(currency.name)
    val green = row.get("greenMinor")
    ProjectionLimits(exactLimit(row.get("bufferMinor")), if (green == JSONObject.NULL) null else exactLimit(green))
}

private fun exactLimit(value: Any): Long = when (value) {
    is Int -> value.toLong()
    is Long -> value
    else -> throw IllegalArgumentException("Invalid projection limit")
}

private suspend fun buildSummary(finance: FinanceRepository, day: LocalDate): FinancialSummary {
    val accounts = finance.workspace.snapshot().accounts.map { account ->
        SummaryRow(
            id = "account:${account.id}",
            alias = account.name,
            currency = account.currency,
            archived = account.archived,
            type = if (account.kind == "LIABILITY") SummaryType.CARD else SummaryType.CASH,
            balanceMinor = account.balanceMinor,
            reservedMinor = account.reservedMinor,
            unassignedMinor = account.unassignedMinor,
        )
    }
    val debts = finance.commitments.debts().map { debt ->
        SummaryRow("debt:${debt.debt.id}", debt.debt.name, debt.debt.currency, false,
            SummaryType.TERM_DEBT, debt.principalMinor)
    }
    return FinancialSummary(day, accounts + debts)
}
