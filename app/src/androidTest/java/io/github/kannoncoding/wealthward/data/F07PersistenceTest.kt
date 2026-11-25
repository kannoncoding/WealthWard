package io.github.kannoncoding.wealthward.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kannoncoding.wealthward.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class F07PersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private suspend fun inDatabase(block: suspend (WealthDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, WealthDatabase::class.java).build()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private suspend fun FinanceRepository.cash(id: String, minor: Long, currency: Currency = Currency.CRC) {
        workspace.execute(F05Command.CreateAccount("new-$id", id, id, "Cash $id", currency, "CASH", minor, "2026-01-01"))
    }

    private suspend fun rejects(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected invalid command")
        } catch (_: DomainFailure) { }
    }

    @Test fun mortgageMaturityKeepsTheExactDateAndEditsSurviveRestore() = runBlocking {
        inDatabase { db ->
            val finance = FinanceRepository(db)
            val store = finance.commitments
            val debt = Debt(
                "mortgage", "Mortgage", "Fixed", Currency.CRC, DebtKind.MORTGAGE,
                30_000, "2027-01-01", "2027-01-31", 10_000, 3,
                maturityDate = "2027-04-17",
            )
            val create = F07Command.CreateDebt("create-mortgage", debt)
            store.execute(create)
            assertEquals("2027-04-17", store.snapshot("2027-04").debts.single().debt.maturityDate)
            assertEquals(3, store.snapshot("2027-04").installments.size)
            val edit = F07Command.UpdateDebtMaturity("change-maturity", debt.id, "2027-06-19")
            store.execute(edit)
            assertEquals(edit.requestId, store.execute(edit))
            val saved = store.snapshot("2027-06")
            assertEquals("2027-06-19", saved.debts.single().debt.maturityDate)
            assertEquals(5, saved.installments.size)
            assertEquals(debt.openingMinor, saved.debts.single().principalMinor)
            inDatabase { restored ->
                val copy = FinanceRepository(restored)
                copy.restoreFixture(finance.exportFixture())
                assertEquals(saved, copy.commitments.snapshot("2027-06"))
                assertEquals(create.requestId, copy.commitments.execute(create))
                assertEquals(edit.requestId, copy.commitments.execute(edit))
            }
        }
    }

    @Test fun mortgageEstimatesHandleCommaRateAndRecurringCharges() = runBlocking {
        inDatabase { db ->
            val store = FinanceRepository(db).commitments
            val rate = normalizedDebtRate("12,0")
            assertEquals("12.0", rate?.toPlainString())
            assertEquals(28_822L, debtPrincipalFromPayments(9_800, 9_800, 3, rate))
            val estimate = checkNotNull(debtPayoff(25_000, 9_800, rate, 600))
            assertEquals(3, estimate.installments)
            val debt = Debt(
                "estimated", "Mortgage", "Fixed", Currency.CRC, DebtKind.MORTGAGE,
                25_000, "2027-01-01", "2027-01-31", 10_000, estimate.installments,
                annualRatePercent = "12,0", amortization = DebtAmortization.FIXED_MONTHLY,
                estimateTermFromBalance = true, installmentChargesMinor = 200,
            )
            store.execute(F07Command.CreateDebt("estimated-mortgage", debt))
            val saved = store.snapshot("2027-04")
            assertEquals(3, saved.debts.single().projectedRemainingMonths)
            assertEquals(estimate.finalPaymentMinor + 200, saved.installments.last().pendingMinor)
            try {
                store.execute(F07Command.CreateDebt("interest-only-mortgage",
                    debt.copy(id = "interest-only", installmentMinor = 450)))
                fail("Expected a payment and rate conflict")
            } catch (error: DomainFailure.Invalid) {
                assertEquals(DEBT_PAYMENT_INTEREST, error.message)
            }
        }
    }

    @Test fun remainingMortgageInstallmentsFollowTheirNominalMonthAndPaymentStatus() = runBlocking {
        inDatabase { db ->
            val finance = FinanceRepository(db)
            finance.cash("cash", 100_000)
            val store = finance.commitments
            val debt = Debt(
                "mortgage-progress", "Mortgage", "Fixed", Currency.CRC, DebtKind.MORTGAGE,
                30_000, "2027-01-01", "2027-01-15", 10_000, 3, estimateTermFromBalance = true,
            )
            store.execute(F07Command.CreateDebt("create-progress-mortgage", debt))
            suspend fun pay(id: String, date: String, month: String, principal: Long) {
                store.execute(F07Command.PayDebt(id, DebtPayment(id, debt.id, "cash", date, month, principal, 0, 0)))
            }
            suspend fun checkPending(vararg months: String) {
                val snapshot = store.snapshot("2027-03")
                assertEquals(months.toList(), snapshot.installments.filter { it.pendingMinor > 0 }.map { it.nominalMonth })
                assertEquals(months.size, snapshot.debts.single().projectedRemainingMonths)
                assertEquals(months.lastOrNull()?.let { "$it-15" }, snapshot.debts.single().projectedPayoffDate)
            }
            checkPending("2027-01", "2027-02", "2027-03")
            pay("early-feb-part", "2027-01-10", "2027-02", 4_000)
            checkPending("2027-01", "2027-02", "2027-03")
            pay("early-feb-rest", "2027-01-12", "2027-02", 6_000)
            checkPending("2027-01", "2027-03")
            pay("late-jan", "2027-02-15", "2027-01", 10_000)
            checkPending("2027-03")
            pay("on-time-mar", "2027-03-15", "2027-03", 10_000)
            checkPending()
        }
    }

    @Test fun t08DebtPaymentPostsComponentsOnceClosesAndRestores() = runBlocking {
        inDatabase { db ->
            val finance = FinanceRepository(db)
            finance.cash("cash", 100_000_000)
            val store = finance.commitments
            val debt = Debt("loan", "Term loan", "Fixed", Currency.CRC, DebtKind.INSTALLMENT, 30_000_000,
                "2027-01-01", "2027-01-31", 5_000_000, 3, extraPaymentPolicy = ExtraPaymentPolicy.REDUCE_TERM)
            store.execute(F07Command.CreateDebt("create-loan", debt))
            val january = DebtPayment("jan-payment", debt.id, "cash", "2027-01-31", "2027-01", 4_000_000, 1_000_000, 0)
            val first = F07Command.PayDebt("jan-request", january)
            assertEquals(5_000_000L, finance.planning.snapshot("2027-01").occurrences.single().pendingMinor)
            assertEquals(first.requestId, store.execute(first))
            assertEquals(first.requestId, store.execute(first))
            assertEquals(26_000_000L, store.snapshot("2027-01").debts.single().principalMinor)
            assertEquals(95_000_000L, finance.workspace.snapshot().accounts.single().balanceMinor)
            assertEquals(1_000_000L, db.ledger().transactions().sumOf { it.expenseMinor })
            assertEquals(0L, finance.planning.snapshot("2027-01").occurrences.single().pendingMinor)
            assertEquals(5_000_000L, finance.planning.snapshot("2027-01").comparisons.single().actualMinor)
            val extra = DebtPayment("extra-payment", debt.id, "cash", "2027-02-02", null, 20_000_000, 0, 0, actualRemainingMonths = 1)
            store.execute(F07Command.PayDebt("extra-request", extra))
            assertEquals(20_000_000L, finance.planning.snapshot("2027-02").comparisons.single().actualMinor)
            store.execute(F07Command.VerifyBalance("verify", CreditorBalance("notice", debt.id, "2027-02-03", 5_500_000, "Synthetic statement")))
            val final = DebtPayment("last", debt.id, "cash", "2027-03-31", "2027-03", 5_500_000, 15_000, 10_000)
            store.execute(F07Command.PayDebt("last-request", final))
            val closed = store.snapshot("2027-03")
            assertEquals(0L, closed.debts.single().principalMinor)
            assertEquals(5_525_000L, closed.installments.single { it.nominalMonth == "2027-03" }.paidMinor)
            assertTrue(closed.installments.all { it.pendingMinor == 0L })
            store.execute(F07Command.VoidDebtPayment("reverse-last", final.id, "Correct payment"))
            assertEquals(5_500_000L, store.snapshot("2027-03").debts.single().principalMinor)
            assertEquals(5_000_000L, finance.planning.snapshot("2027-03").occurrences.single { it.nominalMonth == "2027-03" }.pendingMinor)
            val fixture = finance.exportFixture()
            inDatabase { restored ->
                val copy = FinanceRepository(restored)
                copy.restoreFixture(fixture)
                assertEquals(store.snapshot("2027-03"), copy.commitments.snapshot("2027-03"))
                assertEquals(first.requestId, copy.commitments.execute(first))
                val bad = JSONObject(fixture)
                bad.getJSONObject("workspace").getJSONObject("f07").getJSONArray("payments")
                    .getJSONObject(0).put("principalMinor", 4_000_001)
                rejects { copy.restoreFixture(bad.toString()) }
                assertEquals(fixture, copy.exportFixture())
            }
        }
    }

    @Test fun t09AndX07ReserveFundingPreservesCashAndTracksTheWithdrawalSourceMonth() = runBlocking {
        inDatabase { db ->
            val finance = FinanceRepository(db)
            finance.cash("a", 40_000_000)
            finance.cash("b", 10_000_000)
            val store = finance.commitments
            val goal = SavingGoal("protected", "Savings", "Fixed", Currency.CRC, 100_000_000, "2027-12-31", 1, 8_000_000)
            store.execute(F07Command.SaveGoal("goal", goal))
            suspend fun event(id: String, action: SavingAction, account: String, minor: Long, date: String, to: String? = null) {
                store.execute(F07Command.MoveSaving(id, SavingEvent(id, action, goal.id, account, date, minor, destinationAccountId = to)))
            }
            event("prior-a", SavingAction.CONTRIBUTE, "a", 8_000_000, "2027-01-01")
            event("prior-b", SavingAction.CONTRIBUTE, "b", 2_000_000, "2027-01-01")
            event("monthly", SavingAction.CONTRIBUTE, "a", 3_000_000, "2027-02-01")
            event("move", SavingAction.TRANSFER, "a", 1_000_000, "2027-02-02", "b")
            val allocated = store.snapshot("2027-02").savings.single()
            val liquid = finance.workspace.snapshot().accounts.sumOf { it.unassignedMinor }
            val pending = CommitmentMath.pendingSaving(goal.monthlyMinor, allocated.contributedThisMonthMinor)
            val beforeSavings = liquid + 45_000_000L - 40_000_000L - 12_000_000L - 3_000_000L
            assertEquals(37_000_000L, liquid)
            assertEquals(5_000_000L, pending)
            assertEquals(22_000_000L, CommitmentMath.remainingMargin(beforeSavings, pending))
            assertEquals(2, allocated.accounts.size)
            event("old-withdrawal", SavingAction.WITHDRAW, "b", 1_000_000, "2027-02-03")
            assertEquals(3_000_000L, store.snapshot("2027-02").savings.single().contributedThisMonthMinor)
            event("current-withdrawal", SavingAction.WITHDRAW, "a", 8_000_000, "2027-02-04")
            assertEquals(2_000_000L, store.snapshot("2027-02").savings.single().contributedThisMonthMinor)
            assertEquals(0L, db.ledger().transactions().sumOf { it.expenseMinor })
            rejects { finance.workspace.execute(F05Command.FundReserve("untracked", "a", savingPurpose(goal.id), goal.name, 1)) }
        }
    }

    @Test fun t23AndT24ProjectUsesOneExpenseAndVisualGroupStaysIndependent() = runBlocking {
        inDatabase { db ->
            val finance = FinanceRepository(db)
            finance.cash("cash", 20_000_000)
            val store = finance.commitments
            val goal = SavingGoal("reserve", "Project savings", "Fixed", Currency.CRC, 10_000_000, "2027-12-31", 1, 3_000_000)
            store.execute(F07Command.SaveGoal("goal", goal))
            store.execute(F07Command.MoveSaving("contribute", SavingEvent("contribute", SavingAction.CONTRIBUTE, goal.id, "cash", "2027-02-01", 3_000_000)))
            store.execute(F07Command.MoveSaving("external", SavingEvent("external", SavingAction.EXTERNAL, goal.id, "cash", "2027-02-02", 300_000, recipient = "Outside recipient")))
            store.execute(F07Command.SaveProject("project", Project("project", "Education", "Projects", Currency.CRC, 10_000_000, goal.id)))
            store.execute(F07Command.SaveProjectItem("item", ProjectItem("item", "project", "Deposit", 5_000_000)))
            val household = RecurringTemplate("home", 1, "Household", "Fixed", PlanNature.FIXED, 10_000_000, Currency.CRC,
                "2027-02", householdTotalMinor = 10_000_000, ownPercent = "50")
            finance.planning.execute(F06Command.SaveTemplate("home", household))
            finance.planning.execute(F06Command.OpenPeriod("open", "2027-02"))
            val payment = F07Command.RecordProjectPayment("deposit", "item", "cash", "2027-02-10", 5_000_000)
            store.execute(payment)
            store.execute(payment)
            val project = store.snapshot("2027-02").projects.single()
            assertEquals(5_000_000L, project.items.single().actualMinor)
            assertEquals(0L, project.fundedMinor)
            assertEquals(5_300_000L, db.ledger().transactions().sumOf { it.expenseMinor })
            val plan = finance.planning.snapshot("2027-02")
            assertEquals(5_000_000L, plan.occurrences.single().pendingMinor)
            assertEquals(3_000_000L, plan.comparisons.single { it.nature == PlanNature.SAVING }.consumptionMinor)
            assertEquals(300_000L, plan.comparisons.single { it.group == "Fixed" && it.nature == PlanNature.FIXED }.actualMinor)
            assertEquals(5_000_000L, plan.comparisons.single { it.group == "Projects" }.actualMinor)
            val op = project.items.single().payments.single().operationId
            rejects { finance.planning.execute(F06Command.LinkPayment("double-use", plan.occurrences.single().id, op)) }
        }
    }

    @Test fun cardStatementsLinkTheirOwnPurchasesPaymentsAndEvidence() = runBlocking {
        inDatabase { db ->
            val finance = FinanceRepository(db)
            val store = finance.commitments
            finance.cash("cash", 200_000)
            finance.workspace.execute(F05Command.CreateAccount("card", "card", "card", "Card", Currency.CRC, "LIABILITY", -20_000, "2026-01-01"))
            finance.workspace.execute(F05Command.Record("purchase", "card", "CREDIT_PURCHASE", 50_000, "2027-02-03", "Merchant", "Category"))
            finance.workspace.execute(F05Command.CreateHold("hold", "card", 1_000, "Hold", "2027-02-25"))
            store.execute(F07Command.SaveStatement("statement", CreditStatement("statement", "card", "2027-02-20", "2027-03-03", 70_000, listOf("purchase"))))
            finance.workspace.execute(F05Command.Transfer("payment", "cash", "card", 55_000, 55_000, "2027-02-22",
                creditAllocations = listOf(CreditPaymentAllocation("opening:card", 20_000), CreditPaymentAllocation("purchase", 35_000))))
            store.execute(F07Command.LinkStatementPayment("link", "statement", "payment"))
            store.execute(F07Command.LinkCreditEvidence("evidence", CreditEvidence("mail-ref", "card", "purchase", CreditEvidenceKind.PURCHASE, "Synthetic reference")))
            rejects { store.execute(F07Command.LinkCreditEvidence("wrong", CreditEvidence("wrong", "card", "payment", CreditEvidenceKind.PURCHASE, "Wrong kind"))) }
            val snapshot = store.snapshot("2027-02")
            assertEquals(15_000L, snapshot.statements.single().pendingMinor)
            assertEquals(listOf("hold"), snapshot.statements.single().pendingHoldIds)
            assertEquals("purchase", snapshot.evidence.single().operationId)
            assertEquals(50_000L, db.ledger().transactions().sumOf { it.expenseMinor })
        }
    }
}
