# Zip2Push — v0.3.2 (campo de pasta: subtítulo limpo, reset ao trocar de repo + bateria de testes)

> **Pedidos atendidos:** (1) o subtítulo do Explorer de pastas não menciona mais que arquivos ficam ocultos; (2) trocar de repositório agora **limpa a pasta de destino** (a pasta escolhida pertencia à árvore do repo anterior); (3) uma **bateria de 13 testes** cobre o campo de pasta em todas as situações — e já achou (e corrigiu) um bug no caminho.

## 1. Subtítulo do Explorer sem menção a arquivos

`browser_subtitle` agora é apenas **"Folders of the selected repository and branch."** — a frase "Files are hidden — this picks a destination." foi removida, como pedido.

## 2. Trocar de repositório limpa a pasta de destino

`onRepoSelected` agora limpa o campo de pasta (e as sugestões) **quando o repositório muda de verdade** (`fullName` diferente):

- **Trocar de repo** → pasta limpa, junto com o branch e o botão "View on GitHub" (mesma regra dos outros campos account-bound: outra árvore de pastas, outra seleção).
- **Re-selecionar o MESMO repo** (ex.: para recarregar as branches) → a pasta escolhida **permanece**. Este refinamento saiu da própria bateria de testes: a primeira versão limpara incondicionalmente e o teste B2 provou que isso prejudicava o caso legítimo de refresh do mesmo repo.
- Trocar apenas a **branch** (mesmo repo) continua mantendo a pasta — mesma árvore.

## 3. Bateria de testes do campo de pasta (`FolderFieldTest`, 13 testes)

Quatro grupos, cobrindo o ciclo completo do campo:

**A. Preencher**
- A1: seleção via Explorer põe a pasta escolhida no campo (`assignments`).
- A2: escolher a raiz do repo deixa o campo vazio (via crumb do repo).
- A3: "New folder" com nome aninhado constrói o path completo no campo (`assignments/week4`).
- A4: texto digitado livre permanece no campo.
- A5: a pasta usada num push vira sugestão para o próximo push.

**B. Limpar**
- B1: **trocar de repositório limpa a pasta** (regra nova, rota real do dropdown).
- B2: re-selecionar o MESMO repo mantém a pasta (bug da versão inicial, corrigido).
- B3: trocar de conta limpa a pasta (reset account-bound da v0.3.0).
- B4: "Clear Selection" limpa o campo junto com o resto.

**C. Manter**
- C1: trocar apenas a branch mantém a pasta.
- C2: a pasta sobrevive à rotação de tela.

**D. Consumir**
- D1: o push realmente mira a pasta escolhida — o body da tree do commit contém `"path":"assignments/week3/report.txt"`.
- D2: texto "sujo" (`  ./assignments//week3/  `) é sanitizado e o push vai para `assignments/week3` (verificado no body da tree).

## Testes

Suíte completa: 132 testes no total (13 novos da bateria).

## Notas

- **versionCode 18, versionName 0.3.2** — instala por cima da 0.3.1 (mesma chave).
- Sem mudanças de arquitetura: a regra de clear mora em `onRepoSelected`; seams novos de teste (`targetDirTextForTest`, `setTargetDirTextForTest`, `selectRepoForTest`) seguem o padrão dos existentes.
- Detalhe técnico dos testes D: o ZIP de teste tem pasta raiz única (`docs/report.txt`), que o app remove na extração — o path esperado na tree é `assignments/week3/report.txt`, sem o `docs/`.
