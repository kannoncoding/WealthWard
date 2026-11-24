package io.github.kannoncoding.wealthward.domain

import java.time.*
import java.math.BigDecimal
import java.math.RoundingMode

enum class PlanNature { INCOME, FIXED, SUBSCRIPTION, VARIABLE_NEED, FREE, SAVING, DEBT, TARGET }
enum class Recurrence { MONTHLY, ANNUAL, EVERY_N_MONTHS, TWO_SLOTS }
data class PlanRow(val id:String, val name:String, val group:String, val nature:PlanNature, val minor:Long, val currency:Currency, val suggestedAccountId:String?=null, val accumulate:Boolean=false)
data class PeriodPlan(val month:String, val version:Int, val rows:List<PlanRow>, val targetMinor:Long=0, val targetCurrency:Currency=Currency.CRC, val reason:String="", val effectiveDate:String=month+"-01")
data class RecurringTemplate(val id:String, val version:Int, val name:String, val group:String, val nature:PlanNature, val minor:Long, val currency:Currency, val effectiveMonth:String, val frequency:Recurrence=Recurrence.MONTHLY, val day:Int=1, val secondDay:Int=15, val intervalMonths:Int=1, val suggestedAccountId:String?=null, val purposeId:String?=null, val paused:Boolean=false, val cancelled:Boolean=false, val householdTotalMinor:Long?=null, val ownPercent:String?=null, val ownFixedMinor:Long?=null, val monthlyContributionMinor:Long=0, val reason:String="", val anchorMonth:String=effectiveMonth)
data class SalaryVersion(val id:String, val effectiveMonth:String, val netMinor:Long, val currency:Currency, val accountId:String?=null, val reason:String="", val scheduleDay:Int?=null)
data class PlannedOccurrence(val id:String, val templateId:String, val templateVersion:Int, val nominalMonth:String, val slot:Int, val name:String, val group:String, val nature:PlanNature, val currency:Currency, val dueDate:String, val expectedMinor:Long, val confirmedMinor:Long?, val paidMinor:Long, val pendingMinor:Long, val suggestedAccountId:String?, val purposeId:String?, val overdue:Boolean, val paymentIds:List<String>, val financingMonth:String=nominalMonth, val cashPaidMinor:Long=paidMinor, val previouslyDeducted: Boolean = false, val paymentTotalMinor: Long = confirmedMinor ?: expectedMinor, val paymentPendingMinor: Long = pendingMinor, val shared: Boolean = false)
data class ReminderPreference(val enabled:Boolean=false, val daysBefore:Int=1, val hour:Int=9, val additionalDays:List<Int> = emptyList()) {
 val leads get() = listOfNotNull(daysBefore.takeIf { it >= 0 }) + additionalDays
}
data class BudgetComparison(val group:String, val nature:PlanNature, val currency:Currency, val budgetMinor:Long, val actualMinor:Long, val differenceMinor:Long, val consumptionMinor:Long=actualMinor)
data class F06Snapshot(val plans:List<PeriodPlan> = emptyList(), val templates:List<RecurringTemplate> = emptyList(), val salaries:List<SalaryVersion> = emptyList(), val occurrences:List<PlannedOccurrence> = emptyList(), val comparisons:List<BudgetComparison> = emptyList(), val reminders:ReminderPreference=ReminderPreference(), val targetBaseMinor:Map<Currency,Long> = emptyMap())
sealed interface F06Command {
 val requestId:String
 data class SavePlan(override val requestId:String,val plan:PeriodPlan):F06Command
 data class SaveTemplate(override val requestId:String,val template:RecurringTemplate):F06Command
 data class SaveSalary(override val requestId:String,val salary:SalaryVersion):F06Command
 data class OpenPeriod(override val requestId:String,val month:String):F06Command
 data class CopyPeriod(override val requestId:String,val sourceMonth:String,val targetMonth:String):F06Command
 data class ConfirmAmount(override val requestId:String,val occurrenceId:String,val minor:Long):F06Command
 data class ConfirmPreviouslyDeducted(override val requestId:String,val occurrenceId:String,val accountId:String,val date:String):F06Command
 data class Pay(override val requestId:String,val occurrenceId:String,val accountId:String,val minor:Long,val date:String):F06Command
 data class LinkPayment(override val requestId:String,val occurrenceId:String,val operationId:String):F06Command
 data class ExtraordinaryIncome(override val requestId:String,val id:String,val name:String,val month:String,val dueDate:String,val expectedMinor:Long,val currency:Currency,val accountId:String?=null, val reason:String="", val scheduleDay:Int?=null):F06Command
 data class FundAnnual(override val requestId:String,val occurrenceId:String,val accountId:String,val minor:Long):F06Command
 data class AssignFundingPeriod(override val requestId:String,val occurrenceId:String,val month:String):F06Command
 data class FundPurpose(override val requestId:String,val templateId:String,val accountId:String,val minor:Long):F06Command
 data class SetReminders(override val requestId:String,val preference:ReminderPreference):F06Command
}
object PlanningMath {
 fun ownShare(total:Long,percent:String?,fixed:Long?):Long {
  require(total>=0 && (percent==null || fixed==null))
  if(fixed!=null) { require(fixed in 0..total); return fixed }
  if(percent==null) return total
  val p=BigDecimal(percent); require(p>=BigDecimal.ZERO && p<=BigDecimal(100))
  return BigDecimal(total).multiply(p).divide(BigDecimal(100),0,RoundingMode.HALF_UP).longValueExact()
 }
 fun due(month:YearMonth,day:Int):LocalDate { require(day in 1..31); return month.atDay(minOf(day,month.lengthOfMonth())) }
 fun salaryDate(month:YearMonth):LocalDate { var day=month.atEndOfMonth(); while(day.dayOfWeek==DayOfWeek.SATURDAY || day.dayOfWeek==DayOfWeek.SUNDAY) day=day.minusDays(1); return day }
 fun slots(template:RecurringTemplate,month:YearMonth):List<LocalDate> {
  val start=YearMonth.parse(template.anchorMonth); val delta=java.time.temporal.ChronoUnit.MONTHS.between(start,month)
  if(delta<0 || template.paused || template.cancelled) return emptyList()
  val interval=when(template.frequency) { Recurrence.ANNUAL->12; Recurrence.EVERY_N_MONTHS->template.intervalMonths; else->1 }; require(interval>0)
  if(delta%interval!=0L) return emptyList()
  return if(template.frequency==Recurrence.TWO_SLOTS) listOf(due(month,template.day),due(month,template.secondDay)) else listOf(due(month,template.day))
 }
}

