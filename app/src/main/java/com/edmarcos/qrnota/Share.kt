package com.edmarcos.qrnota

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Pasta temporária de onde os arquivos são compartilhados (ver res/xml/file_paths.xml). */
fun Context.shareDir(): File = File(cacheDir, "share").apply { mkdirs() }

/** Abre o menu de compartilhamento do Android (WhatsApp, Drive, e-mail…) com um arquivo de [shareDir]. */
fun Activity.shareFile(file: File, mimeType: String, title: String) {
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(send, title))
}
