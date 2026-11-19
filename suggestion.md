# Zip2Push — v0.2.7 (rollback para a arquitetura 0.2.6 + Add description)

> **Decisão desta versão:** a linha 0.3.x foi abandonada. Você pediu para voltar ao 0.2.6 — a última versão que funcionava por completo — e implementar **apenas** a feature de descrição. A v0.2.7 é exatamente isso: a arquitetura do 0.2.6 restaurada, com um único acréscimo.

## O que foi removido (tudo que a 0.3.0–0.3.3 introduziu)

Todos os subsistemas que chegaram depois do 0.2.6 saíram do app — eles carregavam os crashes relatados desde a 0.3.0:

| Removido | O que era (v0.3.x) |
| --- | --- |
| `push/PushService.kt` + permissões `POST_NOTIFICATIONS` / `FOREGROUND_SERVICE*` | Push em segundo plano com notificação (foreground service) |
| `storage/PushQueue.kt` + polling de conectividade de 30 s | Fila de pushes com retry automático |
| `storage/PushHistory.kt` + telas de histórico | Histórico de pushes |
| Share target (`ACTION_SEND`/`SEND_MULTIPLE` no manifest) | Compartilhar arquivos de outros apps |
| `AccentColor.kt` + overlays de cor de acento | Cores de acento configuráveis |
| Autocomplete de subpasta-alvo | Sugestões de pastas do repo no campo target |
| `engine/PushManager.kt` (singleton) | Push sobrevivendo a rotação/background |

O manifest volta a ter **uma única permissão: `INTERNET`** — igual ao 0.2.6.

## O que ficou (o 0.2.6 completo)

- Conexão por token (multi-conta com Keystore), lista de repos e branches com paginação, criação de branch, botão reload;
- Seleção de ZIP / arquivos / pasta (com caminhos relativos), botões equalizados, resumo da seleção;
- Mensagem de commit, **pasta-alvo**, **backdate** (data retroativa, com diálogo de aviso — feature v0.2.x), tema claro/escuro, menu About/Donations;
- O push roda **no scope da activity** (arquitetura 0.2.6): upload em `Dispatchers.IO`, resultado na linha de status, crashGuard transformando qualquer exceção em mensagem de erro em vez de crash do processo. Uma rotação durante o upload cancela o push (comportamento do 0.2.6) — sem singletons de fundo para quebrar.

## A única feature nova: Add description (v0.2.7)

Comportamento pedido, implementado exatamente como especificado:

1. O campo de descrição começa **recolhido**; só o botão **"Add description"** é visível.
2. Ao tocar em "Add description", o campo multilinha expande e o botão some (não tem mais utilidade).
3. **Com o campo vazio**, aparece o botão **"Hide description"** para recolhê-lo — é o caso "abriu sem querer / não quer mais usar".
4. **Assim que o usuário digita algo, o botão de recolher desaparece** — texto digitado nunca é escondido silenciosamente.
5. Se o texto for apagado, o botão de recolher volta.
6. Recolher limpa o texto e devolve o "Add description".
7. Na hora do push, a descrição vira o **corpo do commit** no formato padrão do Git: `título` + linha em branco + `corpo`. Após um push bem-sucedido (ou "Clear selection"), o campo volta ao estado inicial (recolhido, vazio).

## Testes — 87/87 verdes

- **78** testes da regressão v0.2.x que continuam válidos (backdate, tema, clear, dropdowns, sessão, parsing do GitHubClient, PushHttp com MockWebServer, ArchiveReader, PathSanitizer, EngineHardening, SelectionTracker, Screenshot, geometria dos botões, resiliência).
- **+6** `DescriptionCollapseTest` — a spec completa da feature, incluindo os casos-limite: texto só-espaços conta como vazio; digitar esconde o recolher; apagar traz de volta; clique real nos botões.
- **+3** `PushFlowTest` — o fluxo v0.2.7 de ponta a ponta com HTTP real: jornada completa (conectar → repos → zip real → push com descrição → o corpo `"título\n\ncorpo"` confirmado no request do commit), segundo toque durante o push recusado (exatamente 1 sequência Git no wire), e backdate sobrevivendo à rotação.
- Removidos os testes dos subsistemas excluídos (PushLifecycleTest, RealUsageJourneyTest, PushServiceContractTest, PushManagerStabilityTest, PushHistoryAndQueueTest, StorageStabilityTest).

## Notas

- **versionCode 13, versionName 0.2.7** — instala por cima de qualquer versão anterior (mesma chave de assinatura).
- A tela, strings e diálogos voltam ao visual do 0.2.6; nenhuma string de fila/histórico/notificação/acento resta no APK.
- Se um push falhar por rede, o comportamento é o do 0.2.6: mensagem de erro na linha de status; o usuário toca Push de novo quando quiser.
