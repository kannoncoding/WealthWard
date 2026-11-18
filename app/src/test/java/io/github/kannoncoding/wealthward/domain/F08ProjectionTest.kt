package io.github.kannoncoding.wealthward.domain

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class F08ProjectionTest {
    private val month = YearMonth.of(2026, 11)
    private fun crc(value: Long) = Money(value, Currency.CRC)
    private fun line(component: ProjectionComponent, amount: Long) = ProjectionLine(component.name, component.name, component, crc(amount))
    private fun input(vararg lines: ProjectionLine) = ProjectionInput(month, month.atDay(1), Currency.CRC, emptyList(), lines.toList(), greenMargin = crc(30_000))

    @Test fun t09FundingPlannedSavingsKeepsFreeStable() {
        val baseline = input(
            line(ProjectionComponent.LIQUID, 500_000), line(ProjectionComponent.PROTECTED, 100_000),
            line(ProjectionComponent.INCOME, 450_000), line(ProjectionComponent.OBLIGATION, 400_000),
            line(ProjectionComponent.VARIABLE, 120_000), line(ProjectionComponent.SAVING, 80_000),
            line(ProjectionComponent.BUFFER, 30_000),
        )
        val funded = baseline.copy(lines = baseline.lines.map {
            when (it.component) {
                ProjectionComponent.PROTECTED -> it.copy(amount = crc(130_000))
                ProjectionComponent.SAVING -> it.copy(amount = crc(50_000))
                else -> it
            }
        })
        assertEquals(crc(220_000), project(baseline).freeRecommended)
        val result = project(funded)
        assertEquals(crc(370_000), result.totals!!.available)
        assertEquals(crc(50_000), result.totals.saving)
        assertEquals(crc(220_000), result.freeRecommended)
    }

    @Test fun t11ConvertsAfterAggregationAndRetainsNativeBalances() {
        val initial = input(line(ProjectionComponent.LIQUID, 5_000_000),
            ProjectionLine("usd", "USD", ProjectionComponent.LIQUID, Money(10_000, Currency.USD)))
        val missing = project(initial)
        assertNull(missing.freeRecommended)
        assertNull(missing.color)
        assertEquals(Money(10_000, Currency.USD), missing.native.getValue(Currency.USD).liquid)
        val rate = ProjectionRate(Currency.USD, Currency.CRC, BigDecimal("500"), initial.cutoff, "Test")
        assertEquals(crc(10_000_000), project(initial.copy(rates = listOf(rate))).totals!!.liquid)
        assertNull(project(initial.copy(rates = listOf(rate.copy(date = initial.cutoff.plusDays(1))))).totals)
        val small = initial.copy(lines = List(2) { initial.lines[1].copy(amount = Money(1, Currency.USD)) },
            rates = listOf(rate.copy(rate = BigDecimal("0.5"))))
        assertEquals(crc(1), project(small).totals!!.liquid)
        val fractionalDeficit = small.copy(lines = listOf(small.lines.first().copy(
            component = ProjectionComponent.OBLIGATION)), rates = listOf(rate.copy(rate = BigDecimal("0.4"))))
        assertEquals(crc(0), project(fractionalDeficit).totals!!.obligationsMargin)
        assertEquals(ProjectionColor.RED, project(fractionalDeficit).color)
    }

    @Test fun t12AppliesOrderedColorBoundaries() {
        val borders = mapOf(-1L to ProjectionColor.RED, 79_999L to ProjectionColor.ORANGE,
            80_000L to ProjectionColor.YELLOW, 109_999L to ProjectionColor.YELLOW, 110_000L to ProjectionColor.GREEN)
        borders.forEach { (margin, expected) ->
            assertEquals(expected, project(input(line(ProjectionComponent.LIQUID, margin), line(ProjectionComponent.SAVING, 80_000))).color)
        }
        val buffer = project(input(line(ProjectionComponent.LIQUID, 10_000), line(ProjectionComponent.BUFFER, 20_000)))
        assertEquals(ProjectionColor.ORANGE, buffer.color)
        assertEquals("Falta cubrir el colchón", buffer.explanation)
        assertNull(project(input(line(ProjectionComponent.LIQUID, 150_000)).copy(issues = listOf("Missing underlying source"))).color)
    }

    @Test fun t13FindsDeficitsBeforeTheReceiptEvenWithPositiveMonth() {
        val baseline = input(line(ProjectionComponent.LIQUID, 20_000), line(ProjectionComponent.OBLIGATION, 30_000), line(ProjectionComponent.INCOME, 100_000))
        val account = ProjectionAccount("cash", "Cash", crc(20_000))
        val result = project(baseline.copy(cutoff = month.atDay(11), accounts = listOf(account), cashEvents = listOf(
            ProjectionCashEvent("incoming", "Income", "cash", month.atDay(16), crc(100_000), receipt = true),
            ProjectionCashEvent("bill", "Bill", "cash", month.atDay(12), crc(-30_000)),
        )))
        assertEquals(crc(90_000), result.freeRecommended)
        assertEquals(crc(0), result.cash.single().safeToday)
        assertEquals(month.atDay(12), result.cash.single().firstDeficit!!.date)
        assertEquals(crc(-10_000), result.cash.single().firstDeficit!!.balance)
        assertEquals(crc(90_000), result.calendar.last().balance)
    }

    @Test fun scenariosAreIndependentAcrossThreeConsecutiveMonths() {
        val months = listOf(month.minusMonths(1), month, month.plusMonths(1))
        val free = months.mapIndexed { index, period ->
            val baseline = input(line(ProjectionComponent.LIQUID, 100_000 + index * 10_000L),
                line(ProjectionComponent.OBLIGATION, 40_000)).copy(month = period, cutoff = period.atDay(5),
                accounts = listOf(ProjectionAccount("cash", "Cash", crc(100_000 + index * 10_000L))))
            val original = project(baseline)
            val simulated = project(baseline, ProjectionScenario(20_000, 15_000, "cash"))
            assertEquals(original.freeRecommended!!.minor - 35_000L, simulated.freeRecommended!!.minor)
            assertEquals(period.atEndOfMonth(), simulated.cash.single().horizon)
            assertEquals(original, project(baseline))
            original.freeRecommended.minor
        }
        assertEquals(listOf(60_000L, 70_000L, 80_000L), free)
    }

    @Test fun fundedObligationReserveAndHoldLimitCashOnlyOnce() {
        val cut = LocalDate.of(2026, 11, 1)
        val account = ProjectionAccount("cash", "Cash", crc(100_000), heldMinor = 10_000, protectedMinor = 20_000, obligationReserveMinor = 30_000)
        val result = project(input(line(ProjectionComponent.LIQUID, 100_000), line(ProjectionComponent.PROTECTED, 20_000),
            line(ProjectionComponent.OBLIGATION, 30_000)).copy(accounts = listOf(account), cashEvents = listOf(
            ProjectionCashEvent("bill", "Bill", "cash", cut.plusDays(1), crc(-30_000), reserveUsedMinor = 30_000),
        )))
        assertEquals(crc(50_000), result.freeRecommended)
        assertEquals(crc(40_000), result.cash.single().safeToday)
    }
}
