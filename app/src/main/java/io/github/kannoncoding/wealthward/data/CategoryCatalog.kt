package io.github.kannoncoding.wealthward.data

import io.github.kannoncoding.wealthward.domain.DomainFailure
import io.github.kannoncoding.wealthward.domain.LedgerCategory
import io.github.kannoncoding.wealthward.domain.PlanNature
import io.github.kannoncoding.wealthward.domain.categoryId
import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.categoryRows(key: String): List<JSONObject> =
 optJSONArray(key)?.let { list -> List(list.length()) { list.getJSONObject(it) } }.orEmpty()

internal fun JSONObject.categoryCatalog(): List<LedgerCategory> {
 val catalog = linkedMapOf<String, LedgerCategory>()
 fun add(label: String) {
  val name = label.trim()
  if (name.isNotEmpty()) {
   val id = categoryId(name)
   catalog.putIfAbsent(id, LedgerCategory(id, name))
  }
 }
 categoryRows("categories").forEach { add(it.getString("name")) }
 categoryRows("operations").forEach { operation ->
  if (operation.getString("type") in setOf("EXPENSE", "INCOME", "CREDIT_PURCHASE")) {
   add(operation.getString("category"))
   operation.categoryRows("splits").forEach { add(it.getString("category")) }
  }
 }
 optJSONObject("f06")?.let { planning ->
  planning.categoryRows("plans").forEach { plan ->
   plan.categoryRows("rows").filter { it.getString("nature") in setOf(PlanNature.VARIABLE_NEED.name, PlanNature.FREE.name) }
    .forEach { add(it.getString("name")) }
  }
  planning.categoryRows("templates").filter { it.getString("nature") in setOf(PlanNature.FIXED.name, PlanNature.SUBSCRIPTION.name) }
   .forEach { add(if (it.isNull("category")) it.getString("name") else it.getString("category")) }
 }
 return catalog.values.toList()
}

internal fun JSONObject.saveCategoryCatalog() {
 val categories = categoryCatalog()
 if (categories.isEmpty()) return
 put("categories", JSONArray(categories.map { JSONObject().put("id", it.id).put("name", it.name) }))
}

internal fun JSONObject.validateCategoryCatalog() {
 if (!has("categories")) return
 val rows = getJSONArray("categories")
 val ids = mutableSetOf<String>()
 for (index in 0 until rows.length()) {
  val row = rows.getJSONObject(index)
  val id = row.getString("id")
  val name = row.get("name") as? String ?: throw DomainFailure.Invalid("Invalid category catalog")
  if (name.isBlank() || name != name.trim() || id != categoryId(name) || !ids.add(id))
   throw DomainFailure.Invalid("Invalid category catalog")
 }
}
