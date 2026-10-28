package com.edmarcos.qrnota

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notas lidas, guardadas num CSV no armazenamento interno do app.
 * O arquivo usa ";" e UTF-8 com BOM para abrir certinho no Excel / Planilhas em português.
 */
class NotesStore(private val context: Context) {

    data class Note(val scannedAt: String, val qr: String, val impostoRenda: Boolean)

    private val file = File(context.filesDir, FILE_NAME)

    fun load(): List<Note> {
        if (!file.exists()) return emptyList()
        return parse(file.readText(Charsets.UTF_8).removePrefix(BOM))
            .drop(1) // cabeçalho
            .filter { it.size >= 3 }
            .map { Note(it[0], fromCell(it[1]), it[2] == YES) }
    }

    fun contains(qr: String) = load().any { it.qr == qr }

    fun add(qr: String, impostoRenda: Boolean, scannedAt: Date = Date()) =
        save(load() + Note(SimpleDateFormat(DATE_FORMAT, Locale.US).format(scannedAt), qr, impostoRenda))

    fun remove(qr: String) = removeAll(setOf(qr))

    fun removeAll(qrs: Set<String>) = save(load().filter { it.qr !in qrs })

    /** Cópia do CSV com a data no nome (cada envio ao Drive cria um arquivo novo). */
    fun exportCopy(): File {
        val dir = context.shareDir()
        dir.listFiles { f -> f.name.startsWith("notas-") }?.forEach { it.delete() }
        val name = "notas-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".csv"
        return File(dir, name).also { file.copyTo(it, overwrite = true) }
    }

    private fun save(notes: List<Note>) {
        val text = buildString {
            append(BOM)
            appendRow(HEADER)
            notes.forEach { appendRow(listOf(it.scannedAt, toCell(it.qr), if (it.impostoRenda) YES else NO)) }
        }
        // Grava num temporário e troca, para não corromper o arquivo se o app fechar no meio
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) throw IllegalStateException("não foi possível salvar o arquivo de notas")
    }

    private fun StringBuilder.appendRow(cells: List<String>) {
        append(cells.joinToString(SEP.toString()) { "\"" + it.replace("\"", "\"\"") + "\"" })
        append("\r\n")
    }

    private fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c != '"') field.append(c)
                else if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ }
                else quoted = false
            } else when (c) {
                '"' -> quoted = true
                SEP -> { row.add(field.toString()); field.clear() }
                '\n' -> { row.add(field.toString()); field.clear(); rows.add(row); row = mutableListOf() }
                '\r' -> {}
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }

    /** Impede que o conteúdo do QR seja interpretado como fórmula ao abrir o CSV. */
    private fun toCell(qr: String) = if (FORMULA_START.containsMatchIn(qr)) "'$qr" else qr

    private fun fromCell(cell: String) =
        if (cell.startsWith("'") && FORMULA_START.containsMatchIn(cell.substring(1))) cell.substring(1) else cell

    companion object {
        const val FILE_NAME = "notas.csv"
        private const val BOM = "\uFEFF"
        private const val SEP = ';'
        private const val YES = "sim"
        private const val NO = "não"
        private const val DATE_FORMAT = "dd/MM/yyyy HH:mm:ss"
        private val HEADER = listOf("lido_em", "conteudo_qr", "imposto_de_renda")
        private val FORMULA_START = Regex("^[=+\\-@]")
    }
}
