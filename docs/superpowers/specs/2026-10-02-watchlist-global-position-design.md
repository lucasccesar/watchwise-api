# Watchlist global e datas regionais — Design

## Objetivo

Alterar a leitura da Watchlist para aceitar `type` opcional, tornar `position` global
entre filmes e séries e retornar a data contextual de lançamento junto com a quantidade
de itens que ainda não foram lançados.

## Contrato da API

O endpoint de leitura será:

```http
GET /users/{userId}/watchlist
GET /users/{userId}/watchlist?type=MOVIE
GET /users/{userId}/watchlist?type=SERIES
```

Sem `type`, a resposta inclui os dois tipos. Com `type`, a consulta filtra o tipo sem
renumerar as entradas: `position` continua mostrando a posição global original e pode
aparecer com lacunas.

A resposta manterá os metadados atuais de paginação e acrescentará `upcomingCount`.
Cada entrada também terá `releaseDate`, que será nullable e contextual ao usuário dono
da watchlist:

- `MOVIE`: data selecionada em `release_dates` do TMDB usando o `preferredRegion` do dono;
- `SERIES`: `first_air_date` do TMDB;
- data ausente ou inválida: `null`, não contabilizada em `upcomingCount`.

`upcomingCount` será global para o filtro escolhido e considerará somente datas posteriores
ao dia atual. Uma série já estreada não será contabilizada mesmo que tenha episódios futuros.

## Posição global

As posições serão únicas por `(user_id, position)`, independentemente de `type`.

- Inserção acrescenta na última posição global.
- Reordenação desloca entradas dos dois tipos quando necessário.
- Remoção fecha o intervalo global.
- Remoções automáticas chamadas por Diário e Dropped reutilizam o mesmo fechamento global.
- PATCH e DELETE continuarão recebendo `type` na rota para validar que a entrada pertence ao
  tipo informado.

A migration reindexará entradas antigas por `created_at ASC, id ASC`, pois o schema atual
não registra a ordem histórica entre as duas listas. A constraint antiga por tipo será
substituída pela constraint global e serão ajustados os índices de leitura.

As operações mutáveis usarão bloqueio transacional por usuário para impedir que inserções,
movimentações e remoções concorrentes criem posições duplicadas ou lacunas.

## Resolução de datas

As datas serão materializadas em `content_release_date_snapshots`, uma tabela de leitura
persistida e compartilhada entre instâncias. Filmes usam uma linha por `(tmdbId, region)`;
séries usam uma linha global por `tmdbId`. A tabela guarda a data, o status da última busca,
`lastCheckedAt` e `nextCheckAt`.

`TmdbClient.getMovieFullDetails` passará a solicitar `append_to_response=release_dates` e
`TmdbMovieFullDetails` passará a desserializar esse bloco. A seleção regional reutilizará
`CalendarMovieReleaseDateSelector`. A mesma resposta cacheada do TMDB alimentará o snapshot
de filme; a série usará `first_air_date`.

O POST da Watchlist fará write-through do snapshot (gravação imediata junto com a inclusão),
e um job assíncrono atualizará snapshots vencidos. Depois do aquecimento, o GET consultará
somente a Watchlist e os snapshots. Um snapshot ausente poderá ser resolvido de forma
síncrona, com concorrência limitada, e persistido antes da resposta; para manter `upcomingCount`
global e exato, todas as séries do filtro serão incluídas nessa resolução, enquanto filmes fora
da página não serão buscados. Snapshot vencido já existente poderá ser servido enquanto uma
atualização assíncrona é disparada. Essa tarefa reivindica o snapshot por lease condicional no
banco, aplica backoff de 60 minutos em falhas e a resolução síncrona usa no máximo quatro chamadas
simultâneas ao TMDB.

A resposta da Watchlist carregará a data resolvida no DTO da entrada, e não em `Content`,
porque uma mesma referência de filme pode possuir datas diferentes por região. Conteúdo
sem data válida no TMDB não é erro e permanece fora da contagem. Falha do TMDB durante o
preenchimento de um snapshot ausente retorna `502`; falha durante refresh de snapshot já
existente preserva o último valor conhecido e será tentada novamente.

## Segurança e visibilidade

A regra atual de visibilidade de perfil será preservada e executada antes da resolução de
datas. A região usada será sempre a do `userId` consultado, nunca a preferência do viewer.

## Testes

Serão cobertos:

- GET sem `type` retornando os dois tipos em ordem global;
- GET com `type` filtrando sem renumerar posições;
- `upcomingCount` usando data futura, data de hoje, data ausente e série já estreada;
- seleção de data de filme pela região do dono;
- inserção global entre tipos;
- remoção e movimentação atravessando tipos;
- migração/constraint global e concorrência das operações mutáveis;
- binding de `type` opcional e erros de TMDB no formato `ApiError`.

## Documentação sincronizada

O contrato será atualizado em `docs/context/openapi.yaml`, e as regras efetivamente
implementadas serão registradas em `docs/context/business-rules.md` e
`docs/context/progress.md`.
