package io.github.kannoncoding.wealthward.ui

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarDateField(
    label: String,
    value: String,
    enabled: Boolean = true,
    optional: Boolean = false,
    onChange: (String) -> Unit,
) {
    var open by rememberSaveable(label) { mutableStateOf(false) }
    val locale = AppLanguage.current.locale
    val selected = remember(value) { runCatching { LocalDate.parse(value) }.getOrNull() }
    val formatter = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val display = selected?.format(formatter) ?: value.ifBlank { localized("Elegir fecha") }
    OutlinedButton(
        onClick = { open = true },
        enabled = enabled,
        shape = MaterialTheme.shapes.extraSmall,
        colors = outlineActionColors(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .semantics { contentDescription = label },
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(display, style = MaterialTheme.typography.bodyLarge)
        }
        val ink = LocalContentColor.current
        Canvas(Modifier.padding(start = 12.dp).size(20.dp)) {
            val stroke = 1.5.dp.toPx()
            drawRoundRect(
                ink, Offset(stroke, size.height * .2f),
                Size(size.width - 2 * stroke, size.height * .75f),
                CornerRadius(stroke), style = Stroke(stroke),
            )
            drawLine(ink, Offset(stroke, size.height * .44f), Offset(size.width - stroke, size.height * .44f), stroke)
            listOf(.3f, .7f).forEach { x ->
                drawLine(ink, Offset(size.width * x, stroke), Offset(size.width * x, size.height * .30f), stroke)
            }
        }
    }
    if (!open) return

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val calendarConfiguration = remember(configuration, locale) {
        Configuration(configuration).apply { setLocale(locale) }
    }
    val calendarContext = remember(context, calendarConfiguration) {
        context.createConfigurationContext(calendarConfiguration)
    }
    CompositionLocalProvider(
        LocalContext provides calendarContext,
        LocalConfiguration provides calendarConfiguration,
    ) {
        val initialDate = selected ?: LocalDate.parse(today())
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = selected?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            initialDisplayedMonthMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = minOf(1900, initialDate.year)..maxOf(2100, initialDate.year),
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                    picker.selectedDateMillis?.let { millis ->
                        onChange(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate().toString())
                        open = false
                    }
                }) { Text(localized("Aplicar fecha")) }
            },
            dismissButton = {
                if (optional && value.isNotBlank()) {
                    TextButton(onClick = { onChange(""); open = false }) { Text(localized("Quitar fecha")) }
                }
                TextButton(onClick = { open = false }) { Text(localized("Cancelar")) }
            },
        ) {
            CompositionLocalProvider(
                LocalContext provides calendarContext,
                LocalConfiguration provides calendarConfiguration,
            ) {
                DatePicker(
                    state = picker,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    title = {
                        Text(
                            label,
                            Modifier.padding(start = 24.dp, top = 16.dp, end = 24.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                )
            }
        }
    }
}
