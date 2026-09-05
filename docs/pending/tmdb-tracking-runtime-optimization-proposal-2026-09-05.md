# Proposta: otimizar tracking diário e cálculo de runtime de série — 2026-09-05

Continuação da investigação de `docs/pending/tmdb-request-audit-2026-09-04.md` (ponto 3).

**Status: implementado em 2026-09-05** (3 commits — ver `docs/context/progress.md`, entrada do mesmo
dia). Divergências entre o que foi implementado e o que este documento propôs originalmente, decididas
durante o desenvolvimento:

- **`averageRuntimeMinutes` não existe como coluna** — só `totalRuntimeMinutes` +
  `runtimeMinutesEpisodeCount` são armazenados; a média é sempre `round(total/count)` calculada na
  leitura, pra não ter dois valores que podem divergir entre si.
- **`NotificationType.PREMIERED` não foi criado** — a estreia de série reaproveita `RELEASE` (mesmo
  significado semântico: "saiu"), e a data de temporada anunciada reaproveita `ANNOUNCED_DATE` (agora
  com `seasonNumber` preenchido no evento). Zero mudança em `openapi.yaml`.
- **Refinamento não previsto originalmente**: quando a série é terminal e já tem baseline,
  `ContentDetailsServiceImpl` busca só as 2 temporadas de maior número (em vez de nenhuma) pra manter
  `recentEpisodes`/`seasons[].airedEpisodeCount` funcionando mesmo pra série encerrada.
- **Fora de escopo, não implementado**: persistir um snapshot de `recentEpisodes` pra série terminal
  (eliminaria também essas 2 chamadas restantes) — ficaria mais próximo do que o "Avoid" do `CLAUDE.md`
  realmente restringe (nome/overview/still de episódio). Registrado como item futuro, não decidido.

Detalhe completo da implementação em `docs/context/business-rules.md` § Content e § Notification.

---

Texto original da proposta abaixo, mantido como registro da discussão.

---

## Problema 1 — `GET /contents/{id}/details` de série busca todas as temporadas

`ContentDetailsServiceImpl.buildSeriesDetails` → `fetchAllSeasonsInParallel` busca `getSeasonFullDetails`
de **todas** as temporadas só pra calcular `totalRuntimeMinutes`/`averageRuntime` e os 3 episódios mais
recentes (`recentlyAiredEpisodes`). Série longa = dezenas de chamadas TMDB no cache frio (a cada 24h,
por idioma). Detalhe completo no audit de 2026-09-04, ponto 3.

## Problema 2 — `ContentTrackingJob` roda diariamente mesmo pra conteúdo que nunca mais muda

`ContentTrackingServiceImpl.trackContentChanges` rastreia todo `Content` em watchlist + toda `SERIES` em
progresso no diário, sem filtrar por status. Um filme já `Released`, ou uma série já `Ended`/`Canceled`,
continua sendo checado todo dia mesmo sabendo que não vai lançar nada novo — só o revival raro
(`Ended`/`Canceled` → `Returning Series`) é uma exceção genuína.

---

## Proposta combinada

### Novos campos em `Content` (só `SERIES`)

- `totalRuntimeMinutes`, `averageRuntimeMinutes` — nullable, mesma filosofia dos campos já existentes
  (`genres`/`releaseYear`/`countries`/`runtimeMinutes` de `MOVIE`/`EPISODE`).
- Um jeito de saber se o valor está "congelado" (definitivo) ou ainda em manutenção — pode reaproveitar
  `TrackedContentState.lastKnownStatus`, sem precisar de coluna nova.

### Tratamento por status de série

| Status | Comportamento |
|---|---|
| `Ended` / `Canceled` | **Congela** `totalRuntimeMinutes`/`averageRuntimeMinutes` definitivamente (igual ao `runtimeMinutes` de `MOVIE` hoje). **Sai do job diário** — não faz mais sentido checar TMDB todo dia pra algo que não muda. Só é "descongelado" se um revival for detectado (ver abaixo). |
| `Returning Series` | Fica no job diário. Quando o job detectar `NEW_EPISODE` (já sabe `seasonNumber`+`episodeNumber` exatos), busca só aquele episódio (`getEpisodeFullDetails`, 1 chamada cacheada) e **soma** o runtime dele ao total já armazenado, em vez de recalcular tudo. Depois do baseline inicial, nunca mais precisa buscar todas as temporadas de novo. |
| `Planned` / `In Production` / `Pilot` | Sem (ou quase nenhum) episódio exibido ainda — custo de computar é baixo. Continua no job normalmente, esperando a transição pra `Returning Series` (ver notificação nova abaixo) ou `Canceled` (produção cancelada antes de estrear). |

### Tratamento por status de filme (mesmo raciocínio, mais simples)

| Status | Comportamento |
|---|---|
| `Released` / `Canceled` | Estado terminal — sai do job diário assim que atingido. Filme já lançado (ou cancelado) não muda mais de status. |
| `Rumored` / `Planned` / `In Production` / `Post Production` | Continua no job normalmente, esperando `Released` ou `Canceled`. |

### Revival lazy (série `Ended`/`Canceled` que volta a ser produzida)

Detectado fora do job diário, de forma lazy: quando `ContentDetailsServiceImpl` buscar
`getTvFullDetails` por outro motivo (alguém pediu os detalhes daquele título) e perceber que o status
mudou de `Ended`/`Canceled` pra `Returning Series`, dispara `RENEWED` ali mesmo e reativa o rastreio
diário (descongela).

**Trade-off assumido:** troca a garantia de "aviso proativo mesmo sem ninguém olhar" por "aviso só
quando alguém visitar os detalhes de novo" — só pra esse caso específico de revival. Considerado
aceitável por ser um evento raro, mas é uma perda de garantia real, não só ganho de graça.

### Nova notificação: estreia de série (`PREMIERED`)

Gap identificado à parte: hoje não existe uma notificação de "sua série estreou" — só existe de forma
incidental via `NEW_EPISODE`, condicionada a já ter uma data de estreia conhecida numa rodada anterior
do job (se `previous == null`, `detectTvChange` retorna vazio e a estreia passa batido). Proposta: um
`NotificationType.PREMIERED` novo, disparado explicitamente na transição
`Planned`/`In Production`/`Pilot` → `Returning Series`, junto com o baseline inicial de runtime.

### Gap adicional: `ANNOUNCED_DATE` não existe para série

`detectTvChange` só tem `CANCELLED`, `RENEWED` e `NEW_EPISODE` — não existe o equivalente ao
`ANNOUNCED_DATE` de filme (avisar assim que uma data de estreia é divulgada, antes dela chegar).

**Solução:** o campo `seasons[]` do próprio `/tv/{series_id}` (resposta padrão, sem custo extra de
chamada) já traz `air_date` por temporada — inclusive temporadas ainda não estreadas, com `air_date:
null` até a data ser anunciada (ex.: `{"season_number": 3, "air_date": null, "episode_count": 0, ...}`).
Esse campo fica conhecido **antes** de `next_episode_to_air` (que só é preenchido quando o TMDB já tem
dado no nível de episódio, não só de temporada) — é o sinal mais cedo disponível de que uma nova
temporada foi anunciada.

Implementação:
1. Adicionar `seasons` (reaproveitando o record `TmdbSeasonSummary`, que já existe e já é usado por
   `TmdbTvFullDetails`) em `TmdbTvDetails` — mesmo endpoint já chamado pelo job, zero chamadas TMDB
   extras.
2. Guardar em `TrackedContentState` a temporada de maior número conhecida e sua `air_date` (dois campos
   novos, mesmo padrão dos já existentes `nextEpisodeSeasonNumber`/`nextEpisodeAirDate`).
3. A cada rodada do job, comparar a temporada de maior número: se a `air_date` dela passou de `null` pra
   uma data futura, dispara um evento (`ANNOUNCED_DATE` reaproveitado ou um `NotificationType` próprio
   pra série) — mesma lógica exata do `ANNOUNCED_DATE` de filme, só que por temporada em vez de pelo
   título inteiro.

### Fallback — quando ainda é necessário buscar todas as temporadas

1. **Primeira vez** que qualquer série é referenciada — precisa de baseline, não tem atalho.
2. **`Returning Series` fora do escopo do job** (ninguém tem em watchlist/progresso) — quem pedir
   detalhes cai no fluxo atual (busca tudo), sem incremento automático disponível.
3. **Reconciliação no momento de congelar**: o incremento via `NEW_EPISODE` só soma episódios novos, não
   corrige se o TMDB editar retroativamente o runtime de um episódio antigo. Pra evitar que o valor
   congelado carregue esse drift pra sempre, fazer **uma última busca completa de todas as temporadas
   exatamente no instante em que o status vira `Ended`/`Canceled`**, garantindo que o valor congelado é
   autoritativo — só nesse momento, não recorrente.

---

## Por que isso não foi implementado ainda

Diverge do `CLAUDE.md` atual em dois pontos que exigem confirmação explícita antes de codar (fluxo
anunciar → perguntar → implementar):

- **Avoid**: hoje documentado que `SEASON`/`SERIES` "não tem duração própria" — este documento propõe
  guardar `totalRuntimeMinutes`/`averageRuntimeMinutes` em `Content` pra `SERIES`, com manutenção
  incremental via job (diferente dos campos write-once já existentes).
- **Novo `NotificationType`** (`PREMIERED`) e nova responsabilidade pro job diário (deixar de rastrear
  automaticamente conteúdo terminal) — mudança de comportamento observável do sistema de notificação.

Se aprovado, precisa de: migração de schema (`Content`), atualização de `openapi.yaml` (se o campo for
exposto em `ContentDetailsDTO`), atualização de `database-schema.md`, `business-rules.md` e
`progress.md` junto com o código, conforme convenção do projeto.
