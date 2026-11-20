# Zip2Push — v0.2.9 (a barra de informação sempre correta)

> **Problema relatado:** durante um push, girar o aparelho fazia o loading "sumir" — a barra parecia dizer que o push parou (embora ele continuasse: tentar de novo mostrava "in progress"). Avisos como "push já em andamento" apareciam na própria barra, poluindo-a. E o campo da pasta de destino era mais fino que os outros campos.

## O que mudou

### 1. A barra de status agora tem um dono claro durante o push

Causa raiz do "loading sumiu": após a rotação, a activity nova reconecta sozinha ("Verifying token…", "Connected as…", "Loading repositories…") e essas mensagens **sobrescreviam** a linha do push; pior, o fim do carregamento de repositórios chamava `setBusy(false)` e **escondia o spinner** no meio do push.

v0.2.9 resolve com três regras:

- **O spinner é a soma de dois estados**: trabalho de rede da tela (conectar/carregar listas) OU o push em andamento. O carregamento de repositórios terminando não pode mais esconder o spinner de um push ativo — um único escritor (`applyBusy`) controla isso.
- **Durante o push, a barra é dele**: mensagens de fundo que chegam no meio do push (conectado, repositórios carregados, branches carregadas) são descartadas — o push é a informação mais importante. A barra continua mostrando "Pushing to repo…" até o resultado real.
- **Depois do push, o resultado fica**: a reconexão automática pós-rotação ("Verifying token…", "Connected as…") não pode mais apagar o "Pushed ✓ files" da barra — o resultado fica retido até você começar algo novo (dar outro push, reconectar manualmente, recarregar, limpar). Ações suas limpam a retenção; processos automáticos, não.

Após a rotação, portanto: a barra continua "Pushing to …", o spinner continua girando, o botão Push continua desabilitado — e quando o push termina, "Pushed ✓ files" + link do commit, que continuam lá mesmo se você girar de novo.

### 2. Avisos de toque viraram pop-ups (toasts)

"Push already in progress…", "Pick a repository first", "Pick files or a ZIP first" agora aparecem como **toast de poucos segundos** perto do fundo da tela, em vez de tomarem a barra de status — exatamente como pedido. A barra fica reservada para o estado real (conexão / push / resultado). Textos curtos próprios para toast (`toast_push_in_progress` etc.).

Bônus corrigido no caminho: girar o aparelho logo após um push concluído **não** repete o toast "Pushed ✓" nem apaga um formulário que você já começou a preencher para o próximo push — cada resultado é apresentado uma única vez no app (o resultado carrega o id do push; a tela nova re-renderiza a barra para contexto, mas sem re-toast e sem re-limpeza).

### 3. Campo da pasta de destino do mesmo tamanho dos outros

O campo usava o estilo base (caixa mais fina); os campos de repositório/branch usam o estilo "ExposedDropdown" (com chevron, banda mais alta). Agora o campo de destino usa o mesmo estilo — **altura medida e igualada: repo 48dp = branch 48dp = pasta 48dp** (verificado em teste de layout real, não a olho).

O chevron agora é um afordance honesto: abre sugestões **offline** de pasta — as pastas-mãe dos arquivos já selecionados (você acabou de mostrar onde seu conteúdo vive) e as pastas usadas em pushes anteriores nesta sessão, mais recente primeiro. Nada de rede, nada de histórico persistente (a sessão zera ao reiniciar o app, como o resto do estado).

## Testes

Suíte completa verde (94 testes), incluindo os novos:

- `push survives screen rotation started before completion` agora também afirma que **logo após a rotação** a barra mostra "Pushing to alice/repo…", o spinner está visível e o Push desabilitado; e ao fim, "Pushed" na barra e spinner fora;
- segundo toque durante o push: o toast certo é disparado, a barra continua no push e o spinner gira (commit POST travado por gate — determinístico);
- rotação pós-resultado: nenhum re-toast, texto digitado preservado, e a barra **continua** com "Pushed…" mesmo depois da auto-reconexão da tela nova;
- `TargetFieldGeometryTest`: alturas medidas iguais (repo 48 = branch 48 = pasta 48) em layout inflado de verdade; sugestões de pasta-mãe dos arquivos escolhidos; pasta usada vira a principal sugestão.

## Notas

- **versionCode 15, versionName 0.2.9** — instala por cima da 0.2.8 (mesma chave).
- Arquitetura 0.2.8 intacta: o push continua no `PushRunner` (escopo de app) com o foreground service mínimo — nada da linha 0.3.x voltou (sem fila, sem retry, sem histórico persistente).
- O comportamento do push em si não mudou: mesmo motor, mesmo formato de commit (título + descrição), mesmo fluxo.
