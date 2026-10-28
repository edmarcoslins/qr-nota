package com.edmarcos.qrnota

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Página "Tutorial google planilha": passo a passo e os scripts do Apps Script
 * (copiados de apps-script/ para os assets do app no build).
 */
class TutorialPage(
    private val activity: Activity,
    private val token: () -> String,
    onGoSend: () -> Unit,
) {
    private val txtToken: TextView = activity.findViewById(R.id.txtTutorialToken)

    init {
        bind(CODE_FILE, R.id.btnCopyCode, R.id.btnDownloadCode, R.id.btnSendCode, REQ_DOWNLOAD_CODE)
        bind(MANIFEST_FILE, R.id.btnCopyManifest, R.id.btnDownloadManifest, R.id.btnSendManifest, REQ_DOWNLOAD_MANIFEST)
        activity.findViewById<View>(R.id.btnTutorialGoSend).setOnClickListener { onGoSend() }
    }

    fun refresh() {
        txtToken.text = token()
    }

    private fun bind(name: String, copyId: Int, downloadId: Int, sendId: Int, requestCode: Int) {
        activity.findViewById<View>(copyId).setOnClickListener { copy(name) }
        activity.findViewById<View>(downloadId).setOnClickListener { download(name, requestCode) }
        activity.findViewById<View>(sendId).setOnClickListener { send(name) }
    }

    /** Conteúdo do arquivo; o Code.gs já sai com o token do app no lugar do marcador. */
    private fun content(name: String): String {
        val text = activity.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (name != CODE_FILE) return text
        val escaped = token().replace("\\", "\\\\").replace("'", "\\'")
        return text.replace(TOKEN_PLACEHOLDER, escaped)
    }

    private fun copy(name: String) {
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(name, content(name)))
        Toast.makeText(activity, "$name copiado. Cole no editor do Apps Script.", Toast.LENGTH_LONG).show()
    }

    /** Abre o seletor de pasta do Android para salvar o arquivo (não precisa de permissão). */
    private fun download(name: String, requestCode: Int) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream") // evita que o Android troque a extensão para .txt
            .putExtra(Intent.EXTRA_TITLE, name)
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            Toast.makeText(activity, "Não foi possível abrir o seletor de arquivos. Use \"Enviar\".", Toast.LENGTH_LONG).show()
        }
    }

    /** Retorno do seletor aberto em [download]; devolve false se o resultado não é desta página. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val name = when (requestCode) {
            REQ_DOWNLOAD_CODE -> CODE_FILE
            REQ_DOWNLOAD_MANIFEST -> MANIFEST_FILE
            else -> return false
        }
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return true
        try {
            activity.contentResolver.openOutputStream(uri)?.use { it.write(content(name).toByteArray(Charsets.UTF_8)) }
            Toast.makeText(activity, "$name salvo", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(activity, "Erro ao salvar: ${e.message}", Toast.LENGTH_LONG).show()
        }
        return true
    }

    private fun send(name: String) {
        try {
            val file = File(activity.shareDir(), name).apply { writeText(content(name), Charsets.UTF_8) }
            activity.shareFile(file, "text/plain", "Enviar $name")
        } catch (e: Exception) {
            Toast.makeText(activity, "Erro ao enviar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val CODE_FILE = "Code.gs"
        private const val MANIFEST_FILE = "appsscript.json"
        private const val TOKEN_PLACEHOLDER = "TROQUE-ESTE-TOKEN"
        private const val REQ_DOWNLOAD_CODE = 11
        private const val REQ_DOWNLOAD_MANIFEST = 12
    }
}
