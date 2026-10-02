# Filtros de série e nota no diary

## Objetivo

Adicionar ao diary:

- filtragem de entries por uma série específica;
- filtragem de entries por uma nota exata;
- uma fonte para o dropdown de séries assistidas, com título e quantidade de entries.

A contagem de uma série inclui todas as `DiaryEntry` relacionadas a ela: entries de
`SERIES`, `SEASON` e `EPISODE`. Cada rewatch permanece uma entry independente na contagem.

## Contrato HTTP

### Filtros no diary

O endpoint existente continua sendo usado:

```http
GET /users/{userId}/diary
```

Novos parâmetros opcionais:

- `seriesTmdbId`: identifica a série a ser filtrada;
- `score`: nota exata, com valor entre 1 e 10.

O filtro `seriesTmdbId` normaliza o conteúdo assim:

- para `ContentType.SERIES`, compara `contents.tmdb_id`;
- para `ContentType.SEASON` e `ContentType.EPISODE`, compara `contents.series_tmdb_id`;
- conteúdos `MOVIE` não pertencem a nenhuma série.

O filtro `score=10` retorna apenas entries com nota 10. Entries sem nota não são incluídas
quando o parâmetro está presente.

Os filtros novos podem ser combinados com `year`, `type`, `dateFrom`, `dateTo`, `hasReview`,
`page` e `size`. O comportamento atual de `year` combinado com `dateFrom` ou `dateTo`
continua retornando `400`. A filtragem ocorre antes da paginação.

### Opções do dropdown

Será criado:

```http
GET /users/{userId}/diary/series
```

Resposta:

```json
[
  {
    "seriesTmdbId": "1399",
    "title": "Game of Thrones",
    "entriesCount": 235
  }
]
```

O endpoint retorna todas as séries presentes no diary do usuário, sem aplicar filtros de
data, ano, tipo, review ou nota. Os itens são ordenados por `entriesCount DESC`, depois por
`title ASC` e, por fim, por `seriesTmdbId ASC` para manter uma ordem determinística.

O endpoint não é paginado porque seu propósito é fornecer todas as opções do dropdown.

## Modelo e persistência

Não haverá migration nem nova entidade. Os dados serão agregados a partir de `diary_entries`
e `contents`.

O repository terá uma projeção específica para a lista de séries, com `seriesTmdbId` e
`entriesCount`. A consulta agregará o identificador normalizado com um `CASE`, agrupará por
série e contará `diary_entries.id`.

A consulta paginada do diary receberá os parâmetros `seriesTmdbId` e `score`. O caminho sem
filtros continuará usando a consulta existente sem filtros extras; o service passará a
considerar os dois parâmetros novos ao decidir entre a consulta simples e a consulta
filtrada.

## Resolução de títulos

O service validará a visibilidade do diary antes de consultar o agregado, usando a mesma
regra de `assertCanViewDiary` já aplicada ao endpoint existente.

Depois da consulta agregada, cada série distinta terá seu título resolvido pelo
`TmdbClient.getTvFullDetails`, usando o idioma preferido do usuário-alvo. O cache do
`TmdbClient` será reutilizado e as requisições serão feitas sequencialmente para evitar um
pico de chamadas externas.

Quando o TMDB retornar `NotFound`, o item será mantido com `title: null`, preservando a
contagem local. Quando o TMDB estiver indisponível, o service lançará
`TmdbUnavailableException`, que será convertido pelo handler existente em `502`.

O ID da série continuará sendo a chave funcional do filtro, mesmo quando o título remoto não
estiver disponível.

## Validação e erros

- `score` fora do intervalo de 1 a 10 retorna `400` em `ApiError`.
- `seriesTmdbId` vazio retorna `400`.
- Um `seriesTmdbId` válido sem entries do usuário retorna resultado vazio, não `404`.
- `seriesTmdbId` combinado com `type=MOVIE` retorna resultado vazio, pois filmes não são
  associados a séries.
- Usuário inexistente continua retornando `404`.
- Diary de perfil privado sem autorização continua retornando `403`.
- Falha transitória do TMDB no endpoint de opções continua retornando `502` no formato
  padronizado do projeto.

## Testes

### Repository

Testes PostgreSQL/Testcontainers cobrirão:

- agrupamento conjunto de entries `SERIES`, `SEASON` e `EPISODE`;
- contagem de rewatches;
- ordenação por quantidade e desempates;
- filtro exato por nota;
- filtro por `seriesTmdbId`;
- combinação do filtro de série com os filtros existentes.

### Service

Testes unitários cobrirão:

- autorização antes da leitura;
- montagem dos DTOs de opções;
- uso do idioma preferido do usuário-alvo;
- item com título `null` quando o TMDB não encontra a série;
- conversão de indisponibilidade do TMDB em `TmdbUnavailableException`;
- validação de `score` e `seriesTmdbId`;
- preservação da paginação e dos filtros existentes.

### Controller e integração

Testes MockMvc e de integração cobrirão:

- recebimento dos novos query params;
- resposta do endpoint `/users/{userId}/diary/series`;
- respostas `400`, `403`, `404` e `502`;
- acesso não autenticado;
- compatibilidade do endpoint do diary sem os novos parâmetros.

## Documentação sincronizada

A implementação atualizará no mesmo change:

- `docs/context/openapi.yaml` com os novos parâmetros, schema e endpoint;
- `docs/context/business-rules.md` com a normalização e a regra de contagem;
- `docs/context/progress.md` com o que foi efetivamente implementado.

## Revisão da especificação

- Não há placeholders ou decisões pendentes.
- O contrato preserva o endpoint atual e adiciona somente os dados necessários ao dropdown.
- A agregação usa o modelo existente e não armazena títulos de mídia localmente.
- A regra de visibilidade é aplicada antes de expor contagens ou títulos.
- A filtragem ocorre no banco antes da paginação.
- Os casos de TMDB indisponível, TMDB sem resultado, notas inválidas e combinação com filme
  estão definidos.
