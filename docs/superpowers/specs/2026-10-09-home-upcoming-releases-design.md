# Home: próximos lançamentos personalizados

**Data:** 2026-10-09  
**Status:** aprovado pelo usuário para detalhamento do design

## Contexto

A tela Home precisa de uma seção com os próximos seis conteúdos que serão lançados para o usuário autenticado. A seção deve seguir a mesma lógica do calendário existente: usar os interesses do usuário, os snapshots compartilhados de agenda, a região e o idioma preferidos, além dos mesmos tipos de evento e agrupamentos.

O repositório atual é o backend da aplicação. Esta mudança implementa o contrato e a montagem dos dados da API; a renderização visual depende do cliente da Home.

## Decisão

Adicionar `upcomingReleases` ao retorno de `GET /users/{userId}/summary/home`, com no máximo seis itens do tipo `CalendarEventDTO`.

A lista será personalizada pelo usuário que está visualizando a Home (`viewerId` autenticado), usando as mesmas fontes do calendário:

- filmes da watchlist;
- séries em andamento;
- séries presentes na watchlist;
- região e idioma preferidos do usuário;
- snapshots e regras de atualização já usados pelo calendário.

O `userId` da rota continua controlando o resumo e as regras de visibilidade. A nova lista não deve expor a agenda/interesses do usuário-alvo quando `viewerId` e `userId` forem diferentes.

## Comportamento

- Considerar eventos cuja data de lançamento seja hoje ou posterior, usando a data do relógio de domínio do calendário.
- Ordenar pela data de lançamento e reutilizar os mesmos desempates determinísticos do calendário.
- Preservar os agrupamentos existentes: `MOVIE`, `EPISODE`, `SEASON` e `SERIES`.
- Preservar `releaseStatus`, `watchStatus`, `sources`, `content` e `releaseContext` exatamente como no calendário.
- Retornar menos de seis itens quando não houver candidatos suficientes.
- Retornar uma lista vazia quando o usuário não tiver fontes de interesse ou quando não houver lançamentos futuros.
- Não criar entidade, tabela, referência `Content` ou endpoint novo.

Eventos lançados hoje continuam elegíveis para a seção porque o limite temporal é inclusivo. O campo `releaseStatus` continuará refletindo a regra atual do calendário: somente datas posteriores a hoje são `UPCOMING`; a data de hoje é `RELEASED`.

## Arquitetura e fluxo de dados

1. `SummaryServiceImpl.getHomeSummary` valida o usuário-alvo e a visibilidade como hoje.
2. O serviço solicita os próximos eventos ao serviço de calendário usando o `viewerId`.
3. O calendário lê as fontes de interesse do usuário, reutiliza snapshots, faz as atualizações remotas necessárias e monta os mesmos eventos usados por `getMonth`.
4. A montagem compartilhada separa a criação/ordenação dos eventos do filtro específico por mês. `getMonth` mantém o filtro de `YearMonth`; a nova leitura filtra por `releaseDate >= today`, ordena e limita a seis.
5. `SummaryServiceImpl` inclui a lista na construção de `HomeSummaryResponseDTO`.
6. Se o TMDB estiver indisponível durante a leitura da agenda, a Home permanece utilizável e recebe `upcomingReleases: []`, seguindo a tolerância já aplicada ao bloco de próximos episódios da Home. A tela de calendário continua com seu comportamento atual de erro.

O serviço de calendário deve evitar uma segunda implementação da atualização de snapshots. A alteração deve extrair ou reutilizar o pipeline comum de leitura/refresh, em vez de consultar o TMDB diretamente no serviço de Summary.

## Contrato da API

O schema `HomeSummary` em `docs/context/openapi.yaml` receberá:

```yaml
upcomingReleases:
  type: array
  maxItems: 6
  description: Até 6 próximos eventos de lançamento personalizados para o usuário que visualiza a Home.
  items:
    $ref: "#/components/schemas/CalendarEvent"
```

O campo será sempre serializado como array, inclusive quando vazio. Nenhuma chamada adicional do cliente será necessária.

## Alternativas consideradas

### Endpoint separado

Um endpoint exclusivo permitiria reutilização futura, mas adicionaria uma chamada à Home e um contrato que atualmente não tem outro consumidor previsto. Não foi escolhido.

### Montagem no cliente a partir do calendário mensal

O cliente teria de buscar vários meses, repetir filtragem, agrupamento e desempates, além de poder duplicar regras do backend. Não foi escolhido.

### Campo dentro de `HomeSummary`

É a opção escolhida: mantém a Home em uma única leitura, aproveita o domínio do calendário e garante que o cliente receba exatamente os mesmos eventos e metadados.

## Testes

Adicionar ou ajustar testes para cobrir:

- serviço de calendário retornando os seis primeiros eventos futuros em ordem;
- inclusão de eventos de hoje e exclusão de datas anteriores;
- preservação dos agrupamentos e metadados do calendário;
- retorno com menos de seis itens e retorno vazio;
- uso do `viewerId` como fonte dos interesses quando ele for diferente do `userId` da rota;
- indisponibilidade do TMDB não derrubando o `summary/home`;
- serialização do novo campo no controller e no teste de integração da Home;
- compatibilidade dos construtores existentes de `HomeSummaryResponseDTO`.

## Documentação sincronizada na implementação

Quando o código for implementado, atualizar no mesmo change:

- `docs/context/openapi.yaml`;
- `docs/context/telas.md` e/ou `docs/context/telas-reqs.md`, substituindo a descrição antiga do preview de calendário da Home;
- `docs/context/business-rules.md`, caso a regra de seleção da Home seja registrada como regra de domínio;
- `docs/context/progress.md`, somente depois que a implementação estiver efetivamente concluída.

