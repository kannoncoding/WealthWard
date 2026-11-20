package io.github.kannoncoding.wealthward.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kannoncoding.wealthward.domain.Currency
import io.github.kannoncoding.wealthward.domain.Debt
import io.github.kannoncoding.wealthward.domain.DebtKind
import io.github.kannoncoding.wealthward.domain.DomainFailure
import io.github.kannoncoding.wealthward.domain.F05Command
import io.github.kannoncoding.wealthward.domain.F06Command
import io.github.kannoncoding.wealthward.domain.F07Command
import io.github.kannoncoding.wealthward.domain.PeriodPlan
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class F10PersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val crc = Currency.CRC
    private val usd = Currency.USD
    private val day = "2026-01-01"

    @Test fun validatedCandidateReplacesLedgerAndLimitsTogether() = runBlocking {
        inDatabase { db, finance, files ->
            finance.workspace.execute(F05Command.CreateAccount("cash", "cash", "cash", "Ahorros", crc, "CASH", 10_000, day))
            finance.workspace.execute(F05Command.FundReserve("reserve", "cash", "goal", "Emergencia", 2_000))
            finance.workspace.execute(F05Command.Record("spend", "cash", "EXPENSE", 500, day, "Prueba", "Casa"))
            finance.planning.execute(F06Command.SavePlan("plan", PeriodPlan("2026-01", 1, emptyList())))
            val debt = Debt("loan", "Préstamo", "Fijos", crc, DebtKind.INSTALLMENT, 5_000, day,
                "2026-01-15", 2_500, 2)
            finance.commitments.execute(F07Command.CreateDebt("loan", debt))
            files.saveProjectionLimits(crc, ProjectionLimits(1_000, 20_000))
            files.saveProjectionLimits(usd, ProjectionLimits(5, 100))
            val backup = files.backup()
            val originalLedger = finance.exportFixture()
            finance.workspace.execute(F05Command.ArchiveAccount("archive", "cash"))
            files.saveProjectionLimits(usd, ProjectionLimits(25, null))
            val liveLedger = finance.exportFixture()

            assertTrue(runCatching { files.inspectBackup(backup.dropLast(1)) }.isFailure)
            val future = JSONObject(backup).put("version", 99).toString()
            assertTrue(runCatching { files.inspectBackup(future) }.exceptionOrNull() is DomainFailure.UnsupportedVersion)
            val reference = JSONObject(backup)
            reference.getJSONObject("ledger").getJSONArray("postings").getJSONObject(0).put("accountId", "missing")
            assertTrue(runCatching { files.inspectBackup(reference.toString()) }.isFailure)
            val candidate = files.inspectBackup(backup)
            assertEquals(liveLedger, finance.exportFixture())
            assertEquals(ProjectionLimits(25, null), files.projectionLimits.first().getValue(usd))
            assertFalse(candidate.summary.rows.first { it.id == "account:cash" }.archived)
            assertEquals(2, candidate.accountCount)
            assertEquals(1, candidate.movementCount)
            assertEquals(1, candidate.planCount)

            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_config BEFORE INSERT ON workspace_state WHEN NEW.id='projection_limits' " +
                    "BEGIN SELECT RAISE(ABORT, 'test failure'); END"
            )
            assertTrue(runCatching { files.restore(candidate) }.isFailure)
            assertEquals(liveLedger, finance.exportFixture())
            assertEquals(ProjectionLimits(25, null), files.projectionLimits.first().getValue(usd))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_config")

            files.restore(candidate)
            assertEquals(originalLedger, finance.exportFixture())
            assertEquals(mapOf(crc to ProjectionLimits(1_000, 20_000), usd to ProjectionLimits(5, 100)),
                files.projectionLimits.first())
        }
    }

    @Test fun summaryKeepsEachCurrencyAndBalanceTypeSeparateInBothFormats() = runBlocking {
        inDatabase { _, finance, files ->
            finance.workspace.execute(F05Command.CreateAccount("cash", "cash", "cash", "Ahorros", crc, "CASH", 10_000, day))
            finance.workspace.execute(F05Command.FundReserve("reserve", "cash", "goal", "Emergencia", 2_000))
            finance.workspace.execute(F05Command.ArchiveAccount("archive", "cash"))
            finance.workspace.execute(F05Command.CreateAccount("card1", "card1", "card1", "Tarjeta A", usd, "LIABILITY", -500, day))
            finance.workspace.execute(F05Command.CreateAccount("card2", "card2", "card2", "Tarjeta B", usd, "LIABILITY", 200, day))
            val baseline = finance.exportFixture()
            val report = files.summary()
            assertEquals(baseline, finance.exportFixture())
            assertTrue(report.rows.first { it.id == "account:cash" }.archived)
            val totals = report.totals()
            assertEquals(BigInteger.valueOf(10_000), totals.getValue(crc).cash)
            assertEquals(BigInteger.valueOf(2_000), totals.getValue(crc).reserves)
            assertEquals(BigInteger.valueOf(8_000), totals.getValue(crc).unassigned)
            assertEquals(BigInteger.valueOf(500), totals.getValue(usd).cardDebt)
            assertEquals(BigInteger.valueOf(200), totals.getValue(usd).cardCredit)
            val selection = report.copy(rows = report.rows.filter { it.currency == usd })
            val csv = ByteArrayOutputStream().apply { selection.writeCsv(this) }.toString(Charsets.UTF_8.name())
            assertTrue(csv.contains("\"Tarjeta A\",\"USD\",\"Tarjeta\",\"\",\"\",\"\",\"\",\"5.00\",\"0.00\""))
            assertTrue(csv.contains("\"Tarjeta B\",\"USD\",\"Tarjeta\",\"\",\"\",\"\",\"\",\"0.00\",\"2.00\""))
            assertFalse(csv.contains("CRC"))
            assertFalse(csv.contains("card1"))

            val pdf = File.createTempFile("summary", ".pdf", context.cacheDir)
            try {
                pdf.outputStream().use { selection.writePdf(it) }
                val descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
                PdfRenderer(descriptor).use { assertEquals(1, it.pageCount) }
            } finally {
                pdf.delete()
            }
        }
    }

    private suspend fun inDatabase(block: suspend (WealthDatabase, FinanceRepository, F10Repository) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, WealthDatabase::class.java).build()
        try {
            val finance = FinanceRepository(db)
            block(db, finance, F10Repository(context, db, finance))
        } finally {
            db.close()
        }
    }
}
