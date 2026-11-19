# Zip2Push — sugestões de novas features (v0.2.6)

> **Nada abaixo foi implementado.** É uma proposta para revisão — me diga quais itens aprovar, alterar ou descartar, e eu implemento na ordem que você escolher.

Cada sugestão tem: o que é, por que vale a pena (problema real que resolve), complexidade estimada (S = ≤ meio dia, M = 1–2 dias, L = 3+ dias) e riscos.

---

## 1. Push em segundo plano com notificação (foreground service) — M

**O que:** transformar o push num `ForegroundService` com notificação de progresso ("Enviando 3/10 arquivos…"), em vez de depender do processo da Activity ficar vivo.

**Por quê:** hoje o `PushManager` sobrevive à rotação e ao background (v0.2.5), mas o Android ainda pode matar o processo em memória baixa com o app em background. Para ZIPs grandes em redes lentas, o service + notificação é a única garantia real de conclusão.

**Riscos:** precisa de permissão `POST_NOTIFICATIONS` (Android 13+) e `FOREGROUND_SERVICE_DATA_SYNC` (Android 14+); mais estados para testar.

## 2. Fila de pushes + retry automático — M

**O que:** se um push falha por rede (timeout, avião), ele entra numa fila persistida (Room ou arquivo) e o app tenta de novo quando a conectividade volta; lista de pendentes visível com opção de cancelar.

**Por quê:** hoje uma falha no meio da sequência Git Data API (ex.: blob 4 de 10 subido) exige refazer tudo manualmente. O retry idempotente é seguro porque o commit só é criado no final (blobs e trees são imutáveis e reutilizáveis).

**Riscos:** persistir o conteúdo dos arquivos até o push dar certo ocupa cache; definir política de expiração.

## 3. Histórico de pushes — S

**O que:** tela/dialog com os últimos pushes da sessão-permanente (repo, branch, data, arquivos, link do commit, status), persistido localmente; tocar abre o commit no GitHub.

**Por quê:** o botão "View commit" só mostra o **último**; quem empurra vários pacotes seguidos perde os links anteriores.

**Riscos:** baixo. Sem sync com servidor — é log local.

## 4. Seleção de subpasta-alvo com autocomplete — S

**O que:** o campo "target directory" virar um dropdown com as pastas existentes no repo (da árvore Git já buscada no push) + digitação livre.

**Por quê:** erros de digitação criam pastas erradas no repo (ex.: `docs ` com espaço). Autocomplete das pastas reais elimina o erro.

**Riscos:** baixo. A árvore pode ser grande em repos enormes — limitar sugestões a ~20.

## 5. Drag & drop entre contas (multi-account) — M

**O que:** ao trocar de conta, manter a seleção de arquivos (hoje a troca limpa tudo) e mostrar de qual conta o repo atual pertence no dropdown (ex.: badge com avatar do owner quando o repo não é da conta ativa).

**Por quê:** com 2+ contas, trocar para publicar num repo da outra conta hoje exige reconectar e re-selecionar tudo.

**Riscos:** o token da conta ativa pode não ter permissão no repo escolhido — precisa validar antes do push.

## 6. Descrição do commit multilinha — S

**O que:** campo de mensagem com botão "expandir" para título + corpo (o corpo vai no mesmo `message` do commit, separado por linha em branco).

**Por quê:** mensagem única de linha dificulta descrever o que o pacote contém; corpo multilinha é o padrão Git.

**Riscos:** nenhum técnico; ajuste de layout.

## 7. Compartilhar com Zip2Push (Share Target) — M

**O que:** registrar a app como destino de compartilhamento (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`): compartilhar arquivos de qualquer app abre o ZipPush com eles pré-selecionados.

**Por quê:** hoje o fluxo é "abrir o ZipPush → procurar arquivos"; Share Target inverte para "arquivo → ZipPush", que é o gesto natural do Android.

**Riscos:** `ACTION_SEND_MULTIPLE` com URIs de outros apps exige cuidado com permissões temporárias (`Intent.FLAG_GRANT_READ_URI_PERMISSION`).

## 8. Suporte a .tar.gz / .tar — M

**O que:** aceitar tarballs além de ZIP no botão de arquivo.

**Por quê:** usuários de Linux frequentemente têm tarballs; hoje recebem "formato não suportado".

**Riscos:** biblioteca de tar nativa no Android é limitada (Apache Commons Compress adiciona ~200KB); testes com tarballs grandes.

## 9. Detecção de conflito (branch moveda) com merge guidance — M

**O que:** quando o `updateRef` falha porque a branch avançou desde o `getBranchHead`, buscar o novo head, recriar o commit com o pai correto (rebase simples) e tentar de novo — em vez de falhar com "Update branch".

**Por quê:** push simultâneo de duas pessoas (ou do próprio usuário no PC) hoje falha no final, depois de subir todos os blobs.

**Riscos:** recriar commit/tree com novo pai exige refazer só o commit final (barato); janela de corrida continua existindo (retry único, depois falha com instrução clara).

## 10. Tema de cor acento configurável — S

**O que:** além do claro/escuro atual, oferecer escolha da cor de acento (verde GitHub padrão, azul, roxo).

**Por quê:** pedido recorrente em apps de upload; baixo custo com Material 3 (tokens dinâmicos).

**Riscos:** nenhum funcional; manter contraste WCAG nas cores escolhidas.

---

## Não recomendo (por ora)

- **Upload de pastas inteiras grandes (>100 arquivos) sem fila:** a API Git Data faz 3 requests por arquivo; acima de ~100 arquivos o risco de rate limit (5.000/h autenticado) é real. Recomendo antes medir e, se necessário, agrupar via Git Tree API em lotes.
- **Login OAuth via navegador:** o GitHub exige uma URL de callback registrada por app; sem domínio próprio o fluxo OAuth publicado não funciona — tokens finos continuam sendo o caminho correto aqui.

## Minha priorização sugerida

1. **#1 Foreground service** (fecharia o ciclo de confiabilidade iniciado no v0.2.5)
2. **#3 Histórico** (S, ganho imediato de UX)
3. **#9 Detecção de conflito** (elimina a falha mais confusa do fluxo)
4. **#6 Mensagem multilinha** (S, polish)
5. Resto conforme seu uso real.
