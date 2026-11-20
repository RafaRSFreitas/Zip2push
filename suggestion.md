# Zip2Push — v0.3.3 (History no menu + hint do campo de pasta simplificado)

> **Pedidos atendidos:** (1) o hint do campo de pasta agora diz apenas **"Target folder (optional)"**; (2) novo item **History** no menu de 3 pontos, mostrando os pushes concluídos de forma limpa e organizada, com detalhes e link para ver as mudanças no GitHub; (3) **22 testes novos** cobrindo o History de ponta a ponta. Suíte completa: **154 testes, 0 falhas**.

## 1. Hint simplificado

`target_dir_hint` era "Target folder in the repo (optional)" → agora **"Target folder (optional)"**, como pedido. O texto de ajuda abaixo do campo (helper) continua explicando o comportamento.

## 2. History — o que mostra e por quê

A composição das linhas segue as duas referências pesquisadas:

- **git log** (https://git-scm.com/book/en/v2/Git-Basics-Viewing-the-Commit-History): assunto, autor, data relativa, SHA abreviado, e os arquivos com seus nomes;
- **GitHub Activity View** (https://docs.github.com/en/repositories/viewing-activity-and-data-for-your-repository/using-the-activity-view-to-see-changes-to-a-repository): quem, quando, branch e o affordance "Compare changes" (ver as mudanças exatas de cada evento).

### A lista (mais recente primeiro)

Cada linha mostra:

| Elemento | Exemplo | Origem da referência |
|---|---|---|
| Ícone de status | ✓ verde / ⚠ vermelho | GitHub Activity |
| Assunto (título do commit) | `Upload week 3` | git log `%s` |
| Data relativa | `2h ago` | git log `%ar` ("most scannable") |
| Repo · branch · pasta | `alice/repo · main · assignments` | GitHub Activity |
| Autor · arquivos · tamanho · SHA | `by alice · 3 files · 24 kB · a1b2c3d` | git log + Activity |

### O detalhe (tocar numa linha expande)

- **Files in this push** — os paths de repo que o push carregou (git log `--name-only`); acima de 8 arquivos, mostra os 8 primeiros + "Show all 20 files";
- **See changes on GitHub** — abre o commit no browser (o "Compare changes" da Activity View); em falhas, mostra o motivo (ex.: `Tree: HTTP 403`) em vez do link;
- Tocar de novo recolhe; apenas uma linha fica expandida por vez.

### Outros comportamentos

- **Falhas também entram no histórico** (com ícone ⚠ e motivo) — o usuário quer saber o que deu errado, não só o que deu certo;
- **Clear history** (botão neutro) com diálogo de confirmação que deixa claro que só a lista local é apagada — nada no GitHub é afetado;
- **Estado vazio** amigável ("No pushes yet…") quando não há histórico;
- **Persistência local** (JSON no storage privado, cap de 100 entradas, escrita atômica temp+rename, arquivo corrompido degrada para lista vazia sem crash);
- **Cap de arquivos por entrada**: sem limite — o detalhe trunca a exibição em 8, mas guarda todos.

## 3. Implementação

| Arquivo | Papel |
|---|---|
| `history/PushHistoryStore.kt` | `HistoryEntry` (modelo + JSON) e o store persistente |
| `history/PushHistoryDialog.kt` | dialog com RecyclerView, expand/collapse, link clicável, Clear com confirmação, datas relativas e bytes formatados |
| `push/PushRunner.kt` | grava a entrada ao push terminar (sucesso **e** falha), com os paths resolvidos durante o upload |
| `MainActivity` | menu 3 pontos: **History** (novo, primeiro item), About, Donations |

A gravação é **best-effort** (um erro de I/O no histórico nunca falha o push em si) e captura os metadados no próprio `PushRunner` (app-scoped), então funciona igual com ou sem a activity viva (rotação, processo em background).

## 4. Testes (22 novos, todos verdes)

**PushHistoryStoreTest (9)** — round trip JSON com todos os campos; ordenação mais-novo-primeiro; entradas de falha (sem SHA, com motivo); cap de 100 mantendo as mais novas; `shortSha()` de 7 chars (git style); clear; arquivo corrompido → lista vazia sem crash; unicode/emoji no round trip; campos opcionais ausentes no parse.

**PushHistoryDialogTest (9)** — buckets de data relativa (now/m/h/d/mo); formatação de bytes (B/kB/MB); estado vazio; linha completa (repo · branch · pasta, autor, arquivos, SHA, tempo); plurais (1 file / 3 files); expand/recolhe com lista de arquivos + link "See changes"; falha mostra motivo no expand; truncamento de muitos arquivos (+ "Show all"); pasta raiz vira "repository root".

**PushHistoryIntegrationTest (4)** — push de sucesso via MockWebServer gera entrada completa (repo, branch, pasta, título, autor, SHA, URL do commit, paths reais do ZIP com root folder stripped, bytes); push com erro 500 é gravado como falha com o motivo; dois pushes = duas entradas, mais nova primeiro; menu abre o History.

Bugs encontrados e corrigidos pela própria bateria: data relativa esperava granularidade errada em um bucket (corrigido antes de rodar — segui as convenções do git log); o teste de falha inicialmente usava um servidor que respondia sucesso em tudo (o push não falhava) — reescrito com endpoints 500 reais.

## Notas

- **versionCode 19, versionName 0.3.3** — instala por cima da 0.3.2 (mesma chave).
- Sem dependências novas: RecyclerView (já presente), JSON org (plataforma), dialogs Material (já presentes).
- O histórico é por dispositivo/app (não sincroniza — é um log local do que foi feito nele).
