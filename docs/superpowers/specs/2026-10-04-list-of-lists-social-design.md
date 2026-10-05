# Interações sociais em listas-de-listas

## Objetivo

Permitir que qualquer `UserList` visível receba comentários e likes, inclusive quando a lista estiver travada como lista-de-listas.

## Escopo

A mudança altera somente a elegibilidade social da lista. Uma lista-de-listas poderá:

- receber e listar comentários;
- receber e remover likes;
- participar das mesmas respostas, notificações, contadores `commentsCount`/`likesCount` e estado `likedByMe` das listas de conteúdo.

As regras de visibilidade (`PUBLIC`, `FOLLOWERS` e `PRIVATE`), autenticação, autorização, idempotência de likes e autorização para respostas permanecem inalteradas.

## Arquitetura

`CommentServiceImpl` deixará de rejeitar a criação e a leitura de comentários quando a lista possuir itens `childListId`. `LikeServiceImpl` deixará de rejeitar likes pelo mesmo motivo.

Não haverá nova coluna, migration, DTO ou endpoint. O banco já modela comentários e likes por `UserList` sem distinguir o tipo da lista, e os fluxos de contagem e estado de curtida já são genéricos para esse alvo.

## Contrato HTTP

Os endpoints existentes continuam sendo usados:

- `GET/POST /lists/{listId}/comments`;
- `POST/DELETE /lists/{listId}/like`.

O contrato deixará de documentar `400` para lista-de-listas. A lista continuará retornando `403` quando não estiver visível para o viewer e `404` quando não existir.

## Testes

Os testes de integração de comentários e likes que atualmente esperam `400` serão convertidos para verificar sucesso em uma lista-de-listas. A cobertura verificará também a leitura dos comentários, a remoção do like, a idempotência e a atualização dos contadores. Os testes existentes de visibilidade, autenticação, CSRF e recurso inexistente permanecerão válidos.

## Documentação

Serão atualizados `docs/context/openapi.yaml`, `docs/context/business-rules.md`, `docs/context/progress.md` e `AGENTS.md` para remover a regra anterior de bloqueio. `docs/context/database-schema.md` não muda porque o modelo relacional já suporta o comportamento solicitado.

## Fora do escopo

Não serão alterados os limites de aninhamento das listas, a forma de busca, a visibilidade de listas, os tipos de alvo social além de `UserList` nem as regras de exclusão em cascata.
