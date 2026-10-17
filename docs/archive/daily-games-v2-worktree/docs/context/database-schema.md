# Watchwise — modelo lógico do banco de dados

Este documento acompanha o schema vigente nas migrations do projeto. Os nomes das entidades abaixo
seguem a convenção usada no código Java; os nomes entre parênteses preservam a nomenclatura do modelo
lógico original. O diagrama visual equivalente está em [`database-schema.html`](database-schema.html).

## Regras gerais

- `Content` (`CONTEUDO`) é uma referência leve ao TMDB. Filmes e séries usam `tmdb_id`; temporadas e
  episódios usam `series_tmdb_id` + `season_number` e, para episódios, `episode_number`.
- `DiaryEntry` (`LOG`) concentra registro, nota e review; não existe tabela `Rating`.
- `Comment` (`COMENTARIO`) aponta para exatamente um alvo entre `Content`, `UserList`, `DiaryEntry`, `DroppedEntry`, `Pick` e `PicksTemplate`.
- `Like` (`CURTIDA`) aponta para exatamente um alvo social permitido e tem unicidade por usuário e alvo.
- `UserList` (`LISTA`) é lista customizada; `WatchlistEntry` (`WATCHLIST`) é a fila por tipo para assistir
  depois.
- Roles globais persistidas em `User.role` são `USER` e `ADMIN`, com default `USER`.
- `UserContentPoster` é o único armazenamento canônico de pôster customizado: um override por par
  `User` + `Content`, independente das entradas de diário, Top5 e listas que possam ter originado a escrita.

## Entidades e tabelas

| Tabela | Chave e conteúdo principal |
| --- | --- |
| `users` (`USUARIO`) | `id`; identidade, credenciais, perfil, preferências, `role`, timestamps. |
| `contents` (`CONTEUDO`) | `id`; `type`, referência TMDB, flags de finale, gêneros, países e agregados de runtime. |
| `user_content_posters` | Override de pôster por usuário + conteúdo; unicidade em `(user_id, content_id)`. |
| `user_content_poster_conflict_audit` | Evidência de candidatos legacy válidos descartados na migração, incluindo sua fonte/timestamps e a fonte/timestamps do vencedor. |
| `followers` (`SEGUIDOR`) | Chave composta de seguidor/seguido, status e `created_at`. |
| `followed_people` (`SEGUE_PESSOA`) | Usuário + `person_tmdb_id`, com unicidade por usuário/pessoa. |
| `top5_entries` (`TOP5`) | Usuário, conteúdo, tipo e posição; unicidade por tipo/posição e tipo/conteúdo. |
| `watchlist_entries` (`WATCHLIST`) | Usuário, conteúdo, tipo e posição; sem limite de itens, mas com deslocamento ordenado. |
| `dropped_entries` (`DROPPED`) | Usuário, conteúdo, tipo, comentário opcional e contador de likes; unicidade por usuário/tipo/conteúdo. |
| `diary_entries` (`LOG`) | Usuário, conteúdo, nota/review, data assistida, rewatch, flags de geração e likes. |
| `user_lists` (`LISTA`) | Dono, nome, descrição, visibilidade e likes. |
| `user_list_items` (`ITEM_LISTA`) | Lista + exatamente um entre conteúdo e lista filha, posição e metadados do item. |
| `comments` (`COMENTARIO`) | Autor, exatamente um alvo entre conteúdo, lista, diário, dropped, Pick ou template, resposta opcional, spoiler, timestamps e contador de likes. |
| `likes` (`CURTIDA`) | Usuário e exatamente um alvo entre comentário, diário, dropped, lista, Pick ou template. |
| `notifications` (`NOTIFICACAO`) | Usuário, conteúdo opcional, tipo, mensagem, leitura, pessoa TMDB opcional e agregação social opcional. |
| `picks_templates` (`PICKS_TEMPLATES`) | Template, creator nullable, origem, instruções, período de elegibilidade e likes. |
| `picks_template_categories` (`PICKS_TEMPLATE_CATEGORIES`) | Template, grupo, ordem, tipo permitido e modo `OPEN`/`FIXED`. |
| `picks_template_options` (`PICKS_TEMPLATE_OPTIONS`) | Opção fixa por categoria, apontando para conteúdo ou pessoa, com contexto opcional. |
| `picks` (`PICKS`) | Usuário + template, visibilidade, timestamps e likes. O mesmo usuário pode ter vários Picks no template. |
| `pick_selections` (`PICK_SELECTIONS`) | Pick + categoria e exatamente um alvo entre conteúdo e pessoa, com contexto opcional. |
| `calendar_schedule_snapshots` | Cache compartilhado de eventos de calendário por identidade, região e idioma. |
| `calendar_schedule_completeness` | Marcadores de cobertura de temporada/série para permitir agrupamento seguro. |
| `tracked_content_states` | Estado compartilhado de rastreamento de conteúdo e datas conhecidas. |
| `tracked_person_states` | Estado compartilhado de rastreamento por `person_tmdb_id`. |
| `tracked_person_credits` | Créditos observados por pessoa rastreada. |
| `series_progress_metadata` | Snapshot agregado compartilhado por série; detalhado abaixo. |
| `series_progress_season_metadata` | Snapshot agregado compartilhado por série/temporada; detalhado abaixo. |
| `daily_challenges` | Desafio diário global por data GMT e modalidade, com coordenadas TMDB e snapshots JSONB. |
| `daily_challenge_hints` | Pistas ordenadas e congeladas de um desafio, com cascade na exclusão do desafio. |
| `user_daily_game_results` | Estado agregado de tentativas e pontuação de um usuário em um desafio, com detalhes JSONB transitórios da data GMT atual. |

### `notifications` (`NOTIFICACAO`)

| Coluna | Tipo | Regra |
| --- | --- | --- |
| `id` | `UUID` | PK. |
| `user_id` | `UUID` | Destinatário obrigatório; FK para `users` com `ON DELETE CASCADE`. |
| `type` | `VARCHAR(30)` | `RELEASE`, `ANNOUNCED_DATE`, `CANCELLED`, `RENEWED`, `NEW_EPISODE`, `FOLLOWED_PERSON_NEW_CREDIT`, `LIKE_RECEIVED` ou `COMMENT_RECEIVED`. |
| `message` | `VARCHAR(280)` | Texto exibido ao destinatário. |
| `content_id` | `UUID` | Nullable. FK para `contents` com `ON DELETE CASCADE`; obrigatório para tipos de sistema e nulo para tipos sociais. |
| `person_tmdb_id` | `VARCHAR(20)` | Nullable; usado por `FOLLOWED_PERSON_NEW_CREDIT`. |
| `actor_user_id` | `UUID` | Nullable. FK para `users` com `ON DELETE SET NULL`, preservando a notificação quando o autor da interação é excluído. |
| `target_type` | `VARCHAR(30)` | Nullable; em notificações sociais é `COMMENT`, `DIARY_ENTRY`, `DROPPED_ENTRY`, `USER_LIST`, `PICK` ou `PICKS_TEMPLATE`. |
| `target_id` | `UUID` | Nullable; identifica o alvo da interação social. |
| `interaction_count` | `INTEGER` | Obrigatório, default `1` e positivo; quantidade agregada da interação social. |
| `is_read` | `BOOLEAN` | Obrigatório, default `FALSE`. |
| `created_at` | `TIMESTAMP` | Obrigatório; atualizado para a nova interação enquanto o agregado está não lido. |
| `updated_at` | `TIMESTAMP` | Obrigatório; base da retenção das notificações sociais. |

`ck_notifications_social_shape` exige uma das duas formas: tipos `LIKE_RECEIVED` e
`COMMENT_RECEIVED` têm `content_id` nulo, `target_type` e `target_id` preenchidos e
`interaction_count > 0`; os tipos de sistema têm `content_id` preenchido, `target_type` e
`target_id` nulos. `uq_notifications_social_aggregate` é um índice único parcial em
`(user_id, type, target_type, target_id)` apenas para os dois tipos sociais, mantendo no máximo
um agregado por destinatário, ação e alvo. `idx_notifications_social_retention` indexa
`(type, is_read, updated_at, id)` com o mesmo predicado parcial para a limpeza em lotes.

## Pôsteres customizados por usuário

`user_content_posters` foi criado em `V57__create-user-content-posters.sql` como a fonte canônica de
`custom_poster_url`. A tabela possui `id`, `user_id`, `content_id`, `custom_poster_url`, `created_at` e
`updated_at`; os dois FKs são `NOT NULL` e usam `ON DELETE CASCADE`. A constraint
`uq_user_content_posters_user_id_content_id` garante exatamente uma linha por `(user_id, content_id)`;
os índices `idx_user_content_posters_user_id` e `idx_user_content_posters_content_id` suportam as leituras
em lote por perfil, autor de review e conteúdo.

O `CHECK` exige o prefixo literal `https://image.tmdb.org/t/p/w342/` e sufixo não vazio. A aplicação
repete uma validação mais estrita: o sufixo não pode ser só espaços, ter whitespace nas extremidades ou
conter qualquer whitespace. A migration fez backfill somente de valores legacy que já atendiam ao
prefixo e ao sufixo não vazio, a partir de `diary_entries`, `top5_entries` e itens de conteúdo de
`user_list_items`. Quando havia mais de uma fonte para o mesmo par, escolheu a mais recente por
`updated_at`, depois a prioridade diário → Top5 → item de lista e, por fim, `source_id`.

`V58__remove-legacy-custom-poster-columns.sql` preserva a evidência antes de remover as colunas legacy:
para cada candidato válido descartado de um par com mais de um candidato válido, registra em
`user_content_poster_conflict_audit` o usuário, conteúdo, fonte, URL e timestamps descartados, além da
fonte, URL e timestamps do vencedor escolhido por V57. Em seguida, V58 remove `custom_poster_url` de
`diary_entries`, `top5_entries` e `user_list_items`; essas colunas não existem no schema vigente. Excluir
uma entrada que originou a escrita não exclui o override canônico. Só o delete explícito do override, ou a
cascata de exclusão de `users`/`contents`, remove a linha canônica.

## Snapshots de progresso de séries

Essas tabelas não pertencem a um usuário. Elas armazenam somente metadados derivados do TMDB para que
o endpoint `GET /users/{userId}/series-in-progress` possa calcular o progresso personalizado a partir
de `diary_entries` sem repetir chamadas externas para cada usuário.

### `series_progress_metadata`

| Coluna | Tipo | Regra |
| --- | --- | --- |
| `series_tmdb_id` | `VARCHAR(20)` | PK. |
| `regular_released_episode_count` | `INTEGER` | Não negativo. |
| `total_known_runtime` | `INTEGER` | Nullable desde V56; não negativo quando presente. |
| `known_runtime_episode_count` | `INTEGER` | Não negativo. |
| `last_released_episode_date` | `DATE` | Nullable. |
| `refreshed_at` | `TIMESTAMP` | Obrigatório; base para frescor. |
| `runtime_verified_at` | `TIMESTAMP` | Nullable; runtime só é considerado verificado quando preenchido. |

Constraints e índices: `ck_series_progress_metadata_non_negative_values`; índice por
`series_tmdb_id` e índice por `refreshed_at`.

### `series_progress_season_metadata`

| Coluna | Tipo | Regra |
| --- | --- | --- |
| `id` | `UUID` | PK. |
| `series_tmdb_id` | `VARCHAR(20)` | Obrigatório; não é FK para `contents`. |
| `season_number` | `INTEGER` | Obrigatório e positivo; temporada 0/Specials não é armazenada. |
| `regular_released_episode_count` | `INTEGER` | Não negativo. |
| `total_known_runtime` | `INTEGER` | Nullable desde V56; não negativo quando presente. |
| `known_runtime_episode_count` | `INTEGER` | Não negativo. |
| `last_released_episode_date` | `DATE` | Nullable. |
| `refreshed_at` | `TIMESTAMP` | Obrigatório. |

Constraints e índices: `ck_series_progress_season_metadata_positive_season`,
`ck_series_progress_season_metadata_non_negative_values` e
`uq_series_progress_season_metadata_identity` em `(series_tmdb_id, season_number)`; índices por
`series_tmdb_id`, `(series_tmdb_id, season_number)` e `refreshed_at`.

## Regras de leitura do progresso

Um snapshot é fresco quando `refreshed_at` corresponde ao dia atual e o snapshot da série tem
`runtime_verified_at` preenchido. Snapshot ausente ou stale é hidratado pelo TMDB; se não houver
snapshot válido e o TMDB falhar, a API retorna `502`. Runtime desconhecido permanece `NULL`.

O endpoint ordena globalmente antes da paginação. `sortBy` aceita `LAST_WATCHED`, `LAST_RELEASED`,
`REMAINING_EPISODES` e `REMAINING_RUNTIME`; `direction` aceita `ASC` e `DESC`; o desempate final é
`seriesTmdbId ASC`. `aggregate` e `totalElements` cobrem todas as séries elegíveis, inclusive as que
ficaram fora da página.

## Jogos diários

As tabelas dos jogos diários foram criadas em `V59__create-daily-games-tables.sql`. Os desafios são
globais, identificados por `challenge_date` e `game_type`; a data é interpretada pela camada de aplicação
em GMT. O desafio guarda somente uma referência e snapshots derivados do TMDB: não existe FK para
`contents`, e pessoas e episódios podem ser alvos sem criar linhas nessa tabela.

### `daily_challenges`

| Coluna | Tipo | Regra |
| --- | --- | --- |
| `id` | `UUID` | PK. |
| `challenge_date` | `DATE` | Obrigatório; junto com `game_type` é único. |
| `game_type` | `VARCHAR(40)` | Obrigatório; exatamente as oito modalidades de `DailyGameType`. |
| `target_kind` | `VARCHAR(10)` | Obrigatório; `MOVIE`, `SERIES`, `PERSON` ou `EPISODE`, compatível com a modalidade. |
| `target_tmdb_id` | `VARCHAR(20)` | Obrigatório para `MOVIE`, `SERIES` e `PERSON`; nulo para `EPISODE`. |
| `series_tmdb_id` | `VARCHAR(20)` | Obrigatório somente para `EPISODE`. |
| `season_number` | `INTEGER` | Obrigatório e positivo para `EPISODE`; nulo nos demais alvos. |
| `episode_number` | `INTEGER` | Obrigatório e positivo para `EPISODE`; nulo nos demais alvos. |
| `answer_key` | `VARCHAR(256)` | Obrigatório; não se repete dentro da mesma modalidade. |
| `source_tmdb_id` | `VARCHAR(20)` | Nullable; origem TMDB usada na geração quando aplicável. |
| `image_path` | `VARCHAR(500)` | Obrigatório; caminho da imagem congelada. |
| `answer_snapshot` | `JSONB` | Obrigatório; snapshot privado da resposta. |
| `display_snapshot` | `JSONB` | Obrigatório; snapshot público da exibição. Em `EPISODE`, inclui `imagePaths`, uma lista compacta de caminhos relativos do TMDB na ordem de exibição. |
| `created_at` / `updated_at` | `TIMESTAMP` | Obrigatórios. |

`pk_daily_challenges` é a chave primária. As constraints `ck_daily_challenges_game_type`,
`ck_daily_challenges_target_kind` e `ck_daily_challenges_game_target_kind` impedem valores fora dos
enums e modalidades incompatíveis. `ck_daily_challenges_coordinates` exige exatamente a forma de
coordenadas correspondente ao alvo, e `ck_daily_challenges_positive_coordinates` rejeita IDs textuais
vazios e números de temporada/episódio não positivos. `uq_daily_challenges_date_game` garante um
desafio por data/modalidade e `uq_daily_challenges_game_answer` impede reutilizar uma resposta na
mesma modalidade. Não há FK de `daily_challenges` para `contents`: toda identidade de mídia é TMDB
e o snapshot congela a resposta (`answer_snapshot`) e a exibição pública (`display_snapshot`) para
que a consulta diária não dependa de uma linha local de conteúdo. Para episódios, `image_path` é o
primeiro caminho da sequência; `display_snapshot.imagePaths` congela as imagens sem armazenar URLs
completas. O cache Caffeine de imagens é somente uma otimização da geração, não uma dependência da
leitura do jogo.

### `daily_challenge_hints`

`daily_challenge_hints` possui `id`, `daily_challenge_id`, `position`, `hint_type` e `hint_value`.
`pk_daily_challenge_hints` é a chave primária. O FK `fk_daily_challenge_hints_challenge` aponta para
`daily_challenges(id)` com `ON DELETE CASCADE`; `position` deve ser positivo por
`ck_daily_challenge_hints_positive_position` e é único por desafio via
`uq_daily_challenge_hints_position`. A ordenação é lida pelo repositório por `position ASC`; não há
coleção JPA obrigatória em `DailyChallenge`, e a constraint única é o único índice próprio dessa tabela.

### `user_daily_game_results`

`user_daily_game_results` possui `id`, FKs obrigatórios `user_id` e `daily_challenge_id`,
`attempts_used`, `score`, `attempt_details`, `status`, `completed_at`, `created_at` e `updated_at`.
`pk_user_daily_game_results` é a chave primária; `fk_user_daily_game_results_user` aponta para
`users(id)` e `fk_user_daily_game_results_challenge` aponta para `daily_challenges(id)`, ambos com
`ON DELETE CASCADE`. `attempts_used` e `score` iniciam em zero e não podem ser negativos por
`ck_user_daily_game_results_non_negative`; `status` aceita somente `IN_PROGRESS`, `COMPLETED` ou
`FAILED` por `ck_user_daily_game_results_status`. `uq_user_daily_game_results_user_challenge`
mantém uma linha por usuário/desafio. `attempt_details` é `JSONB` nullable e armazena, somente enquanto o
desafio pertence à data GMT atual, as tentativas tipadas (número, identidade do candidato e feedback de
episódio, informação ou filmografia). A limpeza agendada zera esse campo para desafios com
`challenge_date` anterior à data GMT corrente. A ausência de detalhes não altera o contador agregado
`attempts_used` nem o snapshot único da resposta em
`daily_challenges.answer_snapshot`.

`attempts_used` continua sendo o contador agregado necessário para avançar ou bloquear o jogo, e `score`
é o resultado pontuado. Os detalhes individuais são transitórios: a aplicação os mantém apenas para o
desafio da data GMT atual e os limpa após a meia-noite; dias anteriores continuam usando essa mesma
linha única sem esses detalhes. Uma linha ausente significa `NOT_PLAYED`, uma linha `IN_PROGRESS` pode
ser retomada e estados terminais não aceitam replay.

share_on_completion é BOOLEAN NOT NULL DEFAULT FALSE e guarda a preferência pendente de publicação.
shared_at é TIMESTAMP nullable, preenchido uma única vez pela aplicação quando o resultado se torna
COMPLETED ou FAILED; a constraint ck_user_daily_game_results_shared_terminal rejeita shared_at em
resultados IN_PROGRESS. O índice parcial idx_user_daily_game_results_feed_shared em
(user_id, shared_at DESC, id DESC) apoia a consulta pull do feed por resultados publicados.

O índice `idx_user_daily_game_results_challenge_score_attempts` apoia a ordenação por desafio,
pontuação e tentativas; `idx_user_daily_game_results_user_status_challenge` apoia leituras por usuário
e status. A criação concorrente usa `INSERT ... ON CONFLICT (user_id, daily_challenge_id) DO NOTHING`,
e a atualização do resultado usa leitura com lock pessimista no repositório.
