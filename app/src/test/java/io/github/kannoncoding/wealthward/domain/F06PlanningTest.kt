package io.github.kannoncoding.wealthward.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class F06PlanningTest {
 private fun template(frequency:Recurrence=Recurrence.MONTHLY)=RecurringTemplate("bill",1,"Service","Essentials",PlanNature.FIXED,12345,Currency.CRC,"2027-01",frequency,31,30,3)
 @Test fun monthEndRestoresNominalDayAndLeapYear() { val t=template(); assertEquals("2027-02-28",PlanningMath.slots(t,YearMonth.parse("2027-02")).single().toString()); assertEquals("2027-03-31",PlanningMath.slots(t,YearMonth.parse("2027-03")).single().toString()); assertEquals("2028-02-29",PlanningMath.slots(t,YearMonth.parse("2028-02")).single().toString()) }
 @Test fun twoFixedSlotsRemainTwoWhenClampedTogether() { val dates=PlanningMath.slots(template(Recurrence.TWO_SLOTS),YearMonth.parse("2027-02")); assertEquals(2,dates.size); assertEquals(dates[0],dates[1]) }
 @Test fun annualAndEveryNMonthsRespectAnchor() { assertTrue(PlanningMath.slots(template(Recurrence.ANNUAL),YearMonth.parse("2027-02")).isEmpty()); assertEquals(1,PlanningMath.slots(template(Recurrence.EVERY_N_MONTHS),YearMonth.parse("2027-04")).size); assertTrue(PlanningMath.slots(template(Recurrence.EVERY_N_MONTHS),YearMonth.parse("2027-05")).isEmpty()) }
 @Test fun salaryUsesLastWeekdayWithoutHolidayInference() { assertEquals("2027-02-26",PlanningMath.salaryDate(YearMonth.parse("2027-02")).toString()); assertEquals("2027-05-31",PlanningMath.salaryDate(YearMonth.parse("2027-05")).toString()) }
 @Test fun weeklyAndFourteenDayIncomeContinueAcrossMonthBoundaries() {
  val anchor = LocalDate.parse("2028-01-30")
  val february = YearMonth.parse("2028-02")
  val march = february.plusMonths(1)
  fun dates(month: YearMonth, frequency: IncomeFrequency) =
   PlanningMath.incomeDates(month,frequency,anchor).map(LocalDate::toString)
  assertEquals(listOf("2028-02-06","2028-02-13","2028-02-20","2028-02-27"),dates(february,IncomeFrequency.WEEKLY))
  assertEquals(listOf("2028-02-13","2028-02-27"),dates(february,IncomeFrequency.EVERY_14_DAYS))
  assertEquals(listOf("2028-03-12","2028-03-26"),dates(march,IncomeFrequency.EVERY_14_DAYS))
  assertEquals(listOf("2028-02-29"),dates(february,IncomeFrequency.MONTHLY))
  assertEquals(listOf("2028-03-30"),dates(march,IncomeFrequency.MONTHLY))
  assertTrue(dates(february.minusMonths(2),IncomeFrequency.WEEKLY).isEmpty())
 }
 @Test fun fixedIncomeDatesClampDayThirtyAndRespectTheFirstDate() {
  val leapFebruary = YearMonth.parse("2028-02")
  val lateStart = leapFebruary.atDay(17)
  assertEquals(listOf(leapFebruary.atEndOfMonth()), PlanningMath.incomeDates(leapFebruary, IncomeFrequency.DAYS_15_30, lateStart))
  assertEquals(listOf(leapFebruary.atDay(25)), PlanningMath.incomeDates(leapFebruary, IncomeFrequency.DAYS_10_25, lateStart))
  val march = leapFebruary.plusMonths(1)
  val salary = SalaryVersion("salary", leapFebruary.toString(), 100, Currency.CRC, frequency = IncomeFrequency.DAYS_15_30)
  assertEquals(listOf(leapFebruary.atDay(15), leapFebruary.atDay(29)), PlanningMath.salaryDates(leapFebruary, salary))
  assertEquals(listOf(march.atDay(15), march.atDay(30)), PlanningMath.salaryDates(march, salary))
  val nonLeapFebruary = leapFebruary.minusYears(1)
  assertEquals(listOf(nonLeapFebruary.atDay(15), nonLeapFebruary.atDay(28)),
   PlanningMath.incomeDates(nonLeapFebruary, IncomeFrequency.DAYS_15_30, nonLeapFebruary.atDay(1)))
 }
 @Test fun householdOwnShareRoundsHalfUpAndValidatesBounds() { assertEquals(51,PlanningMath.ownShare(101,"50",null)); assertEquals(17,PlanningMath.ownShare(101,null,17)); try { PlanningMath.ownShare(100,"100.1",null); fail("Out-of-range percentage") } catch (_:IllegalArgumentException) {} }
}
