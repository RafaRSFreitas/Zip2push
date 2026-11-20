# Zip2Push — v0.3.5 (botões renomeados: Add Rar/Zip/Tar e Add file)

> **Pedidos atendidos:** (1) o botão de adicionar que aparece primeiro agora se chama **"Add Rar/Zip/Tar"** e aceita **apenas esses três formatos** — RAR (RAR4 e RAR5+), ZIP e tar (incl. tar.gz); (2) o botão de baixo agora se chama **"Add file"** — qualquer arquivo, vários de uma vez; (3) **"Add folder"** permanece exatamente como está. Nesta versão também foi reconstruído e mantido tudo o que as entregas anteriores (0.3.0–0.3.4) traziam: suporte RAR/tar via junrar, validação por magic bytes e o **History** de pushes no menu. **28 testes novos**; suíte completa: **122 testes, 0 falhas**.

## 1. "Add Rar/Zip/Tar" (o botão de cima)

Nome e contrato batem: só RAR, ZIP e tar entram por ele.

- **Validação por magic bytes no ato da seleção** — nunca por extensão ou MIME type (provedores mentem: um .rar chega como `application/octet-stream`). RAR4 = `Rar!\x1a\x07\x00`, RAR5+ = `Rar!\x1a\x07\x01\x00`, ZIP = `PK\x03\x04`, tar = `ustar` no header, tar.gz = `1f 8b`.
- Um arquivo que não é um desses formatos é recusado na hora com um toast claro ("That file is not a RAR, ZIP or tar archive") — nada de falhar no meio do push.
- O picker filtra pelos MIME types dos três formatos (com fallback para todos os arquivos, já que a validação real é no conteúdo).
- O resumo da seleção mostra o formato detectado: `Archive (RAR): fotos.rar`, `Archive (zip): site.zip`, `Archive (tar.gz): bundle.tgz`.
- RAR via **junrar 8.1.2** (Java puro, Maven Central, 248 KB, uma dependência — slf4j-api). ZIP/tar/tar.gz via Commons Compress (já usado).

## 2. "Add file" (o botão de baixo à esquerda)

De "Add individual files" para **"Add file"**. O comportamento é o que você pediu: **qualquer arquivo** (não só archives), **vários de uma vez** — o picker multi-documento e o tracker já forneciam isso; agora o nome promete exatamente o que faz. Nenhuma validação de formato acontece aqui de propósito: um .txt ou .jpg é perfeitamente legal por este botão (testado ponta a ponta).

## 3. "Add folder" (à direita) — intocado

Mesmo nome, mesmo ícone, mesmo fluxo de pasta recursiva.

## Reconstruído nesta versão (perdido entre sessões)

O ambiente de build foi reciclado e as entregas 0.3.0–0.3.4 não estavam no zip que voltou (v0.2.9). Tudo foi reimplementado sobre a v0.2.9 e está de volta:

- **RAR completo** (junrar + detecção + extração + segurança) com o **correção de bug real**: o `skipRootFolder` antigo apagava arquivos na raiz do archive — um archive "flat" (sem pastas) empurrava **zero** arquivos. Corrigido e coberto por teste de regressão.
- **History** (menu → History): lista dos pushes concluídos, mais recentes primeiro, com detalhes (repo, branch, data, arquivos) e link para o commit no GitHub; cap de 50 registros, arquivo JSON próprio, à prova de corrupção.
- Detecção sempre por magic bytes + nomes de entrada RAR com `\` normalizados + paths hostis (`../..`, `C:\`) neutralizados pelo `PathSanitizer` (entrada hostil pula só ela, não derruba o push).

## Testes (28 novos; suíte: 122, 0 falhas)

Como não existe gerador de RAR em Java puro, os testes usam **archives RAR reais** do corpus de testes do junrar (rar4.rar, rar5.rar, unicode.rar, test.rar + três hostis de path traversal).

- **ArchiveReaderTest (+10)**: detecção RAR4/RAR5 (arquivo e stream); extração com nomes e bytes exatos nas duas gerações; nomes unicode (japonês/chinês) intactos; os três archives hostis neutralizados; skipRootFolder no RAR (`foo\bar.txt` → `bar.txt`); **regressão do archive flat** (rar e zip); RAR corrompido → exceção limpa.
- **ArchiveButtonsTest (+13)**: labels "Add Rar/Zip/Tar", "Add file" e "Add folder"; não-archive recusado com toast e nada selecionado; RAR5/zip/tar.gz aceitos e resumidos com o formato certo; **"Add file" empurra qualquer arquivo** (push ponta a ponta com 2 arquivos); **push ponta a ponta com RAR5** (2 arquivos + History com `FILE1.TXT`, `FILE2.TXT`); push ponta a ponta com tar.gz; ZIP truncado → falha com motivo legível; History abre pelo menu com o registro correto; push falho **não** grava History.
- **PushHistoryStoreTest (+5)**: round-trip newest-first, cap de 50 entradas, URL nula, arquivo corrompido lido como vazio sem crash.

## Notas

- **versionCode 21, versionName 0.3.5** — mesmo keystore das versões anteriores.
- Dependência nova: `com.github.junrar:junrar:8.1.2`.
- Limitações herdadas: RAR com senha e RAR multi-volume não são suportados (o push falha com motivo claro).
