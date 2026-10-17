# Limite de paginação das listas de usuário

## Objetivo

Restringir a paginação de `GET /users/{userId}/lists` a páginas de tamanho 10,
sem alterar os limites dos demais endpoints que reutilizam o `PageRequestFactory`.

## Comportamento

- `size` omitido usa 10.
- `size = 10` é aceito.
- `size` entre 1 e 9 retorna `400` por estar abaixo do mínimo.
- `size` acima de 10 é limitado a 10, preservando a semântica atual de teto do
  factory.
- Os demais endpoints paginados mantêm o limite global atual.

## Implementação e verificação

O serviço de listas valida o piso específico do endpoint e usa uma sobrecarga do
`PageRequestFactory` que aceita teto junto com ordenação. Os testes unitários do
serviço cobrem default, piso inválido, teto e valor válido; o OpenAPI documenta
`minimum: 10`, `maximum: 10` e `default: 10` para esse endpoint.
