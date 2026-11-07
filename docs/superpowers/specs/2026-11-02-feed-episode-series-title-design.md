# Título da série nos itens de episódio do Feed

## Objetivo

Em `GET /feed`, os itens `DIARY_ENTRY` cujo conteúdo é `EPISODE` devem incluir `seriesTitle` com o título da série-pai. O campo `card.title` continua representando o título do episódio. Os demais eventos não recebem um valor para `seriesTitle`.

## Implementação

Adicionar `seriesTitle` ao `FeedItemViewDTO` e ao schema `FeedItemView` do OpenAPI. Durante a montagem dos cards da página selecionada, incluir as coordenadas SERIES correspondentes aos itens de diário EPISODE. `ContentCardAssembler` já resolve metadados das séries-pai para o backdrop dos cards de episódio; incluir as coordenadas também permite reutilizar o título resolvido, sem uma chamada TMDB adicional.

O assembler do feed copia o título da série para `seriesTitle` somente quando `eventType` é `DIARY_ENTRY` e `content.type` é `EPISODE`. Se a referência da série ou o título não estiver disponível, o valor será `null`. `card.title` permanece inalterado.

## Compatibilidade e falhas

O campo é aditivo e nullable. A falha ou ausência de metadados TMDB não impede a resposta do feed. A mudança não persiste conteúdo nem altera a resolução de outros tipos de evento.

## Documentação

Atualizar `docs/context/openapi.yaml`, registrar a regra em `docs/context/business-rules.md` e resumir o comportamento entregue em `docs/context/progress.md`.

## Verificação

Inspecionar o diff da implementação e do contrato para confirmar que o título do episódio continua em `card.title`, que `seriesTitle` só é populado para os itens especificados e que referências sem título produzem `null`.
