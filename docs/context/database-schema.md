# Watchwise — modelo lógico do banco de dados

Este documento acompanha o schema vigente nas migrations do projeto. Os nomes das entidades abaixo
seguem a convenção usada no código Java; os nomes entre parênteses preservam a nomenclatura do modelo
lógico original. O diagrama visual complementar está em [`database-schema.html`](database-schema.html);
V72 e V74–V95 (`user_blocks`, denúncias, importações, opiniões, autenticação, limitadores
compartilhados e gerações de atualização) estão descritos neste Markdown e no HTML.

## Regras gerais

- `Content` (`CONTEUDO`) é uma referência leve ao TMDB. Filmes e séries usam `tmdb_id`; temporadas e
  episódios usam `series_tmdb_id` + `season_number` e, para episódios, `episode_number`.
- `DiaryEntry` (`LOG`) continua concentrando registro, nota e review para MOVIE/EPISODE. V80/B4 acrescenta
  `ContentRating` e reviews escritas independentes somente para SERIES/SEASON; tabelas e dados legados
  permanecem preservados.
- `Comment` (`COMENTARIO`) aponta para exatamente um alvo entre `Content`, `UserList`, `DiaryEntry`, `DroppedEntry`, `Pick`, `PicksTemplate` e `ContentReview`.
- `Like` (`CURTIDA`) aponta para exatamente um alvo social permitido e tem unicidade por usuário e alvo, incluindo `ContentReview`.
- `UserList` (`LISTA`) é lista customizada; `WatchlistEntry` (`WATCHLIST`) é a fila por tipo para assistir
  depois.
- Roles globais persistidas em `User.role` são `USER` e `ADMIN`, com default `USER`.
- `UserContentPoster` é o único armazenamento canônico de pôster customizado: um override por par
  `User` + `Content`, independente das entradas de diário, Top5 e listas que possam ter originado a escrita.

## Migrations V88–V95 — autenticação e concorrência

- **V88–V92 — confirmação de e-mail:** `users.auth_version` permite invalidar versões de sessão;
  `users` guarda somente o hash e a expiração do token de confirmação e, para uma troca de e-mail,
  `pending_email` reserva o endereço novo sem substituir o atual antes da confirmação. Cadastros ainda
  não confirmados ficam em `pending_registrations`, tabela sem FK para `users`, e só criam a conta
  definitiva após validar o token e a prova de registro. A V92 migra os registros pendentes para essa
  tabela e remove as colunas transitórias antigas de `users`.
- **V93 — limitadores compartilhados:** `shared_limiter_state` persiste contadores de janela e
  bloqueios por um digest SHA-256 da chave, permitindo compartilhar rate limits e lockouts entre
  instâncias sem guardar identificadores crus.
- **V94 — fencing de atualizações externas:** `refresh_observation_generation_seq` e colunas
  `refresh_generation` em `tracked_content_states`, `series_progress_metadata`, `contents` e
  `content_release_date_snapshots` impedem que uma observação antiga sobrescreva uma atualização mais
  recente. `content_release_date_snapshots.refresh_claim_id` identifica a reivindicação ativa.
- **V95 — recuperação de senha:** `users.password_reset_token_hash` e
  `password_reset_expires_at` guardam o token de uso único em hash e seu vencimento; uma constraint
  exige que hash e expiração sejam preenchidos ou nulos juntos.

## Entidades e tabelas

| Tabela | Chave e conteúdo principal |
| --- | --- |
| `users` (`USUARIO`) | `id`; `username` único, `name` obrigatório e não único, credenciais, perfil, preferências, `role`, `auth_version`, invalidação de sessões, confirmação de e-mail, e-mail pendente e recuperação de senha. |
| `pending_registrations` | Dados provisórios de cadastro, hash do token/prova e expiração; a conta `users` só é criada após confirmação. V92. |
| `shared_limiter_state` | Contadores persistidos de rate limit e lockout por digest de chave, compartilhados entre réplicas. V93. |
| `contents` (`CONTEUDO`) | `id`; `type`, referência TMDB, flags de finale, gêneros, países e agregados de runtime. |
| `user_content_posters` | Override de pôster por usuário + conteúdo; unicidade em `(user_id, content_id)`. |
| `user_content_poster_conflict_audit` | Evidência de candidatos legacy válidos descartados na migração, incluindo sua fonte/timestamps e a fonte/timestamps do vencedor. |
| `followers` (`SEGUIDOR`) | Chave composta de seguidor/seguido, status e `created_at`. |
| `user_blocks` (`BLOQUEIO_USUARIO` → `UserBlock`) | Bloqueio direcional entre dois usuários, único por par ordenado; migration V72. |
| `followed_people` (`SEGUE_PESSOA`) | Usuário + `person_tmdb_id`, com unicidade por usuário/pessoa. |
| `top5_entries` (`TOP5`) | Usuário, conteúdo, tipo e posição; unicidade por tipo/posição e tipo/conteúdo. |
| `watchlist_entries` (`WATCHLIST`) | Usuário, conteúdo, tipo e posição global; sem limite de itens, com unicidade em `(user_id, position)` e deslocamento ordenado. |
| `dropped_entries` (`DROPPED`) | Usuário, conteúdo, tipo, comentário opcional e contador de likes; unicidade por usuário/tipo/conteúdo. |
| `diary_entries` (`LOG`) | Eventos assistidos MOVIE/EPISODE, nota/review inline, data assistida, instante interno `watched_at` opcional, passada (`watch_number`), companheiros, flag legada de apresentação (`ignore`) e likes. V82 elimina `auto_generated` e impede referências novas a SERIES/SEASON. |
| `content_ratings` | Nota atual 1–10 por usuário/conteúdo SERIES ou SEASON; unicidade do par. V80. |
| `content_reviews` | Reviews escritas de SERIES/SEASON, com contexto, nota congelada e cronologia de origem. Tabela V80; índice de ordenação de publicação V80_1. |
| `diary_completion_projections` | Cache de conclusão por usuário/conteúdo/passada, sem interações sociais. V80. |
| `user_series_history_states` | Revisão de histórico por usuário/série e versão da agenda; inclui zero conclusões. V80. |
| `user_action_receipts` | Recibo durável de ações idempotentes por usuário/operação/chave. V80. |
| `user_lists` (`LISTA`) | Dono, nome, descrição, visibilidade e likes. |
| `user_list_items` (`ITEM_LISTA`) | Lista + exatamente um entre conteúdo e lista filha, posição e metadados do item. |
| `comments` (`COMENTARIO`) | Autor, exatamente um alvo entre conteúdo, lista, diário, dropped, Pick, template ou ContentReview, resposta opcional, spoiler, timestamps e contador de likes. |
| `likes` (`CURTIDA`) | Usuário e exatamente um alvo entre comentário, diário, dropped, lista, Pick, template ou ContentReview. |
| `notifications` (`NOTIFICACAO`) | Usuário, conteúdo opcional, tipo, mensagem, leitura, pessoa TMDB opcional e agregação social opcional. |
| `notification_actors` | Autores distintos que contribuíram para um agregado social não lido; cascade na exclusão da notificação/usuário. V77. |
| `report_cases` | Alvo tipado, estado de moderação, notas internas e timestamps de abertura/fechamento. V74. |
| `report_submissions` | Denúncia individual por reporter/case, motivo, detalhes e snapshot JSON textual imutável. V74. |
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
| `content_release_date_snapshots` | Data de lançamento materializada por `(tmdb_id, region)` para filmes e por `tmdb_id` para séries, com status e agenda de atualização. |
| `series_progress_metadata` | Snapshot agregado compartilhado por série; detalhado abaixo. |
| `series_progress_season_metadata` | Snapshot agregado compartilhado por série/temporada; detalhado abaixo. |
| `daily_challenges` | Desafio diário global por data GMT e modalidade, com coordenadas TMDB e snapshots JSONB. |
| `daily_challenge_hints` | Pistas ordenadas e congeladas de um desafio, com cascade na exclusão do desafio. |
| `user_daily_game_results` | Estado agregado de tentativas e pontuação de um usuário em um desafio, com detalhes JSONB transitórios da data GMT atual. |
| `import_jobs` | Estado persistido do trabalho de importação, seleção, checkpoint, lease e bytes reservados para upload (`upload_size_bytes`). Um job `IMPORTING` por usuário. V79. |
| `import_items` | Itens normalizados por job/geração, com identidade de origem, resolução, decisão e ordenação por chave. V79. |
| `external_import_links` | Ledger durável por usuário e identidade externa; reserva atômica e estados `IN_PROGRESS`, `APPLIED`, `SKIPPED` e `TOMBSTONE`. V79. |
| `external_content_aliases` | Pontes tipadas de título para referência TMDB/pessoa, globais ou por usuário; não cria `Content`. V79. |
| `imported_watch_assertions` | Evidência importada de visualização ligada a User/Content, sempre com cobertura `UNKNOWN`; não é `DiaryEntry`. V79. |
| `import_deferred_records` | Payload normalizado selecionado que precisa sobreviver à limpeza do arquivo privado. V79. |
| `letterboxd_seed_loads` | Registro idempotente de versão e checksum de carga de aliases Letterboxd. V79. |
| `import_file_cleanup_tasks` | Outbox sem FK para usuário/job, contendo somente storage keys geradas para remoção repetível. V79. |

### Opiniões e projeções de diário (V80; índice V80_1)

`contents` possui `UNIQUE(id,type)` para as FKs compostas de `content_ratings`,
`content_reviews` e `diary_completion_projections`. Essas tabelas aceitam somente SERIES/SEASON.
Ratings são únicos por `(user_id,content_id)` e limitados a inteiros 1–10. Reviews escritas
exigem texto não vazio e `watch_number` positivo.
`context_kind=UNKNOWN` representa contexto desconhecido explicitamente; `context_json` permite
contexto textual. `score_at_publication` é opcional e o mapeamento JPA não o inclui em updates,
mantendo o snapshot ao editar o comentário. Para SEASON há no máximo uma
`WRITTEN_REVIEW` por usuário/conteúdo/`watch_number`; SERIES admite várias na mesma passada.
Somente reviews escritas recebem novas interações sociais. `V80_1` adiciona
`idx_content_reviews_publication_order` em `(user_id,content_id,source_published_at,source_published_on,id)`;
ele permite consultar a fronteira de publicação e seus empates sem carregar todas as reviews do conteúdo.

`diary_completion_projections` é cache sem alvo social, único por usuário/conteúdo/passada, com
âncora opcional, data de conclusão opcional, `ordered_at`, revisões de histórico/agenda e
`valid_until`. `user_series_history_states` guarda `(user_id,series_tmdb_id)`,
`history_revision`, `computed_revision`, `schedule_version` e `valid_until`, inclusive quando
nenhuma conclusão existe. Sua revisão aumenta atomicamente na transação do episódio; a agenda
continua nas tabelas de calendário existentes. `diary_entries.watched_at` é instante opcional
da origem, independente de `watched_date`; dia sem hora não é convertido em meia-noite.
`calendar_schedule_completeness.scope_version` é uma versão monotônica por identidade de temporada
ou série: muda quando a cobertura, coordenadas ou datas do schedule mudam, mas não quando apenas
`last_checked_at` é renovado. As versões de SEASON e SERIES são independentes e não são comparadas
por igualdade entre escopos.
Leitores de progresso só tratam uma projeção SERIES como conclusão atual quando as revisões de
histórico persistida, calculada e projetada coincidem, as validades do estado e da projeção ainda são
futuras, e existe marcador SERIES completo para a região/idioma preferidos com `scope_version` igual à
versão da projeção.

`user_action_receipts` é único por `(user_id,operation,idempotency_key)` e armazena hash e
resultado mínimo. Só `DIARY_BULK` e `CONTENT_REVIEW_PUBLICATION` são operações válidas.
Recibos sobrevivem até a exclusão da conta. V80_1 não altera tabelas ou constraints: adiciona somente
o índice de cronologia de reviews descrito acima. V82 remove as tabelas temporárias de mapa/arquivo
legacy que V80 criou e descarta as antigas DiaryEntry SERIES/SEASON antes do primeiro lançamento público.

### Alvos sociais de reviews (V81)

`comments.content_review_id` e `likes.content_review_id` referenciam `content_reviews(id)` com
`ON DELETE CASCADE`. As constraints `ck_comments_target` e `ck_likes_target` exigem exatamente um alvo,
incluindo a nova coluna, sem remover `diary_entry_id`. O índice parcial
`uq_likes_user_content_review` impede duas curtidas do mesmo usuário na mesma review;
`idx_likes_content_review_id` apoia buscas pelo alvo e
`idx_comments_content_review_id_created_at` apoia a listagem cronológica de comentários.
`notifications.target_type` e `report_cases.target_type` aceitam `CONTENT_REVIEW`; o antigo
`REVIEW` continua identificando DiaryEntry MOVIE/EPISODE. As denúncias preservam o snapshot quando
a review é removida. V82 elimina referências sociais ligadas a DiaryEntry SERIES/SEASON legados e
remove o mapa/arquivo temporário do V80. Para reviews, V82 apaga `LEGACY_ACTIVITY` e suas referências;
`WRITTEN_REVIEW` é preservada mesmo quando o contexto era `LEGACY`, que é reclassificado como `UNKNOWN`
antes da nova constraint.

### Fundação de importação (V79)

`import_jobs` pertence a `users` e é apagado com a conta. Seu `version` JPA não altera o
`preview_version`: apenas mudanças na seleção/resolução visível ao usuário incrementam a geração do
plano. `upload_size_bytes` é `BIGINT NOT NULL DEFAULT 0 CHECK (upload_size_bytes >= 0)` e reserva
bytes pendentes de upload para as cotas globais e por usuário aplicadas em A6. `idx_import_jobs_runnable`
localiza trabalhos pendentes e `uq_import_jobs_one_writer_per_user` permite no máximo um estado
`IMPORTING` por usuário.

`import_items` pertence a um job e é apagado em cascata. Os índices de geração, evento, resolução e
`(job_id, parent_source_id, ordinal, id)` suportam leitura por chave e paginação estável.

`external_import_links` tem unicidade nomeada `uq_external_import_links_origin` em usuário, namespace,
tipo de objeto e ID da origem. A reserva usa `INSERT ... ON CONFLICT DO NOTHING` seguido de leitura na
mesma transação depois de confirmar que o job pertence ao dono; assim a concorrência real não aborta a
conexão e um job alheio não pode reutilizar uma chave já existente. `APPLIED`, `SKIPPED` e `TOMBSTONE`
são consumidos; somente `IN_PROGRESS` pode transicionar para `APPLIED`/`SKIPPED`. Repetir uma transição
terminal idêntica é idempotente; mudar estado/alvo/razão ou finalizar sem reserva falha. Apagar o alvo
transforma o link em tombstone durável, impedindo ressurreição na reimportação. O link sobrevive à
exclusão do job (`owning_job_id` vira nulo), mas é removido na exclusão da conta. `outcome_reason` conserva
a razão de skip.

`external_content_aliases` não possui FK para `contents`: `target_key` identifica o tipo de mídia e sua
referência natural TMDB completa. O formato canônico é `CONTENT|MOVIE|id`, `CONTENT|SERIES|id`,
`CONTENT|SEASON|seriesId|season`, `CONTENT|EPISODE|seriesId|season|episode` ou `PERSON|id`; a constraint
`ck_external_content_aliases_typed_target` valida IDs exigidos, colunas nulas por tipo, coordenadas
positivas (season aceita zero) e consistência da chave. `uq_external_content_aliases_candidate` inclui
`target_key` e usa `NULLS NOT DISTINCT`, então ano nulo também participa da deduplicação sem impedir alvos
distintos para mesmo título/ano. `scope_key` é `GLOBAL` ou `USER:<uuid>`; aliases de usuário têm FK com
cascade. Alvo de pessoa e alvo de conteúdo usam colunas tipadas distintas.

`imported_watch_assertions` pertence a User e Content, com unicidade da identidade de origem e cobertura
fixada em `UNKNOWN`. A2 persiste a evidência, sem registrar conclusão nem presumir que todos os episódios
foram vistos. A política compartilhada de watchlist consome essa evidência em A8. Não depende de job nem
de tipos do pacote `platformimport`.

`import_deferred_records` guarda apenas conteúdo normalizado selecionado e proveniência, com cascade de
usuário. `letterboxd_seed_loads` é único por versão/checksum. `import_file_cleanup_tasks` não tem FK para
User ou Job: a linha sobrevive à exclusão da conta/job e permite apagar bytes privados usando somente a
chave interna gerada, nunca um caminho fornecido pelo cliente.

### `user_blocks` (`BLOQUEIO_USUARIO` → `UserBlock`)

Criada por `V72__create-user-blocks.sql`, depende somente de `users`.

| Coluna | Tipo | Regra |
| --- | --- | --- |
| `id` | `UUID` | PK; gerado pela aplicação/JPA. |
| `blocker_id` | `UUID` | Obrigatória; FK para `users(id)` com `ON DELETE CASCADE`. |
| `blocked_id` | `UUID` | Obrigatória; FK para `users(id)` com `ON DELETE CASCADE`. |
| `created_at` | `TIMESTAMP` | Obrigatória; instante de criação do bloqueio. |

`uq_user_blocks_blocker_id_blocked_id` garante unicidade em `(blocker_id, blocked_id)`;
`ck_user_blocks_no_self_block` exige `blocker_id <> blocked_id`. O par inverso é independente.
`idx_user_blocks_blocker_created_at` indexa `(blocker_id, created_at DESC, id DESC)`;
`idx_user_blocks_blocked_id` indexa `blocked_id`. A exclusão de qualquer usuário remove o bloqueio.
Os efeitos sociais consultam os dois sentidos, embora cada usuário administre somente seus bloqueios.

### `report_cases` e `report_submissions`

Criadas por `V74__create-report-cases-and-submissions.sql`. `report_cases` identifica o alvo por
`(target_type, target_id)` sem FK para a tabela reportada, pois os alvos podem pertencer a tabelas
diferentes e o snapshot deve sobreviver à exclusão do conteúdo. `target_type` aceita `USER`, `COMMENT`,
`REVIEW`, `USER_LIST`, `PICK`, `PICKS_TEMPLATE` e `DROPPED_ENTRY`; `status` aceita `OPEN`, `IN_REVIEW`,
`RESOLVED` e `DISMISSED`. `ck_report_cases_closed_at_by_status` em V76 exige `closed_at` nulo nos casos
ativos e preenchido nos terminais. O índice parcial `uq_report_cases_active_target` permite somente um
case `OPEN`/`IN_REVIEW` por alvo tipado; `idx_report_cases_status_created_at` ordena a fila administrativa.

`report_submissions` aponta para `report_cases` e `users` com `ON DELETE CASCADE`. Cada reporter pode
submeter uma vez por case via `uq_report_submissions_case_reporter`. `reason` aceita
`SPAM_OR_MISLEADING`, `HARASSMENT_OR_BULLYING`, `HATE_SPEECH`, `UNMARKED_SPOILER`,
`SEXUAL_OR_VIOLENT_CONTENT` e `OTHER`; `details` tem no máximo 280 caracteres. `target_snapshot` é texto
JSON allowlisted e congela apenas identidade tipada, autor público e evidência escrita relevante; não
serializa entities nem dados privados. `idx_report_submissions_reporter_id` (V78) apoia a remoção das
submissions na exclusão de conta. Casos resolvidos/dispensados e seus snapshots são removidos após
90 dias; excluir o reporter remove suas submissions e cases que ficam vazios.

`notifications` usa `type = REPORT_RESOLVED` e `target_type = REPORT_CASE` para avisar cada reporter do
fechamento do case. A referência não tem FK; a prévia é genérica e não navegável. V75 permite essa forma
no `ck_notifications_social_shape` sem ator, conteúdo ou snapshot.

`notification_actors` (V77) guarda uma linha por par `(notification_id, actor_user_id)` para cada usuário
que contribuiu para um agregado de likes/comentários. `pk_notification_actors` identifica o par; os dois
FKs usam `ON DELETE CASCADE`, e `idx_notification_actors_actor_notification` permite localizar os
agregados de um ator ao bloquear o par. O agregado inteiro é removido se qualquer participante for
bloqueado. A migração também apaga agregados históricos ambíguos com mais de uma interação das caixas
de entrada que já participam de bloqueios, pois o schema anterior preservava somente `latestActor`.

Settings é apenas uma projeção de leitura de `users`, `followers` e `user_blocks`, sem tabela própria.
`users.profile_picture` e `users.banner` armazenam URLs opcionais. Ausência de foto é persistida como
null, sem URL de avatar padrão; o frontend escolhe a imagem de fallback. Remover o banner limpa
somente `users.banner`, sem tabela, arquivo, upload ou serviço de armazenamento de mídia.

### `content_release_date_snapshots`

| Coluna | Tipo | Regra |
| --- | --- | --- |
| `id` | `UUID` | PK. |
| `tmdb_id` | `VARCHAR(20)` | Identidade TMDB do filme ou série. |
| `type` | `VARCHAR(6)` | `MOVIE` ou `SERIES`. |
| `region` | `VARCHAR(2)` | Obrigatória para `MOVIE`; nula para `SERIES`. |
| `release_date` | `DATE` | Nullable quando o TMDB não fornece uma data válida. |
| `status` | `VARCHAR(11)` | `FOUND`, `NOT_FOUND` ou `UNAVAILABLE`. |
| `last_checked_at` / `next_check_at` | `TIMESTAMP` | Última consulta e próxima tentativa de atualização. |

`uq_content_release_date_snapshots_movie` garante uma linha por `(tmdb_id, region)` para filmes;
`uq_content_release_date_snapshots_series` garante uma linha por `tmdb_id` para séries. O índice
`idx_content_release_date_snapshots_due` apoia o job de refresh por `next_check_at`.

### `notifications` (`NOTIFICACAO`)

| Coluna | Tipo | Regra |
| --- | --- | --- |
| `id` | `UUID` | PK. |
| `user_id` | `UUID` | Destinatário obrigatório; FK para `users` com `ON DELETE CASCADE`. |
| `type` | `VARCHAR(30)` | Tipos de sistema, sociais e follow, incluindo `REPORT_RESOLVED` (V75). |
| `message` | `VARCHAR(280)` | Texto exibido ao destinatário. |
| `content_id` | `UUID` | Nullable. FK para `contents` com `ON DELETE CASCADE`; obrigatório para tipos de sistema e nulo para tipos sociais. |
| `person_tmdb_id` | `VARCHAR(20)` | Nullable; usado por `FOLLOWED_PERSON_NEW_CREDIT`. |
| `actor_user_id` | `UUID` | Nullable. FK para `users` com `ON DELETE SET NULL`, preservando a notificação quando o autor da interação é excluído. |
| `target_type` | `VARCHAR(30)` | Nullable; alvo social ou `REPORT_CASE` para notificação genérica de resolução (V75). |
| `target_id` | `UUID` | Nullable; identifica o alvo da interação social. |
| `interaction_count` | `INTEGER` | Obrigatório, default `1` e positivo; quantidade agregada da interação social. |
| `is_read` | `BOOLEAN` | Obrigatório, default `FALSE`. |
| `created_at` | `TIMESTAMP` | Obrigatório; atualizado para a nova interação enquanto o agregado está não lido. |
| `updated_at` | `TIMESTAMP` | Obrigatório; base da retenção das notificações sociais. |

`ck_notifications_social_shape` exige as formas de agregado, follow e sistema: tipos `LIKE_RECEIVED` e
`COMMENT_RECEIVED` têm `content_id` nulo, `target_type` e `target_id` preenchidos e
`interaction_count > 0`; os tipos de sistema têm `content_id` preenchido, `target_type` e
`target_id` nulos. `uq_notifications_social_aggregate` é um índice único parcial em
`(user_id, type, target_type, target_id)` apenas para os dois tipos sociais, mantendo no máximo
um agregado por destinatário, ação e alvo. `idx_notifications_social_retention` indexa
`(type, is_read, updated_at, id)` com o predicado parcial para os dois tipos sociais e os
três tipos de follow, permitindo a limpeza em lotes. Os tipos de follow nunca usam o índice
`uq_notifications_social_aggregate`; sua linha tem `content_id`, `target_type` e `target_id`
nulos, `interaction_count = 1` e usa `actor_user_id` como referência opcional ao ator. O FK
`actor_user_id` usa `ON DELETE SET NULL`, então excluir o ator não invalida a notificação.

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
