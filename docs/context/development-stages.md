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

| Serviço | Sequência |
|---|---|
| **Busca** | service (agrega Lista, Usuario local + proxy TMDB) → service test → controller (`/search`) → controller test |

*Mesmo espírito das demais agregações da Fase 8 (Resumo, já concluído) — service de leitura pura
sobre dado que já existe, sem entidade nova. Diferente das outras, envolve uma chamada externa real
ao TMDB (proxy) — reaproveitar o mesmo cuidado de cache/erro (`TmdbUnavailableException` → 502) já
usado em `ContentDetailsServiceImpl`.*

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
