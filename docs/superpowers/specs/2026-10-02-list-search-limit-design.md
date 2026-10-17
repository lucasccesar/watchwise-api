# Limite da pesquisa de listas

## Objetivo

Fazer a pesquisa local de listas retornar no máximo 10 listas por página, com tamanho efetivo fixo em 10 quando houver resultados suficientes.

## Desenho

`SearchServiceImpl` continuará usando a paginação solicitada para filmes, séries, pessoas, usuários e templates. Para listas, criará uma `PageRequest` com o número de página recebido e tamanho fixo de 10, ignorando `size` da requisição. A regra será aplicada tanto em `type=LIST` quanto no array `lists` da busca geral sem `type`.

Se a página tiver menos de 10 listas disponíveis, a resposta retornará somente as listas existentes. A visibilidade atual, a ordenação, a busca escapada e o enriquecimento em lote permanecerão inalterados.

## Testes e contrato

- O teste unitário do serviço verificará que a consulta ao repositório recebe tamanho 10 mesmo quando `size` é omitido, menor ou maior.
- A documentação da rota `/search` será atualizada para descrever o limite específico do array `lists`.
- A regra implementada será registrada em `business-rules.md` e `progress.md`.

## Alternativas consideradas

1. Alterar o limite global de `size`: rejeitado porque também mudaria os resultados de filmes, séries, pessoas, usuários e templates.
2. Buscar até 20 e cortar a lista em memória: rejeitado porque consulta e enriquece dados desnecessários.
3. Fixar a paginação no repositório de listas: rejeitado porque o limite é uma regra da busca geral, enquanto outras chamadas de listas podem precisar de tamanhos diferentes.
