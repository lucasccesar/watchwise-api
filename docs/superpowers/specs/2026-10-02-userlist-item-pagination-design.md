# Paginação dos itens de UserList

## Objetivo

Paginar os itens retornados por `GET /lists/{listId}`. A lista continua sendo carregada com as
regras atuais de visibilidade, filtros e ordenação, mas a resposta passa a expor somente a página
solicitada e metadados suficientes para o cliente navegar pelo resultado.

O tamanho efetivo da página terá dois tetos definidos pelo `UserListItemScope` da lista:

- `EPISODE`: no máximo 24 itens;
- todos os demais escopos (`MOVIE_OR_SERIES`, `SEASON`, `LIST` e `MIXED`): no máximo 30 itens.

O limite é aplicado ao tamanho recebido na requisição. O tamanho omitido usa o default global de
20 itens, portanto permanece abaixo dos dois tetos. Valores menores continuam válidos; valores
maiores são limitados ao teto do escopo.

## Desenho aprovado

### Contrato HTTP

`GET /lists/{listId}` receberá os parâmetros opcionais `page` e `size`, seguindo a convenção
existente de paginação: `page` é 1-based na API e `size` aceita valores positivos. Filtros atuais
(`type` e `genre`) e ordenação atual (`sortBy`/`sortDirection`) serão preservados.

A sequência será:

1. carregar os itens autorizados da lista;
2. aplicar `type` e `genre`;
3. aplicar a ordenação solicitada;
4. resolver o `itemScope` da lista inteira, sem inferi-lo da página ou do resultado filtrado;
5. criar a paginação com teto 24 ou 30;
6. recortar a página resultante;
7. devolver os itens recortados junto dos metadados.

O recorte será feito depois dos filtros e da ordenação, mantendo a ordem determinística atual.
Uma página solicitada além do fim retorna conteúdo vazio, sem erro.

### Formato da resposta

`UserListDetailedResponseDTO.items` continuará sendo um array para evitar uma mudança desnecessária
no formato do campo existente. A resposta ganhará campos de metadados específicos dos itens,
reutilizando os mesmos campos de `PageResponseDTO`:

```json
{
  "items": [],
  "itemsPage": 1,
  "itemsSize": 24,
  "itemsTotalElements": 51,
  "itemsTotalPages": 3,
  "itemsHasNext": true
}
```

`itemsTotalElements` e `itemsTotalPages` representam o resultado após `type` e `genre`; a
ordenação não altera a contagem, enquanto `itemsCount`, `watchedPercentage`,
`totalRuntimeMinutes` e os demais agregados existentes continuam representando a lista inteira,
como fazem hoje. A resposta de criação em bulk continuará devolvendo todos os itens criados e
usará metadados de uma única página contendo esse resultado completo.

### Camadas

- `UserListController`: recebe `page` e `size` na rota de detalhe e encaminha os valores.
- `UserListService`: recebe a paginação, resolve o escopo antes do recorte e constrói uma página
  em memória depois do pipeline existente de filtro/ordenação.
- `UserListDetailedResponseDTO`: adiciona os cinco campos de metadados dos itens.
- `UserListMapper`: recebe os itens da página e os metadados necessários ao novo DTO.
- `PageRequestFactory`: fornece validação de página, default global e clamp do teto específico;
  nenhum limite existente de `/users/{userId}/lists` será alterado.

A consulta de itens continuará sendo a consulta em lote já usada pelo detalhe. A paginação em
memória é deliberada nesta mudança porque filtros e ordenações, inclusive médias de episódios,
já são aplicados em memória; mover parte do pipeline para a consulta exigiria alterar a semântica
dos sorts e o enriquecimento de estado.

## Testes

Serão adicionados ou ajustados testes para:

- encaminhamento de `page` e `size` pelo controller;
- página 1 e página posterior com itens preservando a ordem após filtro/ordenação;
- página além do fim retornando vazia e mantendo a contagem total;
- lista `EPISODE` limitando `size` a 24;
- listas `MOVIE_OR_SERIES`, `SEASON`, `LIST` e `MIXED` limitando `size` a 30;
- tamanho omitido usando 20 e tamanho menor que o teto permanecendo válido;
- filtros reduzindo `itemsTotalElements` sem alterar `itemsCount` da lista inteira;
- agregados e progresso permanecendo baseados na lista inteira;
- atualização do contrato OpenAPI, das regras de negócio e do progresso cronológico.

## Alternativas consideradas

1. Paginar somente listas `EPISODE`: rejeitado porque deixaria o mesmo endpoint com formatos e
   semânticas diferentes entre os tipos.
2. Alterar o limite da listagem de entidades em `GET /users/{userId}/lists`: rejeitado porque essa
   é a paginação das entidades `UserList`, atualmente limitada a 10 por `a8490c3`, e não a
   paginação dos itens de uma lista individual.
3. Mudar `items` para um objeto `PageResponseDTO`: rejeitado nesta etapa porque quebraria o formato
   existente do array; os metadados serão adicionados ao lado do array.

## Auto-revisão

- Não há pendência de implementação ou decisão em aberto neste desenho.
- O teto é resolvido pelo escopo da lista inteira, evitando que um filtro `type=EPISODE` transforme
  uma lista de outro escopo em uma lista EPISODE para fins de paginação.
- Os agregados não serão calculados a partir da página, evitando percentuais e contagens falsos.
- O limite de 10 da listagem normal de `UserList` permanece fora do escopo.
