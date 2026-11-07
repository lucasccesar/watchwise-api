# Watchlist global, datas de lançamento e leitura otimizada — Plano de implementação

> **Para execução:** usar TDD; cada tarefa deve começar por testes que falham, seguir com a implementação mínima e terminar com verificação direcionada.

## Objetivo

Implementar a leitura unificada da watchlist com `type` opcional, posições globais entre filmes e séries, contagem de séries ainda não estreadas e datas de lançamento contextualizadas ao usuário. Materializar as datas em snapshots persistidos para que o GET normal não dependa do TMDB.

## Tarefas

### 1. Atualizar o contrato e os testes do endpoint

- Alterar o GET para `/users/{userId}/watchlist`, com `type` opcional.
- Criar `WatchlistPageResponseDTO` com paginação, `upcomingCount` e `releaseDate` nas entradas.
- Testar leitura sem filtro, filtro mantendo as posições globais, visibilidade, binding de enum e contagem usando a data do dia.
- Manter PATCH/DELETE com `type` na rota e validar o tipo da entrada.

### 2. Migrar a posição para o escopo global

- Criar migration reindexando dados antigos por `created_at ASC, id ASC`.
- Substituir a constraint `(user_id, type, position)` por `(user_id, position)`.
- Alterar insert, move, remove e remoções automáticas para deslocar os dois tipos.
- Usar `AdvisoryLock` por usuário em todas as mutações e cobrir concorrência/invariantes com testes.

### 3. Criar snapshots persistidos de datas

- Criar migration e entidade/repositório para `content_release_date_snapshots`.
- Usar chave `(tmdbId, region)` para MOVIE e `tmdbId` global para SERIES.
- Persistir status, data, `lastCheckedAt` e `nextCheckAt`, com índices para lookup e refresh.
- Reutilizar `CalendarMovieReleaseDateSelector`; solicitar `release_dates` no detalhe completo de filme.

### 4. Implementar resolução e atualização dos snapshots

- Fazer write-through no POST da watchlist.
- No GET, carregar a página e os snapshots em lote; snapshot existente vencido pode ser servido e atualizado de forma assíncrona.
- Resolver snapshot ausente de forma síncrona com limite de concorrência; persistir o resultado antes de responder.
- Criar job periódico para refresh de snapshots vencidos e testes para datas ausentes, inválidas, futuras e de hoje.
- Falha no preenchimento de snapshot ausente deve usar o contrato `ApiError`/`502`; falha de refresh mantém o valor anterior.

### 5. Sincronizar documentação

- Atualizar `docs/context/openapi.yaml`, `docs/context/business-rules.md` e `docs/context/progress.md` somente com comportamento implementado.
- Preservar alterações preexistentes e deixar documentação sem commit, conforme `AGENTS.md`.

## Verificação

- Rodar testes unitários dos módulos de watchlist, snapshot e TMDB.
- Rodar testes de controller e repository relacionados.
- Rodar `./mvnw.cmd test` e `git diff --check` antes de afirmar conclusão.
