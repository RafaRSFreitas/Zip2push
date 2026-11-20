# Zip2Push — v0.3.0 (explorador de pastas do repo + card sempre fiel à conta)

> **Pedidos atendidos:** (1) o campo *Target folder in the repo* agora abre um popup estilo **Explorer** com as **pastas reais do repositório/branch selecionados**, navegação por **breadcrumbs**, ação **New folder** e confirmação com um toque; (2) trocar a conta do GitHub **atualiza automaticamente o card "Choose what to push"** — seleções da conta anterior não sobrevivem à troca.

## 1. Explorador de pastas do repositório (target folder)

O ícone de pasta no campo de destino abre o popup. A pesquisa de UX que embasou o desenho: **breadcrumbs** são o padrão clássico de *wayfinding* hierárquico (NN/g, "Breadcrumbs: 11 Design Guidelines"), com a **localização atual sempre visível e distinguível**; listas de uma linha com ícone + nome são o padrão do Material 3 para seleção em hierarquias; e o popup **filtra arquivos** (é um seletor de destino, não um file picker). A navegação busca **uma página de diretório por salto** via `GET /repos/{owner}/{repo}/contents/{path}?ref={branch}` — exatamente o que o browser de arquivos do próprio GitHub faz; nada de baixar a árvore inteira do repo.

- **Breadcrumbs**: `repositório / pasta / subpasta`, cada crumb clicável para pular direto; a crumb atual em negrito.
- **Pastas reais**: subpastas do diretório corrente na branch selecionada (ordenadas alfabeticamente); arquivos ficam ocultos.
- **New folder…**: linha dedicada no fim da lista; aceita nomes aninhados (`assignments/week4`), sanitiza segmentos vazios, `.` e `..` e anexa ao caminho atual **sem chamada de rede** — no Git a pasta só materializa no GitHub quando o primeiro push aterrissa nela. Após criar, o usuário continua podendo aninhar mais ou confirmar.
- **Choose this folder**: grava o caminho no campo (texto simples, o mesmo que o push consome) e fecha o popup.
- **Estados**: spinner durante o fetch, "no subfolders" com a ação de criar pasta acessível, e erro inline com a mensagem da API. Repo vazio (HTTP 409 do contents) é tratado como pasta vazia, não como erro.
- **Pré-requisito**: exige repo+branch selecionados; sem eles, um toast curto avisa ("Pick a repository and branch first") em vez de abrir o popup.
- O campo continua aceitando **texto livre** — digitar `assignments/week3` à mão funciona como sempre funcionou.

Detalhe técnico: o limite de **1.000 entradas por diretório** da API de contents é propagado como erro explícito (o popup diz o que aconteceu em vez de mostrar árvore parcial).

## 2. Card "Choose what to push" atualizado ao trocar de conta

Ao conectar com outra conta (popup de contas → tocar numa conta, ou conectar um token novo), o card é **resetado automaticamente**: repositório, branch, pasta de destino e o botão *View on GitHub* do push anterior são limpos — essa seleções pertencem à conta anterior e não fazem sentido para a nova. A lista de repos da nova conta chega em seguida e o dropdown abre sozinho (comportamento de "sessão nova" preservado).

O que **não** muda: os arquivos escolhidos (ZIP/files/folder) permanecem — são do usuário, não da conta; e o **auto-reconnect pós-rotação** não reseta nada (a seleção precisa sobreviver ao giro de tela, comportamento v0.2.8 mantido e coberto por teste).

## Testes

Suíte completa + 17 testes novos:

- `DirEntryParsingTest` (5): parsing defensivo do `/contents` (nulls, blanks, fallbacks);
- `FolderBrowserTest` (7): popup lista pastas reais e filtra arquivos; navegação por linha atualiza breadcrumbs; crumb volta à raiz; pasta vazia mostra estado próprio; New folder anexa ao caminho (inclusive nome aninhado e sanitização de `..`); Choose grava o caminho no campo e confirma na status line; sem repo selecionado → toast, sem popup — tudo via **MockWebServer** no caminho HTTP real do `GitHubClient`;
- `AccountSwitchCardResetTest` (3): troca de conta reseta o card e preserva arquivos; qualquer connect manual reseta; auto-connect pós-rotação preserva a seleção.

## Notas

- **versionCode 16, versionName 0.3.0** — instala por cima da 0.2.9 (mesma chave).
- Nova dependência: `androidx.recyclerview:recyclerview:1.3.2` (a lista do Explorer).
- Arquitetura intacta: push continua no `PushRunner` (escopo de app) + foreground service; o Explorer é uma classe isolada (`browser/FolderBrowser.kt`) que só lê a API de contents — nenhum caminho de push foi tocado.
- Cores do popup seguem os tokens JornalWeb zinc (dia/noite) do design system do app.
