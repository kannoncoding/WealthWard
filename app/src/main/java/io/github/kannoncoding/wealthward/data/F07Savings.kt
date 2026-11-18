package io.github.kannoncoding.wealthward.data

import io.github.kannoncoding.wealthward.domain.*
import java.time.YearMonth
import org.json.JSONArray
import org.json.JSONObject

internal data class SavingLot(val month: String?, val minor: Long)

private fun JSONObject.lots(key: String): List<SavingLot> =
    f07Rows(key).map { SavingLot(it.f07Text("month"), it.f07Long("minor")) }

private fun List<SavingLot>.json() = JSONArray(map {
    JSONObject().put("month", it.month ?: JSONObject.NULL).put("minor", it.minor)
})

/** Within a reserve, earlier funding is withdrawn first. Transfers keep the funding month. */
internal class SavingLots(data: JSONObject) {
    private val balances = mutableMapOf<Pair<String, String>, MutableList<SavingLot>>()

    init {
        data.f07Rows("savingEvents").forEach(::apply)
    }

    private fun balance(account: String, goal: String) = balances.getOrPut(account to goal) { mutableListOf() }

    fun describeMove(row: JSONObject, bookedMinor: Long) {
        val event = row.savingEvent()
        val balance = balance(event.accountId, event.goalId).toMutableList()
        val tracked = f07Sum(balance.map { it.minor })
        val discrepancy = bookedMinor - tracked
        if (event.action in setOf(SavingAction.WITHDRAW, SavingAction.REALLOCATE, SavingAction.TRANSFER)) {
            align(balance, discrepancy)
            row.put("adjustmentMinor", discrepancy).put("lots", take(balance, event.minor).json())
        } else row.put("adjustmentMinor", 0).put("lots", JSONArray())
    }

    private fun apply(row: JSONObject) {
        val event = row.savingEvent()
        val source = balance(event.accountId, event.goalId)
        val discrepancy = row.f07Long("adjustmentMinor")
        val lots = row.lots("lots")
        when (event.action) {
            SavingAction.CONTRIBUTE -> source.add(SavingLot(event.date.take(7), event.minor))
            SavingAction.WITHDRAW, SavingAction.REALLOCATE, SavingAction.TRANSFER -> {
                align(source, discrepancy)
                if (take(source, event.minor) != lots) f07Invalid("Invalid savings funding history")
                if (event.action == SavingAction.REALLOCATE) balance(event.accountId, event.destinationGoalId!!).addAll(lots)
                if (event.action == SavingAction.TRANSFER) balance(event.destinationAccountId!!, event.goalId).addAll(lots)
            }
            SavingAction.EXTERNAL -> Unit
        }
        if (event.action in setOf(SavingAction.CONTRIBUTE, SavingAction.EXTERNAL) && (discrepancy != 0L || lots.isNotEmpty()))
            f07Invalid("Invalid savings funding history")
    }

    private fun align(balance: MutableList<SavingLot>, discrepancy: Long) {
        // Expenses can use savings and refunds can restore them. A restored amount with
        // no savings event does not count toward the current contribution target.
        if (discrepancy < 0) take(balance, -discrepancy)
        if (discrepancy > 0) balance.add(SavingLot(null, discrepancy))
    }

    private fun take(balance: MutableList<SavingLot>, minor: Long): List<SavingLot> {
        var remaining = minor
        val released = mutableListOf<SavingLot>()
        balance.sortWith(compareBy(nullsFirst<String>()) { it.month })
        while (remaining > 0) {
            val lot = balance.firstOrNull() ?: f07Invalid("Insufficient savings funding")
            val amount = minOf(remaining, lot.minor)
            released.add(lot.copy(minor = amount))
            remaining -= amount
            if (amount == lot.minor) balance.removeAt(0) else balance[0] = lot.copy(minor = lot.minor - amount)
        }
        return released
    }
}

internal fun netSavingContributions(data: JSONObject, goalId: String, month: String): Long {
    YearMonth.parse(month)
    return f07Sum(data.f07Rows("savingEvents").filter { it.getString("date").startsWith(month) }.map { row ->
        val event = row.savingEvent()
        val movedThisMonth = f07Sum(row.lots("lots").filter { it.month == month }.map { it.minor })
        when (event.action) {
            SavingAction.CONTRIBUTE -> if (event.goalId == goalId) event.minor else 0
            SavingAction.WITHDRAW -> if (event.goalId == goalId) -movedThisMonth else 0
            SavingAction.REALLOCATE -> when (goalId) {
                event.goalId -> -movedThisMonth
                event.destinationGoalId -> movedThisMonth
                else -> 0
            }
            SavingAction.TRANSFER, SavingAction.EXTERNAL -> 0
        }
    })
}

internal fun commitmentSavings(state: JSONObject, month: String): Map<Triple<String, PlanNature, Currency>, Pair<Long, Long>> {
    val data = state.commitments()
    return data.f07Rows("goals").map { it.savingGoal() }.groupBy { Triple(it.group, PlanNature.SAVING, it.currency) }
        .mapValues { (_, goals) -> f07Sum(goals.map { it.monthlyMinor }) to f07Sum(goals.map { netSavingContributions(data, it.id, month) }) }
}

internal fun commitmentExternalSpending(state: JSONObject, month: String): Map<Triple<String, PlanNature, Currency>, Long> {
    val data = state.commitments()
    val goals = data.f07Rows("goals").map { it.savingGoal() }.associateBy { it.id }
    return data.f07Rows("savingEvents").map { it.savingEvent() }
        .filter { it.action == SavingAction.EXTERNAL && it.date.startsWith(month) }
        .groupBy { goals.getValue(it.goalId).let { goal -> Triple(goal.group, PlanNature.FIXED, goal.currency) } }
        .mapValues { (_, events) -> f07Sum(events.map { it.minor }) }
}

internal fun savingLedgerCommand(event: SavingEvent, goal: SavingGoal, data: JSONObject, linkId: String): F05Command {
    val purpose = savingPurpose(goal.id)
    return when (event.action) {
        SavingAction.CONTRIBUTE, SavingAction.WITHDRAW -> F05Command.FundReserve(linkId, event.accountId,
            purpose, goal.name, if (event.action == SavingAction.WITHDRAW) -event.minor else event.minor)
        SavingAction.REALLOCATE -> {
            val to = data.f07Row("goals", event.destinationGoalId!!).savingGoal()
            F05Command.Redistribute(linkId, event.accountId, purpose, savingPurpose(to.id), to.name, event.minor)
        }
        SavingAction.TRANSFER -> F05Command.Transfer(linkId, event.accountId, event.destinationAccountId!!,
            event.minor, event.minor, event.date, purposeId = purpose)
        SavingAction.EXTERNAL -> F05Command.Record(linkId, event.accountId, "EXPENSE", event.minor,
            event.date, event.recipient!!, goal.name)
    }
}
