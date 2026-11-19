# Zip2Push — v0.2.8 (o push vai até o fim)

> **Problema relatado:** ao dar push, o upload às vezes parava quando o app ia para o segundo plano ou quando o aparelho era rotacionado.
>
> **Causa:** herança da arquitetura 0.2.6 restaurada na v0.2.7 — o push rodava dentro do *coroutine scope da activity*. Rotação destrói a activity (e cancela o upload no meio); em segundo plano o sistema pode matar o processo a qualquer momento.
>
> **Correção v0.2.8:** dois mecanismos mínimos, sem re-introduzir nada da linha 0.3.x (sem fila, sem retry, sem histórico, sem share, sem accent, sem prompt de notificação).

## O que mudou

### 1. `PushRunner` — o push agora pertence ao app, não à tela

O upload saiu do scope da activity e foi para um **singleton de escopo-de-aplicação** (`push/PushRunner.kt`). O que isso resolve:

- **Rotação:** destruir a activity não cancela mais o upload — ele corre no scope do runner, que ninguém fecha. A activity recriada **re-conecta ao runner** no `onCreate` e re-renderiza o estado: enquanto o push corre mostra o progresso; quando termina, mostra "Pushed ✓" com o botão *View on GitHub*. Um push terminado fica retido até o próximo começar, então mesmo uma rotação logo após o fim ainda mostra o resultado.
- **Activity destruída / usuário saiu:** o push continua sozinho no runner e termina; ao reabrir o app, o resultado está lá.
- **Toque duplo:** `PushRunner.start` só aceita um push por vez — o segundo toque durante um push mostra "already in progress" (igual antes, agora à prova de rotação).
- Todo o trabalho de leitura de arquivos (cache do ZIP, content resolver) também migrou para o runner, com contexto de **aplicação** — nada referencia mais a activity morta.

### 2. `PushService` — o processo não morre em segundo plano

Um **foreground service mínimo** (`push/PushService.kt`, tipo `dataSync`) é iniciado junto com o push e **para sozinho quando o push termina** (observa o runner — sucesso ou falha). Ele não roda lógica nenhuma de upload; só mantém o processo vivo pelo curto período do upload.

Detalhes deliberados:

- Notificação **silenciosa** (canal `IMPORTANCE_LOW`, sem som, sem badge). Nenhum prompt de permissão é exibido — `POST_NOTIFICATIONS` **não** foi adicionada; no Android 13+ sem a permissão a notificação simplesmente não aparece e o push funciona igual.
- `startForeground()` é chamado **antes** de qualquer `stopSelf()` (o contrato do Android que a 0.3.1 quebrou — mantido certo aqui).
- O serviço só é iniciado **enquanto a activity está started** (`onStart`–`onStop`), evitando o `ForegroundServiceStartNotAllowedException` do Android 12+.
- Sem fila, sem polling, sem retry: se o push falhar por rede, a mensagem de erro aparece na linha de status e o usuário toca Push de novo (comportamento 0.2.x que você conhece).

## O que NÃO voltou da 0.3.x

Fila de pushes, polling de 30 s, histórico, share target, cores de acento, autocomplete de pasta, prompt de notificação — tudo continua fora. O manifest ganhou apenas as duas permissões que o foreground service exige (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`) além do `INTERNET`.

## Testes

- Suíte completa verde (regressões 0.2.x de backdate/tema/clear/dropdowns/parsing/engine + `DescriptionCollapseTest` intactos — a feature de descrição da v0.2.7 não mudou).
- `PushFlowTest` reescrito para a nova arquitetura, com os casos novos:
  - **`push survives screen rotation started before completion`** — a rotação acontece com o upload no meio do wire (o POST do commit só responde *depois* da rotação); o push completa e a activity recriada mostra o resultado, com exatamente 1 sequência Git no wire;
  - **`push completes with no activity attached`** — a activity é destruída no meio do push; o runner termina sozinho e um relançamento mostra o commit;
  - **`foreground service starts with push`** — o intent do serviço é emitido ao iniciar o push;
  - **`recreated activity shows busy`** — a activity recriada bloqueia Push enquanto o push herdado corre;
  - jornada completa com descrição (`título\n\ncorpo` confirmado no request), segundo toque recusado, backdate sobrevivendo à rotação.

## Notas

- **versionCode 14, versionName 0.2.8** — instala por cima da 0.2.7 (mesma chave).
- Limite honesto: se o **usuário forçar a parada** do app (ou o sistema matar o processo por memória em condição extrema), o push obviamente morre — foreground service reduz muito a janela, mas não é imunidade absoluta. O retry manual continua sendo: tocar Push de novo.
