# Trending do TMDB

## Objetivo

Adicionar um proxy autenticado para exibir os filmes e as séries em alta no
TMDB, com janela diária ou semanal. A tela inicial consome 12 itens de cada
seção e o botão de expansão consome 21 itens de cada seção, permitindo exibir
mais 9 resultados.

## Contrato HTTP

```text
GET /trending?timeWindow=day|week&size=12|21
```

O endpoint exige autenticação por cookie JWT e usa o idioma preferido do
usuário autenticado ao chamar o TMDB.

Resposta:

```json
{
  "movies": [
    {
      "tmdbId": "603",
      "type": "MOVIE",
      "title": "The Matrix",
      "posterUrl": "https://image.tmdb.org/t/p/w500/matrix.jpg",
      "releaseYear": 1999
    }
  ],
  "series": [
    {
      "tmdbId": "1396",
      "type": "SERIES",
      "title": "Breaking Bad",
      "posterUrl": "https://image.tmdb.org/t/p/w500/breaking-bad.jpg",
      "releaseYear": 2008
    }
  ]
}
```

`size` é opcional e assume `12`. Somente `12` e `21` são aceitos. `timeWindow`
é obrigatório e aceita somente `day` e `week`, preservando a grafia exigida pelo
TMDB.

O serviço retorna até `size` itens por seção. Se uma resposta válida do TMDB
contiver menos itens, a lista não é preenchida artificialmente e nenhuma página
adicional é buscada.

## Arquitetura

O novo pacote `trending` terá controller, service e DTO. O controller resolve o
usuário autenticado, valida os parâmetros e aplica um rate limit próprio de 30
requisições por usuário em 5 minutos. O serviço carrega o usuário e delega ao
`TmdbClient` duas chamadas independentes:

- `/trending/movie/{time_window}?language={preferredLanguage}`;
- `/trending/tv/{time_window}?language={preferredLanguage}`.

Cada resposta externa será convertida para `SearchContentDTO`, mantendo o
contrato de cards já usado pela busca. `TrendingResponseDTO` agrupa as listas
de filmes e séries.

O `TmdbClient` terá modelos próprios para os resultados de trending, separados
dos modelos de busca. Esses modelos conterão somente os campos necessários ao
contrato local: ID, título ou nome, caminho do poster e data de lançamento ou
primeira exibição.

## Cache e falhas

Filmes e séries terão caches Caffeine separados. A chave de cada cache combina
janela temporal e idioma. O TTL e o tamanho máximo serão configuráveis por
propriedades `app.tmdb.trending-cache-ttl-minutes` e
`app.tmdb.trending-cache-max-size`.

O cliente reutiliza o retry existente do TMDB. Resultado indisponível após o
retry gera `TmdbUnavailableException`, que o handler global converte em `502`.
Se uma das duas chamadas estiver indisponível, a resposta agregada inteira será
`502`, evitando uma seção atualizada e outra silenciosamente incompleta.
Resposta `404` do TMDB é tratada como lista vazia, seguindo o comportamento
existente dos proxies de busca.

## Verificação

Os testes cobrirão:

- desserialização dos payloads de filme e série;
- construção das URLs com janela e idioma;
- cache por janela e idioma;
- retry e mapeamento de indisponibilidade;
- conversão para `SearchContentDTO` e limites 12/21;
- validação de parâmetros no controller;
- autenticação, rate limit e resposta agregada via MockMvc;
- retorno `502` quando uma seção do TMDB estiver indisponível.

Não haverá entidade, migration ou repository. O contrato será adicionado ao
`docs/context/openapi.yaml`; a implementação será registrada em
`docs/context/progress.md` e a regra de proxy, validação e falha será registrada
em `docs/context/business-rules.md`.

## Critérios de aceite

- `GET /trending?timeWindow=day` retorna até 12 filmes e até 12 séries.
- `GET /trending?timeWindow=week&size=21` retorna até 21 filmes e até 21 séries.
- Valores diferentes de `day`/`week` ou `12`/`21` retornam `400 ApiError`.
- O idioma preferido do usuário é enviado às duas chamadas do TMDB.
- O poster local usa o builder de URL existente.
- O TMDB é chamado novamente somente quando a entrada correspondente não está
  no cache ou expirou.
- Falha externa após retry retorna `502 ApiError` sem expor detalhes internos.
- A resposta usa a ordem recebida do TMDB.
