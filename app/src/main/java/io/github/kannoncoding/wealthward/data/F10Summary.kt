package io.github.kannoncoding.wealthward.data

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.github.kannoncoding.wealthward.domain.Currency
import java.io.OutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate

internal enum class SummaryType(val label: String) {
    CASH("Depósito o efectivo"), CARD("Tarjeta"), TERM_DEBT("Deuda a plazo")
}

internal data class SummaryRow(
    val id: String,
    val alias: String,
    val currency: Currency,
    val archived: Boolean,
    val type: SummaryType,
    val balanceMinor: Long,
    val reservedMinor: Long = 0,
    val unassignedMinor: Long = 0,
) {
    val amounts: SummaryAmounts
        get() {
            val balance = BigInteger.valueOf(balanceMinor)
            return when (type) {
                SummaryType.CASH -> SummaryAmounts(balance, BigInteger.valueOf(reservedMinor),
                    BigInteger.valueOf(unassignedMinor))
                SummaryType.CARD -> SummaryAmounts(cardDebt = balance.negate().max(BigInteger.ZERO),
                    cardCredit = balance.max(BigInteger.ZERO))
                SummaryType.TERM_DEBT -> SummaryAmounts(termDebt = balance)
            }
        }
}

internal data class SummaryAmounts(
    val cash: BigInteger = BigInteger.ZERO,
    val reserves: BigInteger = BigInteger.ZERO,
    val unassigned: BigInteger = BigInteger.ZERO,
    val cardDebt: BigInteger = BigInteger.ZERO,
    val cardCredit: BigInteger = BigInteger.ZERO,
    val termDebt: BigInteger = BigInteger.ZERO,
) {
    operator fun plus(other: SummaryAmounts) = SummaryAmounts(
        cash + other.cash, reserves + other.reserves, unassigned + other.unassigned,
        cardDebt + other.cardDebt, cardCredit + other.cardCredit, termDebt + other.termDebt,
    )

    fun display(type: SummaryType? = null): String = when (type) {
        SummaryType.CASH -> cashLines()
        SummaryType.CARD -> cardLines()
        SummaryType.TERM_DEBT -> termLine()
        null -> listOf(cashLines(), cardLines(), termLine()).joinToString("\n")
    }

    private fun cashLines() =
        "Saldo propio ${amount(cash)}\nReservas (incluidas en el saldo) ${amount(reserves)}\nSin asignar ${amount(unassigned)}"

    private fun cardLines() = "Deuda de tarjeta ${amount(cardDebt)}\nSaldo a favor ${amount(cardCredit)}"

    private fun termLine() = "Principal de deuda a plazo ${amount(termDebt)}"

    fun csv(type: SummaryType? = null): List<String> {
        val active = listOf(SummaryType.CASH, SummaryType.CASH, SummaryType.CASH, SummaryType.CARD,
            SummaryType.CARD, SummaryType.TERM_DEBT)
        return listOf(cash, reserves, unassigned, cardDebt, cardCredit, termDebt).mapIndexed { index, value ->
            if (type == null || type == active[index]) amount(value) else ""
        }
    }
}

private fun amount(minor: BigInteger) = BigDecimal(minor, 2).toPlainString()

internal data class FinancialSummary(val date: LocalDate, val rows: List<SummaryRow>) {
    fun totals() = rows.groupBy { it.currency }.mapValues { (_, accounts) ->
        accounts.fold(SummaryAmounts()) { total, account -> total + account.amounts }
    }.toSortedMap()

    fun writeCsv(output: OutputStream) {
        val writer = output.writer(Charsets.UTF_8)
        fun row(values: List<String>) {
            writer.write(values.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" })
            writer.write("\r\n")
        }
        row(listOf("Fecha", "Alias", "Moneda", "Tipo", "Estado", "Saldo propio",
            "Reservas (incluidas en el saldo)", "Sin asignar", "Deuda de tarjeta",
            "Saldo a favor", "Principal de deuda a plazo"))
        rows.forEach {
            row(listOf(date.toString(), csvAlias(it.alias), it.currency.name, it.type.label,
                if (it.archived) "Archivada" else "") + it.amounts.csv(it.type))
        }
        totals().forEach { (currency, amounts) ->
            row(listOf(date.toString(), "", currency.name, "Total", "") + amounts.csv())
        }
        writer.flush()
    }

    fun writePdf(output: OutputStream) {
        val pdf = PdfDocument()
        try {
            val page = SummaryPdf(pdf)
            try {
                page.add("WealthWard · Resumen financiero", true)
                page.add("Resumen generado el $date")
                rows.forEach {
                    page.add("${it.alias} · ${it.currency} · ${it.type.label}" +
                        if (it.archived) " · Archivada" else "", true)
                    page.add(it.amounts.display(it.type))
                }
                totals().forEach { (currency, amounts) ->
                    page.add("Totales $currency", true)
                    page.add(amounts.display())
                }
            } finally {
                page.finish()
            }
            pdf.writeTo(output)
        } finally {
            pdf.close()
        }
    }
}

// Keep user-entered aliases as text when a spreadsheet opens the CSV.
private fun csvAlias(alias: String): String =
    if (alias.trimStart().firstOrNull() in listOf('=', '+', '-', '@') ||
        alias.firstOrNull() in listOf('\t', '\r', '\n')) "'$alias" else alias

private class SummaryPdf(private val document: PdfDocument) {
    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 12f
    }
    private var page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
    private var number = 1
    private var y = 40

    fun add(text: String, heading: Boolean = false) {
        paint.typeface = if (heading) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 515)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        for (line in 0 until layout.lineCount) {
            val top = layout.getLineTop(line)
            val height = layout.getLineBottom(line) - top
            if (y + height > 802) {
                finish()
                number++
                page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
                y = 40
            }
            val canvas: Canvas = page.canvas
            canvas.save()
            canvas.clipRect(40, y, 555, y + height)
            canvas.translate(40f, (y - top).toFloat())
            layout.draw(canvas)
            canvas.restore()
            y += height
        }
        y += if (heading) 2 else 12
    }

    fun finish() = document.finishPage(page)
}
