// Recebe o conteúdo do QR code enviado pelo app "QR Nota" e grava uma linha
// na aba "Notas" desta planilha. O script fica vinculado à planilha
// (Extensões → Apps Script) e só tem acesso a ela.

const TOKEN = 'TROQUE-ESTE-TOKEN';   // use o mesmo valor no campo "Token" do app
const SHEET_NAME = 'Notas';
const QR_COLUMN = 'conteudo_qr';
const IR_COLUMN = 'imposto de renda';
const HEADER = ['lido_em', 'enviado_em', QR_COLUMN, IR_COLUMN];
const MAX_QR_LENGTH = 2000;          // QR de nota fiscal tem bem menos que isso
const DUPLICATE_MSG = 'nota já cadastrada, consulte o arquivo';

function doPost(e) {
  try {
    const body = JSON.parse(e.postData.contents);
    if (body.token !== TOKEN) return json_({ ok: false, error: 'token inválido' });

    const qr = String(body.qr || '').trim();
    if (!qr) return json_({ ok: false, error: 'QR vazio' });
    if (qr.length > MAX_QR_LENGTH) return json_({ ok: false, error: 'QR muito longo' });

    const lock = LockService.getScriptLock();
    lock.waitLock(20000);
    try {
      const ss = SpreadsheetApp.getActiveSpreadsheet();
      const sheet = getSheet_(ss);
      const header = getHeader_(sheet);

      if (qrExists_(sheet, header, qr)) {
        return json_({ ok: false, duplicate: true, error: DUPLICATE_MSG, fileUrl: ss.getUrl() });
      }

      // Monta a linha pela posição de cada coluna no cabeçalho
      const scannedAt = new Date(body.scannedAt);
      const row = new Array(header.length).fill('');
      row[header.indexOf('lido_em')] = isNaN(scannedAt) ? '' : scannedAt;
      row[header.indexOf('enviado_em')] = new Date();
      row[header.indexOf(QR_COLUMN)] = asText_(qr);
      sheet.appendRow(row);

      const irCell = sheet.getRange(sheet.getLastRow(), header.indexOf(IR_COLUMN) + 1);
      irCell.insertCheckboxes();
      irCell.setValue(body.impostoRenda === true);
    } finally {
      lock.releaseLock();
    }
    return json_({ ok: true });
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  }
}

// Permite testar a URL no navegador
function doGet() {
  return json_({ ok: true, message: 'Apps Script do QR Nota está no ar.' });
}

/**
 * Aba "Notas"; se não existir (ou estiver vazia), cria com cabeçalho formatado.
 * Em planilhas antigas, acrescenta as colunas que faltarem (ex.: "imposto de renda").
 */
function getSheet_(ss) {
  const sheet = ss.getSheetByName(SHEET_NAME) || ss.insertSheet(SHEET_NAME);
  if (sheet.getLastRow() === 0) {
    sheet.appendRow(HEADER);
    sheet.setFrozenRows(1);
    sheet.getRange('A:B').setNumberFormat('dd/MM/yyyy HH:mm:ss');
    sheet.getRange('C:C').setNumberFormat('@');
    sheet.setColumnWidths(1, 2, 150);
    sheet.setColumnWidth(3, 600);
    sheet.setColumnWidth(4, 130);
  }

  const header = getHeader_(sheet);
  HEADER.filter((name) => header.indexOf(name) === -1).forEach((name) => {
    const col = sheet.getLastColumn() + 1;
    sheet.getRange(1, col).setValue(name);
    sheet.setColumnWidth(col, 130);
  });
  sheet.getRange(1, 1, 1, sheet.getLastColumn()).setFontWeight('bold');
  return sheet;
}

function getHeader_(sheet) {
  return sheet.getRange(1, 1, 1, sheet.getLastColumn()).getValues()[0].map(String);
}

/** Procura o valor exato na coluna "conteudo_qr". */
function qrExists_(sheet, header, qr) {
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return false;
  const col = header.indexOf(QR_COLUMN) + 1;
  return sheet.getRange(2, col, lastRow - 1, 1)
    .createTextFinder(qr)
    .matchCase(true)
    .matchEntireCell(true)
    .findNext() !== null;
}

/** Impede que o conteúdo do QR seja interpretado como fórmula. */
function asText_(value) {
  return /^[=+\-@]/.test(value) ? "'" + value : value;
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
