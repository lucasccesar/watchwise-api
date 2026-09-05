# Log em lote de temporada/série inteira

## Contexto

Hoje logar uma temporada inteira exige clicar em cada episódio individualmente. **Pedido:** um jeito de
declarar de uma vez "assisti essa temporada inteira" (cria a temporada + todos os episódios dela) ou
"assisti essa série inteira" (cria todas as temporadas + todos os episódios).

Tecnicamente viável sem quebrar nenhuma suposição já aceita: `Content` de `EPISODE` não guarda `tmdbId`
próprio (só `seriesTmdbId`+`seasonNumber`+`episodeNumber`), então o backend não precisa que o cliente
enumere id nenhum — só o número do episódio finale, reaproveitando a mesma suposição de numeração
contígua começando em 1 que a auto-completação já assume hoje.

## ⚠️ Confirmação necessária antes de implementar

**Você decidiu:** se o usuário já tem episódios logados e pede pra adicionar a temporada/série inteira,
o comportamento é criar uma **passada nova pra tudo** — inclusive os episódios já logados — em vez de
preencher só o que falta.

Isso tem uma dependência parcial que precisa ficar clara antes de codar:

- **No nível de episódio, funciona hoje sem `watch_number`** — relogar um episódio já vira uma linha
  nova com `isRewatch = true`, mecanismo que já existe e não precisa de nada novo.
- **No nível de temporada/série, NÃO funciona hoje.** O guard atual de completude só cria a `DiaryEntry`
  auto-gerada de temporada/série **uma vez** por usuário+conteúdo — completar um rewatch inteiro em lote
  recria os episódios normalmente, mas **não** gera uma segunda entrada de temporada/série. Isso só
  passa a funcionar depois que `docs/superpowers/specs/2026-08-15-diary-season-series-rewatch-design.md`
  (`watch_number`) estiver implementado.

**Confirme antes de implementar esta spec:** você aceita que a feature suba parcialmente capada — lote
funciona pra progresso novo e pra completar episódios de uma passada em andamento, mas uma **segunda
passada completa em lote não gera a entrada de temporada/série auto-gerada correspondente** até o
`watch_number` chegar — ou prefere que a feature inteira espere o `watch_number` estar pronto antes de
subir, pra não ter esse período intermediário "capado"?

## Design do endpoint

Endpoint **novo e dedicado**, não uma flag no `POST /diary` existente — a resposta é uma lista de
entradas criadas (potencialmente dezenas), forma bem diferente do `DiaryEntryResponseDTO` único do
endpoint atual; misturar as duas semânticas no mesmo contrato fica confuso.

`POST /diary/bulk` (nome sujeito a ajuste):

```json
{
  "content": { "type": "SEASON", "seriesTmdbId": "1399", "seasonNumber": 1, "isSeriesFinale": false },
  "watchedDate": "2025-03-12"
}
```

- `type: SEASON` → cria os `Content`/`DiaryEntry` dos episódios `1..F` (F = número do episódio marcado
  `isSeasonFinale` já registrado, ou passado explicitamente se ainda não existir referência) + a entrada
  da própria temporada.
- `type: SERIES` → mesma coisa, um nível acima: todas as temporadas `1..G` (G = temporada finale) e
  todos os episódios de cada uma.
- Todas as linhas do lote compartilham a mesma `watchedDate` — não uma por episódio (assume-se maratona
  num dia só; granular por episódio fica fora de escopo, adicionar depois se pedido).

Resposta: lista de `DiaryEntryResponseDTO` (todas as linhas criadas nesta chamada).

## Interação com o algoritmo de completude existente

`maybeCompleteSeason`/`maybeCompleteSeries` já são um loop (`enquanto currentMax < minCount`) desenhado
especificamente pra não depender da suposição "só uma passada avança por chamada" — o spec de rewatch já
documentava isso como proteção defensiva, nunca exercitada na prática por falta de log em lote. **Esta
feature é o que finalmente exercita esse loop de verdade.**

Consequência prática: a limitação já registrada no spec de rewatch ("todas as entradas de temporada
criadas no mesmo loop levam a mesma `watchedDate` da chamada que disparou tudo, mesmo sendo passadas
diferentes") deixa de ser teórica e passa a ser alcançável por um usuário real fazendo log em lote de
múltiplas passadas na mesma chamada. **Precisa ser resolvido junto** desta implementação, não deixado
como nota de rodapé — ex.: se o lote cobre N passadas completas, decidir se todas nascem com a mesma
`watchedDate` (mais simples, levemente impreciso) ou se o endpoint aceita uma data por passada (mais
correto, mais complexo).

## Rate limiting

O limite atual de `diary-action` foi pensado pra uma chamada por entrada. Um único request criando
20-30+ linhas de uma vez precisa de um teto próprio — sugestão: limite de episódios por chamada (ex.
máximo 100) e um bucket de rate limit mais conservador que o de criação individual, mesmo raciocínio que
já motivou o rate limit dedicado em `POST /contents/reference` (evitar inflar `contents`/`diary_entries`
sem controle).

## Limitações assumidas

- Assume a mesma numeração contígua 1..F/1..G já assumida pelo resto da feature de completude — não
  trata temporada 0 nem buracos.
- Uma `watchedDate` só pro lote inteiro (ver acima).
- Ver seção de confirmação necessária acima — comportamento no nível de temporada/série depende do
  `watch_number`.

## Testes

- Unit tests: lote de temporada completa cria N episódios + 1 temporada; lote de série completa cria
  todos os episódios + temporadas + a série; lote sobre progresso parcial existente cria passada nova
  pra tudo (episódios já logados incluídos), por decisão de produto.
- Integração: `POST /diary/bulk` numa temporada pequena e verificar via `GET /diary` que todas as
  entradas apareceram; rate limit disparando ao exceder o teto de episódios por chamada.
