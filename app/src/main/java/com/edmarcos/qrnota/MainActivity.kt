package com.edmarcos.qrnota

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private enum class Status(val bg: Int, val fg: Int) {
        INFO(R.color.status_info_bg, R.color.status_info_fg),
        BUSY(R.color.status_busy_bg, R.color.status_busy_fg),
        OK(R.color.status_ok_bg, R.color.status_ok_fg),
        ERROR(R.color.status_err_bg, R.color.status_err_fg),
    }

    private enum class Page(val title: String, val subtitle: String) {
        SEND("Enviar NF", "Leia o QR code da nota fiscal"),
        MANAGE("Gerir NF", "Ver, apagar e compartilhar notas"),
        TUTORIAL("Tutorial", "Configurar o envio para Google Planilha"),
        ABOUT("Sobre", "Notas fiscais para o Imposto de Renda"),
    }

    private val prefs by lazy { getSharedPreferences("config", MODE_PRIVATE) }
    private val notes by lazy { NotesStore(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var drawer: DrawerLayout
    private lateinit var txtPageTitle: TextView
    private lateinit var txtPageSubtitle: TextView
    private lateinit var pages: Map<Page, View>
    private lateinit var navItems: Map<Page, TextView>
    private var currentPage = Page.SEND

    private lateinit var managePage: ManagePage
    private lateinit var tutorialPage: TutorialPage

    private lateinit var cardStatus: View
    private lateinit var statusDot: View
    private lateinit var txtStatusTitle: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtNotes: TextView
    private lateinit var chkIr: CheckBox
    private lateinit var chkSheets: CheckBox
    private lateinit var sheetsContent: View
    private lateinit var edtUrl: EditText
    private lateinit var edtToken: EditText
    private lateinit var txtPending: TextView
    private lateinit var pendingActions: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawer = findViewById(R.id.drawer)
        txtPageTitle = findViewById(R.id.txtPageTitle)
        txtPageSubtitle = findViewById(R.id.txtPageSubtitle)
        pages = mapOf(
            Page.SEND to findViewById(R.id.pageSend),
            Page.MANAGE to findViewById(R.id.pageManage),
            Page.TUTORIAL to findViewById(R.id.pageTutorial),
            Page.ABOUT to findViewById(R.id.pageAbout),
        )
        navItems = mapOf(
            Page.SEND to findViewById(R.id.navSend),
            Page.MANAGE to findViewById(R.id.navManage),
            Page.TUTORIAL to findViewById(R.id.navTutorial),
            Page.ABOUT to findViewById(R.id.navAbout),
        )
        navItems.forEach { (page, item) ->
            item.setOnClickListener {
                showPage(page)
                drawer.closeDrawer(GravityCompat.START)
            }
        }
        findViewById<View>(R.id.btnMenu).setOnClickListener { drawer.openDrawer(GravityCompat.START) }

        cardStatus = findViewById(R.id.cardStatus)
        statusDot = findViewById(R.id.statusDot)
        txtStatusTitle = findViewById(R.id.txtStatusTitle)
        txtStatus = findViewById(R.id.txtStatus)
        txtNotes = findViewById(R.id.txtNotes)
        chkIr = findViewById(R.id.chkIr)
        chkSheets = findViewById(R.id.chkSheets)
        sheetsContent = findViewById(R.id.sheetsContent)
        edtUrl = findViewById(R.id.edtUrl)
        edtToken = findViewById(R.id.edtToken)
        txtPending = findViewById(R.id.txtPending)
        pendingActions = findViewById(R.id.pendingActions)

        if (prefs.getString(KEY_TOKEN, "").isNullOrBlank()) prefs.edit().putString(KEY_TOKEN, newToken()).apply()
        edtUrl.setText(prefs.getString(KEY_URL, ""))
        edtToken.setText(prefs.getString(KEY_TOKEN, ""))
        chkIr.isChecked = prefs.getBoolean(KEY_IR, false)
        chkIr.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(KEY_IR, checked).apply() }
        chkSheets.isChecked = isSheetsEnabled()
        chkSheets.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SHEETS, checked).apply()
            updateSheetsSection()
            if (checked && isConfigured() && pendingCount() > 0) sendPending()
        }

        findViewById<View>(R.id.btnScan).setOnClickListener { scan() }
        findViewById<View>(R.id.btnGoManage).setOnClickListener { showPage(Page.MANAGE) }
        findViewById<View>(R.id.btnGoTutorial).setOnClickListener { showPage(Page.TUTORIAL) }
        findViewById<View>(R.id.btnRetry).setOnClickListener { sendPending() }
        findViewById<View>(R.id.btnClear).setOnClickListener { confirmClearPending() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveConfig() }
        findViewById<View>(R.id.btnDeductibles).setOnClickListener { openLink(DEDUCTIBLES_URL) }

        managePage = ManagePage(this, notes) { updateNotesLabel() }
        tutorialPage = TutorialPage(this, { prefs.getString(KEY_TOKEN, "").orEmpty() }) { showPage(Page.SEND) }

        val version = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.txtFooter).text = Html.fromHtml(
            "Desenvolvido por <b>${getString(R.string.developer)}</b> · v$version",
            Html.FROM_HTML_MODE_COMPACT,
        )

        importPendingIntoNotes()
        showReady()
        updateSheetsSection()
        showPage(savedInstanceState?.getString(KEY_PAGE)?.let { runCatching { Page.valueOf(it) }.getOrNull() } ?: Page.SEND)
        // Tenta enviar o que ficou pendente de uma sessão anterior (ex.: sem internet)
        if (isSheetsEnabled() && isConfigured() && pendingCount() > 0) sendPending()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PAGE, currentPage.name)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    @Deprecated("Activity sem AppCompat: o voltar é tratado aqui")
    override fun onBackPressed() {
        when {
            drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawer(GravityCompat.START)
            currentPage != Page.SEND -> showPage(Page.SEND)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    @Deprecated("Activity sem AppCompat: resultado do seletor de arquivos do tutorial")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!tutorialPage.onActivityResult(requestCode, resultCode, data)) {
            @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // --- Navegação ---

    private fun showPage(page: Page) {
        currentPage = page
        pages.forEach { (p, view) -> view.visibility = if (p == page) View.VISIBLE else View.GONE }
        navItems.forEach { (p, item) ->
            item.isSelected = p == page
            item.compoundDrawableTintList = ColorStateList.valueOf(getColor(if (p == page) R.color.primary else R.color.text_muted))
        }
        txtPageTitle.text = page.title
        txtPageSubtitle.text = page.subtitle
        when (page) {
            Page.SEND -> updateNotesLabel()
            Page.MANAGE -> managePage.reload()
            Page.TUTORIAL -> tutorialPage.refresh()
            Page.ABOUT -> {}
        }
    }

    // --- Página "Enviar NF" ---

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()

        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val qr = barcode.rawValue
                if (qr.isNullOrBlank()) {
                    setStatus(Status.ERROR, "QR code vazio", "Tente ler novamente.")
                    return@addOnSuccessListener
                }
                saveNote(qr.trim())
            }
            .addOnCanceledListener { setStatus(Status.INFO, "Leitura cancelada", "") }
            .addOnFailureListener { e -> setStatus(Status.ERROR, "Erro ao ler QR", e.message.orEmpty()) }
    }

    /** Grava a nota no celular (sempre) e, se o envio estiver ativado, também na planilha. */
    private fun saveNote(qr: String) {
        if (notes.contains(qr)) {
            setStatus(Status.ERROR, "Nota já cadastrada", "Essa nota já está salva no celular. Veja em \"Gerir NF\".")
            return
        }
        try {
            notes.add(qr, chkIr.isChecked)
        } catch (e: Exception) {
            setStatus(Status.ERROR, "Erro ao salvar no celular", e.message ?: e.javaClass.simpleName)
            return
        }
        updateNotesLabel()

        if (!isSheetsEnabled()) {
            setStatus(Status.OK, "Nota salva!", "Armazenada no celular. Para compartilhar o arquivo, vá em \"Gerir NF\".")
            confirmHaptic()
            return
        }
        addPending(qr, nowIso(), chkIr.isChecked)
        if (!isConfigured()) {
            updatePendingLabel()
            setStatus(Status.ERROR, SHEETS_FAILED, "Preencha a URL do Apps Script na seção \"Envio para Google Planilha\".")
            return
        }
        sendPending()
    }

    private fun sendPending() {
        val url = prefs.getString(KEY_URL, "").orEmpty()
        val token = prefs.getString(KEY_TOKEN, "").orEmpty()
        if (!isSheetsEnabled() || url.isBlank()) return
        setStatus(Status.BUSY, "Enviando…", "Nota salva no celular. Gravando na sua planilha do Google.")

        worker.execute {
            var sent = 0
            var duplicates = 0
            var fileUrl = ""
            var error: String? = null

            while (true) {
                val item = firstPending() ?: break
                try {
                    val body = JSONObject()
                        .put("token", token)
                        .put("qr", item.getString("qr"))
                        .put("scannedAt", item.getString("scannedAt"))
                        .put("impostoRenda", item.optBoolean("impostoRenda"))
                    val resp = postJson(url, body.toString())
                    if (resp.optBoolean("duplicate")) {
                        // Nota já está na planilha: não adianta reenviar, sai da fila
                        duplicates++
                        fileUrl = resp.optString("fileUrl", fileUrl)
                        removePending(item.getString("qr"))
                        continue
                    }
                    if (!resp.optBoolean("ok")) {
                        error = resp.optString("error", "erro desconhecido")
                        break
                    }
                    sent++
                    removePending(item.getString("qr"))
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                    break
                }
            }

            val details = buildList {
                if (sent > 0) add(if (sent == 1) "Gravada na planilha." else "$sent notas gravadas na planilha.")
                if (duplicates > 0) {
                    add(
                        (if (duplicates == 1) "1 nota já estava na planilha." else "$duplicates notas já estavam na planilha.") +
                            if (fileUrl.isNotEmpty()) "\n$fileUrl" else "",
                    )
                }
            }.joinToString("\n")

            mainHandler.post {
                when {
                    error != null -> setStatus(
                        Status.ERROR, SHEETS_FAILED,
                        listOf(details, "Motivo: $error", "Toque em \"Reenviar\" para tentar de novo.")
                            .filter { it.isNotEmpty() }.joinToString("\n"),
                    )
                    sent > 0 || duplicates > 0 -> {
                        setStatus(Status.OK, if (sent > 0) "Nota salva e enviada!" else "Nota salva!", details)
                        confirmHaptic()
                    }
                    else -> setStatus(Status.INFO, "Nada para enviar", "")
                }
                updatePendingLabel()
            }
        }
    }

    private fun saveConfig() {
        val url = edtUrl.text.toString().trim()
        if (url.isNotEmpty() && !url.startsWith("https://")) {
            edtUrl.error = "A URL deve começar com https://"
            return
        }
        var token = edtToken.text.toString().trim()
        if (token.isEmpty()) {
            token = newToken()
            edtToken.setText(token)
        }
        prefs.edit().putString(KEY_URL, url).putString(KEY_TOKEN, token).apply()
        Toast.makeText(this, "Configuração salva", Toast.LENGTH_SHORT).show()
        updatePendingLabel()
        if (isConfigured() && pendingCount() > 0) sendPending()
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Nenhum navegador para abrir o link", Toast.LENGTH_SHORT).show()
        }
    }

    /** Versões até a 1.5 guardavam as notas só na fila de envio: garante que estejam no celular. */
    private fun importPendingIntoNotes() {
        try {
            val arr = loadPending()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val qr = item.getString("qr")
                if (notes.contains(qr)) continue
                val at = runCatching { SimpleDateFormat(ISO_FORMAT, Locale.US).parse(item.getString("scannedAt")) }.getOrNull()
                notes.add(qr, item.optBoolean("impostoRenda"), at ?: Date())
            }
        } catch (e: Exception) {
            // Não impede o app de abrir; as notas continuam na fila de envio
        }
    }

    /**
     * POST para o Apps Script. O Google responde com um redirect (302) para outro host,
     * onde está o resultado; seguimos manualmente com GET.
     */
    private fun postJson(url: String, json: String): JSONObject {
        var conn = open(URL(url)).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }

        var code = conn.responseCode
        var hops = 0
        while (code in 300..399 && hops++ < 5) {
            val location = conn.getHeaderField("Location") ?: break
            val next = URL(conn.url, location)
            conn.disconnect()
            conn = open(next)
            code = conn.responseCode
        }

        try {
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw IOException("resposta inesperada do Apps Script. Confira a URL e se o acesso está como \"Qualquer pessoa\".")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: URL) = (url.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 30_000
    }

    // --- Fila de pendentes (SharedPreferences), acessada pela UI e pelo worker ---

    @Synchronized
    private fun loadPending() = JSONArray(prefs.getString(KEY_PENDING, "[]"))

    @Synchronized
    private fun savePending(arr: JSONArray) {
        prefs.edit().putString(KEY_PENDING, arr.toString()).commit()
    }

    @Synchronized
    private fun addPending(qr: String, scannedAt: String, impostoRenda: Boolean) {
        val arr = loadPending()
        // A mesma nota lida de novo enquanto está pendente não entra duas vezes na fila,
        // mas fica com a marcação de Imposto de Renda da leitura mais recente
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            if (item.getString("qr") == qr) {
                item.put("impostoRenda", impostoRenda)
                savePending(arr)
                return
            }
        }
        arr.put(JSONObject().put("qr", qr).put("scannedAt", scannedAt).put("impostoRenda", impostoRenda))
        savePending(arr)
    }

    @Synchronized
    private fun firstPending(): JSONObject? = loadPending().let { if (it.length() > 0) it.getJSONObject(0) else null }

    /** Remove pelo conteúdo (e não pela posição), pois a fila pode ser limpa durante um envio. */
    @Synchronized
    private fun removePending(qr: String) {
        val arr = loadPending()
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).getString("qr") == qr) {
                arr.remove(i)
                break
            }
        }
        savePending(arr)
    }

    @Synchronized
    private fun clearPending() = savePending(JSONArray())

    @Synchronized
    private fun pendingCount() = loadPending().length()

    // --- UI ---

    private fun isConfigured() = !prefs.getString(KEY_URL, "").isNullOrBlank()

    /** Sem escolha salva (quem veio de versões anteriores), fica ativado se já havia URL. */
    private fun isSheetsEnabled() = prefs.getBoolean(KEY_SHEETS, isConfigured())

    private fun showReady() = setStatus(Status.INFO, "Pronto", "Toque no botão acima para ler o QR da nota.")

    private fun updateNotesLabel() {
        txtNotes.text = when (val n = notes.load().size) {
            0 -> "Nenhuma nota salva ainda"
            1 -> "1 nota salva no celular"
            else -> "$n notas salvas no celular"
        }
    }

    private fun updateSheetsSection() {
        sheetsContent.visibility = if (isSheetsEnabled()) View.VISIBLE else View.GONE
        updatePendingLabel()
    }

    private fun updatePendingLabel() {
        val n = pendingCount()
        txtPending.text = when (n) {
            0 -> "✓  Nenhuma nota esperando envio"
            1 -> "1 nota esperando envio para a planilha"
            else -> "$n notas esperando envio para a planilha"
        }
        pendingActions.visibility = if (n > 0) View.VISIBLE else View.GONE
    }

    private fun confirmClearPending() {
        val n = pendingCount()
        if (n == 0) return
        AlertDialog.Builder(this)
            .setTitle("Limpar fila de envio?")
            .setMessage(
                (if (n == 1) "1 nota não será enviada" else "$n notas não serão enviadas") +
                    " à planilha. Elas continuam salvas no celular.",
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Limpar") { _, _ ->
                clearPending()
                updatePendingLabel()
                setStatus(Status.INFO, "Fila de envio vazia", "As notas continuam salvas no celular.")
            }
            .show()
    }

    private fun setStatus(status: Status, title: String, msg: String) {
        cardStatus.backgroundTintList = ColorStateList.valueOf(getColor(status.bg))
        statusDot.backgroundTintList = ColorStateList.valueOf(getColor(status.fg))
        txtStatusTitle.setTextColor(getColor(status.fg))
        txtStatusTitle.text = title
        txtStatus.text = msg
        txtStatus.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun confirmHaptic() = cardStatus.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY,
    )

    private fun nowIso(): String = SimpleDateFormat(ISO_FORMAT, Locale.US).format(Date())

    /** Token aleatório que o app usa e que vai embutido no Code.gs do tutorial. */
    private fun newToken(): String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val random = SecureRandom()
        return (1..24).map { chars[random.nextInt(chars.size)] }.joinToString("")
    }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_TOKEN = "token"
        private const val KEY_PENDING = "pending"
        private const val KEY_IR = "impostoRenda"
        private const val KEY_SHEETS = "sheetsEnabled"
        private const val KEY_PAGE = "page"
        private const val ISO_FORMAT = "yyyy-MM-dd'T'HH:mm:ssXXX"
        private const val DEDUCTIBLES_URL =
            "https://www.gov.br/receitafederal/pt-br/assuntos/meu-imposto-de-renda/preenchimento/manual-mir/pagamentos-ou-doacoes/despesas-dedutiveis"
        private const val SHEETS_FAILED = "O envio para google planilha falhou, mas a nota foi armazenada localmente"
    }
}
