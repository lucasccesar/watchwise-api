# Integração da interface com importações

## Disponibilidade do modo

O backend aceita somente combinações `inputKind` + `sourceFormat` registradas em `ImportModeRegistry`.
Os modos ZIP `PLATFORM_ARCHIVE` + `TRAKT_ZIP` e `PLATFORM_ARCHIVE` + `LETTERBOXD_ZIP`, os modos
avulsos `SINGLE_LIST` e `SINGLE_WATCHLIST` para Trakt/Letterboxd e `PICKS_TEMPLATE` +
`WATCHWISE_TEMPLATE_JSON` estão registrados no runtime.

## Autenticação e envio

Todas as rotas exigem o cookie JWT `access_token`. Requisições mutáveis também enviam o cookie
`XSRF-TOKEN` e seu valor no header `X-XSRF-TOKEN`. O arquivo é enviado como `multipart/form-data`
em `POST /imports`; o nome original nunca é usado como caminho de armazenamento.

| inputKind | sourceFormat | platform | Arquivo |
| --- | --- | --- | --- |
| `PLATFORM_ARCHIVE` (default) | `TRAKT_ZIP` | `TRAKT` | ZIP do Trakt |
| `PLATFORM_ARCHIVE` (default) | `LETTERBOXD_ZIP` | `LETTERBOXD` | ZIP do Letterboxd |
| `SINGLE_LIST` | `TRAKT_JSON` | `TRAKT` | JSON de uma lista Trakt |
| `SINGLE_LIST` | `LETTERBOXD_CSV` | `LETTERBOXD` | CSV de uma lista Letterboxd |
| `SINGLE_WATCHLIST` | `TRAKT_JSON` | `TRAKT` | JSON de watchlist Trakt |
| `SINGLE_WATCHLIST` | `LETTERBOXD_CSV` | `LETTERBOXD` | CSV de watchlist Letterboxd |
| `PICKS_TEMPLATE` | `WATCHWISE_TEMPLATE_JSON` | omitido | JSON de template Watchwise |

`sourceFormat` é obrigatório. `inputKind` pode ser omitido apenas para manter o fluxo de ZIPs;
`platform` é obrigatório nos formatos de plataforma e deve ser omitido para template. `sourceAccountId`
é opcional e identifica a conta externa para preservar o mesmo namespace entre arquivos avulsos e ZIPs.
`timeZone` é opcional e assume `UTC`. Extensão incompatível ou Content-Type explicitamente incompatível
é rejeitado. `application/octet-stream` é aceito quando o navegador não determina o tipo.

Para um ZIP sem `sourceAccountId` no upload, a seleção deve incluir `metadata.sourceAccount` antes da
normalização. Esse identificador é a chave estável que o usuário informa para reconhecer a conta nos
próximos exports; o backend não consulta contas remotas nem infere a identidade pelo nome do arquivo.
Importações são snapshots manuais: não há sincronização automática com Trakt ou Letterboxd; para trazer
alterações posteriores, o usuário exporta e envia um novo ZIP. Uma entrada de origem já consumida é
ignorada integralmente numa reimportação, mesmo quando um ZIP posterior altera seus campos; entradas
distintas que ainda não foram consumidas continuam sendo importadas.

O limite geral é 50 MiB por arquivo e 51 MiB por request multipart; o JSON de template Picks tem
limite máximo de 5 MiB, que `app.import.max-template-upload-bytes` pode reduzir. Há quotas configuráveis de 3 jobs e
150 MiB pendentes por usuário, 2 GiB globais e 2 uploads simultâneos por processo. O staging multipart
usa diretório temporário privado, separado do storage durável. O backend reserva bytes no banco antes
da cópia ao storage; falhas marcam o job `FAILED`, liberam a quota e enfileiram limpeza idempotente.
Prévia não confirmada expira após 24h; arquivos/jobs expirados são limpos a cada 15 minutos por padrão.
Um commit aceito antes do prazo pode terminar depois dele. Se uma revalidação atrasada exigir nova decisão,
o job volta a `RESOLVING` com uma versão nova e prazo renovado.

## Fluxo da interface

1. Envie o arquivo e os campos de formato; `202 Accepted` retorna `ImportJobDTO` com `id`, estado,
   versão e digest. Não existe `storageKey` nem caminho de arquivo na resposta. Após a inspeção de um
   ZIP, `inventory` contém as categorias e listas disponíveis; o campo é nulo antes da inspeção e não
   é usado nos modos avulsos nem em templates Picks.
2. Consulte `GET /imports/{importId}` para observar estado e `previewVersion`. O ID é owner-scoped;
   job inexistente e job de outra conta retornam o mesmo `404`.
3. Para um ZIP, aguarde `AWAITING_SELECTION` e mostre `inventory.categories`/`inventory.lists`.
   Confirme somente `history`, `watchlist` e `listIds` desejados em `PUT /imports/{importId}/selection`. Não é necessário
   enviar essa seleção nos modos avulsos ou em templates Picks; `metadata` pode carregar o nome/descrição
   de destino quando forem necessários.
4. Mostre `GET /imports/{importId}/preview?page=0&size=20`, paginado. O parâmetro `page` é 1-based,
   aceita `0` como alias da primeira página e assume `0`; o campo `page` da resposta é zero-based.
   Depois da primeira página, envie `page=2` e incremente a cada avanço. Cada item tem `itemId`, `ordinal`,
   tipo, decisão, outcome, issue, alvo tipado resolvido e, para histórico, `watchedDate`, `watchedAt` e
   `datePrecision` (`TIMESTAMP`, `DAY` ou `UNKNOWN`). A resposta não inclui payload bruto, locadores internos
   nem caminhos de arquivos. Para itens com review, `reviewText` é texto simples normalizado; `reviewTextLength`
   informa o tamanho completo e `reviewTextTruncated` indica se a prévia foi limitada a 20.000 caracteres.
   Para metadados de lista, `itemTitle`/`itemTitleLength`/`itemTitleTruncated` projetam o nome em até 255
   caracteres; para itens de lista, `itemDescription`/`itemDescriptionLength`/`itemDescriptionTruncated`
   projetam a nota em até 400 caracteres. Use os comprimentos/sinalizadores para indicar correções pendentes.
5. Envie resoluções tipadas em `PATCH /imports/{importId}/resolutions`, sempre com a versão atual e
   `itemId` recebido na prévia. Operações têm limite de 200 por requisição; qualquer alteração produz
   nova `previewVersion` e novo digest, então atualize a prévia antes da confirmação.
6. Só habilite confirmar quando o job estiver `READY`. Envie `{ "previewVersion": n, "digest": "..." }`
   a `POST /imports/{importId}/commit`. O digest é a identidade da prévia, não um segredo. Repetir o
   mesmo commit é idempotente; versão/digest diferente retorna `409` sem iniciar outra aplicação.
7. Consulte estado/progresso e `GET /imports/{importId}/result?page=0&size=20`. Resultado é paginado
   e pode ser lido enquanto o job executa. `POST /imports/{importId}/cancel` cancela entre unidades;
   não desfaz unidades já aplicadas. `POST /imports/{importId}/retry` só aceita jobs `FAILED` cujo
   arquivo privado ainda exista; o retry revalida a quota de jobs/bytes atomicamente e pode retornar
   `429` sem alterar o job falho.

### Templates de Picks (PICKS_TEMPLATE)

Envie o JSON por `POST /imports` com `inputKind=PICKS_TEMPLATE` e
`sourceFormat=WATCHWISE_TEMPLATE_JSON`; omita `platform`, `sourceAccountId` e `PUT /selection`. O job
normaliza o arquivo e resolve os indicados automaticamente. Só habilite commit quando chegar a `READY`.
O arquivo segue o schema [picks-template-v1.schema.json](../../src/main/resources/import/picks-template-v1.schema.json);
`schemaVersion` pode ser omitido e assume `1`. O parser rejeita chaves duplicadas, propriedades fora do
schema e JSON acima do limite de 5 MiB, profundidade 32, 100 categorias, 500 indicados por categoria ou
5.000 no total. Schema/body inválido retorna `400` antes de reservar quota ou criar job; arquivo acima
do limite máximo retorna `413`.

Exemplo de conteúdo aceito:

```json
{
  "title": "Classics Club Awards",
  "description": "Crown the best movies, shows and performances from 1960 to 2009.",
  "coverImage": "https://example.com/covers/classics-club.jpg",
  "eligibility": {"start": "1960-01-01", "end": "2009-12-31"},
  "categories": [
    {"name": "Best movie", "type": "movie"},
    {"name": "Best sequel", "type": "movie", "nominees": [
      "Back to the Future Part II", "The Empire Strikes Back", "Revenge of the Sith"
    ]},
    {"name": "Best series", "type": "series"},
    {"name": "Best episode", "type": "episode"},
    {"name": "Best lead performance", "type": "person", "nominees": [
      "Michael J. Fox", "Christopher Lloyd", "Lea Thompson", "Crispin Glover"
    ]}
  ]
}
```

Sem `nominees`, a categoria fica `OPEN`; com um array não vazio, fica `FIXED`. Texto é uma consulta
limitada ao tipo da categoria. Objetos fortes usam `tmdbId` para filme/série, `personTmdbId` para pessoa
e `seriesTmdbId` + `seasonNumber` + `episodeNumber` para episódio. Pessoas só recebem o
`contextContent` informado no arquivo; não associe uma pessoa a um filme/série por inferência.

Na prévia, `TEMPLATE` é o template, `TEMPLATE_CATEGORY` é uma categoria e `TEMPLATE_OPTION` é um
indicado. `parentItemId` liga categorias ao template e indicados à categoria; `targetType` informa o
tipo permitido. `itemTitle` contém um rótulo seguro do indicado, e `resolvedTarget` contém o alvo tipado
ou candidatos, nunca o JSON bruto. Se um indicado por nome/texto não for encontrado ou for ambíguo, use
`PATCH /imports/{importId}/resolutions` com `RESOLVE_TARGET`, passando `candidateId` ou um `target`
tipado. IDs TMDB que não existam e opções fora da elegibilidade também permanecem como decisões na prévia.
Se o item só tiver identidade forte (`tmdbId`, `personTmdbId` ou coordenadas de episódio), sem query
textual no arquivo, remova-o com `REMOVE_NOMINEE` ou envie outro arquivo com a identidade corrigida; não
confirme um target alternativo sem uma query de origem associada.

O item `TEMPLATE` mostra `templateEligibilityStart`/`templateEligibilityEnd`. Se editar uma data deixar
somente um limite ou inverter a ordem, a prévia marca `TEMPLATE_ELIGIBILITY_PERIOD_INVALID`; envie outra
operação `EDIT_FIELD` com a versão nova para completar/corrigir o par antes de confirmar.

Uma string de episódio sem alias permanece pendente. Pesquise a série com `GET /search?q=...&type=SERIES`,
liste suas temporadas em `GET /contents/selection/series/{seriesTmdbId}/seasons`, depois seus episódios
em `GET /contents/selection/series/{seriesTmdbId}/seasons/{seasonNumber}/episodes`. Envie a escolha como
`target.kind=CONTENT` com `content.type=EPISODE`, `seriesTmdbId`, `seasonNumber` e `episodeNumber`; não use
um ID plano de episódio.

Indicado repetido na mesma categoria fixa recebe `DUPLICATE_TEMPLATE_NOMINEE`; remova uma cópia com
`REMOVE_NOMINEE` ou resolva-a para outro alvo. Uma categoria fixa precisa manter ao menos um indicado;
use `SET_CATEGORY_OPEN` com `open=true` para convertê-la em aberta ou `REMOVE_CATEGORY`. O template precisa
manter ao menos uma categoria. Para abandonar o template inteiro, cancele o job.

O commit cria somente o template, suas categorias e opções fixas: não cria Picks, seleções, reviews,
eventos do diário ou itens de watchlist. Reenviar a mesma estrutura semântica é idempotente e preserva
edições locais; a ordem de indicados não altera a identidade, mas a ordem de categorias altera. Um template
excluído ou desvinculado recebe tombstone (marcador que impede restauração) para a mesma origem.

Para o histórico Letterboxd, a prévia inclui a linha pai `SERIES_EXPANSION` e os episódios regulares
expandidos como itens `DIARY_EVENT`, antes de confirmar a importação. A coluna Letterboxd `Date` indica
quando o registro foi criado e não deve ser apresentada como dia assistido; use `Watched Date`. Um episódio
lançado depois dessa data continua na lista sem data, com `datePrecision: "UNKNOWN"`; timestamps do Trakt
usam `TIMESTAMP`, datas sem horário usam `DAY`. Conflitos de passada aparecem no item do episódio para
`AssignPass`; se os filhos terminarem em passadas diferentes, uma review/nota do pai exige `SkipItem` explícito
para ser descartada. Sem opinião no pai, não há confirmação extra.

Depois que `EXPANSION_PREPARED` é exibido, o alvo da série e o `Watched Date` do pai estão congelados para
manter os itens filhos consistentes. Se essa correspondência/data precisar ser corrigida, cancele o job e
envie um novo arquivo. A review do pai pode continuar sendo editada; a passada não, pois ela é resolvida
separadamente nos itens de episódio.

Para Trakt, um item `WATCHED_SUMMARY` sem evento detalhado correspondente pode trazer `issue` informativo
com outcome `MATCHED`; isso não impede o commit. O resumo não cria `DiaryEntry` nem episódios. Quando nenhum
evento Watchwise correspondente já existe, ele permanece como assertion de cobertura `UNKNOWN`, com `plays`
e última data declarada. Uma nota/review sem prova de passada completa é preservada como opinião adiada, sem
alterar ratings/reviews locais.

Um `watched.csv` Letterboxd sem evento detalhado correspondente, ou um marcador de conteúdo assistido do
Trakt, cria apenas `ImportedWatchAssertion` com cobertura `UNKNOWN`: sem data/`plays` inferidos e sem
`DiaryEntry`. Se o diário Watchwise já contiver evento detalhado equivalente, o marcador é ignorado.
Quando o usuário seleciona somente watchlist, os marcadores lidos como evidência auxiliar apenas filtram
candidatos; não criam diário, assertion ou vínculo de origem.

Uma opinião `CURRENT_RATING`/`REVIEW` pode mostrar `OPINION_EVIDENCE_PENDING` com outcome `MATCHED` para
avisar que a nota/review só será restaurada se houver uma passada completa comprovada. Reviews acima de
20.000 caracteres usam `REVIEW_TEXT_TOO_LONG` e mantêm a unidade pendente até ser editada ou ignorada.

Reviews importadas aparecem em `reviewText` normalizado e devem ser renderizadas como texto escapado, nunca
como HTML. Se `reviewTextTruncated=true`, o texto original completo continua no staging privado enquanto a
review aguarda edição; o usuário pode substituir o texto por uma versão de até 20.000 caracteres antes do
commit. Um item com `REVIEW_TEXT_TOO_LONG` mantém o outcome de espera até ser editado para caber no limite ou
ser explicitamente ignorado; não confirme enquanto houver essa decisão pendente. Não corte o conteúdo
silenciosamente.

Listas importadas são públicas. A prévia mostra nome, descrição, contagem e posição dos itens; `itemDescription`
mostra a nota do item em texto simples. Uma lista selecionada sem itens continua sendo criada vazia. Uma nota
acima de 400 caracteres ou metadado acima do limite aparece como decisão pendente e deve ser corrigida na
prévia, sem truncamento.

Ao reenviar um manifesto de lista idêntico, o Watchwise ignora a operação já aplicada. Se a mesma lista de
origem ganhou itens, o destino existente é reutilizado e somente membros novos são anexados; nome, descrição,
notas e ordem dos itens já existentes permanecem como estão. A ordem dos novos itens segue `rank`/posição da
origem. Conteúdo duplicado na lista é ignorado com motivo visível. Excluir localmente um item importado cria
um tombstone, portanto o próximo ZIP não o restaura.

Watchlist aceita somente filmes e séries elegíveis e acrescenta os novos itens ao fim da ordem global. Conteúdo
que já está na watchlist, conteúdo assistido no Watchwise ou marcado como assistido no conjunto de origem aparece
como ignorado; temporadas/episódios não são convertidos para séries. Excluir localmente um item importado também
impede que o mesmo registro da origem o restaure.


Estados possíveis: `ANALYZING`, `AWAITING_SELECTION`, `RESOLVING`, `READY`, `IMPORTING`, `COMPLETED`,
`COMPLETED_WITH_WARNINGS`, `FAILED`, `CANCELLED` e `EXPIRED`. Não interprete `202` como conclusão.

Erros seguem o formato `ApiError` do backend (falhas de validação de DTO podem usar
`ValidationApiError`): `400` entrada/formato/modo inválido, `401` autenticação, `403` CSRF, `404` job
ausente ou alheio, `409` estado/versão/digest conflitante, `413` excesso de tamanho e `429` concorrência
ou quota. A mensagem não deve ser usada como identificador estável de erro.

## Exemplos de prévia, resolução e resultado

Os exemplos abaixo são parciais: UUIDs e dados de mídia são ilustrativos. `inventory` lista somente o
conteúdo encontrado no arquivo; uma lista vazia continua sendo selecionável quando aparece com
`itemCount: 0`.

```json
{
  "categories": [
    { "kind": "DIARY_EVENT", "count": 1, "available": true },
    { "kind": "WATCHLIST_ITEM", "count": 0, "available": false }
  ],
  "lists": [
    {
      "sourceId": "42",
      "name": "Favorites",
      "description": "",
      "itemCount": 1,
      "coverage": null
    }
  ]
}
```

Uma expansão de série Letterboxd aparece como pai `SERIES_EXPANSION` e filhos de episódios. Para uma
data ausente, o episódio permanece visível com `watchedDate: null` e `datePrecision: "UNKNOWN"`.

```json
{
  "itemId": "00000000-0000-4000-8000-000000000001",
  "ordinal": 10,
  "kind": "SERIES_EXPANSION",
  "outcome": "EXPANSION_PREPARED",
  "issue": null,
  "resolvedTarget": {
    "kind": "CONTENT",
    "content": { "type": "SERIES", "tmdbId": "1396" }
  },
  "watchedDate": "2024-04-03",
  "datePrecision": "DAY",
  "watchScopePreview": {
    "available": true,
    "issue": null,
    "scheduleVersion": "schedule-v17",
    "validUntil": "2026-10-26T12:00:00Z",
    "releaseComplete": true,
    "episodes": [{ "seasonNumber": 1, "episodeNumber": 1 }]
  }
}
```

O exemplo abreviado do filho mostra a referência composta que será gravada:

```json
{
  "itemId": "00000000-0000-4000-8000-000000000002",
  "ordinal": 11,
  "kind": "DIARY_EVENT",
  "outcome": "MATCHED",
  "watchedDate": null,
  "datePrecision": "UNKNOWN",
  "resolvedTarget": {
    "kind": "CONTENT",
    "content": {
      "type": "EPISODE",
      "seriesTmdbId": "1396",
      "seasonNumber": 1,
      "episodeNumber": 1
    }
  }
}
```

Se um item `CURRENT_RATING` tiver `issue` começando por `LATEST_REVIEW_AMBIGUOUS`, as datas disponíveis
não permitem decidir qual visualização é a mais recente. O usuário pode corrigir as datas dos eventos na
prévia quando souber os valores ou descartar a nota com `SKIP_ITEM`; não confirme enquanto o item
continuar com `NORMALIZATION_ISSUE`.

```json
{
  "itemId": "00000000-0000-4000-8000-000000000003",
  "ordinal": 12,
  "kind": "CURRENT_RATING",
  "outcome": "NORMALIZATION_ISSUE",
  "issue": "LATEST_REVIEW_AMBIGUOUS: The latest viewing date is tied without enough time information to choose an event."
}
```

Uma confirmação de alcance usa a versão da agenda e a lista exata de episódios mostrada na prévia. Mesmo
`ALL_RELEASED_EPISODES` envia as referências não vazias, para o backend validar que a agenda não mudou.

```json
{
  "previewVersion": 4,
  "operations": [
    {
      "operation": "CONFIRM_WATCH_SCOPE",
      "itemId": "00000000-0000-4000-8000-000000000001",
      "scope": "ALL_RELEASED_EPISODES",
      "scheduleVersion": "schedule-v17",
      "episodeReferences": [
        {
          "type": "EPISODE",
          "seriesTmdbId": "1396",
          "seasonNumber": 1,
          "episodeNumber": 1
        }
      ]
    }
  ]
}
```

Uma decisão de ignorar é explícita e não importa o item:

```json
{
  "previewVersion": 5,
  "operations": [
    {
      "operation": "SKIP_ITEM",
      "itemId": "00000000-0000-4000-8000-000000000003"
    }
  ]
}
```

Resultado parcial retorna `COMPLETED_WITH_WARNINGS`; consulte suas páginas para ver quais fatos foram
aplicados e quais foram ignorados, com o motivo em `issue`.

```json
{
  "importId": "00000000-0000-4000-8000-000000000010",
  "state": "COMPLETED_WITH_WARNINGS",
  "items": [
    {
      "itemId": "00000000-0000-4000-8000-000000000003",
      "kind": "CURRENT_RATING",
      "outcome": "SKIPPED",
      "issue": "Skipped by the user."
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

## Contrato do diário para séries e temporadas

`GET /users/{userId}/diary/view` combina eventos persistidos e conclusões calculadas. `PERSISTED_ENTRY`
representa apenas MOVIE/EPISODE. Quando os episódios assistidos comprovam uma conclusão de SERIES/SEASON,
a resposta inclui `DERIVED_COMPLETION`, com `diaryEntryId: null`, `completionKey` estável para o conteúdo e
a passada, `watchNumber`, `currentScore`, `reviewCount` e até três `reviewPreviews`. A conclusão não é um
registro gravável nem um alvo social; likes/comentários continuam ligados a reviews persistidas.

`POST /diary/bulk` de SERIES/SEASON cria DiaryEntry EPISODE reais e devolve os eventos criados mais os
descritores de conclusão derivados. EPISODEs criados abaixo do nível solicitado recebem `ignore: true`:
continuam no histórico e como evidência de conclusão, mas não viram posts no feed. A tela pode filtrar os
níveis que apresenta; não deve inventar uma DiaryEntry de série/temporada.

Para SERIES/SEASON, `PUT /contents/{contentId}/my-rating` cria ou altera um único rating atual. Um rating
novo exige uma passada completa atual; um rating existente exige `expectedVersion` e pode continuar
editável enquanto houver evidência assistida anterior, mesmo que novos episódios estejam pendentes.
`POST /contents/{contentId}/reviews` publica uma review persistida após uma passada completa atual;
SEASON permite uma review por passada e SERIES permite várias. Se o payload da review inclui score e já
existe `ContentRating`, `expectedRatingVersion` também é obrigatório. Reviews preservam
`scoreAtPublication` e `watchNumber`, então alterar o rating atual não reescreve a nota histórica de uma
review. A nota atual aparece como `currentScore` nas conclusões derivadas; os previews mostram as
reviews da passada correspondente.
