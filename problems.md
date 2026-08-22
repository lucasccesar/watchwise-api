# Problemas encontrados — falhas silenciosas e lacunas de implementação

Levantamento feito em 2026-08-22 sobre todo o `src/main`, procurando por falhas silenciosas
(erros mascarados, estados inconsistentes sem exceção/log) e lacunas de implementação
(validações ausentes, features incompletas, proteções que não cobrem todos os casos).

Nenhum item abaixo foi corrigido ainda — este arquivo é só o registro dos achados.

## Alta severidade — integridade de dados / segurança

### 1. ~~`AttemptLockout` — check-then-act não atômico~~ — CORRIGIDO
**Arquivo:** `src/main/java/com/watchwise/watchwise_api/common/security/AttemptLockout.java`

`checkAllowed(key)` (leitura) e `recordFailure(key, ...)` (escrita) eram operações separadas.
O autenticador chamava `checkAllowed` antes de verificar a senha e só chamava `recordFailure`
depois (operação lenta, tipicamente BCrypt ~100ms). Entre as duas havia uma janela onde várias
requisições concorrentes passavam por `checkAllowed` com o mapa ainda sem `blockedUntil`,
permitindo que um ataque de força bruta paralelizado ultrapassasse `maxAttempts` no burst
inicial antes do lock passar a valer.

**Correção:** `checkAllowed` agora recebe `maxAttempts`/`window` e reserva atomicamente uma
tentativa (mesmo padrão de `compute()` atômico do `RequestThrottler`), lançando
`TooManyRequestsException` já na reserva se o limite seria excedido — inclusive sob
concorrência, já que o `compute()` do `ConcurrentHashMap` serializa o acesso por chave.
`recordFailure` só passa a marcar `blockedUntil` quando a contagem já reservada atinge o
limite, sem incrementar de novo. Chamadas atualizadas em `AuthController.login` e
`UserController.updateCurrentUser`/`deleteCurrentUser`; `AttemptLockoutTest` reescrito para
cobrir o novo comportamento (incluindo reserva concorrente estourando o limite).

### 2. `DroppedEntryServiceImpl.markAsDropped` — efeito colateral não atômico
**Arquivo:** `src/main/java/com/watchwise/watchwise_api/dropped/service/impl/DroppedEntryServiceImpl.java` (~linhas 79-113)

A remoção da watchlist (`watchlistEntryService.removeEntryIfPresent`, `@Transactional`
própria) comita de forma independente, **antes** da criação do `DroppedEntry`, que roda
em outra transação (`REQUIRES_NEW`, via `newTransactionExecutor`). Apenas
`DataIntegrityViolationException` é recuperada nesse segundo passo. Qualquer outra falha
(erro transitório de BD, `EntityNotFoundException`, etc.) deixa o item removido da
watchlist sem nunca virar "dropped" — o dado não fica em nenhum dos dois lugares, sem
rollback possível.

### 3. `UserServiceImpl.updateUser` — aceite de follows comita antes do save do usuário
**Arquivo:** `src/main/java/com/watchwise/watchwise_api/user/service/impl/UserServiceImpl.java` (~linhas 71-134)

Ao tornar o perfil de privado para público, `applyPatch` chama
`followerService.acceptAllPendingFollowRequestsFor(user.getId())`, que roda numa query
`@Modifying`/`@Transactional` própria e já comita. Isso acontece **antes** de
`userRepository.save(user)`. Como `UserServiceImpl` não tem `@Transactional` em nenhum
método, se o `save` falhar depois por conflito de unicidade (email/username em uso →
409), os follow requests já foram aceitos permanentemente mesmo com a API respondendo
erro ao cliente.

### 4. Cascade delete deixa gap silencioso de posição (userlist e watchlist)
**Arquivos:**
- `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListServiceImpl.java` — `deleteUserList` (~linhas 207-213)
- `src/main/java/com/watchwise/watchwise_api/watchlist/service/impl/WatchlistEntryServiceImpl.java`

`deleteUserList` só chama `userListRepository.delete(userList)`. A FK
`fk_user_list_items_child_list` (migration `V20__create-user-list-items-table.sql`) é
`ON DELETE CASCADE`: se a lista excluída estava aninhada como `childList` dentro de outra
lista, o banco remove o item correspondente na lista pai silenciosamente, sem passar pelo
fluxo `deleteAndCloseGap`/park-and-settle. Resultado: a lista pai fica com gap de posição
(ex.: 1,2,4,5 em vez de 1,2,3,4), quebrando a invariante de contiguidade que o próprio
algoritmo de reorder assume — o que depois pode gerar falsos "conflito de posição"
(`uq_user_list_items_user_list_id_position`) em inserções futuras, com mensagem enganosa
de concorrência quando a causa real é o gap.

Mesmo padrão em `watchlist` via `fk_watchlist_entries_content ON DELETE CASCADE`
(migration `V17`) quando um `Content` referenciado é excluído.

### 5. `UserListItemServiceImpl.resolveChildList` — aninhamento sem limite de profundidade
**Arquivo:** `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemServiceImpl.java` (~linhas 261-276)

A checagem `existsByUserListIdAndChildListIdIsNotNull(childListId)` só valida que a lista
**referenciada** ainda não contém listas aninhadas — nunca valida se a lista que está
**recebendo** o item já é ela própria filha de outra lista. Isso permite cadeias de
profundidade arbitrária (P→X→Y→Z→...), contradizendo a própria mensagem de erro do código
("nesting depth is limited to one level"). Ciclos verdadeiros (A→B→A) continuam bloqueados
corretamente.

### 6. `DiaryEntryServiceImpl.wipeSeriesHistory` — cascata de exclusão desproporcional
**Arquivo:** `src/main/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImpl.java` (~linhas 270-355)

Ao deletar uma diary entry de nível EPISODE ou SEASON, a retração usa um **threshold**
(só remove entradas cujo `watchNumber` excede o que a evidência restante ainda suporta).
Mas para nível SERIES, `wipeSeriesHistory`/`computeSeriesWipeCandidates` busca **todas**
as entradas de episódio/temporada/série do usuário para aquela série inteira — sem filtro
por `watchNumber`. Apagar uma única entrada de nível SERIES (ex.: um registro duplicado de
rewatch) remove candidatos de exclusão de **todos** os ciclos de rewatch daquela série,
inclusive os não relacionados ao registro apagado, ao contrário dos caminhos irmãos.

## Média severidade

### 7. `GlobalExceptionHandler` sem handler genérico de fallback
**Arquivo:** `src/main/java/com/watchwise/watchwise_api/common/exception/GlobalExceptionHandler.java`

Não há `@ExceptionHandler(Exception.class)`. Qualquer exceção não mapeada (NPE,
`IllegalStateException`, erro de infraestrutura) cai no tratamento padrão do Spring Boot
(`BasicErrorController`), que devolve um corpo JSON em formato diferente do `ApiError`
usado no resto da API — quebra de contrato para o cliente nesses casos.

### 8. `User.isEmailVerified` sempre `true` — feature de verificação de email morta
**Arquivos:**
- `src/main/java/com/watchwise/watchwise_api/user/entity/User.java` (~linha 51)
- `src/main/java/com/watchwise/watchwise_api/user/service/impl/UserServiceImpl.java` (~linhas 58, 265-267)

`saveNewUser` força `isEmailVerified=true` na criação. Não existe nenhum fluxo (endpoint
de verificação, envio de token, etc.) que crie um usuário com `isEmailVerified=false`. O
check em `login()` (`if (!isEmailVerified) throw new ForbiddenException(...)`) é código
morto em produção — feature parcialmente implementada (schema + checagem) mas nunca
ativada.

### 9. `GoogleTokenVerifier` mascara falhas de rede como token inválido
**Arquivo:** `src/main/java/com/watchwise/watchwise_api/common/security/GoogleTokenVerifier.java` (~linhas 22-24)

`catch (GeneralSecurityException | IOException | IllegalArgumentException e)` converte
tudo — inclusive `IOException` por indisponibilidade da API do Google — na mesma mensagem
"Invalid or expired provider token", sem logar a exceção original. Mascara uma falha de
infraestrutura como erro do usuário, dificultando diagnóstico de outages do Google.

### 10. `isRewatch=true` no primeiro registro corrompe contadores
**Arquivo:** `src/main/java/com/watchwise/watchwise_api/diaryentry/service/impl/DiaryEntryServiceImpl.java` (~linha 179)

```java
int watchNumber = Math.max(maxWatchNumber + 1, Boolean.TRUE.equals(requestedIsRewatch) ? 2 : 1);
```

Se não há registro anterior (`maxWatchNumber == 0`) e o cliente envia `isRewatch=true`
(nada valida essa consistência no servidor), a entrada é criada com `watchNumber=2`, sem
nunca existir `watchNumber=1`. Isso não gera erro, mas infla o `MAX(watchNumber)` usado
pelos cálculos de conclusão de season/series (`maybeCompleteSeason`/`maybeCompleteSeries`),
causando contagem de ciclos incorreta silenciosamente. Afeta só o endpoint singular
`POST /diary` (o bulk sempre incrementa a partir do máximo real).

## Baixa severidade / informativo

### 11. Vazamento de metadados de `childList` com visibilidade desatualizada
**Arquivo:** `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemServiceImpl.java`

`assertListIsVisibleTo` só é chamada em `resolveChildList`, no momento da inserção. Ao ler
os itens de uma lista pai, a `childList` aninhada é sempre retornada como
`UserListPreviewDTO` (id, nome, dono, visibilidade) sem reverificar a visibilidade atual —
se o dono da lista filha mudar para PRIVATE depois, quem acessa a lista pai pública ainda
vê nome/dono/visibilidade da filha (não o conteúdo, que continua protegido).

### 12. `mapUniqueConstraintViolation` (userlist) com fallback genérico
**Arquivo:** `src/main/java/com/watchwise/watchwise_api/userlist/service/impl/UserListItemServiceImpl.java` (~linhas 345-358)

Só trata 3 nomes de constraint `uq_*`. Qualquer outra `DataIntegrityViolationException`
cai num fallback genérico "Unable to insert this item into the list" (409), mascarando a
causa raiz real com uma mensagem que sugere retry. Não é silencioso (ainda lança exceção),
mas dificulta diagnóstico.

### 13. Validações de entrada ausentes / clamps silenciosos
- `DiaryEntryBulkCreationDTO.finaleSeasonNumber`/mapa de episódios: só tem `@Min(1)`, sem
  `@Max` — limitado na prática pelo tamanho do payload e por `MAX_BULK_EPISODES`, mas sem
  validação explícita.
- `buildPageRequest` (duplicado em `CommentServiceImpl` e `DiaryEntryServiceImpl`): trata
  `pageSize > 1000` silenciosamente como se fosse `null` (usa default 20) em vez de
  rejeitar ou fazer clamp explícito em 1000.

### 14. Ausência de lock pessimista em reordenações concorrentes
**Arquivo:** `UserListItemServiceImpl` (`performMove`/`insertAtPosition`)

Não há lock pessimista nem `@Version` — a segurança depende inteiramente da constraint
única do banco + conversão para `ConflictException`. Não é falha silenciosa (a exceção é
sempre propagada), mas duas reordenações concorrentes na mesma lista podem gerar
"conflitos" falsos ou resultado final que não reflete a intenção de nenhum dos dois
clientes.
</content>
