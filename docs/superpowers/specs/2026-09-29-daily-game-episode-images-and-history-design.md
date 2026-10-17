# Jogos diários: imagens de episódios, histórico jogável e ranking

## Objetivo

Evoluir o jogo EPISODE_BY_FRAME para usar uma sequência determinística de imagens do episódio,
permitir a visualização das imagens já exibidas, tornar os dias anteriores jogáveis e expor no ranking
a quantidade de jogos concluídos junto do usuário e da pontuação.

## Decisões

### Imagens

Na geração de um desafio EPISODE_BY_FRAME, a API consulta o endpoint de imagens do episódio do TMDB
e recebe o array stills. Os itens sem file_path e duplicatas exatas são removidos, a ordem final é
invertida e somente os file_path relativos são persistidos no display_snapshot JSONB como imagePaths.
O primeiro caminho também permanece em image_path como fallback para desafios legados.

A consulta do TMDB ocorrerá uma vez durante a geração e terá cache Caffeine de 24 horas. O cache não
será a fonte de verdade: as tentativas usarão somente o snapshot persistido. Se a consulta de imagens
não retornar caminhos utilizáveis, o still_path válido do endpoint da temporada será usado como uma
sequência de uma imagem.

EPISODE_BY_FRAME terá exatamente seis tentativas. A posição visível será
min(attemptsUsed, imagePaths.size() - 1). Assim, seis imagens distintas ocupam as seis posições quando
disponíveis; com menos imagens, a última é repetida. A resposta aberta carregará visibleImageUrls com
todas as posições já exibidas. Em uma vitória exata, imageUrls conterá todas as imagens do snapshot
para o jogador autenticado. Resultados falhos manterão somente as imagens já exibidas.

### Acerto do palpite

O retorno da tentativa terá feedback transitório com seriesCorrect, seasonCorrect, episodeCorrect e
exactMatch. Cada campo será comparado individualmente; exactMatch só será true quando série, temporada
e episódio coincidirem. O feedback não será persistido como histórico de tentativas.

### Histórico jogável

O resultado do usuário continuará agregado: attemptsUsed, score, status, completedAt e timestamps.
Não haverá tabela ou JSON com palpites, imagens vistas ou sequência de tentativas.

Além das rotas atuais de hoje, serão adicionadas GET /games/{challengeDate} e
POST /games/{challengeDate}/{gameType}/attempt. Somente hoje e datas passadas serão aceitas.
Sem resultado persistido, o usuário inicia; com IN_PROGRESS, retoma; com COMPLETED ou FAILED, uma nova
tentativa gera conflito. As rotas atuais de hoje continuam funcionando como compatibilidade.

### Ranking

O ranking continuará considerando somente resultados terminais COMPLETED e FAILED. O ranking geral
agrupará todos os tipos; o ranking por modalidade filtrará game_type. A ordenação atual por pontuação
decrescente e tentativas agregadas crescentes será preservada. O DTO trocará attemptsUsed por
gamesPlayed, calculado por COUNT(*) dos resultados terminais incluídos.

## Segurança, desempenho e compatibilidade

A sequência global ficará no desafio, mas imageUrls completas só serão montadas em uma resposta do
próprio usuário em estado COMPLETED. Feed, ranking e histórico resumido não receberão a lista completa.
Palpites não gerarão chamadas externas ao TMDB. Desafios legados sem imagePaths usarão image_path.
Documentação do contrato, schema, regras e progresso será atualizada sem commit, conforme AGENTS.md.

## Testes

Cobrir desserialização e cache de imagens, inversão/deduplicação/fallback, seis tentativas, repetição
somente quando necessário, histórico visível, feedback parcial, datas passadas, bloqueio de replay,
ranking geral e por modalidade, exclusão de IN_PROGRESS, soma de pontuação e contagem de jogos.
