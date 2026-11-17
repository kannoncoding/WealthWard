package io.github.kannoncoding.wealthward.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth

class F06PlanningTest {
 private fun template(frequency:Recurrence=Recurrence.MONTHLY)=RecurringTemplate("bill",1,"Service","Essentials",PlanNature.FIXED,12345,Currency.CRC,"2027-01",frequency,31,30,3)
 @Test fun monthEndRestoresNominalDayAndLeapYear() { val t=template(); assertEquals("2027-02-28",PlanningMath.slots(t,YearMonth.parse("2027-02")).single().toString()); assertEquals("2027-03-31",PlanningMath.slots(t,YearMonth.parse("2027-03")).single().toString()); assertEquals("2028-02-29",PlanningMath.slots(t,YearMonth.parse("2028-02")).single().toString()) }
 @Test fun twoFixedSlotsRemainTwoWhenClampedTogether() { val dates=PlanningMath.slots(template(Recurrence.TWO_SLOTS),YearMonth.parse("2027-02")); assertEquals(2,dates.size); assertEquals(dates[0],dates[1]) }
 @Test fun annualAndEveryNMonthsRespectAnchor() { assertTrue(PlanningMath.slots(template(Recurrence.ANNUAL),YearMonth.parse("2027-02")).isEmpty()); assertEquals(1,PlanningMath.slots(template(Recurrence.EVERY_N_MONTHS),YearMonth.parse("2027-04")).size); assertTrue(PlanningMath.slots(template(Recurrence.EVERY_N_MONTHS),YearMonth.parse("2027-05")).isEmpty()) }
 @Test fun salaryUsesLastWeekdayWithoutHolidayInference() { assertEquals("2027-02-26",PlanningMath.salaryDate(YearMonth.parse("2027-02")).toString()); assertEquals("2027-05-31",PlanningMath.salaryDate(YearMonth.parse("2027-05")).toString()) }
 @Test fun householdOwnShareRoundsHalfUpAndValidatesBounds() { assertEquals(51,PlanningMath.ownShare(101,"50",null)); assertEquals(17,PlanningMath.ownShare(101,null,17)); try { PlanningMath.ownShare(100,"100.1",null); fail("Out-of-range percentage") } catch (_:IllegalArgumentException) {} }
}
