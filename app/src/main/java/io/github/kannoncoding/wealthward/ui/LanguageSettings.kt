package io.github.kannoncoding.wealthward.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

internal enum class AppLanguage(val tag: String, val label: String, val locale: Locale) {
    SPANISH("es", "Español", Locale.forLanguageTag("es-CR")),
    ENGLISH("en", "English", Locale.US);

    fun format(spanish: String, vararg args: Any?): String {
        val template = if (this == ENGLISH) englishCatalog[spanish] ?: spanish else spanish
        if (args.isEmpty()) return template
        return placeholder.replace(template) { match -> args[match.groupValues[1].toInt()].toString() }
    }

    fun backendMessage(message: String): String {
        val messageTemplate = backendPrefixes.firstOrNull { (prefix, _) -> message.startsWith(prefix) }
        if (messageTemplate != null) {
            return format(messageTemplate.second, message.removePrefix(messageTemplate.first))
        }
        val missingRate = rateIssue.matchEntire(message)
        if (missingRate != null) {
            return format("Falta tipo de cambio {0} al corte", missingRate.groupValues[1])
        }
        return if (this == SPANISH) spanishBackendCatalog[message] ?: message else format(message)
    }

    companion object {
        private val placeholder = Regex("\\{(\\d+)\\}")
        private val rateIssue = Regex("Falta tipo de cambio ([A-Z]{3}/[A-Z]{3}) al corte")
        private val backendPrefixes = listOf(
            "Importe pendiente de confirmar: " to "Importe pendiente de confirmar: {0}",
            "Deuda inicial sin fecha o periodo de financiación: " to "Deuda inicial sin fecha o periodo de financiación: {0}",
            "Caja: falta cuenta, fecha o moneda compatible para " to "Caja: falta cuenta, fecha o moneda compatible para {0}",
            "Pendiente de un periodo anterior sin reasignar: " to "Pendiente de un periodo anterior sin reasignar: {0}",
            "Ingreso vencido sin cobro confirmado: " to "Ingreso vencido sin cobro confirmado: {0}",
            "Faltan partidas fechadas para " to "Faltan partidas fechadas para {0}",
        )
        private const val PREFS = "app-language"
        private const val KEY = "selected"

        var current by mutableStateOf(SPANISH)
            private set

        fun read(context: Context): AppLanguage {
            val tag = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.tag == tag } ?: SPANISH
        }

        fun load(context: Context) {
            current = read(context)
        }

        fun select(context: Context, language: AppLanguage) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).apply()
            current = language
        }
    }
}

internal fun localized(spanish: String, vararg args: Any?): String =
    AppLanguage.current.format(spanish, *args)

internal fun io.github.kannoncoding.wealthward.domain.PlannedOccurrence.displayName(): String =
    if (templateId == "salary") localized("Salario") else name

@Composable
internal fun LanguageSettings(context: Context) {
    Panel(Modifier.selectableGroup()) {
        Text(localized("Idioma"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        AppLanguage.entries.forEach { language ->
            val selected = language == AppLanguage.current
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected, role = Role.RadioButton, onClick = { AppLanguage.select(context, language) }),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Text(language.label)
            }
        }
    }
}
