package io.github.kannoncoding.wealthward.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.kannoncoding.wealthward.domain.Currency
import io.github.kannoncoding.wealthward.domain.PlanNature
import io.github.kannoncoding.wealthward.domain.PlannedOccurrence
import io.github.kannoncoding.wealthward.domain.RecurringTemplate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccountMonthUiTest {
    @get:Rule val ui = createComposeRule()

    @Test fun recordActionsKeepTwoEqualColumnsAndAccessibleTargets() {
        val names = listOf("Registrar pago", "Confirmar importe", "Vincular movimiento", "Cambiar periodo")
        var clicked: String? = null
        ui.setContent {
            val density = Density(LocalDensity.current.density, fontScale = 1.5f)
            CompositionLocalProvider(LocalDensity provides density) {
                WealthTheme(false) {
                    Box(Modifier.width(240.dp)) {
                        RecordActionGrid(names.map { name ->
                            recordAction(name, "Alquiler") { clicked = name }
                        })
                    }
                }
            }
        }
        val cells = names.map { name ->
            ui.onNodeWithContentDescription("$name · Alquiler")
                .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        val (topLeft, topRight, bottomLeft, bottomRight) = cells.map { it.getUnclippedBoundsInRoot() }
        assertTrue(topLeft.top == topRight.top && bottomLeft.top == bottomRight.top)
        assertTrue(topLeft.left == bottomLeft.left && topRight.left == bottomRight.left)
        assertTrue(topLeft.right <= topRight.left && topLeft.bottom <= bottomLeft.top)
        val widths = listOf(topLeft, topRight, bottomLeft, bottomRight).map { it.right - it.left }
        assertTrue(widths.max() - widths.min() <= 1.dp)
        cells.last().performClick()
        ui.runOnIdle { assertTrue(clicked == names.last()) }
    }

    @Test fun sharedBillsShowOwnShareGrossAndPaymentStatus() {
        val percent = RecurringTemplate("rent", 1, "Alquiler", "Hogar", PlanNature.FIXED,
            16_000, Currency.CRC, "2026-11", householdTotalMinor = 40_000, ownPercent = "40")
        val fixed = percent.copy(id = "utility", name = "Electricidad", householdTotalMinor = 15_000,
            ownPercent = null, ownFixedMinor = 5_000)
        val pending = PlannedOccurrence("rent:2026-11:0", percent.id, 1, "2026-11", 0,
            percent.name, percent.group, percent.nature, percent.currency, "2026-11-12",
            16_000, null, 0, 16_000, "demo", null, true, emptyList(),
            paymentTotalMinor = 40_000, paymentPendingMinor = 40_000, shared = true)
        val paid = pending.copy(id = "utility:2026-11:0", templateId = fixed.id, name = fixed.name,
            expectedMinor = 5_000, paidMinor = 5_000, pendingMinor = 0,
            paymentTotalMinor = 15_000, paymentPendingMinor = 0)
        ui.setContent {
            WealthTheme(false) {
                Column { AccountMonthlyBills(listOf(pending, paid), listOf(percent, fixed)) { _, _ -> } }
            }
        }
        listOf("Mi aporte: CRC 160.00 · 40%", "Total compartido: CRC 400.00", "Pendiente CRC 400.00",
            "Mi aporte: CRC 50.00", "Total compartido: CRC 150.00", "Pagado")
            .forEach { ui.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed() }
    }

    @Test fun appearancePresetsScaleTextAndKeepRecordActionsAccessible() {
        val size = mutableStateOf(UiSize.STANDARD)
        val dark = mutableStateOf(false)
        var buttonContrast = 0f
        ui.setContent {
            val systemDensity = Density(LocalDensity.current.density, fontScale = 1.5f)
            CompositionLocalProvider(LocalDensity provides systemDensity) {
                WealthTheme(dark.value, size.value) {
                    val palette = LocalPalette.current
                    SideEffect {
                        val fill = palette.panel.luminance()
                        val border = palette.actionOutline.luminance()
                        buttonContrast = (maxOf(fill, border) + .05f) / (minOf(fill, border) + .05f)
                    }
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("Muestra", style = MaterialTheme.typography.bodyMedium)
                        CompactRecordAction("Editar prueba") { }
                        CompactRecordAction("Anular prueba", enabled = false) { }
                        AppearanceSettings(Appearance.SYSTEM, size.value, select = {}, selectSize = { size.value = it })
                    }
                }
            }
        }

        val sample = ui.onNodeWithText("Muestra")
        fun sampleHeight() = with(sample.getUnclippedBoundsInRoot()) { bottom - top }
        val standardHeight = sampleHeight()
        val edit = ui.onNodeWithText("Editar prueba")
        ui.runOnIdle { assertTrue(buttonContrast >= 3f) }
        val compact = ui.onNodeWithText(localized("Compacto"))
        compact.performScrollTo().performClick().assertIsSelected()
        assertTrue(sampleHeight() < standardHeight)
        edit.performScrollTo().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)

        ui.runOnIdle { dark.value = true }
        val large = ui.onNodeWithText(localized("Grande"))
        large.performScrollTo().performClick().assertIsSelected()
        assertTrue(sampleHeight() > standardHeight)
        edit.performScrollTo().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        ui.onNodeWithText("Anular prueba").assertIsNotEnabled()
        ui.runOnIdle { assertTrue(buttonContrast >= 3f) }
    }

    @Test fun navigationLabelsFitOnNarrowScreensAndScrollWithLargeText() {
        val largeText = mutableStateOf(false)
        val selected = mutableStateOf(0)
        val tabs = listOf("Resumen", "Movimientos", "Finanzas", "Config")
        ui.setContent {
            val deviceDensity = LocalDensity.current.density
            val systemDensity = Density(deviceDensity, fontScale = if (largeText.value) 2f else 1f)
            CompositionLocalProvider(LocalDensity provides systemDensity) {
                WealthTheme(false, if (largeText.value) UiSize.LARGE else UiSize.STANDARD) {
                    Box(Modifier.width(320.dp)) {
                        WealthNavigationBar(tabs, selected.value) { selected.value = it }
                    }
                }
            }
        }

        fun assertWholeLabel(title: String) {
            val layouts = mutableListOf<TextLayoutResult>()
            ui.onNodeWithText(title, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { read -> assertTrue(read(layouts)) }
            val layout = layouts.single()
            val stage = if (largeText.value) "large" else "standard"
            val visibleEnd = layout.getLineEnd(0, visibleEnd = true)
            val lineWidth = layout.getLineRight(0) - layout.getLineLeft(0)
            // String Text semantics recalculate the paragraph using all available space. Compare
            // the actual glyph line with the measured Text instead of using the paragraph width.
            assertTrue(
                "Tab '$title' in $stage mode clipped: size=${layout.size}, lineWidth=$lineWidth, " +
                    "visibleEnd=$visibleEnd/${title.length}, constraints=${layout.layoutInput.constraints}, " +
                    "heightOverflow=${layout.didOverflowHeight}",
                visibleEnd == title.length && lineWidth <= layout.size.width + 1f && !layout.didOverflowHeight,
            )
        }

        tabs.forEach { title ->
            ui.onNodeWithText(title).assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            assertWholeLabel(title)
        }
        ui.runOnIdle { largeText.value = true }
        listOf("Config", "Movimientos").forEach { title ->
            ui.onNodeWithText(title).performScrollTo().performClick().assertIsSelected()
            assertWholeLabel(title)
        }
    }
    @Test fun financeHubOpensEachModuleInTheRequestedOrder() {
        val routes = mutableListOf<String>()
        val sections = listOf("Cuentas", "Gastos y Suscripciones", "Ingresos", "Deudas y Préstamos", "Ahorros y Metas")
        ui.setContent {
            WealthTheme(false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FinanceMenu { route, _ -> routes.add(route) }
                }
            }
        }
        var lastTop = -1.dp
        sections.forEach { label ->
            val item = ui.onNodeWithText(label)
            val top = item.getUnclippedBoundsInRoot().top
            assertTrue("$label must follow the previous module", top > lastTop)
            lastTop = top
        }
        sections.forEach { ui.onNodeWithText(it).performScrollTo().performClick() }
        ui.runOnIdle {
            org.junit.Assert.assertEquals(
                listOf("sectionFinancialAccounts", "sectionBills", "sectionIncome", "sectionDebt", "sectionFinancialSavings"),
                routes,
            )
        }
    }
}
