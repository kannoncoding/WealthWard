package io.github.kannoncoding.wealthward.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kannoncoding.wealthward.R
import kotlin.math.roundToInt

internal enum class Appearance(val title: String, val detail: String) {
    LIGHT("Claro", "Usa el tema claro"),
    DARK("Oscuro", "Usa el tema oscuro"),
    SYSTEM("Predeterminado del sistema", "Sigue la apariencia del dispositivo"),
}

internal enum class UiSize(
    val title: String,
    val detail: String,
    val fontScale: Float,
    val spacingScale: Float,
) {
    COMPACT("Compacto", "Texto y espaciado reducidos", 0.9f, 0.85f),
    STANDARD("Estándar", "Texto y espaciado habituales", 1f, 1f),
    LARGE("Grande", "Texto y espaciado ampliados", 1.2f, 1.15f),
}

// Preserve Android's system font conversion while leaving physical dp measurements unchanged.
private data class AppTextDensity(val system: Density, val scale: Float) : Density {
    override val density get() = system.density
    override val fontScale get() = system.fontScale * scale

    override fun TextUnit.toDp(): Dp = with(system) { this@toDp.toDp() } * scale

    override fun Dp.toSp(): TextUnit = with(system) { (this@toSp / scale).toSp() }
}

internal data class StatusColors(val background: Color, val text: Color)
internal data class WealthPalette(
    val base: Color, val gradientEnd: Color, val ink: Color, val panel: Color,
    val divider: Color, val accent: Color, val card: Color, val cardInk: Color, val cardPositive: Color, val cardNegative: Color,
    val selection: StatusColors, val green: StatusColors, val orange: StatusColors, val red: StatusColors,
) {
    val actionOutline = ink.copy(alpha = .48f).compositeOver(panel)
}

private val cream = Color(0xFFEEE3CC)
private val charcoal = Color(0xFF151515)
internal val Turquoise = Color(0xFF00C2B5)
private val light = WealthPalette(
    cream, Color(0xFFE8DBC4), charcoal, Color(0xFFF4EAD8),
    Color(0xFFC6BAA6), Color(0xFF007E7D), charcoal, cream, Turquoise, Color(0xFFF08080),
    StatusColors(charcoal, cream), StatusColors(Color(0xFFD4E5DD), Color(0xFF006A68)),
    StatusColors(Color(0xFFF4D6A9), Color(0xFF704517)), StatusColors(Color(0xFFF1C8C5), Color(0xFFB62E32)),
)
private val dark = WealthPalette(
    charcoal, Color(0xFF101010), cream, Color(0xFF202020),
    Color(0xFF484542), Turquoise, cream, charcoal, Turquoise, Color(0xFFB62E32),
    StatusColors(Turquoise, charcoal), StatusColors(Color(0xFF183B36), Color(0xFF75D8CA)),
    StatusColors(Color(0xFF493929), Color(0xFFF5D8AD)), StatusColors(Color(0xFF492828), Color(0xFFF08080)),
)
internal fun WealthPalette.balanceColor(sign: Int) = when {
    sign > 0 -> Turquoise
    sign < 0 -> red.text
    else -> ink
}

internal val LocalPalette = staticCompositionLocalOf { light }
internal val LocalUiSize = staticCompositionLocalOf { UiSize.STANDARD }

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val inter = FontFamily(listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
    Font(R.font.inter_variable, weight = weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
})

private fun interStyle(size: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = inter, fontWeight = weight, fontSize = size.sp, lineHeight = (size + 5).sp)

private val wealthTypography = Typography(
    displayLarge = interStyle(45, FontWeight.Bold), displayMedium = interStyle(36, FontWeight.Bold),
    displaySmall = interStyle(31, FontWeight.Bold), headlineLarge = interStyle(30, FontWeight.Bold),
    headlineMedium = interStyle(27, FontWeight.Bold), headlineSmall = interStyle(23, FontWeight.Bold),
    titleLarge = interStyle(16, FontWeight.Bold), titleMedium = interStyle(15, FontWeight.SemiBold),
    titleSmall = interStyle(13, FontWeight.SemiBold), bodyLarge = interStyle(16),
    bodyMedium = interStyle(14), bodySmall = interStyle(11), labelLarge = interStyle(14, FontWeight.SemiBold),
    labelMedium = interStyle(12, FontWeight.SemiBold), labelSmall = interStyle(10, FontWeight.SemiBold),
)

@Composable
internal fun WealthTheme(isDark: Boolean, uiSize: UiSize = UiSize.STANDARD, content: @Composable () -> Unit) {
    val p = if (isDark) dark else light
    val systemDensity = LocalDensity.current
    val textDensity = remember(systemDensity, uiSize) {
        if (uiSize == UiSize.STANDARD) systemDensity else AppTextDensity(systemDensity, uiSize.fontScale)
    }
    val default = if (isDark) darkColorScheme() else lightColorScheme()
    val scheme = default.copy(
        primary = p.accent, onPrimary = if (isDark) charcoal else Color.White,
        primaryContainer = p.selection.background, onPrimaryContainer = p.selection.text,
        secondary = p.accent, onSecondary = if (isDark) charcoal else Color.White,
        secondaryContainer = p.selection.background, onSecondaryContainer = p.selection.text,
        tertiary = p.green.text, onTertiary = p.green.background,
        tertiaryContainer = p.green.background, onTertiaryContainer = p.green.text,
        background = p.base, onBackground = p.ink, surface = p.panel, onSurface = p.ink,
        surfaceVariant = p.base, onSurfaceVariant = p.ink, surfaceTint = p.accent,
        surfaceContainerLowest = p.panel, surfaceContainerLow = p.panel, surfaceContainer = p.base,
        surfaceContainerHigh = p.panel, surfaceContainerHighest = p.base,
        outline = p.actionOutline, outlineVariant = p.divider,
        inverseSurface = if (isDark) light.base else dark.base,
        inverseOnSurface = if (isDark) light.ink else dark.ink,
        inversePrimary = if (isDark) light.accent else Turquoise,
        error = p.red.text, onError = p.red.background, errorContainer = p.red.background, onErrorContainer = p.red.text,
    )
    CompositionLocalProvider(LocalPalette provides p, LocalUiSize provides uiSize, LocalDensity provides textDensity) {
        MaterialTheme(colorScheme = scheme, typography = wealthTypography, content = content)
    }
}

@Composable
internal fun Modifier.panelShape(color: Color = LocalPalette.current.panel, radius: Int = 19): Modifier {
    return clip(RoundedCornerShape(radius.dp * LocalUiSize.current.spacingScale)).background(color)
}

@Composable
internal fun Modifier.actionSurface(
    accent: Boolean = false,
    enabled: Boolean = true,
    radius: Int = 10,
    selected: Boolean = false,
): Modifier {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(radius.dp * LocalUiSize.current.spacingScale)
    val fill = when {
        enabled && accent -> palette.selection.background
        enabled && selected -> palette.accent.copy(alpha = .12f).compositeOver(palette.panel)
        else -> palette.panel
    }
    val outline = when {
        !enabled -> palette.actionOutline.copy(alpha = .45f)
        accent -> palette.selection.background
        selected -> palette.accent
        else -> palette.actionOutline
    }
    return clip(shape).background(fill).border(1.dp, outline, shape)
}

@Composable
internal fun Panel(modifier: Modifier = Modifier, color: Color = LocalPalette.current.panel, content: @Composable ColumnScope.() -> Unit) {
    val spacing = LocalUiSize.current.spacingScale
    Column(Modifier.fillMaxWidth().panelShape(color, radius = 11).then(modifier).padding(16.dp * spacing),
        verticalArrangement = Arrangement.spacedBy(8.dp * spacing), content = content)
}

@Composable
internal fun TabIcon(index: Int, color: Color) {
    Canvas(Modifier.size(22.dp * LocalUiSize.current.spacingScale)) {
        val u = size.width / 24
        val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Int, y1: Int, x2: Int, y2: Int) =
            drawLine(color, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), stroke.width, StrokeCap.Round)
        when (index) {
            0 -> drawPath(Path().apply {
                moveTo(3 * u, 10 * u); lineTo(12 * u, 3 * u); lineTo(21 * u, 10 * u)
                lineTo(21 * u, 21 * u); lineTo(3 * u, 21 * u); close()
            }, color, style = stroke)
            1 -> {
                line(3, 7, 20, 7); line(16, 3, 20, 7); line(20, 7, 16, 11)
                line(21, 17, 4, 17); line(8, 13, 4, 17); line(4, 17, 8, 21)
            }
            2 -> {
                drawRoundRect(color, Offset(4 * u, 3 * u), Size(16 * u, 18 * u), CornerRadius(u), style = stroke)
                for (y in listOf(8, 12, 16)) line(8, y, 16, y)
            }
            3 -> {
                drawCircle(color, 9 * u, style = stroke)
                drawCircle(color, 4 * u, style = stroke)
            }
            4 -> {
                val gear = Path()
                for (i in 0 until 32) {
                    val angle = Math.PI * 2 * i / 32
                    val radius = if (i % 4 in 1..2) 10.5 else 8.5
                    val x = size.width / 2 + (radius * kotlin.math.cos(angle) * u).toFloat()
                    val y = size.height / 2 + (radius * kotlin.math.sin(angle) * u).toFloat()
                    if (i == 0) gear.moveTo(x, y) else gear.lineTo(x, y)
                }
                gear.close()
                drawPath(gear, color, style = stroke)
                drawCircle(color, 3.3f * u, style = stroke)
            }
        }
    }
}

@Composable
internal fun WealthNavigationBar(tabs: List<String>, selected: Int, icons: List<Int> = tabs.indices.toList(), onSelect: (Int) -> Unit) {
    if (tabs.isEmpty()) return
    val palette = LocalPalette.current
    val gap = 2.dp
    val labelStyle = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val minWidths = tabs.map { title ->
        val labelWidth = measurer.measure(title, style = labelStyle, softWrap = false, maxLines = 1).size.width
        with(density) { labelWidth.toDp() + 8.dp }.coerceAtLeast(48.dp)
    }
    val scroll = rememberScrollState()

    BoxWithConstraints(
        Modifier.fillMaxWidth().background(palette.gradientEnd).navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val available = maxWidth - gap * (tabs.size - 1)
        val minimum = minWidths.fold(0.dp) { total, width -> total + width }
        val equalWidthsFit = minWidths.all { it * tabs.size <= available }
        val fits = minimum <= available
        val extra = (available - minimum).coerceAtLeast(0.dp) / tabs.size
        val scrollable = if (fits) Modifier else Modifier.horizontalScroll(scroll)
        Row(Modifier.fillMaxWidth().then(scrollable).selectableGroup().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
            tabs.forEachIndexed { index, title ->
                val active = selected == index
                val width = if (equalWidthsFit) Modifier.weight(1f) else Modifier.width(minWidths[index] + extra)
                Column(
                    width.heightIn(min = 52.dp)
                        .selectable(active, role = Role.Tab, onClick = { onSelect(index) })
                        .padding(horizontal = 3.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TabIcon(icons[index], if (active) Turquoise else palette.ink)
                    Text(title, color = if (active) Turquoise else palette.ink, style = labelStyle, softWrap = false, maxLines = 1, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
internal fun BrandLockup(modifier: Modifier = Modifier) {
    val logo = ImageBitmap.imageResource(R.drawable.wealthward_logo)
    // These rectangles isolate the emblem and the original lettering in Logo.png.
    val emblem = IntOffset(301, 305) to IntSize(641, 453)
    val wordmark = IntOffset(152, 777) to IntSize(947, 149)
    val ink = LocalPalette.current.ink
    Canvas(modifier.widthIn(max = 148.dp).fillMaxWidth().aspectRatio(6.9f)
        .semantics { contentDescription = "WealthWard" }) {
        fun drawPart(source: Pair<IntOffset, IntSize>, x: Float, width: Float, color: Color, gold: Boolean = false) {
            val matrix = androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
                0f, 0f, 0f, 0f, color.red * 255, 0f, 0f, 0f, 0f, color.green * 255,
                0f, 0f, 0f, 0f, color.blue * 255,
                if (gold) 5f else -1f, 0f, if (gold) -5f else 2.4f, 0f, if (gold) -65f else -50f,
            ))
            val height = width * source.second.height / source.second.width
            drawImage(logo, source.first, source.second,
                IntOffset(x.roundToInt(), ((size.height - height) / 2).roundToInt()),
                IntSize(width.roundToInt(), height.roundToInt()), colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(matrix))
        }
        drawPart(emblem, 0f, size.width * 0.214f, ink)
        drawPart(emblem, 0f, size.width * 0.214f, Turquoise, gold = true)
        drawPart(wordmark, size.width * 0.272f, size.width * 0.728f, ink)
    }
}

@Composable
internal fun AppearanceSettings(
    current: Appearance,
    uiSize: UiSize,
    select: (Appearance) -> Unit,
    selectSize: (UiSize) -> Unit,
) {
    Panel {
        Text(localized("Tema"), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Appearance.entries.forEach { mode ->
                AppearanceChoice(mode.title, mode.detail, selected = mode == current) { select(mode) }
            }
        }
    }
    Panel {
        Text(localized("Tamaño de interfaz"), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UiSize.entries.forEach { size ->
                AppearanceChoice(size.title, size.detail, selected = size == uiSize) { selectSize(size) }
            }
        }
    }
}

@Composable
private fun AppearanceChoice(title: String, detail: String, selected: Boolean, select: () -> Unit) {
    val p = LocalPalette.current
    val spacing = LocalUiSize.current.spacingScale
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .actionSurface(selected = selected)
            .selectable(selected, role = Role.RadioButton, onClick = select)
            .padding(horizontal = 10.dp * spacing, vertical = 6.dp * spacing),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp * spacing),
    ) {
        RadioButton(selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = p.accent, unselectedColor = p.ink))
        Column {
            Text(localized(title), color = p.ink, fontWeight = FontWeight.SemiBold)
            Text(localized(detail), color = p.ink, style = MaterialTheme.typography.bodySmall)
        }
    }
}
