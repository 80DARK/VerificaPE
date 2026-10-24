package com.notibridge.app

import android.content.Context
import com.notibridge.app.db.NotificationLogEntity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exportador de PAGOS a EXCEL (.xlsx) — sin dependencias externas:
 * un .xlsx es un ZIP de XMLs; aquí lo generamos a mano para tener
 * colores, formatos de moneda y dos hojas:
 *   · Hoja 1 «Pagos»: Fecha, Hora, Nombre, Monto (S/), Billetera, App
 *   · Hoja 2 «Resumen por billetera»: cantidad y total por cada
 *     billetera (Yape, Plin, SIP, bIM, BCP…) + TOTAL GENERAL
 */
object ExcelExporter {

    // ─────────────────── XML del paquete ───────────────────

    private fun xmlHeader() = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    private fun contentTypes() = xmlHeader() +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
        "</Types>"

    private fun relsRoot() = xmlHeader() +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private fun workbook() = xmlHeader() +
        "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
        "<sheets>" +
        "<sheet name=\"Pagos\" sheetId=\"1\" r:id=\"rId1\"/>" +
        "<sheet name=\"Resumen\" sheetId=\"2\" r:id=\"rId2\"/>" +
        "</sheets></workbook>"

    private fun workbookRels() = xmlHeader() +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>" +
        "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"

    /** Estilos: encabezado verde, celdas cebra, monto verde S/, total resaltado. */
    private fun styles() = xmlHeader() +
        "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
        "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.00\"/></numFmts>" +
        "<fonts count=\"4\">" +
        "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
        "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font>" +
        "<font><b/><sz val=\"11\"/><color rgb=\"FF059669\"/><name val=\"Calibri\"/></font>" +
        "<font><b/><sz val=\"12\"/><color rgb=\"FF1E1B4B\"/><name val=\"Calibri\"/></font>" +
        "</fonts>" +
        "<fills count=\"5\">" +
        "<fill><patternFill patternType=\"gray125\"/></fill>" +
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFFFFF\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF10B981\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE7F6EF\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFF3CD\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
        "</fills>" +
        "<borders count=\"2\">" +
        "<border><left/><right/><top/><bottom/><diagonal/></border>" +
        "<border>" +
        "<left style=\"thin\"><color rgb=\"FFD1D5DB\"/></left>" +
        "<right style=\"thin\"><color rgb=\"FFD1D5DB\"/></right>" +
        "<top style=\"thin\"><color rgb=\"FFD1D5DB\"/></top>" +
        "<bottom style=\"thin\"><color rgb=\"FFD1D5DB\"/></bottom>" +
        "<diagonal/></border>" +
        "</borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"9\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"1\" borderId=\"1\" xfId=\"0\" applyFill=\"1\" applyBorder=\"1\"/>" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyFill=\"1\" applyBorder=\"1\"/>" +
        "<xf numFmtId=\"164\" fontId=\"2\" fillId=\"1\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
        "<xf numFmtId=\"164\" fontId=\"2\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
        "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
        "<xf numFmtId=\"164\" fontId=\"3\" fillId=\"4\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
        "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"4\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
        "</cellXfs>" +
        "</styleSheet>"

    // ─────────────────── helpers de hoja ───────────────────

    private fun esc(s: String?) = (s ?: "")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** celda de texto */
    private fun c(ref: String, style: Int, texto: String) =
        "<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t>${esc(texto)}</t></is></c>"

    /** celda numérica */
    private fun n(ref: String, style: Int, valor: Double) =
        "<c r=\"$ref\" s=\"$style\"><v>${if (valor == valor.toLong().toDouble()) valor.toLong().toString() else valor.toString()}</v></c>"

    // ─────────────────── Hoja 1: Pagos ───────────────────

    private fun sheetPagos(rows: List<NotificationLogEntity>, desde: Long, hasta: Long): String {
        val fFecha = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val fHora = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val fNombre = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
        val sb = StringBuilder()
        sb.append(xmlHeader())
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<cols>")
        sb.append("<col min=\"1\" max=\"1\" width=\"12\" customWidth=\"1\"/>")   // fecha
        sb.append("<col min=\"2\" max=\"2\" width=\"11\" customWidth=\"1\"/>")   // hora
        sb.append("<col min=\"3\" max=\"3\" width=\"30\" customWidth=\"1\"/>")   // nombre
        sb.append("<col min=\"4\" max=\"4\" width=\"12\" customWidth=\"1\"/>")   // monto
        sb.append("<col min=\"5\" max=\"5\" width=\"14\" customWidth=\"1\"/>")   // billetera
        sb.append("<col min=\"6\" max=\"6\" width=\"20\" customWidth=\"1\"/>")   // app
        sb.append("</cols><sheetData>")

        // Título con el rango elegido
        sb.append("<row r=\"1\">")
            .append(c("A1", 6, "VerificaPE · Pagos recibidos · " +
                "${fNombre.format(Date(desde))} al ${fNombre.format(Date(hasta))}")).append("</row>")

        // Encabezados (verde)
        sb.append("<row r=\"2\">")
        listOf("Fecha", "Hora", "Nombre", "Monto", "Billetera", "App").forEachIndexed { i, h ->
            sb.append(c("${'A' + i}2", 1, h))
        }
        sb.append("</row>")

        // Datos (cebra claro/verde) + TOTAL
        var fila = 3
        var zebra = false
        var total = 0.0
        for (p in rows) {
            val base = if (zebra) 3 else 2   // estilos dato / cebra
            val monto = if (zebra) 5 else 4  // estilos monto / monto cebra
            sb.append("<row r=\"$fila\">")
            sb.append(c("A$fila", base, fFecha.format(Date(p.loggedAt))))
            sb.append(c("B$fila", base, fHora.format(Date(p.loggedAt))))
            sb.append(c("C$fila", base, p.payerName ?: ""))
            sb.append(n("D$fila", monto, p.amount ?: 0.0))
            sb.append(c("E$fila", base, p.wallet ?: ""))
            sb.append(c("F$fila", base, p.appName))
            sb.append("</row>")
            total += p.amount ?: 0.0
            zebra = !zebra
            fila++
        }

        // Fila de total general
        sb.append("<row r=\"$fila\">")
        sb.append(c("A$fila", 8, "TOTAL"))
        sb.append(c("B$fila", 8, "${rows.size} pago(s)"))
        sb.append(c("C$fila", 8, ""))
        sb.append(n("D$fila", 7, total))
        sb.append(c("E$fila", 8, ""))
        sb.append(c("F$fila", 8, ""))
        sb.append("</row>")

        sb.append("</sheetData>")
        sb.append("<mergeCells count=\"1\"><mergeCell ref=\"A1:F1\"/></mergeCells>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    // ─────────────────── Hoja 2: Resumen por billetera ───────────────────

    private fun sheetResumen(rows: List<NotificationLogEntity>): String {
        val porWallet = LinkedHashMap<String, IntArray>() // [count, no importa]
        val totales = LinkedHashMap<String, Double>()
        for (p in rows) {
            val w = p.wallet ?: p.appName
            porWallet[w] = porWallet[w] ?: IntArray(1)
            totales[w] = (totales[w] ?: 0.0) + (p.amount ?: 0.0)
        }
        val ordenados = totales.entries.sortedByDescending { it.value }

        val sb = StringBuilder()
        sb.append(xmlHeader())
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<cols>")
        sb.append("<col min=\"1\" max=\"1\" width=\"22\" customWidth=\"1\"/>")
        sb.append("<col min=\"2\" max=\"2\" width=\"12\" customWidth=\"1\"/>")
        sb.append("<col min=\"3\" max=\"3\" width=\"14\" customWidth=\"1\"/>")
        sb.append("</cols><sheetData>")

        sb.append("<row r=\"1\">").append(c("A1", 6, "Resumen por billetera")).append("</row>")
        sb.append("<row r=\"2\">")
        sb.append(c("A2", 1, "Billetera")).append(c("B2", 1, "Pagos")).append(c("C2", 1, "Total"))
        sb.append("</row>")

        var fila = 3
        var zebra = false
        for ((w, total) in ordenados) {
            val base = if (zebra) 3 else 2
            val monto = if (zebra) 5 else 4
            val count = rows.count { (it.wallet ?: it.appName) == w }
            sb.append("<row r=\"$fila\">")
            sb.append(c("A$fila", base, w))
            sb.append(c("B$fila", base, count.toString()))
            sb.append(n("C$fila", monto, total))
            sb.append("</row>")
            zebra = !zebra
            fila++
        }

        sb.append("<row r=\"$fila\">")
        sb.append(c("A$fila", 8, "TOTAL GENERAL"))
        sb.append(c("B$fila", 8, rows.size.toString()))
        sb.append(n("C$fila", 7, rows.sumOf { it.amount ?: 0.0 }))
        sb.append("</row>")

        sb.append("</sheetData>")
        sb.append("<mergeCells count=\"1\"><mergeCell ref=\"A1:C1\"/></mergeCells>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    // ─────────────────── empaquetado ───────────────────

    private fun put(zip: ZipOutputStream, nombre: String, contenido: String) {
        zip.putNextEntry(ZipEntry(nombre))
        zip.write(contenido.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /**
     * Genera el .xlsx con los pagos del rango indicado.
     * @return el archivo creado, listo para compartir.
     */
    fun generar(context: Context, rows: List<NotificationLogEntity>, desde: Long, hasta: Long): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val fNombre = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
        val archivo = File(
            dir,
            "verificape_pagos_${fNombre.format(Date(desde))}_al_${fNombre.format(Date(hasta))}.xlsx"
        )
        ZipOutputStream(FileOutputStream(archivo)).use { zip ->
            put(zip, "[Content_Types].xml", contentTypes())
            put(zip, "_rels/.rels", relsRoot())
            put(zip, "xl/workbook.xml", workbook())
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels())
            put(zip, "xl/styles.xml", styles())
            put(zip, "xl/worksheets/sheet1.xml", sheetPagos(rows, desde, hasta))
            put(zip, "xl/worksheets/sheet2.xml", sheetResumen(rows))
        }
        return archivo
    }
}
