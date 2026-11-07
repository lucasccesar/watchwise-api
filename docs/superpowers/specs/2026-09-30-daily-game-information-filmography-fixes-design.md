# Correções dos jogos diários de informação e filmografia

## Objetivo

Corrigir os contratos de `MOVIE_BY_INFO`, `SERIES_BY_INFO`,
`ACTOR_BY_MOVIE_FILMOGRAPHY` e `ACTOR_BY_SERIES_FILMOGRAPHY` para que os jogos
não vazem a imagem da resposta, não exibam hints indevidos, projetem a
filmografia completa com títulos redigidos e comparem metadados reais sem
produzir `NO_DATA` por snapshots antigos incompletos.

## Diagnóstico confirmado

- `DailyChallengeResponseAssembler.view` calcula `imageUrl` e
  `visibleImageUrls` para qualquer modalidade. Isso expõe a imagem do alvo em
  jogos que deveriam ser baseados apenas em metadados ou filmografia.
- `MovieByInfoGenerator` e `SeriesByInfoGenerator` persistem hints com os
  mesmos metadados que deveriam ser revelados progressivamente pelo feedback
  das tentativas.
- `DailyGameInfoComparisonServiceImpl` compara exclusivamente o
  `answerSnapshot`. Desafios criados antes da inclusão dos campos de
  comparação não possuem `platforms`, `genres`, `year` e demais campos, logo
  cada célula resulta em `NO_DATA`.
- `DailyChallengeResponseAssembler.filmography` também depende de
  `answerSnapshot.filmography`. Desafios antigos criados antes desse campo
  retornam uma lista vazia, embora o fluxo novo já saiba montar as obras.
- A regra de títulos ocultos está correta conceitualmente: uma obra não
  compartilhada deve retornar metadados auxiliares e `title: null`; somente a
  interseção com um ator escolhido revela o título.

## Desenho aprovado

### Política de exposição

`DailyChallengeResponseAssembler` terá uma política explícita por modalidade:

- Em partidas abertas de `MOVIE_BY_INFO`, `SERIES_BY_INFO` e ambos os jogos de
  filmografia, `imageUrl` será `null` e `visibleImageUrls` será vazio.
- Em estado terminal, a resposta congelada poderá revelar a imagem por meio de
  `answer.imageUrl`, preservando a conclusão do jogo.
- As modalidades visuais existentes (`MOVIE_BY_POSTER`, `SERIES_BY_POSTER`,
  `PERSON_BY_FACE` e `EPISODE_BY_FRAME`) manterão o comportamento atual.

### Jogos por informação

- Os geradores não criarão `DailyChallengeHint` para os jogos de informação.
- O snapshot secreto continuará armazenando plataformas, gêneros, ano,
  certificação, diretor/criadores, elenco, produtoras e receita/temporadas.
- A camada de resposta ignorará hints legados desses jogos, evitando que dados
  antigos continuem sendo vazados.
- A geração rejeitará candidatos sem metadados comparáveis suficientes, sem
  fabricar valores ausentes.
- A comparação continuará sendo feita contra o snapshot congelado e manterá
  `MATCH`, `PARTIAL`, `NO_MATCH` e `NO_DATA` somente quando um dos lados
  realmente não possuir o dado.

### Jogos de filmografia

- O snapshot secreto conterá a filmografia normalizada do ator sorteado.
- A leitura inicial retornará todas as obras elegíveis, com ano, gêneros,
  poster, período, personagem e contagem de episódios quando disponíveis.
- `title` permanecerá `null` e `revealed`/`highlighted` permanecerão falsos
  até que a obra apareça na interseção com um palpite.
- Obras compartilhadas revelarão apenas o próprio título e seus indicadores de
  revelação; o nome do ator secreto continuará protegido até o estado terminal.
- A projeção `majorRoles` continuará sendo aplicada somente ao jogo de séries,
  conforme o parâmetro existente.

### Reparo de snapshots legados

Será criado um fluxo de reparo idempotente (repetir a operação não muda a
resposta) dentro da geração dos desafios:

- Um desafio existente será identificado como incompleto quando faltar o
  snapshot de comparação ou a chave `filmography` esperada pela modalidade.
- O reparo consultará o TMDB usando os IDs já congelados, reconstruirá somente
  os campos ausentes e persistirá o JSONB atualizado.
- `answerKey`, coordenadas, imagem, data e qualquer campo já presente não serão
  substituídos.
- O reparo não será feito como fallback silencioso em cada leitura, evitando
  que um desafio congelado dependa de alterações atuais do TMDB.
- Falha temporária do TMDB não apagará dados existentes nem gravará um snapshot
  parcial; o desafio permanecerá reparável na próxima execução.

## Fluxo de dados

1. O job diário chama `ensureGenerated`.
2. Para cada modalidade existente, o serviço verifica se o snapshot está
   completo.
3. Se estiver incompleto, o serviço hidrata somente os campos ausentes a
   partir do alvo já persistido e salva o mesmo desafio.
4. Para uma modalidade ausente, o fluxo normal gera um novo candidato com
   snapshot completo e sem hints indevidos.
5. As leituras projetam a política de exposição sem consultar o TMDB.
6. Uma tentativa válida compara o candidato ao snapshot congelado e persiste
   o feedback tipado.

## Erros e segurança

- Nenhuma resposta aberta poderá conter título, nome ou imagem do alvo secreto
  fora dos campos explicitamente permitidos.
- O reparo nunca trocará a identidade de um desafio já publicado.
- `NotFound` e indisponibilidade do TMDB seguirão o tratamento já existente;
  uma falha de reparo não transformará um snapshot antigo em dados inventados.
- Hints legados serão filtrados também na montagem da resposta, garantindo
  defesa em profundidade além da correção dos geradores.

## Testes

Serão adicionados ou ajustados testes para:

- ocultação de imagem nos quatro jogos não visuais enquanto estão abertos;
- preservação da imagem nas modalidades visuais e em respostas terminais;
- ausência de hints nos jogos por informação, inclusive quando hints legados
  são fornecidos ao assembler;
- snapshots novos de informação completos e sem hints persistidos;
- comparação de todos os campos com `MATCH`, `PARTIAL`, `NO_MATCH` e
  `NO_DATA` apenas quando apropriado;
- filmografia inicial com títulos ocultos e metadados presentes;
- revelação de obras compartilhadas após tentativas;
- reparo de snapshots antigos, preservação de identidade e comportamento
  idempotente;
- falha de TMDB durante o reparo sem perda de dados.

## Documentação sincronizada

Depois da implementação, serão atualizados `docs/context/openapi.yaml`,
`docs/context/business-rules.md` e a entrada cronológica de
`docs/context/progress.md`. A especificação de design permanece como documento
de trabalho e não será incluída em commit separado, conforme a convenção deste
repositório para alterações somente em documentação.
