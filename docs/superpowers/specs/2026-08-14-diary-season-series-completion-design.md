# Auto-completar temporada/série no diário

## Contexto

Hoje, logar um episódio (`DiaryEntry` com `Content.type = EPISODE`) não tem nenhum efeito sobre a
temporada ou a série a que ele pertence — o usuário precisa logar a temporada e a série manualmente,
mesmo depois de já ter assistido a todos os episódios/temporadas. Esta feature faz isso acontecer
sozinho: quando o usuário completa todos os episódios de uma temporada, uma `DiaryEntry` de temporada é
criada automaticamente; quando completa todas as temporadas de uma série, o mesmo acontece para a
série.

O backend nunca chama a API do TMDB (não existe client HTTP pra isso em lugar nenhum do código — a
única menção é uma chamada futura, ainda não construída, no serviço de `Search`) e `Content` só guarda
referências (`type` + ids), nunca metadado de filme/série (`CLAUDE.md` § Avoid). Por isso não há como o
backend saber sozinho "quantos episódios essa temporada tem" ou "qual é o episódio/temporada final" —
essa informação **precisa vir do cliente**, que já a obtém do TMDB para montar a tela de episódios.

**Divergência assumida (aprovada pelo usuário durante o brainstorming):** os campos de finale abaixo são
armazenados em `Content`, o que é guardar um fato sobre o conteúdo além do id de referência puro —
tensiona com a regra "nunca guardar metadado de filme/série" do `CLAUDE.md`, mas foi a opção escolhida
frente à alternativa (reenviar a flag em toda requisição, sem persistir nada). `CLAUDE.md` deve ser
atualizado para registrar essa exceção pontual quando o código for implementado.

## Modelo de dados

**`Content`** ganha duas colunas novas, nullable, preenchidas apenas na primeira vez que aquele
episódio/temporada é referenciado (mesmo mecanismo do `getOrCreateReference` já existente) e imutáveis
depois disso — mesmo tratamento que os demais campos type-specific de `Content`:

- `is_season_finale BOOLEAN` — só tem sentido quando `type = EPISODE`. `true` se este é o último
  episódio da temporada.
- `is_series_finale BOOLEAN` — só tem sentido quando `type = SEASON`. `true` se esta é a última
  temporada da série.

**`ContentRefCreationDTO`** ganha os campos correspondentes — `isSeasonFinale` e `isSeriesFinale`,
ambos opcionais/nullable. `isSeasonFinale` só é aceito quando `type = EPISODE`; `isSeriesFinale` é
aceito tanto em `type = EPISODE` (informando se a temporada daquele episódio é a última da série) quanto
em `type = SEASON` (informando diretamente sobre aquela temporada). O cliente deve mandar essas flags
sempre que loga um episódio.

**`DiaryEntry`** ganha `auto_generated BOOLEAN NOT NULL DEFAULT false` — `true` apenas nas entradas de
temporada/série criadas por esta feature; `false` (default) em tudo criado por ação direta do usuário.
É o que permite decidir, na hora de uma remoção em cascata, se é seguro apagar uma entrada de
temporada/série sem apagar algo que o usuário criou de propósito.

## Algoritmo

A checagem de completude roda em **todo** log de episódio (não só quando o episódio logado é o
finale) — o finale pode já ter sido logado antes, ou ser logado depois, então a ordem de quais
episódios o usuário assiste não pode ser assumida.

### Episódio → temporada

Ao criar uma `DiaryEntry` com `content.type = EPISODE` (`DiaryEntryServiceImpl.createDiaryEntry`),
depois de salvar a entrada do episódio normalmente:

1. Busca, entre os `Content` da mesma série+temporada (`seriesTmdbId` + `seasonNumber`), se algum tem
   `is_season_finale = true`. Se nenhum `Content` desses existir ainda em `contents` (ninguém referenciou
   o finale daquela temporada ainda, nem o próprio episódio logado agora), para aqui — não dá pra saber
   o tamanho da temporada.
2. Se existe, pega o `episodeNumber` desse finale (**F**).
3. Conta quantos episódios **distintos** dessa temporada o usuário já tem logados no diário (uma query
   nova: distinct `Content` de `type = EPISODE` com aquele `seriesTmdbId`+`seasonNumber` que o usuário
   tem pelo menos uma `DiaryEntry` apontando).
4. Se a contagem `== F`, a temporada está completa (por exclusão: se a temporada tem F episódios e o
   usuário já logou F distintos, ele assistiu todos).
5. Se completa **e** o usuário ainda não tem nenhuma `DiaryEntry` para o `Content` de temporada
   correspondente, resolve/cria a referência de `Content` da temporada via
   `ContentService.getOrCreateReference` — usando `isSeriesFinale` do `ContentRefCreationDTO` do
   episódio que acabou de completar a temporada (é por isso que esse campo existe também em payloads de
   `EPISODE`, não só de `SEASON`) — e cria a `DiaryEntry` automaticamente:
   - `comment` / `score`: `null`.
   - `watchedDate`: a mesma do episódio que acabou de ser logado (o que "faltava" para completar —
     pode não ser o último episódio da temporada em ordem).
   - `isRewatch`: calculado do mesmo jeito que já existe hoje para criação normal (é rewatch se já
     havia uma `DiaryEntry` anterior para esse `Content` de temporada).
   - `watchedInTheater`: `null` (não permitido para `SEASON`, mesma regra já implementada).
   - `autoGenerated`: `true`.

### Temporada → série

Sempre que uma `DiaryEntry` de temporada é persistida — seja pelo passo 5 acima, seja pelo usuário
logando a temporada diretamente — o mesmo processo roda um nível acima: busca `Content` de
`type = SEASON` daquela série com `is_series_finale = true`, pega seu `seasonNumber` (**G**), conta
temporadas distintas logadas pelo usuário para aquela série, e se `== G` e ainda não existe `DiaryEntry`
de série para o usuário, cria a `DiaryEntry` da série do mesmo jeito (campos análogos, `autoGenerated =
true`).

## Cascata na remoção

**Ao deletar uma `DiaryEntry` de episódio** (`deleteDiaryEntry`):
1. Deleta a entrada do episódio.
2. Se existir uma `DiaryEntry` de temporada (para aquela série+temporada) com `autoGenerated = true`,
   reexecuta a checagem de completude com os episódios restantes.
3. Se a temporada não estiver mais completa, deleta essa `DiaryEntry` de temporada.
4. Se essa remoção aconteceu e existia uma `DiaryEntry` de série `autoGenerated = true`, repete o mesmo
   raciocínio um nível acima.

**Ao deletar uma `DiaryEntry` de temporada diretamente**: mesmo efeito em cascata para cima — reverifica
e remove a `DiaryEntry` de série se ela for `autoGenerated = true` e não fizer mais sentido.

**Regra chave:** só entradas com `autoGenerated = true` podem ser removidas por esta lógica. Uma
temporada/série criada manualmente pelo usuário nunca é tocada, mesmo que os episódios/temporadas que
"a sustentavam" tenham sido apagados depois.

## Limitações assumidas (fora de escopo)

- Assume numeração de episódio/temporada contígua começando em 1 — não trata temporada 0 (specials) nem
  buracos na numeração do TMDB.
- Confia inteiramente na flag que o cliente manda; como `Content` é imutável após criado, um valor
  incorreto na primeira referência a um episódio/temporada gruda ali — mesmo comportamento que os
  demais campos de `Content` já têm hoje.
- Sem verificação server-side contra o TMDB — decisão consciente para não introduzir integração HTTP
  nesta feature.

## Testes

Segue o padrão de cobertura já estabelecido no projeto (`CLAUDE.md` § Test coverage baseline) —
service unit tests para os métodos novos/alterados (incluindo os branches de completude
episódio→temporada e temporada→série, e os de cascata na remoção) e testes de integração cobrindo o
fluxo ponta a ponta (logar todos os episódios de uma temporada pequena e verificar que a temporada
aparece automaticamente no diário; apagar um episódio depois e verificar que a temporada some).