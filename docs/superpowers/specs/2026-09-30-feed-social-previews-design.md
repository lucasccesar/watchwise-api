# Feed Social Previews Design

## Goal

Enriquecer os eventos sociais do feed (`DIARY_ENTRY`, `DROPPED`, `PICK_CREATED` e
`PICKS_TEMPLATE_CREATED`) com a quantidade de comentários e os três comentários
mais recentes do próprio objeto, mantendo também a quantidade de likes já
existente.

## Scope

- `DIARY_ENTRY` e `DROPPED` recebem `commentsCount` e `recentComments` no
  `FeedItemDTO`.
- `PICK_CREATED` recebe os dados sociais do próprio Pick em `pick` e os dados
  sociais do template relacionado em `picksTemplate`.
- `PICKS_TEMPLATE_CREATED` recebe os dados sociais do próprio template em
  `picksTemplate`.
- `TOP5_UPDATE` continua sem likes ou comentários.
- `recentComments` contém no máximo três `CommentResponseDTO`, ordenados do
  mais recente para o mais antigo por `createdAt DESC, id DESC`.
- A lista usa o mesmo formato de comentário já exposto pelos endpoints de
  comentários, incluindo `likesCount` e `likedByMe` de cada comentário.

## Contract shape

The existing object hierarchy remains intact instead of introducing a new
social block at the root of every feed item:

```text
FeedItemDTO
├── DIARY_ENTRY / DROPPED
│   ├── likesCount
│   ├── commentsCount
│   └── recentComments
└── PICK_CREATED / PICKS_TEMPLATE_CREATED
    └── pick or picksTemplate
        ├── likesCount
        ├── commentsCount
        └── recentComments
```

For `PICK_CREATED`, `pick` and `picksTemplate` are separate social targets and
therefore each carries its own count and comment list. Comments are never
combined across those targets.

## Data flow

Create a shared comment-preview assembler that accepts target IDs and the
viewer ID, then performs bounded batch loads per target type. It returns a
mapping from target ID to the target's comment count and recent comments.

The assembler will:

1. Count comments in batch for the requested target IDs.
2. Load at most three comments per target with a window-function query using
   `ROW_NUMBER() OVER (PARTITION BY target_id ORDER BY created_at DESC, id DESC)`.
3. Batch-load which returned comment IDs the viewer liked.
4. Map comments through the existing `CommentMapper` into
   `CommentResponseDTO`.

The feed and Pick preview assemblers reuse this component. No per-feed-item
comment query is allowed.

## Visibility and edge cases

- Comments are loaded only for parent objects already selected by the feed's
  visibility rules.
- An object without comments returns `commentsCount = 0` and an empty
  `recentComments` array, never `null`.
- A target with fewer than three comments returns all available comments.
- The three comments are ordered newest first; `id DESC` makes equal timestamps
  deterministic.
- `TOP5_UPDATE` keeps its social fields `null` because it is not a social
  target.
- Existing comment endpoints and their ordering are unchanged.

## Tests

- Feed service tests verify counts and recent comments for DiaryEntry and
  DroppedEntry, including zero comments and fewer than three comments.
- Pick preview tests verify a Pick's own comments are not mixed with its
  template's comments.
- Template preview tests verify the same behavior for template-created feed
  items.
- Repository tests verify the per-target top-three ordering and tie-breaker.
- Controller/integration tests verify the serialized feed contract and empty
  arrays.

## Documentation

Update `docs/context/openapi.yaml` for `FeedItem`, `PickPreview` and
`PicksTemplatePreview`. Update the Feed section of
`docs/context/business-rules.md` and append the shipped behavior to the current
day in `docs/context/progress.md`.
