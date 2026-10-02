# Jogos diários v2 — especificação de design

## Objetivo

Evoluir os jogos diários para suportar telas independentes por modalidade, desistência explícita,
seleção hierárquica no jogo `EPISODE_BY_FRAME`, comparação estruturada nos jogos `by info` e
dedução por filmografias compartilhadas nos dois jogos de atores.

Esta especificação substitui, para os pontos cobertos aqui, as decisões anteriores que diziam que
não haveria qualquer detalhe transitório de tentativas e que o jogo de episódio pesquisaria
episódios por texto. O resumo histórico, a resposta congelada em `daily_challenges` e o restante da
infraestrutura diária permanecem válidos.

## Princípios de otimização

- Não criar uma tabela por jogo.
- Não duplicar a resposta correta por usuário.
- Não consultar o TMDB ao reconstruir o estado de uma partida atual ou histórica.
- Persistir detalhes de tentativas somente para o desafio da data GMT atual.
- Manter o núcleo de geração, ranking, autenticação e pontuação compartilhado.
- Fazer comparações e filtros no backend para que a interface receba um contrato determinístico.

## Contrato de leitura por modalidade

As rotas atuais continuam disponíveis:

```text
GET /games/today
GET /games/{challengeDate}
```

Serão adicionadas leituras específicas:

```text
GET /games/{gameType}/today
GET /games/{challengeDate}/{gameType}
```

As rotas específicas retornam o mesmo estado que o item correspondente do endpoint agregado. O
endpoint agregado não será removido e continua útil para a tela geral, compatibilidade e
pré-carregamento.

As tentativas continuam específicas por modalidade:

```text
POST /games/{gameType}/attempt
POST /games/{challengeDate}/{gameType}/attempt
```

Não serão criados oito endpoints de tentativa com implementações duplicadas. O parâmetro
`gameType` seleciona o serviço e o contrato específico de comparação.

## Persistência e ciclo de vida dos detalhes

`daily_challenges` continua sendo a fonte congelada do desafio. Há uma linha por combinação de
data GMT e modalidade e ela armazena uma única vez:

- identidade do alvo;
- `answer_snapshot` com a resposta e metadados necessários;
- `display_snapshot` com imagens e dados de exibição;
- pistas já existentes.

`user_daily_game_results` continua armazenando, para todos os dias, somente o resumo do resultado:
tentativas usadas, score, status e timestamps. A resposta final é montada a partir do desafio
referenciado, nunca copiada para cada usuário.

O resultado receberá um único campo JSONB transitório, chamado conceitualmente de
`attempt_details`, com os dados mínimos necessários para reconstruir a partida do dia atual:

- número da tentativa;
- identidade do palpite;
- nome e imagem do candidato quando necessários para a tabela;
- feedback calculado e IDs de obras compartilhadas.

O campo será preenchido somente quando o `daily_challenge.challenge_date` for a data GMT atual.
Uma tarefa de limpeza executada após `00:00 GMT` remove o campo de resultados de datas anteriores.
Ela não remove `answer_snapshot`, `display_snapshot`, score, status ou histórico.

Tentativas inválidas não são inseridas no JSONB nem incrementam `attempts_used`. Em partidas
históricas, a pontuação e o status continuam persistidos, mas os detalhes transitórios não precisam
ser reconstruídos depois da virada do dia.

O resultado do usuário será bloqueado durante tentativa e desistência. A restrição única existente
para `(user_id, daily_challenge_id)` continua protegendo a criação concorrente do primeiro
resultado; a leitura bloqueada decide qual operação vence uma corrida entre tentativa e desistência.

## Desistência

Serão adicionadas:

```text
POST /games/{gameType}/give-up
POST /games/{challengeDate}/{gameType}/give-up
```

O serviço cria o resultado se necessário, bloqueia a linha, verifica se a partida ainda está aberta
e então define:

- `status = FAILED`;
- `score = 0`;
- `completed_at = now`;
- resposta final revelada no retorno.

A desistência não incrementa `attempts_used` e não cria uma tentativa no JSONB. Uma desistência ou
tentativa concorrente que perder o bloqueio encontra o resultado terminal e recebe `409`. O endpoint
usa o mesmo limite de requisições das ações de tentativa.

## Jogo `EPISODE_BY_FRAME`

### Seleção

O fluxo público deixa de pesquisar episódios por texto. As rotas são:

```text
GET /games/EPISODE_BY_FRAME/search/series?q=...&page=...&size=...
GET /games/EPISODE_BY_FRAME/series/{seriesTmdbId}/seasons
GET /games/EPISODE_BY_FRAME/series/{seriesTmdbId}/seasons/{seasonNumber}/episodes
```

A busca de séries retorna ID, nome, poster e ano. A lista de temporadas retorna somente número,
nome e quantidade de episódios, sem imagens. A lista de episódios retorna número, nome e data de
exibição, também sem imagens. Temporadas especiais não entram no dropdown; episódios futuros não
entram na lista de seleção. Um episódio sem seis imagens pode ser escolhido como palpite, porque a
regra das seis imagens pertence somente ao segredo.

Uma série, temporada ou coordenada inválida retorna erro de domínio; uma indisponibilidade do TMDB
retorna `502`. A seleção usa a identidade composta `seriesTmdbId + seasonNumber + episodeNumber`.

### Geração

O gerador escolhe uma série elegível e percorre temporadas regulares já lançadas em ordem aleatória.
Dentro de cada temporada, percorre episódios lançados em ordem aleatória e consulta as imagens do
episódio. O primeiro episódio com pelo menos seis `file_path` distintos e válidos é aceito.

Se nenhuma tentativa da temporada atingir seis imagens, outra temporada é tentada. Se nenhuma
temporada da série atingir o requisito, uma nova série é escolhida. O limite global de candidatos
do gerador impede loop infinito e a chave composta continua impedindo repetição histórica.

O snapshot persiste a série completa necessária para a resposta final — ID, nome, poster e ano — e
o episódio — temporada, número e nome — além das imagens relativas. A resposta final expõe esses
dados e URLs derivadas sem consultar o TMDB.

## Jogos `MOVIE_BY_INFO` e `SERIES_BY_INFO`

O snapshot de informação passa a conter valores estruturados e normalizados, preservando valores
ausentes como ausentes em vez de concatenar somente strings existentes. Os campos são:

| Filme | Série |
|---|---|
| plataformas | plataformas |
| gêneros | gêneros |
| ano de lançamento | ano de estreia |
| classificação BR | classificação BR |
| diretor | criadores |
| elenco | elenco |
| produtoras | produtoras |
| bilheteria | número de temporadas |

Cada palpite válido retorna uma linha com o candidato e uma comparação por coluna. Os estados são
`MATCH`, `PARTIAL`, `NO_MATCH` e `NO_DATA`; cores e ícones são responsabilidade da interface.

As regras são:

- conjuntos: igualdade exata é `MATCH`, interseção não vazia é `PARTIAL`, interseção vazia é
  `NO_MATCH`;
- classificação e diretor: igualdade exata é `MATCH`, diferença é `NO_MATCH`;
- criadores: algum criador em comum é `PARTIAL`, conjunto completo é `MATCH`;
- ano: igualdade é `MATCH`, diferença de até um ano é `PARTIAL`, diferença maior é `NO_MATCH`;
- bilheteria: diferença relativa de até 10% é `MATCH`, até 30% é `PARTIAL`, acima disso é
  `NO_MATCH`;
- elenco: um ou dois atores em comum são `PARTIAL`, três ou mais são `MATCH`;
- ausência de dado no TMDB é `NO_DATA`, nunca `NO_MATCH`.

Ano, bilheteria e temporadas carregam a direção `SECRET_HIGHER` ou `SECRET_LOWER` quando os
valores são diferentes. O segredo não é enviado em estado aberto. O retorno terminal revela o
snapshot completo.

Os jogos `by info` usam as linhas de comparação como pistas do jogo; as pistas textuais antigas
continuam disponíveis para modalidades que ainda as utilizam, mas não substituem a comparação.

## Jogos `ACTOR_BY_MOVIE_FILMOGRAPHY` e `ACTOR_BY_SERIES_FILMOGRAPHY`

O gerador escolhe um ator elegível e exige pelo menos duas obras elegíveis no modo correspondente.
O snapshot do ator secreto inclui IDs das obras, ano, gêneros, poster opcional e dados auxiliares.
Para séries inclui também episódios da participação, personagem e período quando o TMDB fornecer a
informação.

Após cada palpite de pessoa, o backend compara a filmografia do palpite com a do segredo. Obras
compartilhadas têm título e dados completos revelados e ficam marcadas como destacadas. Obras não
compartilhadas continuam com título oculto, mas mostram ano, gêneros e os campos auxiliares
permitidos. A lista de atores usados no jogo também é retornada.

O jogo de séries aceita `majorRoles=true|false` nas leituras e tentativas, com `true` como padrão.
O filtro é aplicado tanto ao ator secreto quanto ao palpite:

- até 6 episódios: major a partir de `ceil(totalEpisodes * 0,33)`;
- de 7 a 20 episódios: major a partir de `ceil(totalEpisodes * 0,40)`;
- acima de 20 episódios: major a partir de `ceil(totalEpisodes * 0,50)`;
- com `majorRoles=false`, toda participação com pelo menos um episódio entra.

O feedback transitório guarda as conexões dos dois modos, permitindo alternar o toggle sem nova
chamada ao TMDB. A confirmação do próprio ator encerra a partida como `COMPLETED`; o nome do ator
secreto permanece oculto em todos os outros estados abertos.

O adaptador TMDB lerá os campos de gênero e participação presentes em créditos agregados sem fazer
uma chamada por obra cinematográfica. Para séries, como o crédito agregado não informa o total de
episódios da série, ele consultará `TmdbTvFullDetails.numberOfEpisodes` uma vez por série distinta,
usando o cache existente do `TmdbClient`; esse valor será o denominador da fórmula de `Major roles`.
Quando o TMDB não fornecer nomes ou período, o campo será nulo ou usará o identificador disponível,
sem inventar informação.

## Respostas e segurança contra vazamento

Os estados e retornos de tentativa receberão blocos opcionais para:

- tentativas do dia atual;
- comparações de `by info`;
- filmografia redigida e obras reveladas;
- metadados terminais da série no jogo de episódio.

O contrato comum continuará contendo status, tentativas, score, imagens e resposta. Os blocos
específicos são omitidos ou redigidos quando a partida está aberta. O `answer` terminal usa o
snapshot de `daily_challenges`; nenhuma resposta aberta pode expor título, nome do ator secreto ou
valores completos do desafio.

Busca continua autenticada, sem CSRF para leituras. Tentativa e desistência continuam exigindo
cookie/header CSRF. IDs positivos, enums, datas e paginação passam pelo `ApiError` global. Falhas
de binding de Spring devem continuar sendo convertidas pelo `GlobalExceptionHandler`, sem `ProblemDetail`
vazando.

## Verificação

O desenvolvimento será test-first e cobrirá:

- rotas agregadas e específicas com o mesmo estado;
- desistência atual e histórica, sem consumir chance;
- corrida entre tentativa e desistência;
- busca hierárquica de série, temporada e episódio;
- fallback de geração entre episódio, temporada e série;
- rejeição de episódios secretos com menos de seis imagens;
- snapshot final da série no jogo de episódio;
- comparação de conjuntos, limites numéricos, direção e `NO_DATA`;
- revelação de filmes e séries compartilhados;
- limiares proporcionais de `Major roles` e alternância sem TMDB;
- limpeza GMT do JSONB transitório;
- ausência de resposta secreta em todos os estados abertos;
- regressão do endpoint agregado, histórico, ranking e modalidades não alteradas.

## Fora do escopo

- uma tabela exclusiva para cada jogo;
- um cache distribuído adicional;
- armazenamento indefinido de palpites;
- duplicação da resposta final em resultados de usuários;
- chamadas do frontend diretamente ao TMDB;
- alteração da fórmula de pontuação ou dos limites de tentativas existentes.
