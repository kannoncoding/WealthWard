package io.github.kannoncoding.wealthward.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.kannoncoding.wealthward.data.BACKUP_VERSION
import io.github.kannoncoding.wealthward.data.BackupCandidate
import io.github.kannoncoding.wealthward.data.F10Repository
import io.github.kannoncoding.wealthward.data.FinancialSummary
import io.github.kannoncoding.wealthward.domain.BusinessTime
import io.github.kannoncoding.wealthward.domain.DomainFailure
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.Reader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BACKUP_WARNING =
    "El archivo contiene tus datos financieros y se guarda sin cifrar. Guárdalo en un lugar privado."
private const val REPLACE_WARNING =
    "Al restaurar, los datos financieros actuales se reemplazarán por los del respaldo."
private const val BACKUP_CHARACTER_LIMIT = 16 * 1024 * 1024
private class BackupSizeException : IOException()

@Composable
internal fun F10Configuration(open: (String) -> Unit) {
    Text("Respaldo y exportación", style = MaterialTheme.typography.titleMedium)
    Action("Crear respaldo") { open("f10Backup") }
    Action("Restaurar desde respaldo") { open("f10Restore") }
    Action("Exportar resumen") { open("f10Summary") }
}

@Composable
internal fun F10Screen(
    screen: String,
    files: F10Repository,
    busy: Boolean,
    setBusy: (Boolean) -> Unit,
    showError: (String) -> Unit,
    showSuccess: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resolver = context.contentResolver
    var inspected by remember { mutableStateOf<Pair<String, BackupCandidate>?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<FinancialSummary?>(null) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var pendingExport by remember { mutableStateOf<FinancialSummary?>(null) }

    fun perform(errorMessage: String, work: suspend () -> Unit) {
        if (busy) return
        setBusy(true)
        showError("")
        showSuccess("")
        scope.launch {
            try {
                work()
            } catch (error: CancellationException) {
                throw error
            } catch (error: DomainFailure.UnsupportedVersion) {
                showError("La versión del respaldo no es compatible. Elige un archivo de respaldo de una versión compatible de WealthWard.")
            } catch (_: BackupSizeException) {
                showError("El respaldo supera el tamaño admitido por esta versión de WealthWard.")
            } catch (_: Exception) {
                showError(errorMessage)
            } finally {
                setBusy(false)
            }
        }
    }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) perform("No se pudo guardar el respaldo. Elige otra ubicación e inténtalo de nuevo.") {
            withContext(Dispatchers.IO) {
                val document = files.backup()
                if (document.length > BACKUP_CHARACTER_LIMIT) throw BackupSizeException()
                resolver.writeDocument(uri) { output ->
                    output.writer(Charsets.UTF_8).apply { write(document); flush() }
                }
            }
            showSuccess("Respaldo guardado.")
        }
    }
    val read = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            inspected = null
            perform("El archivo no se pudo leer o sus datos son inválidos. Elige otro respaldo. Tus datos siguen intactos.") {
                inspected = withContext(Dispatchers.IO) {
                    val input = resolver.openInputStream(uri) ?: throw IOException("Document unavailable")
                    val document = InputStreamReader(input, Charsets.UTF_8.newDecoder()).use { it.readBackup() }
                    val candidate = files.inspectBackup(document)
                    resolver.documentName(uri) to candidate
                }
            }
        }
    }

    fun saveSummary(uri: Uri?, pdf: Boolean) {
        val exactReport = pendingExport
        pendingExport = null
        if (uri == null) return
        if (exactReport == null) {
            showError("Se cerró la vista previa. Revisa el resumen y vuelve a guardarlo.")
            return
        }
        perform("No se pudo guardar el resumen. Elige otra ubicación e inténtalo de nuevo.") {
            withContext(Dispatchers.IO) {
                resolver.writeDocument(uri) { output ->
                    if (pdf) exactReport.writePdf(output) else exactReport.writeCsv(output)
                }
            }
            showSuccess(if (pdf) "Resumen PDF guardado." else "Resumen CSV guardado.")
        }
    }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) {
        saveSummary(it, pdf = true)
    }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {
        saveSummary(it, pdf = false)
    }
    fun loadSummary() = perform("No se pudo generar el resumen. Inténtalo de nuevo.") {
        report = files.summary()
        selection = report?.rows.orEmpty().mapTo(mutableSetOf()) { it.id }
    }

    fun launchPicker(action: () -> Unit) {
        showError("")
        showSuccess("")
        try {
            action()
        } catch (_: Exception) {
            pendingExport = null
            showError("No se pudo abrir el selector de archivos de Android.")
        }
    }

    when (screen) {
        "f10Backup" -> {
            Text(BACKUP_WARNING)
            Action("Elegir dónde guardar", !busy) {
                launchPicker { create.launch("wealthward-respaldo-${today()}.json") }
            }
        }
        "f10Restore" -> {
            Text("Elige un respaldo de WealthWard para revisar sus datos antes de restaurar.")
            Action("Elegir archivo de respaldo", !busy) {
                launchPicker { read.launch(arrayOf("*/*")) }
            }
            inspected?.let { (name, candidate) ->
                val createdDay = candidate.createdAt.atZone(BusinessTime.zone).toLocalDate()
                InfoCard("Respaldo revisado", "$name\nCreado el $createdDay · Versión $BACKUP_VERSION\n" +
                    "Cuentas y deudas: ${candidate.accountCount}\nMovimientos: ${candidate.movementCount}\n" +
                    "Planes: ${candidate.planCount}", 0)
                SummaryTotals(candidate.summary)
                Text(REPLACE_WARNING)
                Action("Continuar para restaurar", !busy) { confirm = true }
                if (confirm) {
                    AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = { Text("¿Reemplazar los datos actuales?") },
                        text = { Text(REPLACE_WARNING) },
                        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
                        confirmButton = {
                            TextButton(onClick = {
                                confirm = false
                                perform("No se pudo restaurar el respaldo. Tus datos siguen intactos.") {
                                    files.restore(candidate)
                                    inspected = null
                                    showSuccess("Respaldo restaurado. Los saldos y la configuración se actualizaron.")
                                }
                            }) { Text("Reemplazar y restaurar") }
                        },
                    )
                }
            }
        }
        "f10Summary" -> {
            LaunchedEffect(files) { loadSummary() }
            val current = report
            if (current == null && !busy) Action("Generar vista previa") { loadSummary() }
            if (current != null) {
                val selected = current.copy(rows = current.rows.filter { it.id in selection })
                Text("Elige las cuentas y deudas que incluirás. El archivo contiene datos financieros.")
                current.rows.forEach { row ->
                    val checked = row.id in selection
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(checked, enabled = !busy, role = Role.Checkbox) {
                                selection = if (it) selection + row.id else selection - row.id
                            }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked, onCheckedChange = null, enabled = !busy,
                            modifier = Modifier.padding(end = 12.dp))
                        Text("${row.alias} · ${row.currency} · ${row.type.label}" +
                            if (row.archived) " · Archivada" else "")
                    }
                }
                Text("Vista previa del resumen", style = MaterialTheme.typography.titleLarge)
                Text("Resumen generado el ${selected.date}")
                if (selected.rows.isEmpty()) Text("Selecciona al menos una cuenta o deuda para guardar el resumen.")
                selected.rows.forEach { row ->
                    InfoCard("${row.alias} · ${row.currency}",
                        row.type.label + (if (row.archived) " · Archivada" else "") + "\n" +
                            row.amounts.display(row.type), 1)
                }
                SummaryTotals(selected)
                listOf("PDF" to pdfPicker, "CSV" to csvPicker).forEach { (format, picker) ->
                    Action("Guardar $format", !busy && selected.rows.isNotEmpty()) {
                        launchPicker {
                            pendingExport = selected
                            picker.launch("wealthward-resumen-${selected.date}.${format.lowercase()}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryTotals(report: FinancialSummary) {
    Column {
        report.totals().forEach { (currency, amounts) ->
            InfoCard("Totales $currency", amounts.display(), 2)
        }
    }
}

private fun ContentResolver.writeDocument(uri: Uri, write: (OutputStream) -> Unit) {
    val output = openOutputStream(uri, "wt") ?: throw IOException("Document unavailable")
    output.buffered().use(write)
}

private fun Reader.readBackup(): String {
    val buffer = CharArray(8192)
    val text = StringBuilder()
    while (true) {
        val size = read(buffer)
        if (size < 0) return text.toString()
        if (text.length + size > BACKUP_CHARACTER_LIMIT) throw BackupSizeException()
        text.append(buffer, 0, size)
    }
}

private fun ContentResolver.documentName(uri: Uri): String = runCatching {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}.getOrNull() ?: "Archivo seleccionado"
