# QR Nota

App Android simples para guardar notas fiscais, principalmente as de despesas dedutíveis
na Declaração do Imposto de Renda (mas serve para qualquer nota). Lê o QR code da nota
e salva o conteúdo num arquivo CSV no próprio celular. Não precisa de conta, configuração nem internet.
Opcionalmente, envia cada nota também para uma planilha do Google.

## Telas (menu lateral ☰)

- **Enviar NF** (tela inicial), com três seções:
  1. **Leitor de QR Code da nota fiscal**: botão de leitura e a caixa *Imposto de Renda*.
  2. **Armazenamento local**: sempre ativo; mostra quantas notas estão salvas no celular.
     Notas repetidas são recusadas.
  3. **Envio para Google Planilha**: só funciona com a caixa *Ativar envio* marcada.
     Ali ficam a URL e o token do Apps Script. Se o envio falhar, a nota continua salva
     no celular e fica na fila para reenvio.
- **Gerir NF**: lista as notas, apaga uma ou várias (caixas de seleção / *Selecionar
  todas*) e compartilha todas como `notas-AAAA-MM-DD.csv` pelo WhatsApp, Drive, e-mail…
  O CSV usa `;` e UTF-8, e abre direto no Excel / Google Planilhas. Toque numa nota para
  ver os detalhes ou abrir o link da SEFAZ.
- **Tutorial google planilha**: passo a passo para configurar o Apps Script, com botões
  para copiar, baixar ou enviar o `Code.gs` e o `appsscript.json`.
- **Sobre**: objetivo do app e link da Receita Federal sobre
  [despesas dedutíveis](https://www.gov.br/receitafederal/pt-br/assuntos/meu-imposto-de-renda/preenchimento/manual-mir/pagamentos-ou-doacoes/despesas-dedutiveis).

O arquivo entra no backup automático do Android, mas se perder o celular sem backup as
notas vão junto: compartilhe de vez em quando.

## Opcional: enviar também para uma planilha do Google

O jeito mais fácil é seguir a tela **Tutorial google planilha** do app. O app gera um
token sozinho, e o `Code.gs` copiado/baixado por lá já vem com esse token.

Resumo do passo a passo, usando os arquivos deste repositório:

1. Crie uma planilha em https://sheets.google.com (ex.: "Notas QR").
2. Na planilha: **Extensões → Apps Script**.
3. Cole o conteúdo de `apps-script/Code.gs` e troque `TROQUE-ESTE-TOKEN` pelo token
   mostrado no app (seção *Envio para Google Planilha*).
4. Em ⚙️ **Configurações do projeto**, marque "Mostrar arquivo de manifesto appsscript.json",
   e cole o conteúdo de `apps-script/appsscript.json`. Isso limita o script a acessar
   **somente esta planilha** (escopo `spreadsheets.currentonly`).
5. **Implantar → Nova implantação** → tipo **App da Web**:
   - Executar como: **Eu**
   - Quem pode acessar: **Qualquer pessoa**
6. Autorize e copie a **URL do app da Web** (termina em `/exec`).
   Teste no navegador: deve aparecer `{"ok":true,...}`.
7. No app, marque **Ativar envio para Google Planilha**, cole a URL e toque em
   **Salvar configuração**.

> Se alterar o script depois: **Implantar → Gerenciar implantações → editar → Nova versão**
> (assim a URL continua a mesma).

A caixa **Imposto de Renda** do app preenche a coluna `imposto de renda` (caixa de seleção
na planilha; o script cria a coluna sozinho se ela não existir).

Notas repetidas (mesmo valor em `conteudo_qr`) são recusadas com
`nota já cadastrada, consulte o arquivo`. Para zerar, apague as linhas da aba "Notas"
(mantenha o cabeçalho) direto na planilha.

Os arquivos de `apps-script/` vão dentro do app no build (assets), então o tutorial
sempre oferece a versão atual deles.

## Instalar o app no celular

1. Envie o `QR-Nota.apk` para o celular (Drive, WhatsApp, cabo USB…).
2. Abra o arquivo e permita "instalar apps de fontes desconhecidas".

## Usar

Toque em **Ler QR da nota** → aponte para o QR → pronto.

## Recompilar

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleRelease   # → app/build/outputs/apk/release/app-release.apk
```

## Assinatura

O APK é assinado com o certificado `CN=edmarcos.lins` (`edmarcos-lins.jks`, senhas em
`keystore.properties`). **Guarde uma cópia desses dois arquivos em local seguro**: sem eles,
uma nova versão do app não pode ser instalada por cima da anterior.
