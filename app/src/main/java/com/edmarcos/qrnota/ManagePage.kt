package com.edmarcos.qrnota

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/** Página "Gerir NF": lista as notas do CSV, apaga as selecionadas e compartilha o arquivo. */
class ManagePage(
    private val activity: Activity,
    private val notes: NotesStore,
    private val onNotesChanged: () -> Unit,
) {
    private val txtCount: TextView = activity.findViewById(R.id.txtManageCount)
    private val txtEmpty: View = activity.findViewById(R.id.txtManageEmpty)
    private val chkSelectAll: CheckBox = activity.findViewById(R.id.chkSelectAll)
    private val btnDelete: Button = activity.findViewById(R.id.btnDeleteSelected)
    private val btnShare: Button = activity.findViewById(R.id.btnShareCsv)
    private val list: ListView = activity.findViewById(R.id.listNotes)

    private var items: List<NotesStore.Note> = emptyList()
    private val selected = mutableSetOf<String>() // pelo conteúdo do QR

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: activity.layoutInflater.inflate(R.layout.item_note, parent, false)
            val note = items[position]
            view.findViewById<TextView>(R.id.txtNoteDate).text =
                note.scannedAt + if (note.impostoRenda) "  ·  Imposto de Renda" else ""
            view.findViewById<TextView>(R.id.txtNoteKey).text = shortQr(note.qr)
            view.findViewById<CheckBox>(R.id.chkNote).apply {
                isChecked = note.qr in selected
                setOnClickListener {
                    if (isChecked) selected.add(note.qr) else selected.remove(note.qr)
                    refreshSelection()
                }
            }
            return view
        }
    }

    init {
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> showDetails(items[position]) }
        chkSelectAll.setOnClickListener {
            if (chkSelectAll.isChecked) selected.addAll(items.map { it.qr }) else selected.clear()
            refreshSelection()
        }
        btnDelete.setOnClickListener { confirmDelete(selected.toSet()) }
        btnShare.setOnClickListener { share() }
    }

    fun reload() {
        items = notes.load().asReversed() // mais recentes primeiro
        selected.retainAll(items.map { it.qr }.toSet())
        txtCount.text = when (items.size) {
            0 -> "Nenhuma nota salva"
            1 -> "1 nota salva no celular"
            else -> "${items.size} notas salvas no celular"
        }
        txtEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        refreshSelection()
    }

    private fun refreshSelection() {
        adapter.notifyDataSetChanged()
        chkSelectAll.isChecked = items.isNotEmpty() && selected.size == items.size
        chkSelectAll.isEnabled = items.isNotEmpty()
        btnDelete.text = if (selected.isEmpty()) "Apagar" else "Apagar (${selected.size})"
        setEnabled(btnDelete, selected.isNotEmpty())
        setEnabled(btnShare, items.isNotEmpty())
    }

    private fun setEnabled(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.4f
    }

    private fun confirmDelete(qrs: Set<String>) {
        if (qrs.isEmpty()) return
        AlertDialog.Builder(activity)
            .setTitle("Apagar notas?")
            .setMessage(
                (if (qrs.size == 1) "1 nota será apagada do celular." else "${qrs.size} notas serão apagadas do celular.") +
                    " Se quiser guardar uma cópia, toque em \"Compartilhar CSV\" antes.",
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Apagar") { _, _ ->
                notes.removeAll(qrs)
                selected.removeAll(qrs)
                reload()
                onNotesChanged()
                Toast.makeText(activity, if (qrs.size == 1) "Nota apagada" else "${qrs.size} notas apagadas", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showDetails(note: NotesStore.Note) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(note.scannedAt)
            .setMessage("Imposto de Renda: " + (if (note.impostoRenda) "sim" else "não") + "\n\n" + note.qr)
            .setNegativeButton("Fechar", null)
            .setPositiveButton("Apagar") { _, _ -> confirmDelete(setOf(note.qr)) }
        if (note.qr.startsWith("http://") || note.qr.startsWith("https://")) {
            dialog.setNeutralButton("Abrir na SEFAZ") { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(note.qr)))
                } catch (e: Exception) {
                    Toast.makeText(activity, "Nenhum app para abrir o link", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun share() {
        try {
            activity.shareFile(notes.exportCopy(), "text/csv", "Compartilhar notas")
        } catch (e: Exception) {
            Toast.makeText(activity, "Erro ao compartilhar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Mostra a chave de acesso (44 dígitos) quando houver; senão, o início do QR. */
    private fun shortQr(qr: String): String {
        Regex("\\d{44}").find(qr)?.let { return "Chave " + it.value.chunked(4).joinToString(" ") }
        return if (qr.length > 60) qr.take(60) + "…" else qr
    }
}
