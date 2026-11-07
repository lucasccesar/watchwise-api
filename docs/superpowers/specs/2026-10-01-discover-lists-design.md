# Discover Lists Design

## Goal

Adicionar uma rota autenticada para descobrir listas públicas mais curtidas, com paginação limitada a 10 listas por página.

## API contract

`GET /lists/discover` recebe `page` e `size` opcionais e retorna `PageResponseDTO<UserListResponseDTO>`.

- A autenticação é obrigatória.
- `page` usa o comportamento existente de paginação: omitido ou `0` representa a primeira página; valores positivos são tratados como páginas iniciadas em 1.
- `size` omitido usa 10.
- `size` entre 1 e 9 retorna `400`.
- `size` acima de 10 é limitado a 10.
- Apenas listas com `visibility = PUBLIC` são elegíveis.
- A ordem é `likesCount DESC`, depois `createdAt DESC` e `id DESC`.
- A resposta usa os mesmos campos das listagens existentes, incluindo `likedByMe` para o usuário autenticado.

## Architecture

O caso de uso ficará no `UserListService`/`UserListServiceImpl`, porque a montagem de `UserListResponseDTO` já é responsabilidade desse serviço. A consulta será adicionada ao `UserListRepository` e executará filtro, ordenação e paginação no banco usando o contador desnormalizado `UserList.likesCount`.

O controller adicionará `GET /lists/discover` e obterá o usuário autenticado pelo mesmo mecanismo das demais rotas de listas. O serviço reutilizará `mapToResponseDtoPage` para preservar o carregamento em lote de previews, contadores, progresso e curtidas do viewer.

Nenhuma entidade, DTO, migration ou alteração na política global de segurança será necessária.

## Data flow

1. O controller lê `page` e `size` e chama o serviço com o ID do usuário autenticado.
2. O serviço cria um `PageRequest` com teto de 10 e valida o piso específico das listas.
3. O repository busca somente listas públicas, ordenadas por likes, data de criação e UUID.
4. O serviço transforma a página com o assembler existente e resolve `likedByMe` em lote para o viewer.
5. O controller serializa a página em `PageResponseDTO`.

## Security and edge cases

- O filtro `PUBLIC` ocorre na consulta; listas de seguidores ou privadas não podem vazar por paginação ou por filtragem posterior.
- A ordenação usa `id` como desempate para evitar itens instáveis entre páginas quando likes e datas coincidirem.
- Página vazia retorna metadados normais de uma página Spring Data, sem erro.
- A rota permanece autenticada por `anyRequest().authenticated()`.

## Testing

- Teste unitário do serviço verifica query pública, ordenação delegada, teto de tamanho e reutilização da montagem da resposta.
- Teste do controller verifica a chamada com o usuário autenticado e o envelope de página.
- Teste de integração verifica que listas privadas/de seguidores são excluídas, que a ordem é por likes e que uma página contém no máximo 10 listas.
- Teste de repository verifica a ordenação e o filtro de visibilidade com dados persistidos.

## Documentation

Atualizar `docs/context/openapi.yaml`, `docs/context/business-rules.md` e `docs/context/progress.md` para registrar a rota, a regra de visibilidade e a ordenação por likes.
