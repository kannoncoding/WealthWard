package io.github.kannoncoding.wealthward.ui

import org.junit.Assert.*
import org.junit.Test

class ImportTextTest {
 @Test fun tsvPreservesNotesAndNeverExtractsAmounts() {
  val result = parseImportText("Cuenta\tContenido\tNotas\nDepósito A\tPropósito\tSaldo sugerido 500000; pendiente")
  assertEquals(listOf(listOf("Depósito A", "Propósito", "Saldo sugerido 500000; pendiente")), result)
 }
 @Test fun csvPreservesQuotedCommasAndMultilineNotes() {
  val result = parseImportText("Cuenta,Contenido,Notas\r\nA,\"Reserva, anual\",\"Primera línea\nSegunda \"\"citada\"\"\"")
  assertEquals(listOf(listOf("A", "Reserva, anual", "Primera línea\nSegunda \"citada\"")), result)
 }
 @Test fun missingColumnRejected() {
  assertThrows(IllegalArgumentException::class.java) { parseImportText("Cuenta\tContenido\tNotas\nA\tB") }
 }
 @Test fun unclosedQuotesRejected() {
  assertThrows(IllegalArgumentException::class.java) { parseImportText("A,B,\"nota") }
 }
 @Test fun duplicateRowsRemainVisibleForExplicitResolution() {
  assertEquals(2, parseImportText("A,B,C\nA,B,C").size)
 }
}
