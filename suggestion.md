# Zip2Push — v0.3.4 (botão File + suporte a tar e RAR)

> **Pedidos atendidos:** (1) o botão de adicionar arquivo agora se chama **"File"**; (2) o mesmo botão aceita **tar**, **tar.gz** e **RAR** (RAR4 e RAR5+) além do ZIP — um único botão para todos os formatos de archive. **23 testes novos** com archives RAR reais; suíte completa: **170 testes, 0 falhas**. A bateria ainda achou e corrigiu um **bug real** que fazia archives "flat" (sem pastas) submeterem **zero arquivos**.

## 1. Botão renomeado

"Add ZIP archive" → **"File"**. O mesmo botão, o mesmo fluxo — só o nome e os formatos mudaram.

## 2. tar e RAR no mesmo botão

### O que o botão File aceita agora

| Formato | Detecção | Backend |
|---|---|---|
| ZIP (incl. ZIP64) | magic `PK\x03\x04` | Commons Compress (já usado) |
| tar | magic `ustar` no header | Commons Compress (já usado) |
| tar.gz / tgz | magic `1f 8b` (gzip) | Commons Compress (já usado) |
| **RAR 4** | magic `Rar!\x1a\x07\x00` | **junrar 8.1.2** (novo) |
| **RAR 5 e até v7** | magic `Rar!\x1a\x07\x01\x00` | **junrar 8.1.2** (novo) |

A escolha da biblioteca veio de pesquisa: **junrar** ([github.com/junrar/junrar](https://github.com/junrar/junrar)) é Java puro (sem código nativo), suporta "RAR up to v7" incluindo arquivos com senha, está no Maven Central (v8.1.2), pesa 248 KB e tem uma única dependência transitiva (slf4j-api).

### Segurança em primeiro lugar

- A detecção é sempre por **magic bytes**, nunca por extensão ou MIME type (provedores mentem: um .rar chega como `application/octet-stream`);
- No ato da seleção, um arquivo que não é archive é **recusado com um toast claro** ("That file is not a ZIP, tar or RAR archive") — nada de falhar no meio do push;
- Nomes de entrada RAR usam `\` (separador Windows) — normalizados antes do saneamento;
- Paths hostis (`../..`, `C:\`, absolutos) seguem pelo mesmo `PathSanitizer` de sempre: ou viram um path seguro dentro do repo, ou a entrada é pulada. Uma entrada corrompida/hostil **não derruba o push inteiro** (só ela é ignorada);
- O resumo da seleção agora mostra o formato detectado: `Archive (RAR): fotos.rar`, `Archive (tar.gz): bundle.tgz`.

### Bug real encontrado pela bateria de testes

O `skipRootFolder` antigo transformava paths sem `/` (arquivos na raiz do archive) em **string vazia** — ou seja, um ZIP/RAR "flat" (só arquivos, sem pasta) empurrava **0 arquivos** para o GitHub. Corrigido: arquivos de nível raiz são mantidos como estão; a regra de remover a pasta raiz vale só quando há uma pasta para remover. Isso valia para ZIP e tar também — o bug existia desde antes do RAR.

## 3. Testes (23 novos, todos verdes)

Como não existe gerador de RAR em Java puro, os testes usam **archives RAR reais** do corpus de testes do próprio junrar (repo Apache-2.0): `rar4.rar`, `rar5.rar`, `unicode.rar`, `test.rar` e três archives hostis (`mkdir-escape`, `parent-dir`, `sibling-prefix-traversal`).

**ArchiveReaderTest (12 novos)**: detecção RAR4/RAR5 (file e stream); extração RAR4 com nomes e bytes exatos (`FILE1.TXT` = "file1"); extração RAR5 idem; nomes unicode (japonês/chinês) intactos; os três archives de traversal neutralizados (nenhum path com `..`, `\`, `:` ou absoluto); `skipRootFolder` aplica ao RAR (`foo\bar.txt` → `bar.txt`); RAR com magic válido e corpo corrompido falha como exceção limpa.

**FileButtonArchiveTest (11 novos)**: botão labelizado "File"; não-archive recusado com toast correto e nada selecionado; RAR5 aceito e resumido como "Archive (RAR)"; tar.gz aceito e resumido como "Archive (tar.gz)"; **push ponta-a-ponta com RAR5** (MockWebServer → 2 arquivos no commit → History registra `FILE1.TXT`, `FILE2.TXT`); **push ponta-a-ponta com tar.gz** (entry `docs/readme.txt` → repo path `readme.txt`); ZIP truncado falha o push com motivo legível.

## 4. Notas

- **versionCode 20, versionName 0.3.4** — instala por cima da 0.3.2/0.3.3 (mesma chave).
- Dependência nova: `com.github.junrar:junrar:8.1.2` (Maven Central, Java puro, 248 KB + slf4j-api).
- Toasts e textos da UI atualizados: "Pick an archive (ZIP/tar/RAR) or some files first"; o About menciona os três formatos.
- Limitação herdada da biblioteca: RAR **com senha** não é suportado (o push falha com motivo claro — junrar até decifra, mas exigir senha do usuário está fora deste escopo); RAR **multi-volume** (partes .rar/.r00) também não.
