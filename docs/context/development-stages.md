# Watchwise

Sequência recomendada de desenvolvimento — Entity → Repository → Service → Controller

Ordem de implementação baseada nas dependências de chave estrangeira do modelo lógico e nos
endpoints do OpenAPI da aplicação. A lógica geral: primeiro as entidades "raiz" sem dependências,
depois quem depende só delas, e por último quem depende de várias outras entidades (Comentario,
Curtida) ou agrega tudo (Busca, Resumo).

**Atualizado em 2026-09-01**: todas as Fases 1-7 e a maior parte da Fase 8 estão concluídas — ver
resumo no final da página. O que resta do roteiro original, mais o que falta pra ir pra produção,
está listado abaixo, na ordem recomendada de execução.

---

## Pendente — última peça do roteiro original

### Fase 8 (a concluir) — Busca

`Search` é uma agregação de leitura: não cria entidade, migration, `Content` nem persiste resultados
do TMDB. `GET /search` reúne conteúdos e pessoas retornados ao vivo pelo TMDB com usuários e listas
do banco local. O array `contents` mistura filmes e séries; `people`, `lists` e `users` continuam
arrays separados. Status em 2026-09-07: os itens 1-3 abaixo já foram preparados; a implementação restante
deve seguir esta ordem:

1. **[preparado] Atualizar o contrato OpenAPI antes do código.** Manter `GET /search` autenticado e o filtro
   opcional `type` (`MOVIE`, `SERIES`, `PERSON`, `LIST`, `USER`). Exigir `q` não vazio, depois de
   trim, com no mínimo 3 caracteres. Adicionar `page` e `size`; ambos são 1-based para o cliente e
   `size` tem máximo de 20. Modelar a paginação sem um total global fictício: a página TMDB cobre os
   resultados externos e as páginas locais cobrem listas e usuários, pois cada fonte tem seu próprio
   total.
2. **[preparado] Definir os DTOs de Search.** Criar DTOs de resultado para conteúdo TMDB (filme ou série), pessoa
   TMDB e a resposta agregada. Para listas, criar `SearchUserListDTO` em vez de reutilizar
   `UserListResponseDTO` ou `UserListPreviewDTO`: ele deve conter `id`, `user` (`UserPreviewDTO`),
   `name`, `previewItems` (até os 5 primeiros `ContentRefDTO`, por `position`) e
   `nestedListsCount`. O dono é necessário porque Search reúne listas de vários usuários; os cinco
   previews são necessários para o card de lista. Atualizar `SearchResult` para usar esse DTO.
3. **[preparado] Preparar as consultas locais.** Reaproveitar a busca prefixada e case-insensitive de usuário
   existente, incluindo perfis privados apenas no formato seguro de `UserPreviewDTO`. Adicionar a
   consulta de listas por nome com escape dos curingas de `LIKE`, paginação e filtros de visibilidade:
   o dono vê as próprias listas, qualquer viewer vê `PUBLIC`, e `FOLLOWERS` só aparece para quem
   segue o dono com status aceito; `PRIVATE` não aparece para terceiros. Reaproveitar
   `UserListItemService.getPreviewItemsByListIds(...)` para buscar em lote os cinco previews, sem
   consulta por lista.
4. **Estender o proxy TMDB.** Adicionar os modelos de resposta e métodos de busca de filme, série,
   pessoa e busca múltipla. Sem `type`, usar a busca múltipla e separar pessoas de `contents`,
   preservando filmes e séries misturados e a ordenação do TMDB. Com `type`, chamar somente o
   endpoint TMDB necessário; `LIST` e `USER` não fazem chamada externa. Enviar idioma preferido do
   viewer, `page` e `include_adult=false`. Criar cache Caffeine específico de Search, de TTL
   curto e chaveado por consulta normalizada, tipo, idioma e página; jamais persistir o retorno.
5. **Implementar `SearchService` e `SearchServiceImpl`.** Orquestrar TMDB, `UserRepository`,
   `UserListRepository`, `UserListItemService`, `FollowerRepository` e os mappers/DTOs de Search.
   Respeitar o limite de 20 tanto na página enviada ao TMDB quanto nas `PageRequest` locais; ampliar
   `PageRequestFactory` de forma reutilizável se ele precisar aceitar um teto específico por chamada,
   sem criar validação de paginação dentro de Search. Uma indisponibilidade do TMDB deve resultar em
   `TmdbUnavailableException` e resposta `502`, sem resposta parcial ou cache vencido. Uma busca só
   local não depende do TMDB e continua funcionando quando ele estiver indisponível.
6. **Cobrir o serviço com testes.** Testar cada `type`, a busca múltipla com filmes e séries no mesmo
   array, mapeamento dos cards, paginação e limite de 20, consulta mínima de três caracteres, escape
   de `%` e `_`, listas públicas/seguidores/privadas, previews em lote e resultados vazios. Cobrir
   também a ausência de chamada TMDB para `LIST`/`USER` e o `502` para falha TMDB nos tipos externos.
7. **Implementar `SearchController` em `/search` e os testes de controller.** Validar os parâmetros,
   obter o viewer autenticado e converter todos os erros para `ApiError`/`ValidationApiError` pelo
   `GlobalExceptionHandler`. Testar `200`, `400` para termo/tipo/paginação inválidos, `401` sem sessão
   e `502` para indisponibilidade TMDB, confirmando que nenhum erro usa o corpo padrão do Spring.
8. **Fechar a documentação e a verificação.** Ajustar `openapi.yaml`, atualizar
   `business-rules.md` com visibilidade das listas e semântica da busca somente após elas existirem no
   código, e registrar a entrega em `progress.md`. Rodar testes unitários, testes de controller e a
   suíte Maven completa antes de considerar a fase concluída.

*Como as demais agregações da Fase 8, Search é leitura pura sobre dados já existentes. A diferença é
o proxy TMDB: ele deve seguir o padrão de retry, cache e falha explícita (`TmdbUnavailableException`
→ `502`) de `ContentDetailsServiceImpl`, sem deixar uma falha externa vazar como resposta padrão.*

---

## Pré-deploy — na ordem recomendada de execução

Detalhe completo de cada item em `docs/pending/pending-to-deploy.md` (arquivo separado, atualizado
independente deste). Ordem pensada por dependência técnica: primeiro fechar a última peça de feature
do roteiro original, depois preparar a config real, depois o que depende dela, depois revisar
segurança do conjunto, e só por último empacotar pra deploy de verdade.

| # | Item | Por quê nessa posição |
|---|---|---|
| 1 | **Implementar `/search`** (ver Fase 8 acima) | Termina o roteiro de features antes de entrar em trabalho de infraestrutura. |
| 2 | **`application-prod.properties` virar config real** (secrets via variável de ambiente/secret manager — nunca hardcoded) — no mesmo arquivo, aproveitar pra descomentar `spring.jpa.open-in-view=false`/`show-sql=false`/`format_sql=false` | Base pra tudo que segue — Actuator, security review e Dockerfile/CI todos assumem que esse profile existe de verdade. |
| 3 | **Adicionar Spring Boot Actuator (health-check)** | Precisa existir antes do pipeline de CI/CD, que vai depender dele pra validar deploy. |
| 4 | **Restringir domínio de `profilePicture`** (allowlist ou blocklist de host) | Item de segurança pequeno e já bem definido — resolver antes da revisão de segurança geral, não depois. |
| 5 | **Rodar `/code-review security-review`** | Revisa o conjunto (config de prod, Actuator exposto, restrição de domínio) de uma vez, depois que as peças acima já existem — rodar antes seria revisar um estado incompleto. |
| 6 | **Dockerfile + pipeline de CI/CD** | Último passo — empacota e automatiza o deploy de tudo que já foi preparado e revisado acima. |

---

## Resumo do que já foi implementado (Fases 1-7 + maior parte da 8)

**Fase 1 — Fundação:** `Usuario` e `Conteudo` completos (entity → repository → service → controller
→ testes).

**Fase 1.5 — Auth hardening:** completo, exceto o envio real de e-mail de verificação (schema/checagem
existem, mas o fluxo de envio/token/confirmação nunca foi construído — item opcional, roteiro
completo em `docs/pending/to-fix.md` item 1, não bloqueia nada). Feito: `/auth/oauth/{provider}`
(Google), e-mail tirado do payload do JWT, detecção de reuso de refresh token (revoga todas as
sessões), `POST /auth/logout-all`, rate limiting em login/registro/oauth/refresh (e em várias outras
rotas mutáveis, não só auth), limpeza agendada de refresh tokens expirados (`RefreshTokenCleanupJob`).

**Fase 2 — Dependem só de Usuario:** `Seguidor`/`Follower` e `SeguePessoa`/`FollowedPerson`
completos.

**Fase 3 — Dependem de Usuario + Conteudo:** `Top5`/`Top5Entry`, `Watchlist`/`WatchlistEntry`,
`Dropped`/`DroppedEntry` e `Log`/`DiaryEntry` completos, incluindo a cascata de remoção automática de
watchlist/dropped ao logar. Autorização por dono do recurso padronizada aqui e reaproveitada em todas
as fases seguintes.

**Fase 4 — Dependem de Usuario (+ Conteudo via itens):** `Lista`/`UserList` e `ItemLista`/
`UserListItem` completos, incluindo listas aninhadas (profundidade máxima 1 nível, sem ciclo, sem
comentário/curtida em lista-de-listas) e a trava de grupo de tipo de conteúdo (filme/série, temporada
e episódio não se misturam na mesma lista, adicionada em 2026-09-01).

**Fase 5 — Depende de Usuario + Conteudo + Lista + Log:** `Comentario`/`Comment` completo (alvo único
entre Conteudo/Lista/Log, resposta a comentário, cascata de exclusão, limite de 280 caracteres).

**Fase 6 — Depende de Usuario + Comentario + Log:** `Curtida`/`Like` completo (comentário e diário),
com `likesCount` denormalizado.

**Fase 7 — Satélite:** `Notificacao`/`Notification` completo, incluindo a primeira integração real do
backend com a API do TMDB (`TmdbClient`, jobs agendados `ContentTrackingJob`/
`FollowedPersonTrackingJob`).

**Fase 8 — Agregações (concluído, exceto Busca):** `Resumo`/`Summary` (incluindo Month/Year in
Review, All Time Stats, grade de notas por episódio, resumo da Home) e as agregações que surgiram de
levantamentos de gaps contra `docs/context/telas.md` ao longo do caminho — `series-in-progress`,
`liked-lists`, estatísticas/reviews por `Content`, e `GET /feed` (paginação por cursor). Só falta
**Busca**, listada acima.

Também construído fora do roteiro original, a pedido do usuário ao longo do caminho: proxy de
detalhe do TMDB (`GET /contents/{contentId}/details`, incluindo budget/revenue/production
companies/crew/videos), "Assistido com" (`WatchCompanion`), poster customizado em diário/Top5/listas,
filtro de posts mecânicos no feed (`DiaryEntry.ignore`).

---

## Por que essa ordem otimiza seu tempo

- Você nunca implementa um repository/service que referencia uma FK para uma entidade que ainda não existe (evita mocks provisórios e retrabalho).
- O módulo Auth saiu "de graça" junto com Usuario, já que os endpoints de login/registro só manipulam essa entidade — e a Fase 1.5 garantiu que ele ficasse fechado antes de virar dependência silenciosa do resto.
- Autorização por dono do recurso nasceu na Fase 3 (primeiro recurso "de alguém" a proteger) e se repetiu como padrão já decidido nas fases seguintes, em vez de ser reinventada a cada entidade nova.
- Busca ficou por último porque é leitura pura sobre dado que só existe depois que tudo mais estivesse populado — testá-la antes seria testar contra um banco vazio.
- A ordem de pré-deploy segue a mesma lógica: nunca revisar segurança ou empacotar pra produção antes de a config e a superfície exposta (Actuator, domínio de imagem) já existirem de verdade.
