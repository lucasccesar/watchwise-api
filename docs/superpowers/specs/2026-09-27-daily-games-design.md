# Daily Games — especificação de design

## Objetivo

Adicionar ao Watchwise um conjunto de jogos diários globais baseados em dados e imagens do TMDB. Todos os usuários recebem o mesmo desafio para cada modalidade na mesma data GMT. Cada usuário pode concluir cada modalidade uma única vez; o histórico mostra o resultado final e não permite novas respostas.

O recurso terá oito modalidades e nove rankings: um ranking geral e um ranking específico para cada modalidade.

## Decisões de produto

- A data do desafio é calculada em `GMT` (`UTC+00:00`), com virada às `00:00 GMT`.
- Todos os usuários recebem os mesmos oito desafios do dia.
- Cada modalidade tem seu próprio limite de tentativas.
- Somente um palpite confirmado consome tentativa; pesquisar ou digitar não consome.
- O usuário recebe pontos somente quando acerta.
- A pontuação do acerto na tentativa `n` é `maxAttempts - n + 1`.
- Um jogo concluído sem acerto vale zero.
- Depois de acertar ou esgotar as tentativas, a modalidade fica bloqueada para aquele usuário.
- Não são armazenados palpites individuais nem sua sequência.
- O resultado armazena somente estado agregado: tentativas usadas, status, pontuação e conclusão.
- Desafios anteriores são somente leitura. A resposta correta fica disponível no histórico, e não há pontuação retroativa.
- A resposta do desafio atual só é revelada ao usuário depois que ele conclui a modalidade.
- A não repetição vale pela combinação `modalidade + conteúdo`. O mesmo conteúdo pode aparecer em modalidades diferentes.
- Se o pool elegível de uma modalidade acabar, o sistema não repete conteúdo automaticamente; a geração sinaliza falha operacional.

## Modalidades e pontuação

| Código | Jogo | Alvo | Tentativas | Pontuação máxima |
|---|---|---|---:|---:|
| `MOVIE_BY_POSTER` | Movie by Poster | filme | 6 | 6 |
| `SERIES_BY_POSTER` | Series by Poster | série | 6 | 6 |
| `PERSON_BY_FACE` | Person by Face | pessoa | 6 | 6 |
| `EPISODE_BY_FRAME` | Episode by Frame | episódio | 10 | 10 |
| `MOVIE_BY_INFO` | Movie by Info | filme | 10 | 10 |
| `SERIES_BY_INFO` | Series by Info | série | 10 | 10 |
| `ACTOR_BY_MOVIE_FILMOGRAPHY` | Actor by Movies Filmography | pessoa | 10 | 10 |
| `ACTOR_BY_SERIES_FILMOGRAPHY` | Actor by Series Filmography | pessoa | 10 | 10 |

A pontuação máxima diária do ranking geral é 68 pontos: 18 pontos dos três jogos de seis tentativas e 50 pontos dos cinco jogos de dez tentativas. O ranking geral soma a pontuação bruta, sem normalização.

## Rankings

Existem nove visões de ranking:

1. geral: soma da pontuação de todas as modalidades;
2. um ranking para cada um dos oito códigos acima.

O primeiro critério é a pontuação total. O desempate é a menor soma de tentativas usadas nos resultados considerados. Persistindo o empate, os usuários ocupam a mesma posição.

Os rankings começam com agregações sobre os resultados finais, usando índices apropriados. Uma tabela de totais acumulados ou um cache de ranking não faz parte da primeira versão; essa projeção (tabela derivada para leitura rápida) só deve ser adicionada se métricas reais demonstrarem necessidade.

## Modelo de dados

### `daily_challenges`

Representa o desafio público de uma modalidade em uma data GMT.

Campos conceituais:

- `id` UUID;
- `challenge_date` `DATE`, interpretada em GMT;
- `game_type` enum;
- `target_kind` enum (`MOVIE`, `SERIES`, `PERSON`, `EPISODE`);
- `target_tmdb_id` para filme, série ou pessoa;
- `series_tmdb_id`, `season_number` e `episode_number` para episódio;
- `answer_key`, chave canônica usada pela restrição de não repetição;
- `source_tmdb_id` quando a geração usa um filme ou série de origem para obter elenco/filmografia;
- `image_path` da imagem congelada;
- snapshot das pistas e metadados de exibição;
- registros de criação e atualização.

Restrições:

- único por `(challenge_date, game_type)`;
- único por `(game_type, answer_key)`;
- `answer_key` para episódio inclui série, temporada e episódio;
- `target_kind` determina quais IDs e campos compostos podem ser preenchidos.

O desafio não cria uma linha em `contents`: ele referencia dados externos do TMDB e também suporta pessoas e episódios, que não correspondem uniformemente ao agregado `Content` usado pelas interações sociais.

### `user_daily_game_results`

Representa o resultado agregado de um usuário em um desafio.

Campos conceituais:

- `id` UUID;
- `user_id` FK para `users`;
- `daily_challenge_id` FK para `daily_challenges`;
- `attempts_used`;
- `score`;
- `status` (`IN_PROGRESS`, `COMPLETED`, `FAILED`);
- `completed_at`;
- registros de criação e atualização.

Restrições e índices:

- único por `(user_id, daily_challenge_id)`;
- índice por `(user_id, daily_challenge_id)`;
- índice por `(daily_challenge_id, score)`;
- índices que apoiem agregações por `user_id` e `game_type`.

Não existe tabela de palpites. O contador agregado é necessário para aplicar o limite entre requisições, mas não revela qual foi o palpite nem a ordem dos palpites.

### `daily_challenge_hints`

As pistas são congeladas durante a geração para que mudanças posteriores no TMDB não alterem um jogo histórico. A primeira versão usará uma tabela filha relacional, alinhada ao padrão atual do projeto:

- `id` UUID;
- `daily_challenge_id` FK;
- `position` sequencial;
- `hint_type`;
- `hint_value`;
- unicidade por `(daily_challenge_id, position)`.

As pistas só serão carregadas até a posição desbloqueada. Nenhuma pista futura deve ser mapeada para um DTO público enquanto o jogo estiver aberto.

## Geração diária

A geração é feita por um job idempotente (executável novamente sem duplicar desafios) que prepara o próximo dia GMT. O job deve rodar periodicamente e verificar se os oito desafios necessários existem, em vez de depender de uma única execução exatamente na virada.

Fluxo por modalidade:

1. sortear uma página entre 1 e 50;
2. buscar o retorno do endpoint TMDB da modalidade;
3. sortear um item do retorno;
4. validar poster, foto, frame e metadados necessários;
5. carregar detalhes e créditos complementares;
6. montar o snapshot de resposta, imagem e pistas;
7. tentar persistir respeitando a chave única de modalidade e conteúdo;
8. repetir com outro candidato quando o item for inválido ou já utilizado.

O job deve usar lock advisory do PostgreSQL para impedir que duas instâncias gerem o mesmo dia. Falhas transitórias do TMDB devem usar retries e o cache existente do `TmdbClient`. Se a geração não conseguir produzir um desafio válido, ela deve deixar o desafio ausente e registrar uma falha operacional; não deve inserir um item incompleto ou reutilizar conteúdo silenciosamente.

Se o desafio do dia não estiver pronto, os endpoints não devolvem um conjunto parcial: retornam `503` com `ApiError` indicando indisponibilidade temporária do jogo.

### Regras por modalidade

- `MOVIE_BY_POSTER`: `/movie/popular?page=x`; exige `poster_path`.
- `SERIES_BY_POSTER`: `/tv/popular?page=x`; exige `poster_path`.
- `PERSON_BY_FACE`: combina pessoas deduplicadas por `personTmdbId`, obtidas de elencos de filmes e séries escolhidos independentemente; exige `profile_path`.
- `ACTOR_BY_MOVIE_FILMOGRAPHY`: escolhe um filme de `/movie/top_rated?page=x`, carrega os créditos e escolhe uma pessoa elegível; a resposta é a pessoa.
- `ACTOR_BY_SERIES_FILMOGRAPHY`: escolhe uma série de `/tv/top_rated?page=x`, carrega os créditos e escolhe uma pessoa elegível; a resposta é a pessoa.
- `EPISODE_BY_FRAME`: escolhe uma série pelo processo de série popular, exclui especiais e episódios futuros, escolhe uma temporada regular e um episódio lançado, e seleciona uma imagem de cena válida.

Para episódios, a identidade é sempre `seriesTmdbId + seasonNumber + episodeNumber`. O ID isolado retornado pelo TMDB não é usado como chave de busca.

## Pistas dos jogos de informação

Os jogos `by info` liberam uma nova pista depois de cada erro. A sequência é a mesma para todos os usuários daquele desafio.

Para filmes, a ordem das pistas é: plataforma, gêneros, ano de lançamento, classificação etária, diretor, elenco, produtoras e bilheteria.

Para séries, a ordem equivalente é: plataforma, gêneros, ano de estreia, classificação etária, criador, elenco, redes/produtoras e quantidade de temporadas ou episódios. Bilheteria não é uma pista de série. A região de classificação etária deve ser uma configuração fixa da aplicação, com `BR` como padrão, e uma pista ausente deve ser omitida do snapshot. Depois que todas as pistas disponíveis forem liberadas, erros adicionais não liberam uma nova pista.

Nos jogos visuais, a imagem fica disponível desde o início; erros apenas consomem tentativas.

## Pesquisa e envio de palpites

Endpoint conceitual:

```text
GET /games/{gameType}/search?q=...
```

O serviço interpreta `gameType` no servidor e não permite que o cliente misture tipos:

- jogos de filme retornam filmes;
- jogos de série retornam séries;
- jogos de pessoa/ator retornam pessoas;
- episódios retornam candidatos compostos por série, temporada e episódio.

A pesquisa não consome tentativa. O envio usa IDs validados:

- filme: `tmdbId`;
- série: `tmdbId`;
- pessoa: `personTmdbId`;
- episódio: `seriesTmdbId`, `seasonNumber` e `episodeNumber`.

O serviço valida autenticação, modalidade, desafio aberto, resultado não concluído, tentativas restantes, tipo do candidato e identidade correta. Texto livre nunca é comparado diretamente para decidir o acerto.

O episódio usa uma busca em duas etapas, pois o TMDB não oferece a mesma busca plana por episódio usada para filmes, séries e pessoas:

```text
GET /games/EPISODE_BY_FRAME/search/series?q=...
GET /games/EPISODE_BY_FRAME/search/episodes?seriesTmdbId=...&q=...
```

A primeira etapa retorna séries. Depois que o usuário escolhe a série, a segunda carrega somente temporadas regulares e episódios lançados daquela série, filtra pelo texto do nome do episódio e retorna a identidade composta `seriesTmdbId + seasonNumber + episodeNumber`. A pesquisa continua sem consumir tentativa.

## Fluxo do usuário

### Abrir os jogos do dia

`GET /games/today` retorna as oito modalidades com imagem, pistas liberadas, tentativas usadas, tentativas restantes e status. A resposta correta fica omitida enquanto o jogo estiver aberto.

### Enviar palpite

`POST /games/{gameType}/attempt` avalia um candidato. Um erro incrementa somente o contador agregado. Um acerto calcula a pontuação, marca o resultado como concluído e devolve a resposta correta. O sexto erro nos jogos de seis tentativas e o décimo erro nos jogos de dez tentativas marca o resultado como falho, com pontuação zero, e devolve a resposta correta.

### Consultar histórico

`GET /games/history` e `GET /games/{gameType}/history` são somente leitura. Desafios anteriores mostram a resposta correta e o resultado do usuário. Usuários sem resultado aparecem como `NOT_PLAYED`, sem possibilidade de resposta retroativa.

### Consultar rankings

```text
GET /games/rankings/general
GET /games/{gameType}/ranking
```

As consultas usam a pontuação bruta e o desempate definido nesta especificação.

## Concorrência e segurança

- O resultado do usuário deve ser bloqueado para atualização durante a avaliação do palpite.
- A criação concorrente do primeiro resultado deve depender da restrição única e recuperar o registro vencedor da corrida.
- A geração usa lock no banco, não apenas `synchronized` local, pois a aplicação pode ter várias instâncias.
- A resposta correta e as pistas futuras nunca devem aparecer em DTOs de jogo aberto.
- O cliente não pode escolher `gameType`, `targetKind` ou IDs para alterar a modalidade; esses valores são conferidos pelo serviço.
- Não confiar em contadores enviados pelo cliente; `attemptsUsed` é calculado e persistido pelo backend.
- Toda exceção de TMDB, geração, concorrência ou validação deve terminar em `ApiError` pelo `GlobalExceptionHandler`.

## Testes necessários

- mesma data GMT retorna os mesmos oito desafios para usuários diferentes;
- virada de data ocorre exatamente em `00:00 GMT`;
- dois jobs concorrentes não criam desafios duplicados;
- conteúdo repetido na mesma modalidade é rejeitado pelo banco;
- conteúdo repetido em modalidade diferente continua permitido;
- cada erro consome uma tentativa e não grava o palpite;
- acertos nas tentativas inicial e final calculam 6/10 até 1 ponto;
- esgotamento marca falha e pontuação zero;
- nova resposta depois da conclusão é rejeitada;
- pesquisa não consome tentativa;
- tipos inválidos não passam na busca nem no envio;
- episódio valida a identidade composta;
- histórico revela a resposta somente conforme as regras de conclusão;
- rankings geral e específicos agregam corretamente;
- empates usam a soma de tentativas e depois a mesma posição;
- TMDB sem imagem, dados incompletos ou indisponível não gera desafio incompleto;
- respostas de erro continuam no formato `ApiError`.

## Fora do escopo inicial

- ranking diário separado do ranking acumulado;
- replay ou pontuação retroativa;
- ranking normalizado por modalidade;
- tabela materializada de totais sem evidência de gargalo;
- painel administrativo para substituir desafios;
- notificações de novo desafio;
- armazenamento de cada palpite do usuário.
