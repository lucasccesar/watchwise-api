# Auditoria completa da aplicação — 2026-09-04

Revisão pedida pelo usuário: "revise a aplicação inteira e veja se tem algum erro silencioso, alguma
incoerência, alguma má implementação, alguma falha de segurança, coisas desse tipo". Feita em 5 frentes
paralelas (segurança/auth; user/follower/followedperson; content/diaryentry/watchlist/dropped/top5entry;
comment/like/userlist; notification/summary/search + transversais), cada uma cruzando os achados contra
`business-rules.md` e `docs/pending/to-fix.md` antes de reportar, pra não duplicar o que já está
rastreado. Só investigação — nada foi implementado ainda.

---

## Alta severidade

### 1. ✅ CORRIGIDO (2026-09-05) — `DELETE /users/me` quebrava com 500 se o usuário já foi `watchedWith` (companion) em diário alheio

**Arquivos:** `db/migration/V35__create-watch-companions-table.sql:7` + `UserServiceImpl.deleteAccount`
(`user/service/impl/UserServiceImpl.java:285-293`)

A FK `fk_watch_companions_user` (`watch_companions.user_id → users.id`) é a **única** referência a
`users(id)` em todo o schema sem `ON DELETE CASCADE` — todas as outras 15+ FKs para `users` têm.
`deleteAccount` chama `userRepository.delete(user)` sem tratar essa violação específica.

Reproduzível de verdade, não teórico: usuário A adiciona B como `watchedWith` num `DiaryEntry` (B nunca
precisou logar nada no próprio diário pra isso acontecer) → B tenta `DELETE /users/me` → a FK barra o
delete → `DataIntegrityViolationException` sem handler dedicado no `GlobalExceptionHandler` (só existe
o catch-all `Exception.class`) → **500 "An unexpected error occurred"**, sem nenhuma pista pro usuário
ou suporte.

**Correção sugerida:** migration nova adicionando `ON DELETE CASCADE` na FK — faz sentido de domínio: se
um dos dois participantes do "assistimos juntos" some, o registro de companion sozinho não faz mais
sentido.

**Corrigido:** `V44__add-cascade-delete-to-watch-companions-user-fk.sql` adiciona `ON DELETE CASCADE` na
constraint. Teste de integração novo
(`UserControllerIntegrationTest.shouldDeleteAccountAndCascadeWatchCompanionRowWhenUserWasTaggedAsACompanionInAnotherUsersDiaryEntry`)
prova o cenário fim a fim. `business-rules.md`/`business-rules-summary.md` atualizados.

---

## Média severidade

### 2. ✅ CORRIGIDO (2026-09-05) — Lockout de login é por (IP + identifier), não só por identifier — bypass por rotação de IP

**Arquivo:** `user/controller/AuthController.java:210` (`buildLockoutKey`)

```java
return "login|" + request.getRemoteAddr() + "|" + identifier.trim().toLowerCase();
```

O `attemptLockout` (bloqueia a CONTA após N tentativas falhas) usa uma chave composta IP+identifier. Um
atacante fazendo brute-force distribuído contra uma conta específica (IPs diferentes — proxies, botnet,
VPN) nunca aciona o lockout daquela conta, porque cada IP tem contador independente. O
`requestThrottler` por IP (linha 116) limita tentativas totais por IP, mas não substitui o lockout por
conta.

Contraste dentro do próprio código: `UserController.lockoutKey` (linha 139-141, usado em patch/delete
de conta) usa **só o identificador do usuário**, sem IP — é o padrão correto que o projeto já usa em
outro lugar. O login é a exceção inconsistente.

**Corrigido:** `AuthController.buildLockoutKey` não recebe mais `HttpServletRequest`/IP — a chave passa
a ser só `"login|" + identifier.trim().toLowerCase()`, igual ao padrão já usado em
`UserController.lockoutKey`. O `requestThrottler` por IP (linha 116) continua intocado — ele é uma
camada complementar, não o lockout por conta. Teste de integração que documentava o comportamento antigo
(`shouldNotBlockADifferentIpWhenAnotherIpIsRateLimited`) foi invertido pra provar o comportamento
corrigido (`shouldStillBlockADifferentIpWhenSameIdentifierIsRateLimited` —
`AuthControllerIntegrationTest`); teste unitário `AuthControllerTest` também atualizado
(`shouldBuildLockoutKeyFromIdentifierOnlyWhenCalled`).

### 3. ✅ CORRIGIDO (2026-09-05) — `request.getRemoteAddr()` sem `forward-headers-strategy` configurado em prod

**Arquivos:** `AuthController.java:210,214`, `application-prod.properties:31`

`server.forward-headers-strategy=native` está comentado no template de prod. Atrás de um reverse
proxy/load balancer (cenário normal pra terminar HTTPS), `request.getRemoteAddr()` — usado como chave
de todo rate-limit por IP (`throttleKey`, `buildLockoutKey`) — sempre retorna o IP do proxy, não o do
cliente real. O rate-limit de login/register/refresh/oauth vira um contador **global compartilhado por
todos os usuários** atrás do mesmo proxy; um único usuário gerando tráfego intenso pode bloquear
login/registro pra todo mundo.

**Corrigido:** `application-prod.properties:31` descomentado — `server.forward-headers-strategy=native`
agora é ativo por padrão no profile `prod` (é um valor estático, sem segredo, então não conflita com a
decisão pendente maior de "virar config real" registrada em `docs/pending/pending-to-deploy.md` item 1,
que trata de segredos/infra ainda não provisionados). Comentário acima da linha atualizado pra refletir
que a suposição "atrás de proxy" agora é o padrão assumido em prod, não mais opcional — quem fizer deploy
sem reverse proxy na frente precisa remover a linha em vez de só deixá-la comentada.

### 4. ✅ CORRIGIDO (2026-09-05) — `login()` vaza existência de conta por timing (BCrypt só roda quando o usuário existe)

**Arquivo:** `user/service/impl/UserServiceImpl.java:261-276`

A mensagem de erro já é idêntica nos dois casos (sem enumeration por texto). Mas quando o identifier
não existe, a resposta volta quase instantânea; quando existe e a senha está errada,
`passwordEncoder.matches` roda BCrypt (deliberadamente lento, dezenas a centenas de ms). Essa diferença
de tempo é mensurável remotamente com amostras suficientes e permite enumerar quais
usernames/emails existem, apesar da mensagem genérica.

**Corrigido:** `UserServiceImpl.login` agora sempre chama `passwordEncoder.matches(...)`, mesmo quando o
identifier não existe — comparando contra um hash BCrypt fixo (`DUMMY_PASSWORD_HASH`, formato válido,
nunca usado por nenhum usuário real) em vez de pular a chamada. Isso equaliza o custo computacional
entre "usuário não existe" e "usuário existe mas senha errada", fechando a diferença de tempo mensurável.
Teste que antes provava `verifyNoInteractions(passwordEncoder, ...)` nesse cenário foi ajustado — agora
verifica só `userMapper` — e um teste novo
(`shouldCompareAgainstADummyHashToEqualizeTimingWhenIdentifierDoesNotMatchAnyUser`) prova explicitamente
que `passwordEncoder.matches` é chamado com o hash dummy.

### 5. 🟡 Rate limiting inconsistente em endpoints que disparam `getOrCreateReference` (custo TMDB)

**Arquivos:** `watchlist/controller/WatchlistEntryController.java:37-44`,
`top5entry/controller/Top5EntryController.java:33-40`

Ambos chamam `contentService.getOrCreateReference(...)` — mesmo caminho de código e mesmo custo de
verificação/derivação via TMDB para um `tmdbId` novo que `POST /contents/reference`
(`ContentController.java:43-49`), `POST /diary`/`POST /diary/bulk` e
`POST /users/{userId}/dropped/{type}` (`DroppedEntryController.java:44-54`). Esses quatro têm
`requestThrottler.checkAllowed(...)` antes de chamar o service; watchlist e top5 **não têm throttle
nenhum**. Um usuário autenticado pode enumerar `tmdbId`s distintos via
`POST /users/me/watchlist/{type}` ou `POST /users/me/top5/{type}` sem limite algum, contornando
exatamente o controle que os outros quatro endpoints foram construídos pra impor.

### 6. 🟡 `UserList.name`/`description` sem `@Size` → nome grande vira `500` em vez de `400`

**Arquivos:** `userlist/dto/UserListCreationDTO.java:7`, `UserListBulkCreationDTO.java:13`,
`UserListPatchDTO.java`

As três DTOs de entrada pra `UserList` validam `name` só com `@NotBlank`, sem `@Size`. A coluna real é
`VARCHAR(255)` (`V19__create-user-lists-table.sql:4`). Um nome com mais de 255 caracteres não é barrado
pela Bean Validation, chega no insert/update, o Postgres rejeita ("value too long for type character
varying(255)"), vira `DataIntegrityViolationException`, e nada em `UserListServiceImpl` trata esse caso
específico (só trata conflitos de `rank`/unicidade). Cai no `handleUnexpectedException` genérico →
**`500`** em vez de `400`. `description` (coluna `TEXT`, sem limite no banco) também está sem `@Size`
em nenhuma das três DTOs — não quebra no banco, mas foge do padrão já usado em
`UserListItemCreationDTO.description`/`UserListItemPatchDTO.description` (`@Size(max = 400)`), que
mostra que o projeto já tem essa convenção, só não foi aplicada aqui. Bate com o item 4 do checklist
"Recurring bug patterns" do próprio `CLAUDE.md`.

### 7. 🟡 `GET /users/{userId}/follow-people` pagina sem nenhuma ordenação

**Arquivo:** `followedperson/repository/FollowedPersonRepository.java:20`
(`Page<FollowedPerson> findByUserId(UUID userId, Pageable pageable)`)

Derived query method sem `@Query`/`ORDER BY`. `FollowedPersonServiceImpl.getFollowedPeople` usa o
overload de 2 argumentos de `pageRequestFactory.build(...)`, que produz um `PageRequest` sem `Sort`
(`PageRequestFactory.java:42`). A query final não tem `ORDER BY` nenhum — a ordem das linhas fica a
critério do plano de execução do Postgres, podendo mudar entre duas chamadas da mesma paginação e causar
itens duplicados ou pulados entre páginas. Aqui não é só falta de tie-breaker secundário — não existe
ordenação primária alguma.

### 8. 🟡 Paginação de followers/following: fix marcado "corrigido" em `to-fix.md` ficou incompleto

**Arquivo:** `follower/repository/FollowerRepository.java:35,43`
(`ORDER BY f.createdAt DESC` em `findByFollowedIdAndStatus`/`findByFollowerIdAndStatus`)

`to-fix.md` (atualização 2026-08-25 (3)) registra esse item como corrigido — hoje existe
`ORDER BY createdAt DESC`, mas **sem tie-breaker secundário**. Duas linhas `Follower` criadas no mesmo
milissegundo (plausível sob ações concorrentes de seguir) têm ordem instável entre páginas — a mesma
classe de bug que o fix deveria ter fechado. O padrão correto já existe em outro lugar do próprio
código: `diaryentry/repository/DiaryEntryRepository.java:33` usa
`ORDER BY d.createdAt DESC, d.id DESC`. Correção: replicar esse padrão
(`f.createdAt DESC, f.id DESC`) nas duas queries do `FollowerRepository`.

**Achado relacionado, mesma classe de bug, fora do escopo original do revisor que achou o item acima:**
`DiaryEntryRepository.findByUserIdOrderByCreatedAtDesc` (linha ~49) tem exatamente o mesmo problema —
`ORDER BY createdAt DESC` sem `, id DESC` — apesar de outra query no mesmo arquivo (linha 33) já usar o
padrão correto com tie-breaker. Inconsistência dentro do próprio `DiaryEntryRepository`.

### 9. 🟡 `business-rules.md` desatualizado sobre a lógica de `RELEASE` em `ContentChangeDetector`

**Arquivos:** `docs/context/business-rules.md:1778-1780` vs
`notification/tracking/ContentChangeDetector.java:32-34`

A doc descreve `RELEASE` como disparado por comparação de data
(`!today.isBefore(previousReleaseDate)`, "dispara uma vez, no primeiro run em que a data vira
presente/passado"). O código real hoje usa transição de **status**
(`previousStatus != null && !RELEASED_STATUS.equals(previousStatus) && RELEASED_STATUS.equals(fresh.status())`).
Confirmado via `git log`: mudança veio do commit `cdd5fcb` —
`fix(notification): stop RELEASE re-firing and first-observation notification storm` (2026-08-30) —
porque a lógica antiga (baseada em data) reenviava `RELEASE` todo dia após o lançamento, já que a
condição de data permanece verdadeira pra sempre uma vez satisfeita. O fix em si é correto; só a doc não
foi atualizada junto, violando a própria regra do `CLAUDE.md` de manter `business-rules.md` em
sincronia com mudanças de regra de negócio. Problema real: quem ler a doc hoje entende uma lógica que
não existe mais — e pode reintroduzir o bug de re-disparo se implementar "de acordo com a doc".

---

## Baixa severidade / informativo

### 10. 🟢 `GET /diary/{diaryEntryId}/deletion-impact` sem rate limit, apesar de executar a cascata de deletes real

**Arquivo:** `diaryentry/controller/DiaryEntryController.java:127-133`

Não tem `requestThrottler.checkAllowed`, mas internamente chama `computeDeletionImpact` →
`deleteDiaryEntry(...)` (o mesmo método que faz os deletes de verdade, com toda a cascata de retração) e
só desfaz no final via `markCurrentTransactionRollbackOnly()`. O par de escrita real,
`DELETE /diary/{diaryEntryId}`, é throttled (`diaryActionKey`); essa rota de "simulação" paga a mesma
carga de escrita no banco (deletes reais, cascata calculada) e pode ser chamada sem limite.

### 11. 🟢 Nota de divergência: `to-fix.md` item 4 está parcialmente desatualizado

`to-fix.md` item 4 diz que `GET /notifications` devolve array puro sem `page`/`size`. Reconferido agora:
**`/notifications` já usa o envelope `PageMeta`** tanto no código quanto no `openapi.yaml` — só
`/search` continua sem paginação, mas `/search` **não está implementado** ainda, então não há
divergência código-vs-doc ali, é feature pendente. Vale atualizar/fechar a parte de `/notifications`
desse item em `to-fix.md`.

---

## Sem achados novos (conferido, correto)

- **`SecurityConfig`**: ordem dos matchers, CSRF ignorado só nas rotas certas, `/auth/logout-all`
  continua exigindo CSRF.
- **Cookies** (`CookieUtil`): `HttpOnly`, `Secure`, `SameSite=Lax`, paths consistentes.
- **CORS**: lista explícita de origens (não wildcard) com `allowCredentials(true)`.
- **JWT**: assinatura HMAC verificada, `exp` validado, sem bug de fuso horário na geração.
- **Refresh token rotation**: reuso de token revogado invalida todas as sessões; corrida na rotação
  tratada via `ObjectOptimisticLockingFailureException`.
- **`GoogleOAuthConfig`**: `audience` validado — sem token-confusion attack.
- **`GlobalExceptionHandler`**: catch-all loga a exceção real e nunca vaza stacktrace/mensagem interna,
  exceto o gap do item 1 (`DataIntegrityViolationException` sem handler dedicado).
- **Ownership/IDOR**: conferido em `DiaryEntry`/`WatchlistEntry`/`DroppedEntry`/`Top5Entry`,
  `Follower`/`FollowedPerson`, `Comment`/`Like`/`UserList`/`UserListItem`, `Notification` — todos os
  services filtram corretamente pelo `userId` do token, sem um único IDOR encontrado. Padrão consistente
  de usar `404` (não `403`) pra não vazar existência de recurso de outro dono.
  `GET /users/{userId}` retornando `403` estrito por `isProfilePublic` sem exceção pra dono/seguidor é
  comportamento documentado em `openapi.yaml:350-356`, não é bug.
- **Visibilidade de perfil privado**: replicada de forma consistente entre `SummaryServiceImpl`
  (`assertCanViewSummary`), `DiaryEntryServiceImpl`, `Top5EntryServiceImpl` — duplicação deliberada, não
  incoerência.
- **`UserMapper`/`PublicUserDTO`/`PublicUserProfileDTO`**: não vazam `email`/`password`.
- **Domínio `comment`/`like`/`userlist`**: races de like/unlike, cascata de exclusão, trava de 1 nível
  de aninhamento em `UserListItem`, contadores desnormalizados — tudo já corrigido e coberto por teste.
- **`ContentServiceImpl`/`DiaryEntryServiceImpl`/`Top5EntryServiceImpl`/`DroppedEntryServiceImpl`**:
  idempotência de get-or-create, cascata de auto-completude, todos batendo com `business-rules.md`.
- **`PageRequestFactory`**: clamp de `pageSize` acima do limite é comportamento deliberado e testado
  (a palavra "default" na tabela do `CLAUDE.md` é só imprecisão de wording, não bug funcional).
- **`NotificationServiceImpl`**: `markAsRead` checa ownership; `getNotifications` filtra por `userId`.
- Rotas de `NotificationController`/`SummaryController` batem com `openapi.yaml` e `CLAUDE.md`.
