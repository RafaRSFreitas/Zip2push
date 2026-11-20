# Zip2Push — v0.3.1 (botão de subir um nível + contagem de arquivos no progresso)

> **Pedidos atendidos:** (1) no Explorer de pastas, depois de entrar numa subpasta não havia como voltar à anterior — agora há um **botão dedicado de subir um nível** ao lado dos breadcrumbs; (2) a barra de progresso do push agora mostra **quantos arquivos estão sendo enviados** ("x of y files") e vira uma barra **determinada** que anda a cada arquivo.

## 1. Botão "up one level" no Explorer de pastas

O popup ganhou uma seta para cima à esquerda dos breadcrumbs, sempre na mesma posição:

- **Dentro de qualquer pasta**: um toque volta à pasta pai, buscando a listagem real dela no GitHub (mesmo caminho de navegação dos toques em pasta — mesmo spinner, mesmos breadcrumbs atualizados, mesmo cancelamento de fetch em voo).
- **Na raiz do repositório**: o botão fica **visível porém desabilitado** (opacidade reduzida) — não há pai acima da raiz, e a posição do botão nunca "pula" durante a navegação.
- **Pasta criada localmente** (via "New folder", que ainda não existe no GitHub): o up também funciona — volta ao pai do caminho construído.
- Um toque durante o carregamento de uma listagem é ignorado (sem empilhar dois fetches); os breadcrumbs clicáveis continuam disponíveis para saltos diretos a qualquer nível.

## 2. Contagem de arquivos na barra de progresso

- **UploadEngine** ganhou um callback `onProgress(done, total)` — disparado uma vez por arquivo, no momento em que o blob dele sobe (a parte longa e por-arquivo do push).
- **PushRunner** re-emite o estado `Running` carregando `Progress(done, total)`; um guard garante que nenhum `Running` tardio possa sobrescrever o `Done` final.
- **UI**: assim que o primeiro arquivo termina, a linha de status vira **"Pushing to repo — 3 of 7 files…"** e a barra inferior vira **determinada** (max = total, progress = enviados), com animação suave. Antes do primeiro blob (leitura do ZIP / resolução da branch) e nas outras operações (conectar, carregar listas) a barra continua indeterminada como antes; ao fim do push ela volta ao estado normal.
- A contagem é a do número de ARQUIVOS do push (entradas do ZIP + arquivos soltos + arquivos de pasta) — exatamente o que será commitado.

## Testes

Suíte completa + 9 testes novos (3 classes):

- `FolderBrowserUpTest` (4): botão desabilitado na raiz; habilita dentro de pasta e um toque volta ao pai com a listagem real; up aninhado sobe UM nível (não à raiz) e um segundo up chega à raiz; up funciona após criar pasta localmente.
- `PushProgressEngineTest` (2): o engine reporta 1/3, 2/3, 3/3 na ordem; o runner propaga os ticks como estados `Running(progress)` — validado direto no nível engine→runner.
- `PushProgressTest` (3): durante o push a linha de status mostra "of 3 files" e a barra fica determinada em 3/3 (commit gated para manter o push em voo); antes do primeiro blob a barra é indeterminada; após o Done ela volta a ser indeterminada. Os ticks observados são exatamente [1/3, 2/3, 3/3].

Detalhe descoberto no caminho: os testes de progresso precisam de ZIP **com pasta raiz** (`docs/a.txt`) — o app remove a pasta raiz na extração (comportamento documentado), então um ZIP plano gerava zero arquivos e "No readable files".

## Notas

- **versionCode 17, versionName 0.3.1** — instala por cima da 0.3.0/0.2.9 (mesma chave).
- Novo ícone `ic_arrow_up` (Lucide arrow-up, mesmo estilo stroke dos ícones do tema); strings `push_progress_files` e `browser_up_desc`.
- Arquitetura inalterada: o callback de progresso é opcional no engine (default `null` — nenhum chamador existente quebra), o runner continua single-push com foreground service.
