package io.github.kannoncoding.wealthward.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal data class CompactAction(val label: String, val shortLabel: String, val onClick: () -> Unit)

@Composable
internal fun MovementActions(open: (String, String) -> Unit) {
    CompactActionGrid(listOf(
        CompactAction(localized("Registrar gasto"), localized("Gasto")) { open("expense", "") },
        CompactAction(localized("Registrar ingreso"), localized("Ingreso")) { open("income", "") },
        CompactAction(localized("Compra con tarjeta de crédito"), localized("Compra\na crédito")) { open("credit", "") },
        CompactAction(localized("Pagar tarjeta de crédito"), localized("Pagar\ntarjeta")) { open("payment", "") },
        CompactAction(localized("Transferir entre cuentas"), localized("Transferir")) { open("transfer", "") },
        CompactAction(localized("Retirar efectivo"), localized("Retirar\nefectivo")) { open("withdraw", "") },
        CompactAction(localized("Conversión CRC / USD"), localized("Cambio\nCRC / USD")) { open("fx", "") },
        CompactAction(localized("Autorizar retención pendiente"), localized("Autorizar\nretención")) { open("hold", "") },
    ))
}

@Composable
internal fun AccountActions(actions: List<CompactAction>) {
    CompactActionGrid(actions)
}

@Composable
private fun CompactActionGrid(actions: List<CompactAction>) {
    val palette = LocalPalette.current
    val spacing = LocalUiSize.current.spacingScale
    val gap = 6.dp * spacing
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val readableCell = (62.dp * fontScale).coerceAtLeast(48.dp)
        val columns = when {
            maxWidth >= readableCell * 4 + gap * 3 -> 4
            maxWidth >= readableCell * 2 + gap -> 2
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            actions.chunked(columns).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    row.forEach { action ->
                        Box(
                            Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)
                                .actionSurface()
                                .clickable(role = Role.Button, onClick = action.onClick)
                                .semantics { contentDescription = action.label }
                                .padding(horizontal = 4.dp, vertical = 6.dp * spacing),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                action.shortLabel,
                                modifier = Modifier.clearAndSetSemantics { },
                                color = palette.ink,
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                            )
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
internal fun outlineActionColors(): ButtonColors {
    val palette = LocalPalette.current
    return ButtonDefaults.outlinedButtonColors(
        containerColor = palette.panel,
        contentColor = palette.ink,
        disabledContainerColor = palette.panel,
        disabledContentColor = palette.ink.copy(alpha = .45f),
    )
}

@Composable
internal fun CompactRecordAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val spacing = LocalUiSize.current.spacingScale
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        shape = RoundedCornerShape(9.dp * spacing),
        colors = outlineActionColors(),
        border = BorderStroke(1.dp, palette.actionOutline.copy(alpha = if (enabled) 1f else 0.45f)),
        contentPadding = PaddingValues(horizontal = 10.dp * spacing, vertical = 4.dp * spacing),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
