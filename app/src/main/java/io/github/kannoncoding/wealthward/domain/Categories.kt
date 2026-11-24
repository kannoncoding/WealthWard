package io.github.kannoncoding.wealthward.domain

import java.util.Locale
import java.util.UUID

data class LedgerCategory(val id: String, val name: String)

internal fun categoryKey(name: String): String = name.trim().lowercase(Locale.ROOT)

internal fun categoryId(name: String): String =
 UUID.nameUUIDFromBytes("wealthward:category:${categoryKey(name)}".toByteArray(Charsets.UTF_8)).toString()

internal fun OperationDetail.matchesCategory(query: String): Boolean {
 val key = categoryKey(query)
 return categoryKey(category).contains(key) || splits.any { categoryKey(it.category).contains(key) }
}
