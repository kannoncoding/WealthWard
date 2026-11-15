package io.github.kannoncoding.wealthward.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.kannoncoding.wealthward.domain.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.security.MessageDigest

/** Parses literal tabular text only; never interprets notes as money or formulas. */
internal fun parseImportText(text: String): List<List<String>> {
 require(text.isNotBlank()) { "Pega una tabla con Cuenta, Contenido y Notas." }
 val separator = if (text.contains('\t')) '\t' else ','
 val rows = mutableListOf<List<String>>()
 val row = mutableListOf<String>()
 val cell = StringBuilder()
 var quoted = false
 var index = 0
 fun finishRow() { row.add(cell.toString()); cell.clear(); if (row.any { it.isNotBlank() }) rows.add(row.toList()); row.clear() }
 while (index < text.length) {
  val ch = text[index]
  when {
   ch == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> { cell.append('"'); index++ }
   ch == '"' -> quoted = !quoted
   ch == separator && !quoted -> { row.add(cell.toString()); cell.clear() }
   (ch == '\n' || ch == '\r') && !quoted -> { finishRow(); if (ch == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++ }
   else -> cell.append(ch)
  }
  index++
 }
 require(!quoted) { "Hay comillas sin cerrar en la tabla." }
 if (cell.isNotEmpty() || row.isNotEmpty()) finishRow()
 val content = if (rows.firstOrNull()?.firstOrNull()?.trim()?.lowercase() in listOf("cuenta", "account")) rows.drop(1) else rows
 require(content.isNotEmpty()) { "La tabla no tiene filas." }
 require(content.size <= 100) { "Revisa hasta 100 filas por lote." }
 require(content.all { it.size == 3 }) { "Cada fila debe tener tres columnas: Cuenta, Contenido y Notas. Usa tabuladores o CSV con comillas." }
 return content
}
private fun rowIdentity(cells: List<String>): String = MessageDigest.getInstance("SHA-256").digest(cells.joinToString("\u001f").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

@Composable internal fun ImportForm(snapshot: F05Snapshot, draft: Drafts, busy: Boolean, onError: (String) -> Unit, onSubmit: (F05Command) -> Unit, preview: suspend (List<ImportRow>) -> List<ImportIssue>) {
 val f = Fields(draft, busy)
 val scope = rememberCoroutineScope()
 var checking by remember { mutableStateOf(false) }
 val rows = runCatching { JSONArray(f.value("rows", "[]")).let { array -> (0 until array.length()).map { index -> array.getJSONArray(index).let { row -> (0 until row.length()).map { row.getString(it) } } } } }.getOrDefault(emptyList())
 Text("Pega tres columnas: Cuenta, Contenido y Notas. Se conserva el texto original. Solo los importes que escribas y confirmes podrán cambiar saldos o reservas.")
 f.text("paste", "Tabla CSV o TSV · Cuenta / Contenido / Notas", lines = 5)
 Action("Preparar vista previa", !busy && !checking) {
  try {
   val parsed = parseImportText(f.value("paste"))
   draft.set("rows", JSONArray(parsed.map { JSONArray(it) }).toString())
   draft.set("issues", "")
   draft.set("previewed", "false")
   draft.set("batchConfirmed", "false")
  } catch (e: Exception) { onError(userError(e)) }
 }
 if (rows.isEmpty()) return
 val accounts = snapshot.accounts.filter { !it.archived }
 Text("${rows.size} filas · lote ${draft.request().take(8)}", style = MaterialTheme.typography.titleMedium)
 Text("Crea primero las cuentas necesarias en Cuentas. La apertura solo puede importarse a cuentas sin historial. Un importe desconocido permanece sin confirmar; no se deduce de las notas.")
 rows.groupBy { it[0] }.filterValues { it.size > 1 }.forEach { (alias, matching) ->
  val aliasKey = "alias-${rowIdentity(listOf(alias))}"
  Text("Alias repetido: $alias", style = MaterialTheme.typography.titleMedium)
  val account = f.choice("$aliasKey-account", "Resolver alias $alias", listOf("" to "Sin asignar") + accounts.map { it.id to "${it.name} · ${it.currency}" })
  f.text("$aliasKey-opening", "Apertura común verificada · $alias", numeric = true)
  f.text("$aliasKey-date", "Fecha de apertura común · $alias · AAAA-MM-DD")
  Action("Aplicar mapeo a ${matching.size} filas de $alias", !busy && account.isNotBlank()) {
   matching.forEach { cells -> val prefix = "row-${rowIdentity(cells)}"; draft.set("$prefix-account", account); draft.set("$prefix-opening", f.value("$aliasKey-opening")); draft.set("$prefix-date", f.value("$aliasKey-date")); draft.set("$prefix-confirmed", "false") }
   draft.set("previewed", "false")
  }
 }
 val mappings = mutableListOf<ImportRow>()
 var parsable = true
 rows.forEachIndexed { index, cells ->
  val identity = rowIdentity(cells)
  val prefix = "row-$identity"
  InfoCard("Fila ${index + 1} · ${cells[0]}", "Contenido: ${cells[1]}\nNotas originales: ${cells[2]}", 3)
  if (rows.count { rowIdentity(it) == identity } > 1) Text("Fila duplicada en este lote: elimina el duplicado de la tabla antes de confirmar.", color = MaterialTheme.colorScheme.error)
  val account = f.choice("$prefix-account", "Cuenta y moneda de destino · fila ${index + 1}", listOf("" to "Sin asignar · requiere decisión") + accounts.map { it.id to "${it.name} · ${it.currency}" })
  f.text("$prefix-content", "Contenido revisado · fila ${index + 1}", cells[1], lines = 2)
  f.text("$prefix-notes", "Notas conservadas · fila ${index + 1}", cells[2], lines = 2)
  f.text("$prefix-opening", "Apertura explícita · fila ${index + 1}", numeric = true)
  f.text("$prefix-date", "Fecha de apertura · fila ${index + 1} · AAAA-MM-DD")
  val reserve = f.check("$prefix-reserve", "También confirmar una reserva financiada en esta cuenta")
  val purpose = if (reserve) f.choice("$prefix-purpose", "Propósito de la reserva · fila ${index + 1}", listOf("new" to "Crear propósito") + snapshot.accounts.flatMap { it.reserves }.distinctBy { it.purposeId }.map { it.purposeId to it.name }) else ""
  if (reserve) { if (purpose == "new") f.text("$prefix-purposeName", "Nombre del propósito · fila ${index + 1}"); f.text("$prefix-reserveAmount", "Importe real de reserva · fila ${index + 1}", numeric = true) }
  val confirmed = f.check("$prefix-confirmed", "Verifiqué cuenta, moneda, apertura y fecha de esta fila")
  val opening = if (f.value("$prefix-opening").isBlank()) null else runCatching { f.amount("$prefix-opening", "apertura de fila ${index + 1}", allowZero = true, signed = accounts.find { it.id == account }?.kind == "LIABILITY") }.getOrElse { parsable = false; null }
  val reserveAmount = if (reserve) runCatching { f.amount("$prefix-reserveAmount", "reserva de fila ${index + 1}") }.getOrElse { parsable = false; null } else null
  val date = f.value("$prefix-date").ifBlank { null }
  mappings.add(ImportRow(identity, cells[0], cells[1], cells[2], account.ifBlank { null }, opening, date, reserveAmount, if (reserve) if (purpose == "new") "purpose-${draft.request()}-$identity" else purpose else null, if (reserve) if (purpose == "new") f.value("$prefix-purposeName") else snapshot.accounts.flatMap { it.reserves }.find { it.purposeId == purpose }?.name else null, confirmed, f.value("$prefix-content", cells[1]), f.value("$prefix-notes", cells[2])))
 }
 val fingerprint = mappings.toString()
 val verified = f.value("previewFingerprint") == fingerprint && f.value("previewed") == "true"
 if (f.value("issues").isNotBlank()) Text(f.value("issues"), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
 Action(if (checking) "Revisando…" else "Validar mapeo y mostrar incidencias", !busy && !checking) {
  if (!parsable) onError("Revisa los importes de apertura y reserva: máximo dos decimales.")
  else {
   checking = true
   scope.launch {
    try {
     val issues = preview(mappings)
     draft.set("issues", if (issues.isEmpty()) "Vista previa validada. Ningún saldo ha cambiado." else issues.joinToString("\n") { issue -> "Fila ${mappings.indexOfFirst { it.rowId == issue.rowId } + 1}: ${issue.message}" })
     draft.set("previewed", issues.isEmpty().toString())
     draft.set("previewFingerprint", fingerprint)
    } catch (e: Exception) { onError(userError(e)) }
    finally { checking = false }
   }
  }
 }
 val confirmed = f.check("batchConfirmed", "Confirmo explícitamente este lote; sus saldos y reservas son los que revisé")
 if (verified) Text("Listo para confirmar ${mappings.size} filas. Aperturas por moneda: " + Currency.entries.joinToString(" · ") { currency -> "$currency ${total(mappings.distinctBy { it.accountId }.filter { row -> accounts.find { it.id == row.accountId }?.currency == currency }.map { it.confirmedOpeningMinor ?: 0L })}" })
 Button(onClick = { try { require(parsable && verified) { "Valida la vista previa después del último cambio." }; require(confirmed) { "Confirma el lote después de revisarlo." }; onSubmit(F05Command.ImportBatch(draft.request(), mappings)) } catch (e: Exception) { onError(userError(e)) } }, enabled = !busy && !checking && verified && confirmed, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Confirmar importación atómica") }
}
